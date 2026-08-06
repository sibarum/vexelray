package dev.vexelray.vulkan.offscreen;

import dev.vexelray.os.ffi.NativeException;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Renders to an offscreen image on the GPU and reads the result back to CPU memory — the headless path (no
 * window, no swapchain) that makes GPU output inspectable and screenshotable. This first cut clears the image to
 * a colour via {@code vkCmdClearColorImage} (no render pass yet) and copies it into a host-visible buffer; it
 * proves the whole image/memory/command/readback plumbing that real draws reuse.
 *
 * <p>Non-dispatchable Vulkan handles (image, buffer, memory, command pool, fence) are {@code uint64} — bound as
 * {@code JAVA_LONG}; dispatchable ones (device, queue, command buffer) are pointers ({@code ADDRESS}). Struct
 * fields are written by name through {@code layout.byteOffset(...)} (offsets from the layout, never literals).
 */
public final class OffscreenReadback {

    private static final GroupLayout IMAGE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("imageType"), JAVA_INT.withName("format"),
            JAVA_INT.withName("extent_width"), JAVA_INT.withName("extent_height"), JAVA_INT.withName("extent_depth"),
            JAVA_INT.withName("mipLevels"), JAVA_INT.withName("arrayLayers"), JAVA_INT.withName("samples"),
            JAVA_INT.withName("tiling"), JAVA_INT.withName("usage"), JAVA_INT.withName("sharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices"), JAVA_INT.withName("initialLayout"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageCreateInfo");

    private static final GroupLayout MEMORY_REQUIREMENTS = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"), JAVA_LONG.withName("alignment"),
            JAVA_INT.withName("memoryTypeBits"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryRequirements");

    private static final GroupLayout MEMORY_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("allocationSize"), JAVA_INT.withName("memoryTypeIndex"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryAllocateInfo");

    private static final GroupLayout BUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("size"),
            JAVA_INT.withName("usage"), JAVA_INT.withName("sharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices")
    ).withName("VkBufferCreateInfo");

    private static final GroupLayout COMMAND_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("queueFamilyIndex")
    ).withName("VkCommandPoolCreateInfo");

    private static final GroupLayout COMMAND_BUFFER_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("commandPool"), JAVA_INT.withName("level"), JAVA_INT.withName("commandBufferCount")
    ).withName("VkCommandBufferAllocateInfo");

    private static final GroupLayout COMMAND_BUFFER_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pInheritanceInfo")
    ).withName("VkCommandBufferBeginInfo");

    private static final GroupLayout IMAGE_MEMORY_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("oldLayout"), JAVA_INT.withName("newLayout"),
            JAVA_INT.withName("srcQueueFamilyIndex"), JAVA_INT.withName("dstQueueFamilyIndex"),
            JAVA_LONG.withName("image"),
            JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"), JAVA_INT.withName("sr_levelCount"),
            JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageMemoryBarrier");

    private static final GroupLayout SUBRESOURCE_RANGE = MemoryLayout.structLayout(
            JAVA_INT.withName("aspectMask"), JAVA_INT.withName("baseMipLevel"), JAVA_INT.withName("levelCount"),
            JAVA_INT.withName("baseArrayLayer"), JAVA_INT.withName("layerCount")
    ).withName("VkImageSubresourceRange");

    private static final GroupLayout BUFFER_IMAGE_COPY = MemoryLayout.structLayout(
            JAVA_LONG.withName("bufferOffset"), JAVA_INT.withName("bufferRowLength"), JAVA_INT.withName("bufferImageHeight"),
            JAVA_INT.withName("is_aspectMask"), JAVA_INT.withName("is_mipLevel"),
            JAVA_INT.withName("is_baseArrayLayer"), JAVA_INT.withName("is_layerCount"),
            JAVA_INT.withName("off_x"), JAVA_INT.withName("off_y"), JAVA_INT.withName("off_z"),
            JAVA_INT.withName("ext_width"), JAVA_INT.withName("ext_height"), JAVA_INT.withName("ext_depth")
    ).withName("VkBufferImageCopy");

    private static final GroupLayout SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")
    ).withName("VkSubmitInfo");

    private static final GroupLayout FENCE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)
    ).withName("VkFenceCreateInfo");

    private OffscreenReadback() {
    }

    /**
     * Clear a {@code width}×{@code height} image to the given RGBA colour on the GPU and read it back. Returns
     * tightly-packed R8G8B8A8 bytes, row-major, top-to-bottom ({@code width*height*4} bytes).
     */
    public static byte[] clearToRgba(VulkanDevice device, int width, int height,
                                     float r, float g, float b, float a) {
        MemorySegment dev = device.handle();
        long pixelBytes = (long) width * height * 4;

        MethodHandle vkCreateImage = device.command("vkCreateImage",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkDestroyImage = device.command("vkDestroyImage",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        MethodHandle vkGetImageMemoryRequirements = device.command("vkGetImageMemoryRequirements",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkFreeMemory = device.command("vkFreeMemory",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        MethodHandle vkBindImageMemory = device.command("vkBindImageMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkDestroyBuffer = device.command("vkDestroyBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkUnmapMemory = device.command("vkUnmapMemory",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkDestroyCommandPool = device.command("vkDestroyCommandPool",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle vkEndCommandBuffer = device.command("vkEndCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle vkCmdPipelineBarrier = device.command("vkCmdPipelineBarrier",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        MethodHandle vkCmdClearColorImage = device.command("vkCmdClearColorImage",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        MethodHandle vkCmdCopyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkCreateFence = device.command("vkCreateFence",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkDestroyFence = device.command("vkDestroyFence",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        MethodHandle vkWaitForFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));

        try (Arena arena = Arena.ofConfined()) {
            // --- image + device-local memory ---
            MemorySegment imageInfo = arena.allocate(IMAGE_CREATE_INFO);
            si(imageInfo, IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            si(imageInfo, IMAGE_CREATE_INFO, "imageType", Vk.IMAGE_TYPE_2D);
            si(imageInfo, IMAGE_CREATE_INFO, "format", Vk.FORMAT_R8G8B8A8_UNORM);
            si(imageInfo, IMAGE_CREATE_INFO, "extent_width", width);
            si(imageInfo, IMAGE_CREATE_INFO, "extent_height", height);
            si(imageInfo, IMAGE_CREATE_INFO, "extent_depth", 1);
            si(imageInfo, IMAGE_CREATE_INFO, "mipLevels", 1);
            si(imageInfo, IMAGE_CREATE_INFO, "arrayLayers", 1);
            si(imageInfo, IMAGE_CREATE_INFO, "samples", Vk.SAMPLE_COUNT_1_BIT);
            si(imageInfo, IMAGE_CREATE_INFO, "tiling", Vk.IMAGE_TILING_OPTIMAL);
            si(imageInfo, IMAGE_CREATE_INFO, "usage", Vk.IMAGE_USAGE_TRANSFER_DST_BIT | Vk.IMAGE_USAGE_TRANSFER_SRC_BIT);
            si(imageInfo, IMAGE_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            si(imageInfo, IMAGE_CREATE_INFO, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);

            MemorySegment pImage = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImage, dev, imageInfo, MemorySegment.NULL, pImage), "vkCreateImage");
            long image = pImage.get(JAVA_LONG, 0);

            MemorySegment imageReq = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetImageMemoryRequirements, dev, image, imageReq);
            long imageMemory = allocate(arena, vkAllocateMemory, dev, gl(imageReq, MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(imageReq, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(invoke(vkBindImageMemory, dev, image, imageMemory, 0L), "vkBindImageMemory");

            // --- host-visible readback buffer ---
            MemorySegment bufferInfo = arena.allocate(BUFFER_CREATE_INFO);
            si(bufferInfo, BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            sl(bufferInfo, BUFFER_CREATE_INFO, "size", pixelBytes);
            si(bufferInfo, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
            si(bufferInfo, BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);

            MemorySegment pBuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateBuffer, dev, bufferInfo, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(JAVA_LONG, 0);

            MemorySegment bufferReq = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetBufferMemoryRequirements, dev, buffer, bufferReq);
            long bufferMemory = allocate(arena, vkAllocateMemory, dev, gl(bufferReq, MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(bufferReq, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(invoke(vkBindBufferMemory, dev, buffer, bufferMemory, 0L), "vkBindBufferMemory");

            // --- command pool + buffer ---
            MemorySegment poolInfo = arena.allocate(COMMAND_POOL_CREATE_INFO);
            si(poolInfo, COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            si(poolInfo, COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
            MemorySegment pPool = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateCommandPool, dev, poolInfo, MemorySegment.NULL, pPool), "vkCreateCommandPool");
            long pool = pPool.get(JAVA_LONG, 0);

            MemorySegment cbAlloc = arena.allocate(COMMAND_BUFFER_ALLOCATE_INFO);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            sl(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
            MemorySegment pCmd = arena.allocate(ADDRESS);
            check(invoke(vkAllocateCommandBuffers, dev, cbAlloc, pCmd), "vkAllocateCommandBuffers");
            MemorySegment cmd = pCmd.get(ADDRESS, 0);

            // --- record: barrier -> clear -> barrier -> copy ---
            MemorySegment beginInfo = arena.allocate(COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(invoke(vkBeginCommandBuffer, cmd, beginInfo), "vkBeginCommandBuffer");

            MemorySegment toDst = imageBarrier(arena, image, 0, Vk.ACCESS_TRANSFER_WRITE_BIT,
                    Vk.IMAGE_LAYOUT_UNDEFINED, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            invokeVoid(vkCmdPipelineBarrier, cmd, Vk.PIPELINE_STAGE_TOP_OF_PIPE_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT,
                    0, 0, MemorySegment.NULL, 0, MemorySegment.NULL, 1, toDst);

            MemorySegment color = arena.allocate(JAVA_FLOAT, 4);
            color.setAtIndex(JAVA_FLOAT, 0, r);
            color.setAtIndex(JAVA_FLOAT, 1, g);
            color.setAtIndex(JAVA_FLOAT, 2, b);
            color.setAtIndex(JAVA_FLOAT, 3, a);
            MemorySegment range = arena.allocate(SUBRESOURCE_RANGE);
            si(range, SUBRESOURCE_RANGE, "aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(range, SUBRESOURCE_RANGE, "levelCount", 1);
            si(range, SUBRESOURCE_RANGE, "layerCount", 1);
            invokeVoid(vkCmdClearColorImage, cmd, image, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, 1, range);

            MemorySegment toSrc = imageBarrier(arena, image, Vk.ACCESS_TRANSFER_WRITE_BIT, Vk.ACCESS_TRANSFER_READ_BIT,
                    Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            invokeVoid(vkCmdPipelineBarrier, cmd, Vk.PIPELINE_STAGE_TRANSFER_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT,
                    0, 0, MemorySegment.NULL, 0, MemorySegment.NULL, 1, toSrc);

            MemorySegment region = arena.allocate(BUFFER_IMAGE_COPY);
            si(region, BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(region, BUFFER_IMAGE_COPY, "is_layerCount", 1);
            si(region, BUFFER_IMAGE_COPY, "ext_width", width);
            si(region, BUFFER_IMAGE_COPY, "ext_height", height);
            si(region, BUFFER_IMAGE_COPY, "ext_depth", 1);
            invokeVoid(vkCmdCopyImageToBuffer, cmd, image, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, region);

            check(invoke(vkEndCommandBuffer, cmd), "vkEndCommandBuffer");

            // --- submit + wait ---
            MemorySegment fenceInfo = arena.allocate(FENCE_CREATE_INFO);
            si(fenceInfo, FENCE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
            MemorySegment pFence = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateFence, dev, fenceInfo, MemorySegment.NULL, pFence), "vkCreateFence");
            long fence = pFence.get(JAVA_LONG, 0);

            MemorySegment pCmdArray = arena.allocate(ADDRESS, 1);
            pCmdArray.setAtIndex(ADDRESS, 0, cmd);
            MemorySegment submit = arena.allocate(SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "commandBufferCount", 1);
            sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmdArray);
            check(invoke(vkQueueSubmit, device.queue(), 1, submit, fence), "vkQueueSubmit");

            MemorySegment pFenceArray = arena.allocate(JAVA_LONG);
            pFenceArray.set(JAVA_LONG, 0, fence);
            check(invoke(vkWaitForFences, dev, 1, pFenceArray, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");

            // --- map + read ---
            MemorySegment ppData = arena.allocate(ADDRESS);
            check(invoke(vkMapMemory, dev, bufferMemory, 0L, pixelBytes, 0, ppData), "vkMapMemory");
            byte[] pixels = ppData.get(ADDRESS, 0).reinterpret(pixelBytes).toArray(JAVA_BYTE);
            invokeVoid(vkUnmapMemory, dev, bufferMemory);

            // --- teardown ---
            invokeVoid(vkDestroyFence, dev, fence, MemorySegment.NULL);
            invokeVoid(vkDestroyCommandPool, dev, pool, MemorySegment.NULL);
            invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, bufferMemory, MemorySegment.NULL);
            invokeVoid(vkDestroyImage, dev, image, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, imageMemory, MemorySegment.NULL);
            return pixels;
        }
    }

    private static long allocate(Arena arena, MethodHandle vkAllocateMemory, MemorySegment dev,
                                 long size, int memoryTypeIndex) {
        MemorySegment info = arena.allocate(MEMORY_ALLOCATE_INFO);
        si(info, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
        sl(info, MEMORY_ALLOCATE_INFO, "allocationSize", size);
        si(info, MEMORY_ALLOCATE_INFO, "memoryTypeIndex", memoryTypeIndex);
        MemorySegment pMemory = arena.allocate(JAVA_LONG);
        check(invoke(vkAllocateMemory, dev, info, MemorySegment.NULL, pMemory), "vkAllocateMemory");
        return pMemory.get(JAVA_LONG, 0);
    }

    private static MemorySegment imageBarrier(Arena arena, long image, int srcAccess, int dstAccess,
                                              int oldLayout, int newLayout) {
        MemorySegment barrier = arena.allocate(IMAGE_MEMORY_BARRIER);
        si(barrier, IMAGE_MEMORY_BARRIER, "sType", Vk.STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        si(barrier, IMAGE_MEMORY_BARRIER, "srcAccessMask", srcAccess);
        si(barrier, IMAGE_MEMORY_BARRIER, "dstAccessMask", dstAccess);
        si(barrier, IMAGE_MEMORY_BARRIER, "oldLayout", oldLayout);
        si(barrier, IMAGE_MEMORY_BARRIER, "newLayout", newLayout);
        si(barrier, IMAGE_MEMORY_BARRIER, "srcQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
        si(barrier, IMAGE_MEMORY_BARRIER, "dstQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
        sl(barrier, IMAGE_MEMORY_BARRIER, "image", image);
        si(barrier, IMAGE_MEMORY_BARRIER, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
        si(barrier, IMAGE_MEMORY_BARRIER, "sr_levelCount", 1);
        si(barrier, IMAGE_MEMORY_BARRIER, "sr_layerCount", 1);
        return barrier;
    }

    // --- tiny FFM helpers: offsets from the layout, never literals ---

    private static long off(GroupLayout layout, String field) {
        return layout.byteOffset(PathElement.groupElement(field));
    }

    private static void si(MemorySegment s, GroupLayout l, String f, int v) {
        s.set(JAVA_INT, off(l, f), v);
    }

    private static void sl(MemorySegment s, GroupLayout l, String f, long v) {
        s.set(JAVA_LONG, off(l, f), v);
    }

    private static void sa(MemorySegment s, GroupLayout l, String f, MemorySegment v) {
        s.set(ADDRESS, off(l, f), v);
    }

    private static int gi(MemorySegment s, GroupLayout l, String f) {
        return s.get(JAVA_INT, off(l, f));
    }

    private static long gl(MemorySegment s, GroupLayout l, String f) {
        return s.get(JAVA_LONG, off(l, f));
    }

    private static int invoke(MethodHandle h, Object... args) {
        try {
            return (int) h.invokeWithArguments(args);
        } catch (Throwable t) {
            throw NativeException.rethrow("vulkan call", t);
        }
    }

    private static void invokeVoid(MethodHandle h, Object... args) {
        try {
            h.invokeWithArguments(args);
        } catch (Throwable t) {
            throw NativeException.rethrow("vulkan call", t);
        }
    }

    private static void check(int result, String call) {
        if (result != Vk.VK_SUCCESS) {
            throw new NativeException(call + " failed: VkResult " + result);
        }
    }
}
