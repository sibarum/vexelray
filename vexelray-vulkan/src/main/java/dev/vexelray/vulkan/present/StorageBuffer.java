package dev.vexelray.vulkan.present;

import sibarum.probe.Lane;
import sibarum.probe.Probe;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.gi;
import static dev.supirvast.vulkan.Ffm.gl;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A host-visible, persistently mapped storage buffer of {@code float}s, together with the descriptor set that
 * binds it — the path by which a shader reads an <em>array</em> rather than a handful of push constants.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link VertexBuffer} is the same allocation with a different usage bit, and the two could have been one
 * class with a flag. They are separate because what they are <em>for</em> differs at the descriptor: a vertex
 * buffer is bound by the pipeline's vertex input state and needs no descriptor at all, while a storage buffer is
 * bound through a descriptor set whose layout the pipeline had to be built against. Folding them together would
 * mean a vertex buffer carrying a descriptor pool it never uses, and every caller deciding which half applies.
 *
 * <p>The motivating use is a distance field whose geometry is <b>data rather than code</b>. A scene compiled into
 * the shader means new geometry is new SPIR-V and a new pipeline, and building one is the slowest thing a
 * ray-marching viewport does — measured at five seconds for a curve of a few hundred segments, on the thread that
 * presents. Reading the same geometry out of this buffer makes the shader independent of what it draws: compiled
 * once, and a new expression becomes a memory copy.
 *
 * <p>Host-visible and host-coherent, like {@link VertexBuffer}, for the same reason — the contents change often
 * and are small, so a staging copy would cost more than it saved. And with the same obligation on the caller:
 * <b>the previous draw must have completed before {@link #update} rewrites it</b>. Nothing here enforces that,
 * because nothing here knows when a submission finished; {@code SampledColorTarget.renderInto} waits on its own
 * fence before returning, so a caller that updates between calls to it is already safe.
 *
 * <p>The descriptor half is {@link BoundStorageBuffer}, which also serves a buffer this class did not make.
 */
public final class StorageBuffer implements AutoCloseable {

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor MEMREQ = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    private static final GroupLayout BUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("size"),
            JAVA_INT.withName("usage"), JAVA_INT.withName("sharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices")).withName("VkBufferCreateInfo");

    private static final GroupLayout MEMORY_REQUIREMENTS = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"), JAVA_LONG.withName("alignment"),
            JAVA_INT.withName("memoryTypeBits"), MemoryLayout.paddingLayout(4)).withName("VkMemoryRequirements");

    private static final GroupLayout MEMORY_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("allocationSize"), JAVA_INT.withName("memoryTypeIndex"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryAllocateInfo");

    private final VulkanDevice device;
    private final long buffer;
    private final long memory;
    private final int capacityFloats;
    private final MemorySegment mapped;
    private final BoundStorageBuffer binding;

    private final MethodHandle vkDestroyBuffer;
    private final MethodHandle vkFreeMemory;

    /**
     * Room for {@code capacityFloats} floats, bound at set 0 / the given {@code binding}, readable from the
     * fragment stage.
     *
     * <p>Sized once and refilled, rather than grown: the descriptor points at the allocation, so growing means a
     * new buffer, a new descriptor write and — because the pipeline was built against this layout — care about
     * what is in flight. A caller that cannot bound its data should say so by checking {@link #capacityFloats()}.
     */
    public StorageBuffer(VulkanDevice device, int capacityFloats, int binding) {
        Probe.opened(Lane.GPU, "StorageBuffer", this);
        this.device = device;
        this.capacityFloats = capacityFloats;
        MemorySegment dev = device.handle();
        long byteSize = (long) capacityFloats * Float.BYTES;

        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        this.vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        this.vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(BUFFER_CREATE_INFO);
            si(info, BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            sl(info, BUFFER_CREATE_INFO, "size", byteSize);
            si(info, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_STORAGE_BUFFER_BIT);
            si(info, BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            MemorySegment pBuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateBuffer, dev, info, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            this.buffer = pBuffer.get(JAVA_LONG, 0);

            MemorySegment req = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetBufferMemoryRequirements, dev, buffer, req);
            MemorySegment alloc = arena.allocate(MEMORY_ALLOCATE_INFO);
            si(alloc, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            sl(alloc, MEMORY_ALLOCATE_INFO, "allocationSize", gl(req, MEMORY_REQUIREMENTS, "size"));
            si(alloc, MEMORY_ALLOCATE_INFO, "memoryTypeIndex",
                    device.findMemoryType(gi(req, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT));
            MemorySegment pMem = arena.allocate(JAVA_LONG);
            check(invoke(vkAllocateMemory, dev, alloc, MemorySegment.NULL, pMem), "vkAllocateMemory");
            this.memory = pMem.get(JAVA_LONG, 0);
            check(invoke(vkBindBufferMemory, dev, buffer, memory, 0L), "vkBindBufferMemory");

            MemorySegment ppData = arena.allocate(ADDRESS);
            check(invoke(vkMapMemory, dev, memory, 0L, byteSize, 0, ppData), "vkMapMemory");
            this.mapped = ppData.get(ADDRESS, 0).reinterpret(byteSize);   // persistent map (host-coherent)
        }
        this.binding = new BoundStorageBuffer(device, buffer, binding);
    }

    /**
     * Overwrite the first {@code floats} entries with {@code data}.
     *
     * <p>The length is separate from the array's own so a caller holding a scratch array larger than its content
     * can write the part that counts without copying it into a right-sized one first.
     */
    public void update(float[] data, int floats) {
        if (floats > capacityFloats || floats > data.length) {
            throw new IllegalArgumentException("storage data (" + floats + " floats) exceeds capacity ("
                    + capacityFloats + ")");
        }
        MemorySegment.copy(data, 0, mapped, JAVA_FLOAT, 0, floats);
    }

    /** How many floats fit — what a caller checks against instead of discovering by exception. */
    public int capacityFloats() {
        return capacityFloats;
    }

    /** The layout a pipeline reading this buffer must be built against. */
    public long descriptorSetLayout() {
        return binding.descriptorSetLayout();
    }

    /** The set to bind at set 0 when drawing with such a pipeline. */
    public long descriptorSet() {
        return binding.descriptorSet();
    }

    private boolean closed;

    /** Whether {@link #close} has run, so an owner keeping a list of these can drop the ones already released. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Release everything. Idempotent, because a buffer an application replaces is closed by the application and
     * then again by whatever owns the list it was made from; a second destroy of a Vulkan handle is not reported,
     * it corrupts the loader's heap.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Probe.closed(Lane.GPU, "StorageBuffer", this);
        MemorySegment dev = device.handle();
        binding.close();
        invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, memory, MemorySegment.NULL);
    }
}
