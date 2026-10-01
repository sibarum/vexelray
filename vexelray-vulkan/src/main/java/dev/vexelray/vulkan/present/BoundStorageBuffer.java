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
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.sa;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The descriptor that lets a fragment shader read a storage buffer <b>somebody else owns</b>: a set layout, a pool
 * and a set pointing at a {@code VkBuffer} this class did not make and will not free.
 *
 * <h2>Why it is its own class</h2>
 *
 * <p>{@link StorageBuffer} is two things — a host-visible allocation, and the descriptor that binds it — and only
 * the second is what a renderer needs from a buffer. A simulation that computes on the device this one draws on
 * holds its state in buffers of its own, device-local and written by kernels, and the picture of it should read
 * that state where it already is: copying it to the host and back into a {@code StorageBuffer} would put a
 * readback in every frame to move numbers that never needed to leave the GPU. So the descriptor is separated from
 * the allocation, {@code StorageBuffer} is built on it, and a caller with a buffer already in hand binds it here.
 *
 * <h2>What the caller owes</h2>
 *
 * <ul>
 *   <li><b>The buffer is the caller's, and must outlive this.</b> It must have been made with
 *       {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT} on the <em>same device</em> as the one given here; a buffer from
 *       another device is a validation error, not a blank picture.
 *   <li><b>Whatever wrote it must be finished, and visible.</b> This does not order the producer against the
 *       draw. A producer that submitted work and waited on its fence before the draw is submitted is enough: the
 *       wait is the execution dependency, and the submit that follows makes its writes visible to the shaders
 *       that read them.
 * </ul>
 *
 * <p>The buffer is read whole ({@code VK_WHOLE_SIZE}) at the one binding, from the fragment stage.
 */
public final class BoundStorageBuffer implements AutoCloseable {

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);

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
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;

    private final MethodHandle vkDestroyDescriptorSetLayout;
    private final MethodHandle vkDestroyDescriptorPool;

    /**
     * A set binding {@code buffer} at set 0 / {@code binding}, readable from the fragment stage.
     *
     * @param buffer a {@code VkBuffer} on {@code device}, made with storage-buffer usage; not owned
     */
    public BoundStorageBuffer(VulkanDevice device, long buffer, int binding) {
        Probe.opened(Lane.GPU, "BoundStorageBuffer", this);
        this.device = device;
        MemorySegment dev = device.handle();

        MethodHandle vkCreateDescriptorSetLayout = device.command("vkCreateDescriptorSetLayout", C4);
        this.vkDestroyDescriptorSetLayout = device.command("vkDestroyDescriptorSetLayout", D_LONG);
        MethodHandle vkCreateDescriptorPool = device.command("vkCreateDescriptorPool", C4);
        this.vkDestroyDescriptorPool = device.command("vkDestroyDescriptorPool", D_LONG);
        MethodHandle vkAllocateDescriptorSets = device.command("vkAllocateDescriptorSets",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkUpdateDescriptorSets = device.command("vkUpdateDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

        try (Arena arena = Arena.ofConfined()) {
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

    /** The layout a pipeline reading this buffer must be built against. */
    public long descriptorSetLayout() {
        return descriptorSetLayout;
    }

    /** The set to bind at set 0 when drawing with such a pipeline. */
    public long descriptorSet() {
        return descriptorSet;
    }

    /** Frees the layout, the pool and the set. The buffer it pointed at is untouched. */
    @Override
    public void close() {
        Probe.closed(Lane.GPU, "BoundStorageBuffer", this);
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyDescriptorPool, dev, descriptorPool, MemorySegment.NULL);
        invokeVoid(vkDestroyDescriptorSetLayout, dev, descriptorSetLayout, MemorySegment.NULL);
    }
}
