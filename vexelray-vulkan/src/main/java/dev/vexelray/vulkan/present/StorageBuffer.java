package dev.vexelray.vulkan.present;

import sibarum.probe.Lane;
import sibarum.probe.Probe;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.vexelray.vulkan.vk.Ffm.check;
import static dev.vexelray.vulkan.vk.Ffm.gi;
import static dev.vexelray.vulkan.vk.Ffm.gl;
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.invokeVoid;
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
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

    private static final GroupLayout DESCRIPTOR_SET_LAYOUT_BINDING = MemoryLayout.structLayout(
            JAVA_INT.withName("binding"), JAVA_INT.withName("descriptorType"), JAVA_INT.withName("descriptorCount"),
            JAVA_INT.withName("stageFlags"), ADDRESS.withName("pImmutableSamplers")
    ).withName("VkDescriptorSetLayoutBinding");

    private static final GroupLayout DESCRIPTOR_SET_LAYOUT_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("bindingCount"), ADDRESS.withName("pBindings")
    ).withName("VkDescriptorSetLayoutCreateInfo");

    private static final GroupLayout DESCRIPTOR_POOL_SIZE = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), JAVA_INT.withName("descriptorCount")).withName("VkDescriptorPoolSize");

    private static final GroupLayout DESCRIPTOR_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("maxSets"), JAVA_INT.withName("poolSizeCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pPoolSizes")).withName("VkDescriptorPoolCreateInfo");

    private static final GroupLayout DESCRIPTOR_SET_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("descriptorPool"), JAVA_INT.withName("descriptorSetCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSetLayouts")).withName("VkDescriptorSetAllocateInfo");

    private static final GroupLayout DESCRIPTOR_BUFFER_INFO = MemoryLayout.structLayout(
            JAVA_LONG.withName("buffer"), JAVA_LONG.withName("offset"), JAVA_LONG.withName("range")
    ).withName("VkDescriptorBufferInfo");

    private static final GroupLayout WRITE_DESCRIPTOR_SET = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("dstSet"), JAVA_INT.withName("dstBinding"), JAVA_INT.withName("dstArrayElement"),
            JAVA_INT.withName("descriptorCount"), JAVA_INT.withName("descriptorType"), ADDRESS.withName("pImageInfo"),
            ADDRESS.withName("pBufferInfo"), ADDRESS.withName("pTexelBufferView")).withName("VkWriteDescriptorSet");

    private final VulkanDevice device;
    private final long buffer;
    private final long memory;
    private final int capacityFloats;
    private final MemorySegment mapped;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;

    private final MethodHandle vkDestroyBuffer;
    private final MethodHandle vkFreeMemory;
    private final MethodHandle vkDestroyDescriptorSetLayout;
    private final MethodHandle vkDestroyDescriptorPool;

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
        MethodHandle vkCreateDescriptorSetLayout = device.command("vkCreateDescriptorSetLayout", C4);
        this.vkDestroyDescriptorSetLayout = device.command("vkDestroyDescriptorSetLayout", D_LONG);
        MethodHandle vkCreateDescriptorPool = device.command("vkCreateDescriptorPool", C4);
        this.vkDestroyDescriptorPool = device.command("vkDestroyDescriptorPool", D_LONG);
        MethodHandle vkAllocateDescriptorSets = device.command("vkAllocateDescriptorSets",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkUpdateDescriptorSets = device.command("vkUpdateDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

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

            MemorySegment bindingDesc = arena.allocate(DESCRIPTOR_SET_LAYOUT_BINDING);
            si(bindingDesc, DESCRIPTOR_SET_LAYOUT_BINDING, "binding", binding);
            si(bindingDesc, DESCRIPTOR_SET_LAYOUT_BINDING, "descriptorType", Vk.DESCRIPTOR_TYPE_STORAGE_BUFFER);
            si(bindingDesc, DESCRIPTOR_SET_LAYOUT_BINDING, "descriptorCount", 1);
            si(bindingDesc, DESCRIPTOR_SET_LAYOUT_BINDING, "stageFlags", Vk.SHADER_STAGE_FRAGMENT_BIT);
            MemorySegment dslInfo = arena.allocate(DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            si(dslInfo, DESCRIPTOR_SET_LAYOUT_CREATE_INFO, "sType",
                    Vk.STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            si(dslInfo, DESCRIPTOR_SET_LAYOUT_CREATE_INFO, "bindingCount", 1);
            sa(dslInfo, DESCRIPTOR_SET_LAYOUT_CREATE_INFO, "pBindings", bindingDesc);
            MemorySegment pDsl = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateDescriptorSetLayout, dev, dslInfo, MemorySegment.NULL, pDsl),
                    "vkCreateDescriptorSetLayout");
            this.descriptorSetLayout = pDsl.get(JAVA_LONG, 0);

            MemorySegment poolSize = arena.allocate(DESCRIPTOR_POOL_SIZE);
            si(poolSize, DESCRIPTOR_POOL_SIZE, "type", Vk.DESCRIPTOR_TYPE_STORAGE_BUFFER);
            si(poolSize, DESCRIPTOR_POOL_SIZE, "descriptorCount", 1);
            MemorySegment poolInfo = arena.allocate(DESCRIPTOR_POOL_CREATE_INFO);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "maxSets", 1);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "poolSizeCount", 1);
            sa(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "pPoolSizes", poolSize);
            MemorySegment pPool = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateDescriptorPool, dev, poolInfo, MemorySegment.NULL, pPool), "vkCreateDescriptorPool");
            this.descriptorPool = pPool.get(JAVA_LONG, 0);

            MemorySegment pSetLayouts = arena.allocate(JAVA_LONG);
            pSetLayouts.set(JAVA_LONG, 0, descriptorSetLayout);
            MemorySegment allocInfo = arena.allocate(DESCRIPTOR_SET_ALLOCATE_INFO);
            si(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            sl(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "descriptorPool", descriptorPool);
            si(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "descriptorSetCount", 1);
            sa(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "pSetLayouts", pSetLayouts);
            MemorySegment pSet = arena.allocate(JAVA_LONG);
            check(invoke(vkAllocateDescriptorSets, dev, allocInfo, pSet), "vkAllocateDescriptorSets");
            this.descriptorSet = pSet.get(JAVA_LONG, 0);

            MemorySegment bufferInfo = arena.allocate(DESCRIPTOR_BUFFER_INFO);
            sl(bufferInfo, DESCRIPTOR_BUFFER_INFO, "buffer", buffer);
            sl(bufferInfo, DESCRIPTOR_BUFFER_INFO, "offset", 0L);
            sl(bufferInfo, DESCRIPTOR_BUFFER_INFO, "range", Vk.WHOLE_SIZE);
            MemorySegment write = arena.allocate(WRITE_DESCRIPTOR_SET);
            si(write, WRITE_DESCRIPTOR_SET, "sType", Vk.STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            sl(write, WRITE_DESCRIPTOR_SET, "dstSet", descriptorSet);
            si(write, WRITE_DESCRIPTOR_SET, "dstBinding", binding);
            si(write, WRITE_DESCRIPTOR_SET, "descriptorCount", 1);
            si(write, WRITE_DESCRIPTOR_SET, "descriptorType", Vk.DESCRIPTOR_TYPE_STORAGE_BUFFER);
            sa(write, WRITE_DESCRIPTOR_SET, "pBufferInfo", bufferInfo);
            invokeVoid(vkUpdateDescriptorSets, dev, 1, write, 0, MemorySegment.NULL);
        }
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
        return descriptorSetLayout;
    }

    /** The set to bind at set 0 when drawing with such a pipeline. */
    public long descriptorSet() {
        return descriptorSet;
    }

    @Override
    public void close() {
        Probe.closed(Lane.GPU, "StorageBuffer", this);
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyDescriptorPool, dev, descriptorPool, MemorySegment.NULL);
        invokeVoid(vkDestroyDescriptorSetLayout, dev, descriptorSetLayout, MemorySegment.NULL);
        invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, memory, MemorySegment.NULL);
    }
}
