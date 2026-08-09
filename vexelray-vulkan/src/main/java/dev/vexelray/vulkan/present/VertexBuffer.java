package dev.vexelray.vulkan.present;

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
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A host-visible, host-coherent vertex buffer holding interleaved {@code float}s — the simplest upload path
 * (map, copy). Persistently mapped, so {@link #update(float[])} can rewrite its contents cheaply each frame; this
 * is what immediate-mode UI (a Canvas rebuilt per frame) needs. Allocate once at a capacity and refill; the caller
 * must ensure the previous frame's draw has completed before updating (the windowed present loop's per-frame fence
 * wait guarantees this at one frame in flight). A device-local + staging variant is a later optimisation.
 */
public final class VertexBuffer implements AutoCloseable {

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
    private final long capacityFloats;
    private final MemorySegment mapped;
    private final MethodHandle vkDestroyBuffer;
    private final MethodHandle vkFreeMemory;

    /** A static buffer initialised with {@code vertices} and sized exactly to them. */
    public VertexBuffer(VulkanDevice device, float[] vertices) {
        this(device, vertices.length);
        update(vertices);
    }

    /** A dynamic buffer with room for {@code capacityFloats} floats, ready for repeated {@link #update(float[])}. */
    public VertexBuffer(VulkanDevice device, int capacityFloats) {
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
            si(info, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_VERTEX_BUFFER_BIT);
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
    }

    /** Overwrite the buffer's contents with {@code vertices} (must not exceed the buffer's capacity). */
    public void update(float[] vertices) {
        if (vertices.length > capacityFloats) {
            throw new IllegalArgumentException("vertex data (" + vertices.length + " floats) exceeds capacity ("
                    + capacityFloats + ")");
        }
        MemorySegment.copy(vertices, 0, mapped, JAVA_FLOAT, 0, vertices.length);
    }

    /** The {@code VkBuffer} handle to bind at vertex-input binding 0. */
    public long handle() {
        return buffer;
    }

    @Override
    public void close() {
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, memory, MemorySegment.NULL);
    }
}
