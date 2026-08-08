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
 * A graphics pipeline built against a <em>supplied</em> {@link VulkanRenderPass} — a pure {@code VkPipeline}
 * (plus its layout and shader modules) from a vertex + fragment SPIR-V pair. It does <em>not</em> own the render
 * pass: the render pass is created once by the runtime (or caller) and shared, so several pipelines — i.e.
 * several {@link dev.vexelray.engine.RenderTechnique}s — can be built against the same pass and composite into one
 * colour+depth target (see docs/refactor-decisions.md, Phase 1). Empty vertex input (the fullscreen triangle
 * comes from {@code gl_VertexIndex}); static viewport at the given extent. {@link #close()} destroys the pipeline,
 * its layout, and the shader modules — but never the render pass, which outlives it.
 */
public final class GraphicsPipeline implements AutoCloseable {

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
            JAVA_FLOAT.withName("height"), JAVA_FLOAT.withName("minDepth"), JAVA_FLOAT.withName("maxDepth")).withName("VkViewport");

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

    private static final GroupLayout PUSH_CONSTANT_RANGE = MemoryLayout.structLayout(
            JAVA_INT.withName("stageFlags"), JAVA_INT.withName("offset"), JAVA_INT.withName("size"))
            .withName("VkPushConstantRange");

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

    private final VulkanDevice device;
    private final long pipelineLayout;
    private final long pipeline;
    private final long vertModule;
    private final long fragModule;
    private final MethodHandle vkDestroyPipeline;
    private final MethodHandle vkDestroyPipelineLayout;
    private final MethodHandle vkDestroyShaderModule;

    /**
     * Build a pipeline against the shared {@code renderPass}. The render pass is not owned here — the caller
     * (runtime) creates and destroys it. {@code width}/{@code height} set the static viewport/scissor.
     */
    public GraphicsPipeline(VulkanDevice device, long renderPass, int width, int height,
                            byte[] vertexSpirv, String vertexEntry, byte[] fragmentSpirv, String fragmentEntry,
                            int pushConstantBytes) {
        this.device = device;
        MemorySegment dev = device.handle();

        MethodHandle vkCreateShaderModule = device.command("vkCreateShaderModule", C4);
        MethodHandle vkCreatePipelineLayout = device.command("vkCreatePipelineLayout", C4);
        MethodHandle vkCreateGraphicsPipelines = device.command("vkCreateGraphicsPipelines",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        this.vkDestroyShaderModule = device.command("vkDestroyShaderModule", D_LONG);
        this.vkDestroyPipelineLayout = device.command("vkDestroyPipelineLayout", D_LONG);
        this.vkDestroyPipeline = device.command("vkDestroyPipeline", D_LONG);

        try (Arena arena = Arena.ofConfined()) {
            this.vertModule = shaderModule(arena, vkCreateShaderModule, dev, vertexSpirv);
            this.fragModule = shaderModule(arena, vkCreateShaderModule, dev, fragmentSpirv);

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
            if (pushConstantBytes > 0) {
                MemorySegment range = arena.allocate(PUSH_CONSTANT_RANGE);
                si(range, PUSH_CONSTANT_RANGE, "stageFlags", Vk.SHADER_STAGE_FRAGMENT_BIT);
                si(range, PUSH_CONSTANT_RANGE, "size", pushConstantBytes);
                si(layoutInfo, PIPELINE_LAYOUT_CREATE_INFO, "pushConstantRangeCount", 1);
                sa(layoutInfo, PIPELINE_LAYOUT_CREATE_INFO, "pPushConstantRanges", range);
            }
            MemorySegment pLayout = arena.allocate(JAVA_LONG);
            check(invoke(vkCreatePipelineLayout, dev, layoutInfo, MemorySegment.NULL, pLayout), "vkCreatePipelineLayout");
            this.pipelineLayout = pLayout.get(JAVA_LONG, 0);

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
            this.pipeline = pPipeline.get(JAVA_LONG, 0);
        }
    }

    public long pipeline() {
        return pipeline;
    }

    public long pipelineLayout() {
        return pipelineLayout;
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

    @Override
    public void close() {
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyPipeline, dev, pipeline, MemorySegment.NULL);
        invokeVoid(vkDestroyPipelineLayout, dev, pipelineLayout, MemorySegment.NULL);
        invokeVoid(vkDestroyShaderModule, dev, fragModule, MemorySegment.NULL);
        invokeVoid(vkDestroyShaderModule, dev, vertModule, MemorySegment.NULL);
    }

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
}
