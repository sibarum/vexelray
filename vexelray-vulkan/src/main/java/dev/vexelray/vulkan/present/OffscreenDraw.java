package dev.vexelray.vulkan.present;

import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkStructs;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static dev.vexelray.vulkan.vk.Ffm.check;
import static dev.vexelray.vulkan.vk.Ffm.gi;
import static dev.vexelray.vulkan.vk.Ffm.gl;
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.invokeVoid;
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Renders a single vertex-buffer draw (optionally with a bound descriptor set) into an offscreen colour image and
 * reads it back as RGBA — the reusable "draw to a texture" path. The supplied {@link GraphicsPipeline} must have
 * been built against {@code renderPass}, whose final layout is {@code TRANSFER_SRC_OPTIMAL} (e.g. a
 * {@link VulkanRenderPass} for that layout), so the image is copy-ready after the pass. Everything transient
 * (image, framebuffer, readback buffer, command buffer) is created and destroyed per call.
 *
 * <p>Binding-agnostic to what is being drawn: the caller owns the vertex buffer and (optional) descriptor set.
 * A future runtime will cache these; this proves the path and backs the Canvas texture target.
 */
public final class OffscreenDraw {

    private OffscreenDraw() {
    }

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor MEMREQ = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    /**
     * Render {@code vertexCount} vertices from {@code vertexBuffer} (binding 0), with {@code descriptorSet} bound at
     * set 0 if non-zero, into a {@code width}×{@code height} R8G8B8A8 image cleared to {@code (cr,cg,cb,ca)}, and
     * return the result as tightly-packed RGBA (row-major, top-to-bottom).
     */
    public static byte[] toRgba(VulkanDevice device, long renderPass, GraphicsPipeline pipeline, int width, int height,
                                long vertexBuffer, long descriptorSet, int vertexCount,
                                float cr, float cg, float cb, float ca) {
        return toRgba(device, renderPass, pipeline, width, height, vertexBuffer, descriptorSet, 0L, vertexCount,
                cr, cg, cb, ca);
    }

    /**
     * As {@link #toRgba(VulkanDevice, long, GraphicsPipeline, int, int, long, long, int, float, float, float, float)},
     * additionally binding {@code descriptorSet1} at set 1 when non-zero — for a pipeline whose layout declares two
     * sets, such as the Canvas pipeline's atlas + image pair. Both are bound in one call, so a pipeline expecting
     * two sets never records a draw with only one of them live.
     */
    public static byte[] toRgba(VulkanDevice device, long renderPass, GraphicsPipeline pipeline, int width, int height,
                                long vertexBuffer, long descriptorSet, long descriptorSet1, int vertexCount,
                                float cr, float cg, float cb, float ca) {
        return toRgba(device, renderPass, pipeline, width, height, vertexBuffer, descriptorSet,
                List.of(new WindowedPresenter.Run(descriptorSet1, 0, vertexCount)), cr, cg, cb, ca);
    }

    /**
     * As the single-span overload, but drawing the buffer as a sequence of {@link WindowedPresenter.Run}s — the
     * headless twin of {@code WindowedPresenter.setRuns}, so a tree holding images captures to PNG exactly as it
     * presents to a window. Sharing the run type with the presenter is the point: the two paths cannot drift into
     * disagreeing about what a frame is.
     */
    public static byte[] toRgba(VulkanDevice device, long renderPass, GraphicsPipeline pipeline, int width, int height,
                                long vertexBuffer, long descriptorSet, List<WindowedPresenter.Run> runs,
                                float cr, float cg, float cb, float ca) {
        MemorySegment dev = device.handle();
        long pixelBytes = (long) width * height * 4;

        MethodHandle vkCreateImage = device.command("vkCreateImage", C4);
        MethodHandle vkDestroyImage = device.command("vkDestroyImage", D_LONG);
        MethodHandle vkGetImageMemoryRequirements = device.command("vkGetImageMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        MethodHandle vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        MethodHandle vkBindImageMemory = device.command("vkBindImageMemory", BIND);
        MethodHandle vkCreateImageView = device.command("vkCreateImageView", C4);
        MethodHandle vkDestroyImageView = device.command("vkDestroyImageView", D_LONG);
        MethodHandle vkCreateFramebuffer = device.command("vkCreateFramebuffer", C4);
        MethodHandle vkDestroyFramebuffer = device.command("vkDestroyFramebuffer", D_LONG);
        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkUnmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool", C4);
        MethodHandle vkDestroyCommandPool = device.command("vkDestroyCommandPool", D_LONG);
        MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle vkEndCommandBuffer = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle vkCmdBeginRenderPass = device.command("vkCmdBeginRenderPass",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        MethodHandle vkCmdEndRenderPass = device.command("vkCmdEndRenderPass", FunctionDescriptor.ofVoid(ADDRESS));
        MethodHandle vkCmdBindPipeline = device.command("vkCmdBindPipeline",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
        MethodHandle vkCmdBindVertexBuffers = device.command("vkCmdBindVertexBuffers",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle vkCmdBindDescriptorSets = device.command("vkCmdBindDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        MethodHandle vkCmdDraw = device.command("vkCmdDraw",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        MethodHandle vkCmdCopyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment imgInfo = arena.allocate(VkStructs.IMAGE_CREATE_INFO);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "imageType", Vk.IMAGE_TYPE_2D);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "format", Vk.FORMAT_R8G8B8A8_UNORM);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "extent_width", width);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "extent_height", height);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "extent_depth", 1);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "mipLevels", 1);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "arrayLayers", 1);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "samples", Vk.SAMPLE_COUNT_1_BIT);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "tiling", Vk.IMAGE_TILING_OPTIMAL);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "usage", Vk.IMAGE_USAGE_COLOR_ATTACHMENT_BIT | Vk.IMAGE_USAGE_TRANSFER_SRC_BIT);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
            MemorySegment pImage = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImage, dev, imgInfo, MemorySegment.NULL, pImage), "vkCreateImage");
            long image = pImage.get(JAVA_LONG, 0);
            MemorySegment imgReq = arena.allocate(VkStructs.MEMORY_REQUIREMENTS);
            invokeVoid(vkGetImageMemoryRequirements, dev, image, imgReq);
            long imageMemory = allocate(arena, vkAllocateMemory, dev, gl(imgReq, VkStructs.MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(imgReq, VkStructs.MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(invoke(vkBindImageMemory, dev, image, imageMemory, 0L), "vkBindImageMemory");

            MemorySegment viewInfo = arena.allocate(VkStructs.IMAGE_VIEW_CREATE_INFO);
            si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            sl(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "image", image);
            si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
            si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "format", Vk.FORMAT_R8G8B8A8_UNORM);
            si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_levelCount", 1);
            si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
            MemorySegment pView = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImageView, dev, viewInfo, MemorySegment.NULL, pView), "vkCreateImageView");
            long view = pView.get(JAVA_LONG, 0);

            MemorySegment pAttachViews = arena.allocate(JAVA_LONG);
            pAttachViews.set(JAVA_LONG, 0, view);
            MemorySegment fbInfo = arena.allocate(VkStructs.FRAMEBUFFER_CREATE_INFO);
            si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
            sl(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "renderPass", renderPass);
            si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "attachmentCount", 1);
            sa(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "pAttachments", pAttachViews);
            si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "width", width);
            si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "height", height);
            si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "layers", 1);
            MemorySegment pFramebuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateFramebuffer, dev, fbInfo, MemorySegment.NULL, pFramebuffer), "vkCreateFramebuffer");
            long framebuffer = pFramebuffer.get(JAVA_LONG, 0);

            MemorySegment bufferInfo = arena.allocate(VkStructs.BUFFER_CREATE_INFO);
            si(bufferInfo, VkStructs.BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            sl(bufferInfo, VkStructs.BUFFER_CREATE_INFO, "size", pixelBytes);
            si(bufferInfo, VkStructs.BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
            si(bufferInfo, VkStructs.BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            MemorySegment pBuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateBuffer, dev, bufferInfo, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(JAVA_LONG, 0);
            MemorySegment bufReq = arena.allocate(VkStructs.MEMORY_REQUIREMENTS);
            invokeVoid(vkGetBufferMemoryRequirements, dev, buffer, bufReq);
            long bufferMemory = allocate(arena, vkAllocateMemory, dev, gl(bufReq, VkStructs.MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(bufReq, VkStructs.MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(invoke(vkBindBufferMemory, dev, buffer, bufferMemory, 0L), "vkBindBufferMemory");

            MemorySegment poolInfo = arena.allocate(VkStructs.COMMAND_POOL_CREATE_INFO);
            si(poolInfo, VkStructs.COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            si(poolInfo, VkStructs.COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
            MemorySegment pPool = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateCommandPool, dev, poolInfo, MemorySegment.NULL, pPool), "vkCreateCommandPool");
            long pool = pPool.get(JAVA_LONG, 0);
            MemorySegment cbAlloc = arena.allocate(VkStructs.COMMAND_BUFFER_ALLOCATE_INFO);
            si(cbAlloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            sl(cbAlloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
            si(cbAlloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
            si(cbAlloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
            MemorySegment pCmd = arena.allocate(ADDRESS);
            check(invoke(vkAllocateCommandBuffers, dev, cbAlloc, pCmd), "vkAllocateCommandBuffers");
            MemorySegment cmd = pCmd.get(ADDRESS, 0);

            MemorySegment beginInfo = arena.allocate(VkStructs.COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, VkStructs.COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, VkStructs.COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(invoke(vkBeginCommandBuffer, cmd, beginInfo), "vkBeginCommandBuffer");

            MemorySegment clearValue = arena.allocate(JAVA_FLOAT, 4);
            clearValue.setAtIndex(JAVA_FLOAT, 0, cr);
            clearValue.setAtIndex(JAVA_FLOAT, 1, cg);
            clearValue.setAtIndex(JAVA_FLOAT, 2, cb);
            clearValue.setAtIndex(JAVA_FLOAT, 3, ca);
            MemorySegment rpBegin = arena.allocate(VkStructs.RENDER_PASS_BEGIN_INFO);
            si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            sl(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "renderPass", renderPass);
            sl(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "framebuffer", framebuffer);
            si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "area_extent_width", width);
            si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "area_extent_height", height);
            si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "clearValueCount", 1);
            sa(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "pClearValues", clearValue);

            invokeVoid(vkCmdBeginRenderPass, cmd, rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
            invokeVoid(vkCmdBindPipeline, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
            if (descriptorSet != 0) {
                MemorySegment pSet = arena.allocate(JAVA_LONG);
                pSet.set(JAVA_LONG, 0, descriptorSet);
                invokeVoid(vkCmdBindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(),
                        0, 1, pSet, 0, MemorySegment.NULL);
            }
            if (vertexBuffer != 0) {
                MemorySegment pVb = arena.allocate(JAVA_LONG);
                pVb.set(JAVA_LONG, 0, vertexBuffer);
                MemorySegment pOff = arena.allocate(JAVA_LONG);
                pOff.set(JAVA_LONG, 0, 0L);
                invokeVoid(vkCmdBindVertexBuffers, cmd, 0, 1, pVb, pOff);
                MemorySegment pSet1 = arena.allocate(JAVA_LONG);
                long bound = 0;
                for (WindowedPresenter.Run r : runs) {
                    if (r.vertexCount() <= 0) {
                        continue;
                    }
                    if (r.descriptorSet1() != 0 && r.descriptorSet1() != bound) {
                        bound = r.descriptorSet1();
                        pSet1.set(JAVA_LONG, 0, bound);
                        invokeVoid(vkCmdBindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS,
                                pipeline.pipelineLayout(), 1, 1, pSet1, 0, MemorySegment.NULL);
                    }
                    invokeVoid(vkCmdDraw, cmd, r.vertexCount(), 1, r.firstVertex(), 0);
                }
            }
            invokeVoid(vkCmdEndRenderPass, cmd);

            MemorySegment region = arena.allocate(VkStructs.BUFFER_IMAGE_COPY);
            si(region, VkStructs.BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(region, VkStructs.BUFFER_IMAGE_COPY, "is_layerCount", 1);
            si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_width", width);
            si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_height", height);
            si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_depth", 1);
            invokeVoid(vkCmdCopyImageToBuffer, cmd, image, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, region);
            check(invoke(vkEndCommandBuffer, cmd), "vkEndCommandBuffer");

            MemorySegment pCmdArray = arena.allocate(ADDRESS);
            pCmdArray.set(ADDRESS, 0, cmd);
            MemorySegment submit = arena.allocate(VkStructs.SUBMIT_INFO);
            si(submit, VkStructs.SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, VkStructs.SUBMIT_INFO, "commandBufferCount", 1);
            sa(submit, VkStructs.SUBMIT_INFO, "pCommandBuffers", pCmdArray);
            check(invoke(vkQueueSubmit, device.queue(), 1, submit, 0L), "vkQueueSubmit");
            device.waitIdle();

            MemorySegment ppData = arena.allocate(ADDRESS);
            check(invoke(vkMapMemory, dev, bufferMemory, 0L, pixelBytes, 0, ppData), "vkMapMemory");
            byte[] pixels = ppData.get(ADDRESS, 0).reinterpret(pixelBytes).toArray(JAVA_BYTE);
            invokeVoid(vkUnmapMemory, dev, bufferMemory);

            invokeVoid(vkDestroyCommandPool, dev, pool, MemorySegment.NULL);
            invokeVoid(vkDestroyFramebuffer, dev, framebuffer, MemorySegment.NULL);
            invokeVoid(vkDestroyImageView, dev, view, MemorySegment.NULL);
            invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, bufferMemory, MemorySegment.NULL);
            invokeVoid(vkDestroyImage, dev, image, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, imageMemory, MemorySegment.NULL);
            return pixels;
        }
    }

    private static long allocate(Arena arena, MethodHandle vkAllocateMemory, MemorySegment dev, long size, int typeIndex) {
        MemorySegment info = arena.allocate(VkStructs.MEMORY_ALLOCATE_INFO);
        si(info, VkStructs.MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
        sl(info, VkStructs.MEMORY_ALLOCATE_INFO, "allocationSize", size);
        si(info, VkStructs.MEMORY_ALLOCATE_INFO, "memoryTypeIndex", typeIndex);
        MemorySegment pMem = arena.allocate(JAVA_LONG);
        check(invoke(vkAllocateMemory, dev, info, MemorySegment.NULL, pMem), "vkAllocateMemory");
        return pMem.get(JAVA_LONG, 0);
    }
}
