package dev.vexelray.vulkan.offscreen;

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
import static dev.vexelray.vulkan.vk.Ffm.sf;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Rasterises a draw into an offscreen colour image and reads it back — the first path that runs an actual
 * graphics pipeline whose shaders are SPIR-V (composed by SupirVast). Creates an image + view, a one-attachment
 * render pass whose final layout is {@code TRANSFER_SRC_OPTIMAL} (so the image is copy-ready after the pass), a
 * framebuffer, shader modules, and a graphics pipeline with an empty vertex input (the fullscreen triangle comes
 * from {@code gl_VertexIndex}); records clear → draw → copy-to-buffer; then maps the buffer and returns RGBA.
 *
 * <p>Everything is created and destroyed per call — deliberately simple for the first cut. The reusable
 * runtime ({@code RuntimeManager}) will cache these; this proves the pipeline end to end.
 */
public final class OffscreenRenderer {

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

    private static final GroupLayout IMAGE_VIEW_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("image"),
            JAVA_INT.withName("viewType"), JAVA_INT.withName("format"),
            JAVA_INT.withName("c_r"), JAVA_INT.withName("c_g"), JAVA_INT.withName("c_b"), JAVA_INT.withName("c_a"),
            JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"), JAVA_INT.withName("sr_levelCount"),
            JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageViewCreateInfo");

    private static final GroupLayout ATTACHMENT_DESCRIPTION = MemoryLayout.structLayout(
            JAVA_INT.withName("flags"), JAVA_INT.withName("format"), JAVA_INT.withName("samples"),
            JAVA_INT.withName("loadOp"), JAVA_INT.withName("storeOp"), JAVA_INT.withName("stencilLoadOp"),
            JAVA_INT.withName("stencilStoreOp"), JAVA_INT.withName("initialLayout"), JAVA_INT.withName("finalLayout")
    ).withName("VkAttachmentDescription");

    private static final GroupLayout ATTACHMENT_REFERENCE = MemoryLayout.structLayout(
            JAVA_INT.withName("attachment"), JAVA_INT.withName("layout")).withName("VkAttachmentReference");

    private static final GroupLayout SUBPASS_DESCRIPTION = MemoryLayout.structLayout(
            JAVA_INT.withName("flags"), JAVA_INT.withName("pipelineBindPoint"), JAVA_INT.withName("inputAttachmentCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pInputAttachments"),
            JAVA_INT.withName("colorAttachmentCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pColorAttachments"), ADDRESS.withName("pResolveAttachments"),
            ADDRESS.withName("pDepthStencilAttachment"), JAVA_INT.withName("preserveAttachmentCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pPreserveAttachments")).withName("VkSubpassDescription");

    private static final GroupLayout SUBPASS_DEPENDENCY = MemoryLayout.structLayout(
            JAVA_INT.withName("srcSubpass"), JAVA_INT.withName("dstSubpass"), JAVA_INT.withName("srcStageMask"),
            JAVA_INT.withName("dstStageMask"), JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("dependencyFlags")).withName("VkSubpassDependency");

    private static final GroupLayout RENDER_PASS_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("attachmentCount"), ADDRESS.withName("pAttachments"),
            JAVA_INT.withName("subpassCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pSubpasses"),
            JAVA_INT.withName("dependencyCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pDependencies")
    ).withName("VkRenderPassCreateInfo");

    private static final GroupLayout FRAMEBUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("renderPass"),
            JAVA_INT.withName("attachmentCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pAttachments"),
            JAVA_INT.withName("width"), JAVA_INT.withName("height"), JAVA_INT.withName("layers"),
            MemoryLayout.paddingLayout(4)).withName("VkFramebufferCreateInfo");

    private static final GroupLayout SHADER_MODULE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("codeSize"),
            ADDRESS.withName("pCode")).withName("VkShaderModuleCreateInfo");

    private static final GroupLayout SHADER_STAGE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("stage"), JAVA_LONG.withName("module"),
            ADDRESS.withName("pName"), ADDRESS.withName("pSpecializationInfo")
    ).withName("VkPipelineShaderStageCreateInfo");

    private static final GroupLayout VERTEX_INPUT_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("vertexBindingDescriptionCount"),
            ADDRESS.withName("pVertexBindingDescriptions"), JAVA_INT.withName("vertexAttributeDescriptionCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pVertexAttributeDescriptions")
    ).withName("VkPipelineVertexInputStateCreateInfo");

    private static final GroupLayout INPUT_ASSEMBLY_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("topology"), JAVA_INT.withName("primitiveRestartEnable"),
            MemoryLayout.paddingLayout(4)).withName("VkPipelineInputAssemblyStateCreateInfo");

    private static final GroupLayout VIEWPORT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("x"), JAVA_FLOAT.withName("y"), JAVA_FLOAT.withName("width"),
            JAVA_FLOAT.withName("height"), JAVA_FLOAT.withName("minDepth"), JAVA_FLOAT.withName("maxDepth")
    ).withName("VkViewport");

    private static final GroupLayout RECT2D = MemoryLayout.structLayout(
            JAVA_INT.withName("offset_x"), JAVA_INT.withName("offset_y"),
            JAVA_INT.withName("extent_width"), JAVA_INT.withName("extent_height")).withName("VkRect2D");

    private static final GroupLayout VIEWPORT_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("viewportCount"), ADDRESS.withName("pViewports"),
            JAVA_INT.withName("scissorCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pScissors")
    ).withName("VkPipelineViewportStateCreateInfo");

    private static final GroupLayout RASTERIZATION_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("depthClampEnable"), JAVA_INT.withName("rasterizerDiscardEnable"),
            JAVA_INT.withName("polygonMode"), JAVA_INT.withName("cullMode"), JAVA_INT.withName("frontFace"),
            JAVA_INT.withName("depthBiasEnable"), JAVA_FLOAT.withName("depthBiasConstantFactor"),
            JAVA_FLOAT.withName("depthBiasClamp"), JAVA_FLOAT.withName("depthBiasSlopeFactor"),
            JAVA_FLOAT.withName("lineWidth"), MemoryLayout.paddingLayout(4)
    ).withName("VkPipelineRasterizationStateCreateInfo");

    private static final GroupLayout MULTISAMPLE_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("rasterizationSamples"), JAVA_INT.withName("sampleShadingEnable"),
            JAVA_FLOAT.withName("minSampleShading"), ADDRESS.withName("pSampleMask"),
            JAVA_INT.withName("alphaToCoverageEnable"), JAVA_INT.withName("alphaToOneEnable")
    ).withName("VkPipelineMultisampleStateCreateInfo");

    private static final GroupLayout COLOR_BLEND_ATTACHMENT = MemoryLayout.structLayout(
            JAVA_INT.withName("blendEnable"), JAVA_INT.withName("srcColorBlendFactor"), JAVA_INT.withName("dstColorBlendFactor"),
            JAVA_INT.withName("colorBlendOp"), JAVA_INT.withName("srcAlphaBlendFactor"), JAVA_INT.withName("dstAlphaBlendFactor"),
            JAVA_INT.withName("alphaBlendOp"), JAVA_INT.withName("colorWriteMask")
    ).withName("VkPipelineColorBlendAttachmentState");

    private static final GroupLayout COLOR_BLEND_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("logicOpEnable"), JAVA_INT.withName("logicOp"),
            JAVA_INT.withName("attachmentCount"), ADDRESS.withName("pAttachments"),
            JAVA_FLOAT.withName("bc0"), JAVA_FLOAT.withName("bc1"), JAVA_FLOAT.withName("bc2"), JAVA_FLOAT.withName("bc3")
    ).withName("VkPipelineColorBlendStateCreateInfo");

    private static final GroupLayout PIPELINE_LAYOUT_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("setLayoutCount"), ADDRESS.withName("pSetLayouts"),
            JAVA_INT.withName("pushConstantRangeCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pPushConstantRanges")).withName("VkPipelineLayoutCreateInfo");

    private static final GroupLayout GRAPHICS_PIPELINE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("stageCount"), ADDRESS.withName("pStages"),
            ADDRESS.withName("pVertexInputState"), ADDRESS.withName("pInputAssemblyState"),
            ADDRESS.withName("pTessellationState"), ADDRESS.withName("pViewportState"),
            ADDRESS.withName("pRasterizationState"), ADDRESS.withName("pMultisampleState"),
            ADDRESS.withName("pDepthStencilState"), ADDRESS.withName("pColorBlendState"),
            ADDRESS.withName("pDynamicState"), JAVA_LONG.withName("layout"), JAVA_LONG.withName("renderPass"),
            JAVA_INT.withName("subpass"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("basePipelineHandle"),
            JAVA_INT.withName("basePipelineIndex"), MemoryLayout.paddingLayout(4)
    ).withName("VkGraphicsPipelineCreateInfo");

    private static final GroupLayout RENDER_PASS_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("renderPass"), JAVA_LONG.withName("framebuffer"),
            JAVA_INT.withName("area_offset_x"), JAVA_INT.withName("area_offset_y"),
            JAVA_INT.withName("area_extent_width"), JAVA_INT.withName("area_extent_height"),
            JAVA_INT.withName("clearValueCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pClearValues")
    ).withName("VkRenderPassBeginInfo");

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
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private static final GroupLayout FENCE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("VkFenceCreateInfo");

    private static final GroupLayout PUSH_CONSTANT_RANGE = MemoryLayout.structLayout(
            JAVA_INT.withName("stageFlags"), JAVA_INT.withName("offset"), JAVA_INT.withName("size"))
            .withName("VkPushConstantRange");

    private OffscreenRenderer() {
    }

    /** Render with no push constants (fullscreen triangle / gradient). */
    public static byte[] render(VulkanDevice device, int width, int height,
                                byte[] vertexSpirv, String vertexEntry,
                                byte[] fragmentSpirv, String fragmentEntry,
                                int vertexCount, float cr, float cg, float cb, float ca) {
        return render(device, width, height, vertexSpirv, vertexEntry, fragmentSpirv, fragmentEntry,
                vertexCount, cr, cg, cb, ca, null);
    }

    /**
     * Rasterise a draw of {@code vertexCount} vertices (with an empty vertex input) using the given vertex +
     * fragment SPIR-V into a {@code width}×{@code height} R8G8B8A8 image, and read it back. The image is cleared
     * to {@code (cr,cg,cb,ca)} first. Returns tightly-packed RGBA, row-major, top-to-bottom.
     */
    public static byte[] render(VulkanDevice device, int width, int height,
                                byte[] vertexSpirv, String vertexEntry,
                                byte[] fragmentSpirv, String fragmentEntry,
                                int vertexCount, float cr, float cg, float cb, float ca, byte[] pushConstants) {
        MemorySegment dev = device.handle();
        long pixelBytes = (long) width * height * 4;
        boolean hasPush = pushConstants != null && pushConstants.length > 0;

        MethodHandle vkCreateImage = device.command("vkCreateImage", C4);
        MethodHandle vkDestroyImage = device.command("vkDestroyImage", D_LONG);
        MethodHandle vkGetImageMemoryRequirements = device.command("vkGetImageMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        MethodHandle vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        MethodHandle vkBindImageMemory = device.command("vkBindImageMemory", BIND);
        MethodHandle vkCreateImageView = device.command("vkCreateImageView", C4);
        MethodHandle vkDestroyImageView = device.command("vkDestroyImageView", D_LONG);
        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkUnmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        MethodHandle vkCreateRenderPass = device.command("vkCreateRenderPass", C4);
        MethodHandle vkDestroyRenderPass = device.command("vkDestroyRenderPass", D_LONG);
        MethodHandle vkCreateFramebuffer = device.command("vkCreateFramebuffer", C4);
        MethodHandle vkDestroyFramebuffer = device.command("vkDestroyFramebuffer", D_LONG);
        MethodHandle vkCreateShaderModule = device.command("vkCreateShaderModule", C4);
        MethodHandle vkDestroyShaderModule = device.command("vkDestroyShaderModule", D_LONG);
        MethodHandle vkCreatePipelineLayout = device.command("vkCreatePipelineLayout", C4);
        MethodHandle vkCmdPushConstants = device.command("vkCmdPushConstants",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        MethodHandle vkDestroyPipelineLayout = device.command("vkDestroyPipelineLayout", D_LONG);
        MethodHandle vkCreateGraphicsPipelines = device.command("vkCreateGraphicsPipelines",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkDestroyPipeline = device.command("vkDestroyPipeline", D_LONG);
        MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool", C4);
        MethodHandle vkDestroyCommandPool = device.command("vkDestroyCommandPool", D_LONG);
        MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers", GET_REQ);
        MethodHandle vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle vkEndCommandBuffer = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle vkCmdBeginRenderPass = device.command("vkCmdBeginRenderPass",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        MethodHandle vkCmdBindPipeline = device.command("vkCmdBindPipeline",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
        MethodHandle vkCmdDraw = device.command("vkCmdDraw",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        MethodHandle vkCmdEndRenderPass = device.command("vkCmdEndRenderPass", FunctionDescriptor.ofVoid(ADDRESS));
        MethodHandle vkCmdCopyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkCreateFence = device.command("vkCreateFence", C4);
        MethodHandle vkDestroyFence = device.command("vkDestroyFence", D_LONG);
        MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        MethodHandle vkWaitForFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));

        try (Arena arena = Arena.ofConfined()) {
            // --- colour image (COLOR_ATTACHMENT + TRANSFER_SRC) + device-local memory ---
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
            si(imageInfo, IMAGE_CREATE_INFO, "usage", Vk.IMAGE_USAGE_COLOR_ATTACHMENT_BIT | Vk.IMAGE_USAGE_TRANSFER_SRC_BIT);
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

            // --- image view ---
            MemorySegment viewInfo = arena.allocate(IMAGE_VIEW_CREATE_INFO);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            sl(viewInfo, IMAGE_VIEW_CREATE_INFO, "image", image);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "format", Vk.FORMAT_R8G8B8A8_UNORM);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_levelCount", 1);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
            MemorySegment pView = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImageView, dev, viewInfo, MemorySegment.NULL, pView), "vkCreateImageView");
            long view = pView.get(JAVA_LONG, 0);

            // --- render pass: one colour attachment, clear -> store, final TRANSFER_SRC_OPTIMAL ---
            MemorySegment attachment = arena.allocate(ATTACHMENT_DESCRIPTION);
            si(attachment, ATTACHMENT_DESCRIPTION, "format", Vk.FORMAT_R8G8B8A8_UNORM);
            si(attachment, ATTACHMENT_DESCRIPTION, "samples", Vk.SAMPLE_COUNT_1_BIT);
            si(attachment, ATTACHMENT_DESCRIPTION, "loadOp", Vk.ATTACHMENT_LOAD_OP_CLEAR);
            si(attachment, ATTACHMENT_DESCRIPTION, "storeOp", Vk.ATTACHMENT_STORE_OP_STORE);
            si(attachment, ATTACHMENT_DESCRIPTION, "stencilLoadOp", Vk.ATTACHMENT_LOAD_OP_DONT_CARE);
            si(attachment, ATTACHMENT_DESCRIPTION, "stencilStoreOp", Vk.ATTACHMENT_STORE_OP_DONT_CARE);
            si(attachment, ATTACHMENT_DESCRIPTION, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
            si(attachment, ATTACHMENT_DESCRIPTION, "finalLayout", Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);

            MemorySegment colorRef = arena.allocate(ATTACHMENT_REFERENCE);
            si(colorRef, ATTACHMENT_REFERENCE, "attachment", 0);
            si(colorRef, ATTACHMENT_REFERENCE, "layout", Vk.IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            MemorySegment subpass = arena.allocate(SUBPASS_DESCRIPTION);
            si(subpass, SUBPASS_DESCRIPTION, "pipelineBindPoint", Vk.PIPELINE_BIND_POINT_GRAPHICS);
            si(subpass, SUBPASS_DESCRIPTION, "colorAttachmentCount", 1);
            sa(subpass, SUBPASS_DESCRIPTION, "pColorAttachments", colorRef);

            // Two dependencies: EXTERNAL -> subpass (start writing), subpass -> EXTERNAL (make writes visible to
            // the follow-up copy, since the render pass finalLayout leaves the image in TRANSFER_SRC_OPTIMAL).
            MemorySegment dependency = arena.allocate(SUBPASS_DEPENDENCY, 2);
            MemorySegment dep0 = dependency.asSlice(0, SUBPASS_DEPENDENCY.byteSize());
            si(dep0, SUBPASS_DEPENDENCY, "srcSubpass", Vk.SUBPASS_EXTERNAL);
            si(dep0, SUBPASS_DEPENDENCY, "dstSubpass", 0);
            si(dep0, SUBPASS_DEPENDENCY, "srcStageMask", Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            si(dep0, SUBPASS_DEPENDENCY, "dstStageMask", Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            si(dep0, SUBPASS_DEPENDENCY, "srcAccessMask", 0);
            si(dep0, SUBPASS_DEPENDENCY, "dstAccessMask", Vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            MemorySegment dep1 = dependency.asSlice(SUBPASS_DEPENDENCY.byteSize(), SUBPASS_DEPENDENCY.byteSize());
            si(dep1, SUBPASS_DEPENDENCY, "srcSubpass", 0);
            si(dep1, SUBPASS_DEPENDENCY, "dstSubpass", Vk.SUBPASS_EXTERNAL);
            si(dep1, SUBPASS_DEPENDENCY, "srcStageMask", Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            si(dep1, SUBPASS_DEPENDENCY, "dstStageMask", Vk.PIPELINE_STAGE_TRANSFER_BIT);
            si(dep1, SUBPASS_DEPENDENCY, "srcAccessMask", Vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            si(dep1, SUBPASS_DEPENDENCY, "dstAccessMask", Vk.ACCESS_TRANSFER_READ_BIT);

            MemorySegment rpInfo = arena.allocate(RENDER_PASS_CREATE_INFO);
            si(rpInfo, RENDER_PASS_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
            si(rpInfo, RENDER_PASS_CREATE_INFO, "attachmentCount", 1);
            sa(rpInfo, RENDER_PASS_CREATE_INFO, "pAttachments", attachment);
            si(rpInfo, RENDER_PASS_CREATE_INFO, "subpassCount", 1);
            sa(rpInfo, RENDER_PASS_CREATE_INFO, "pSubpasses", subpass);
            si(rpInfo, RENDER_PASS_CREATE_INFO, "dependencyCount", 2);
            sa(rpInfo, RENDER_PASS_CREATE_INFO, "pDependencies", dependency);
            MemorySegment pRenderPass = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateRenderPass, dev, rpInfo, MemorySegment.NULL, pRenderPass), "vkCreateRenderPass");
            long renderPass = pRenderPass.get(JAVA_LONG, 0);

            // --- framebuffer ---
            MemorySegment pAttachViews = arena.allocate(JAVA_LONG);
            pAttachViews.set(JAVA_LONG, 0, view);
            MemorySegment fbInfo = arena.allocate(FRAMEBUFFER_CREATE_INFO);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
            sl(fbInfo, FRAMEBUFFER_CREATE_INFO, "renderPass", renderPass);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "attachmentCount", 1);
            sa(fbInfo, FRAMEBUFFER_CREATE_INFO, "pAttachments", pAttachViews);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "width", width);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "height", height);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "layers", 1);
            MemorySegment pFramebuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateFramebuffer, dev, fbInfo, MemorySegment.NULL, pFramebuffer), "vkCreateFramebuffer");
            long framebuffer = pFramebuffer.get(JAVA_LONG, 0);

            // --- shader modules ---
            long vertModule = shaderModule(arena, vkCreateShaderModule, dev, vertexSpirv);
            long fragModule = shaderModule(arena, vkCreateShaderModule, dev, fragmentSpirv);

            // --- pipeline ---
            MemorySegment stages = arena.allocate(SHADER_STAGE, 2);
            MemorySegment vertName = arena.allocateFrom(vertexEntry);
            MemorySegment fragName = arena.allocateFrom(fragmentEntry);
            MemorySegment stage0 = stages.asSlice(0, SHADER_STAGE.byteSize());
            si(stage0, SHADER_STAGE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            si(stage0, SHADER_STAGE, "stage", Vk.SHADER_STAGE_VERTEX_BIT);
            sl(stage0, SHADER_STAGE, "module", vertModule);
            sa(stage0, SHADER_STAGE, "pName", vertName);
            MemorySegment stage1 = stages.asSlice(SHADER_STAGE.byteSize(), SHADER_STAGE.byteSize());
            si(stage1, SHADER_STAGE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO);
            si(stage1, SHADER_STAGE, "stage", Vk.SHADER_STAGE_FRAGMENT_BIT);
            sl(stage1, SHADER_STAGE, "module", fragModule);
            sa(stage1, SHADER_STAGE, "pName", fragName);

            MemorySegment vertexInput = arena.allocate(VERTEX_INPUT_STATE);
            si(vertexInput, VERTEX_INPUT_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            MemorySegment inputAssembly = arena.allocate(INPUT_ASSEMBLY_STATE);
            si(inputAssembly, INPUT_ASSEMBLY_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            si(inputAssembly, INPUT_ASSEMBLY_STATE, "topology", Vk.PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            MemorySegment viewport = arena.allocate(VIEWPORT);
            sf(viewport, VIEWPORT, "width", width);
            sf(viewport, VIEWPORT, "height", height);
            sf(viewport, VIEWPORT, "maxDepth", 1.0f);
            MemorySegment scissor = arena.allocate(RECT2D);
            si(scissor, RECT2D, "extent_width", width);
            si(scissor, RECT2D, "extent_height", height);
            MemorySegment viewportState = arena.allocate(VIEWPORT_STATE);
            si(viewportState, VIEWPORT_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            si(viewportState, VIEWPORT_STATE, "viewportCount", 1);
            sa(viewportState, VIEWPORT_STATE, "pViewports", viewport);
            si(viewportState, VIEWPORT_STATE, "scissorCount", 1);
            sa(viewportState, VIEWPORT_STATE, "pScissors", scissor);

            MemorySegment rasterizer = arena.allocate(RASTERIZATION_STATE);
            si(rasterizer, RASTERIZATION_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO);
            si(rasterizer, RASTERIZATION_STATE, "polygonMode", Vk.POLYGON_MODE_FILL);
            si(rasterizer, RASTERIZATION_STATE, "cullMode", Vk.CULL_MODE_NONE);
            si(rasterizer, RASTERIZATION_STATE, "frontFace", Vk.FRONT_FACE_COUNTER_CLOCKWISE);
            sf(rasterizer, RASTERIZATION_STATE, "lineWidth", 1.0f);

            MemorySegment multisample = arena.allocate(MULTISAMPLE_STATE);
            si(multisample, MULTISAMPLE_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO);
            si(multisample, MULTISAMPLE_STATE, "rasterizationSamples", Vk.SAMPLE_COUNT_1_BIT);

            MemorySegment blendAttachment = arena.allocate(COLOR_BLEND_ATTACHMENT);
            si(blendAttachment, COLOR_BLEND_ATTACHMENT, "colorWriteMask", Vk.COLOR_COMPONENT_RGBA);
            MemorySegment colorBlend = arena.allocate(COLOR_BLEND_STATE);
            si(colorBlend, COLOR_BLEND_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            si(colorBlend, COLOR_BLEND_STATE, "attachmentCount", 1);
            sa(colorBlend, COLOR_BLEND_STATE, "pAttachments", blendAttachment);

            MemorySegment layoutInfo = arena.allocate(PIPELINE_LAYOUT_CREATE_INFO);
            si(layoutInfo, PIPELINE_LAYOUT_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            if (hasPush) {
                MemorySegment range = arena.allocate(PUSH_CONSTANT_RANGE);
                si(range, PUSH_CONSTANT_RANGE, "stageFlags", Vk.SHADER_STAGE_FRAGMENT_BIT);
                si(range, PUSH_CONSTANT_RANGE, "size", pushConstants.length);
                si(layoutInfo, PIPELINE_LAYOUT_CREATE_INFO, "pushConstantRangeCount", 1);
                sa(layoutInfo, PIPELINE_LAYOUT_CREATE_INFO, "pPushConstantRanges", range);
            }
            MemorySegment pLayout = arena.allocate(JAVA_LONG);
            check(invoke(vkCreatePipelineLayout, dev, layoutInfo, MemorySegment.NULL, pLayout), "vkCreatePipelineLayout");
            long pipelineLayout = pLayout.get(JAVA_LONG, 0);

            MemorySegment pipelineInfo = arena.allocate(GRAPHICS_PIPELINE_CREATE_INFO);
            si(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO);
            si(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "stageCount", 2);
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pStages", stages);
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pVertexInputState", vertexInput);
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pInputAssemblyState", inputAssembly);
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pViewportState", viewportState);
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pRasterizationState", rasterizer);
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pMultisampleState", multisample);
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pColorBlendState", colorBlend);
            sl(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "layout", pipelineLayout);
            sl(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "renderPass", renderPass);
            si(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "subpass", 0);
            MemorySegment pPipeline = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateGraphicsPipelines, dev, 0L, 1, pipelineInfo, MemorySegment.NULL, pPipeline),
                    "vkCreateGraphicsPipelines");
            long pipeline = pPipeline.get(JAVA_LONG, 0);

            // --- readback buffer ---
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

            // --- command buffer: clear + draw + copy ---
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

            MemorySegment beginInfo = arena.allocate(COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(invoke(vkBeginCommandBuffer, cmd, beginInfo), "vkBeginCommandBuffer");

            MemorySegment clearValue = arena.allocate(JAVA_FLOAT, 4);
            clearValue.setAtIndex(JAVA_FLOAT, 0, cr);
            clearValue.setAtIndex(JAVA_FLOAT, 1, cg);
            clearValue.setAtIndex(JAVA_FLOAT, 2, cb);
            clearValue.setAtIndex(JAVA_FLOAT, 3, ca);
            MemorySegment rpBegin = arena.allocate(RENDER_PASS_BEGIN_INFO);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            sl(rpBegin, RENDER_PASS_BEGIN_INFO, "renderPass", renderPass);
            sl(rpBegin, RENDER_PASS_BEGIN_INFO, "framebuffer", framebuffer);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_extent_width", width);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_extent_height", height);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "clearValueCount", 1);
            sa(rpBegin, RENDER_PASS_BEGIN_INFO, "pClearValues", clearValue);

            invokeVoid(vkCmdBeginRenderPass, cmd, rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
            invokeVoid(vkCmdBindPipeline, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline);
            if (hasPush) {
                MemorySegment pc = arena.allocate(pushConstants.length);
                MemorySegment.copy(pushConstants, 0, pc, JAVA_BYTE, 0, pushConstants.length);
                invokeVoid(vkCmdPushConstants, cmd, pipelineLayout, Vk.SHADER_STAGE_FRAGMENT_BIT, 0,
                        pushConstants.length, pc);
            }
            invokeVoid(vkCmdDraw, cmd, vertexCount, 1, 0, 0);
            invokeVoid(vkCmdEndRenderPass, cmd);

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
            invokeVoid(vkDestroyPipeline, dev, pipeline, MemorySegment.NULL);
            invokeVoid(vkDestroyPipelineLayout, dev, pipelineLayout, MemorySegment.NULL);
            invokeVoid(vkDestroyShaderModule, dev, fragModule, MemorySegment.NULL);
            invokeVoid(vkDestroyShaderModule, dev, vertModule, MemorySegment.NULL);
            invokeVoid(vkDestroyFramebuffer, dev, framebuffer, MemorySegment.NULL);
            invokeVoid(vkDestroyRenderPass, dev, renderPass, MemorySegment.NULL);
            invokeVoid(vkDestroyImageView, dev, view, MemorySegment.NULL);
            invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, bufferMemory, MemorySegment.NULL);
            invokeVoid(vkDestroyImage, dev, image, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, imageMemory, MemorySegment.NULL);
            return pixels;
        }
    }

    private static long shaderModule(Arena arena, MethodHandle vkCreateShaderModule, MemorySegment dev, byte[] spirv) {
        MemorySegment code = arena.allocate(spirv.length, 4);
        MemorySegment.copy(spirv, 0, code, JAVA_BYTE, 0, spirv.length);
        MemorySegment info = arena.allocate(SHADER_MODULE_CREATE_INFO);
        si(info, SHADER_MODULE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO);
        sl(info, SHADER_MODULE_CREATE_INFO, "codeSize", spirv.length);
        sa(info, SHADER_MODULE_CREATE_INFO, "pCode", code);
        MemorySegment pModule = arena.allocate(JAVA_LONG);
        check(invoke(vkCreateShaderModule, dev, info, MemorySegment.NULL, pModule), "vkCreateShaderModule");
        return pModule.get(JAVA_LONG, 0);
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

    // Common command descriptors: C4 = (device, pCreateInfo, pAllocator, pOut)->VkResult;
    // D_LONG = (device, handle, pAllocator)->void; GET_REQ = (device, ptr, ptr)->void/VkResult; BIND = (device, h, mem, off)->VkResult.
    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor GET_REQ = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor MEMREQ = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);
}
