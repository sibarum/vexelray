package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.function.Consumer;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.gi;
import static dev.supirvast.vulkan.Ffm.gl;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.sa;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A picture on the device read back to the heap, and a command buffer recorded, submitted and waited for: what a test
 * or a tool needs to see what the GPU made ({@link FeedSlots#read}, {@link SampledColorTarget#read}). Each call waits
 * for the device's queue, so never in a frame. On the thread that submits to the queue.
 */
public final class ImageReadback {

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor MEMREQ = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    private static final GroupLayout MEMORY_REQUIREMENTS = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"), JAVA_LONG.withName("alignment"),
            JAVA_INT.withName("memoryTypeBits"), MemoryLayout.paddingLayout(4)).withName("VkMemoryRequirements");
    private static final GroupLayout MEMORY_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("allocationSize"), JAVA_INT.withName("memoryTypeIndex"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryAllocateInfo");
    private static final GroupLayout BUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("size"),
            JAVA_INT.withName("usage"), JAVA_INT.withName("sharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices")).withName("VkBufferCreateInfo");
    private static final GroupLayout IMAGE_MEMORY_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("oldLayout"), JAVA_INT.withName("newLayout"),
            JAVA_INT.withName("srcQueueFamilyIndex"), JAVA_INT.withName("dstQueueFamilyIndex"),
            JAVA_LONG.withName("image"), JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"),
            JAVA_INT.withName("sr_levelCount"), JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"),
            MemoryLayout.paddingLayout(4)).withName("VkImageMemoryBarrier");
    private static final GroupLayout BUFFER_IMAGE_COPY = MemoryLayout.structLayout(
            JAVA_LONG.withName("bufferOffset"), JAVA_INT.withName("bufferRowLength"), JAVA_INT.withName("bufferImageHeight"),
            JAVA_INT.withName("is_aspectMask"), JAVA_INT.withName("is_mipLevel"),
            JAVA_INT.withName("is_baseArrayLayer"), JAVA_INT.withName("is_layerCount"),
            JAVA_INT.withName("off_x"), JAVA_INT.withName("off_y"), JAVA_INT.withName("off_z"),
            JAVA_INT.withName("ext_width"), JAVA_INT.withName("ext_height"), JAVA_INT.withName("ext_depth")
    ).withName("VkBufferImageCopy");
    private static final GroupLayout COMMAND_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("queueFamilyIndex")).withName("VkCommandPoolCreateInfo");
    private static final GroupLayout COMMAND_BUFFER_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("commandPool"), JAVA_INT.withName("level"), JAVA_INT.withName("commandBufferCount")
    ).withName("VkCommandBufferAllocateInfo");
    private static final GroupLayout COMMAND_BUFFER_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pInheritanceInfo")
    ).withName("VkCommandBufferBeginInfo");
    private static final GroupLayout SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private ImageReadback() {
    }

    /**
     * Level {@code level} ({@code w x h} texels) of a 4-byte-per-texel colour {@code image} in
     * {@code SHADER_READ_ONLY_OPTIMAL}, read back row after row, and left in that layout.
     */
    public static byte[] read(VulkanDevice device, long image, int w, int h, int level) {
        long bytes = (long) w * h * 4;
        MemorySegment dev = device.handle();
        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkUnmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        MethodHandle vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        MethodHandle vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        MethodHandle vkCmdCopyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkCmdPipelineBarrier = device.command("vkCmdPipelineBarrier",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bufInfo = arena.allocate(BUFFER_CREATE_INFO);
            si(bufInfo, BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            sl(bufInfo, BUFFER_CREATE_INFO, "size", bytes);
            si(bufInfo, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
            si(bufInfo, BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            MemorySegment pBuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateBuffer, dev, bufInfo, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(JAVA_LONG, 0);
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
            long memory = pMem.get(JAVA_LONG, 0);
            check(invoke(vkBindBufferMemory, dev, buffer, memory, 0L), "vkBindBufferMemory");
            try {
                oneShot(device, cmd -> {
                    barrier(arena, vkCmdPipelineBarrier, cmd, image, level, Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, Vk.ACCESS_SHADER_READ_BIT, Vk.ACCESS_TRANSFER_READ_BIT,
                            Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT);
                    MemorySegment region = arena.allocate(BUFFER_IMAGE_COPY);
                    si(region, BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                    si(region, BUFFER_IMAGE_COPY, "is_mipLevel", level);
                    si(region, BUFFER_IMAGE_COPY, "is_layerCount", 1);
                    si(region, BUFFER_IMAGE_COPY, "ext_width", w);
                    si(region, BUFFER_IMAGE_COPY, "ext_height", h);
                    si(region, BUFFER_IMAGE_COPY, "ext_depth", 1);
                    invokeVoid(vkCmdCopyImageToBuffer, cmd, image, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1,
                            region);
                    barrier(arena, vkCmdPipelineBarrier, cmd, image, level, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, Vk.ACCESS_TRANSFER_READ_BIT,
                            Vk.ACCESS_SHADER_READ_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT,
                            Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                });
                MemorySegment ppData = arena.allocate(ADDRESS);
                check(invoke(vkMapMemory, dev, memory, 0L, bytes, 0, ppData), "vkMapMemory");
                byte[] out = ppData.get(ADDRESS, 0).reinterpret(bytes).toArray(JAVA_BYTE);
                invokeVoid(vkUnmapMemory, dev, memory);
                return out;
            } finally {
                invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
                invokeVoid(vkFreeMemory, dev, memory, MemorySegment.NULL);
            }
        }
    }

    /** Record {@code body} into a command buffer of its own, submit it and wait for the queue. */
    public static void oneShot(VulkanDevice device, Consumer<MemorySegment> body) {
        oneShot(device, null, body);
    }

    /**
     * A frame's pre-pass ({@link WindowPresenter#prePass}) submitted on its own at {@code width x height}, waiting for
     * what it reads that another queue wrote ({@link Recorder#waits}), as a presenter submits it; then wait for the queue.
     * For a test, or a capture of what a pre-pass draws.
     */
    public static void oneShot(VulkanDevice device, Recorder pre, int width, int height) {
        FrameWaits waits = new FrameWaits();
        oneShot(device, waits, cmd -> {
            pre.record(cmd, width, height);
            pre.waits(waits);
        });
    }

    /** As {@link #oneShot(VulkanDevice, Consumer)}, the submission also waiting for {@code waits} once {@code body} has run. */
    private static void oneShot(VulkanDevice device, FrameWaits waits, Consumer<MemorySegment> body) {
        MemorySegment dev = device.handle();
        MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool", C4);
        MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle vkEndCommandBuffer = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        MethodHandle vkQueueWaitIdle = device.command("vkQueueWaitIdle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle vkDestroyCommandPool = device.command("vkDestroyCommandPool", D_LONG);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment cpInfo = arena.allocate(COMMAND_POOL_CREATE_INFO);
            si(cpInfo, COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            si(cpInfo, COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
            MemorySegment pCp = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateCommandPool, dev, cpInfo, MemorySegment.NULL, pCp), "vkCreateCommandPool");
            long pool = pCp.get(JAVA_LONG, 0);
            try {
                MemorySegment cbInfo = arena.allocate(COMMAND_BUFFER_ALLOCATE_INFO);
                si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
                sl(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
                si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
                si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
                MemorySegment pCmd = arena.allocate(ADDRESS);
                check(invoke(vkAllocateCommandBuffers, dev, cbInfo, pCmd), "vkAllocateCommandBuffers");
                MemorySegment cmd = pCmd.get(ADDRESS, 0);
                MemorySegment begin = arena.allocate(COMMAND_BUFFER_BEGIN_INFO);
                si(begin, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                si(begin, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(invoke(vkBeginCommandBuffer, cmd, begin), "vkBeginCommandBuffer");
                body.accept(cmd);
                check(invoke(vkEndCommandBuffer, cmd), "vkEndCommandBuffer");
                MemorySegment submit = arena.allocate(SUBMIT_INFO);
                si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
                si(submit, SUBMIT_INFO, "commandBufferCount", 1);
                sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmd);
                if (waits != null) {
                    waits.applyTo(arena, submit);
                }
                check(invoke(vkQueueSubmit, device.queue(), 1, submit, 0L), "vkQueueSubmit");
                check(invoke(vkQueueWaitIdle, device.queue()), "vkQueueWaitIdle");
            } finally {
                invokeVoid(vkDestroyCommandPool, dev, pool, MemorySegment.NULL);
            }
        }
    }

    private static void barrier(Arena arena, MethodHandle vkCmdPipelineBarrier, MemorySegment cmd, long image, int level,
                                int oldLayout, int newLayout, int srcAccess, int dstAccess, int srcStage, int dstStage) {
        MemorySegment b = arena.allocate(IMAGE_MEMORY_BARRIER);
        si(b, IMAGE_MEMORY_BARRIER, "sType", Vk.STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        si(b, IMAGE_MEMORY_BARRIER, "srcAccessMask", srcAccess);
        si(b, IMAGE_MEMORY_BARRIER, "dstAccessMask", dstAccess);
        si(b, IMAGE_MEMORY_BARRIER, "oldLayout", oldLayout);
        si(b, IMAGE_MEMORY_BARRIER, "newLayout", newLayout);
        si(b, IMAGE_MEMORY_BARRIER, "srcQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
        si(b, IMAGE_MEMORY_BARRIER, "dstQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
        sl(b, IMAGE_MEMORY_BARRIER, "image", image);
        si(b, IMAGE_MEMORY_BARRIER, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
        si(b, IMAGE_MEMORY_BARRIER, "sr_baseMipLevel", level);
        si(b, IMAGE_MEMORY_BARRIER, "sr_levelCount", 1);
        si(b, IMAGE_MEMORY_BARRIER, "sr_layerCount", 1);
        invokeVoid(vkCmdPipelineBarrier, cmd, srcStage, dstStage, 0, 0, MemorySegment.NULL, 0, MemorySegment.NULL,
                1, b);
    }
}
