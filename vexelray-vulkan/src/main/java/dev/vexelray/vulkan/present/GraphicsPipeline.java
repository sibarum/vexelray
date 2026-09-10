package dev.vexelray.vulkan.present;

import sibarum.probe.Lane;
import sibarum.probe.Probe;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkStructs;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

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
 * A graphics pipeline built against a <em>supplied</em> {@link VulkanRenderPass} — a {@code VkPipeline} (plus its
 * layout and shader modules) from a vertex + fragment SPIR-V pair. It does <em>not</em> own the render pass: the
 * render pass is created once by the runtime (or caller) and shared, so several pipelines — i.e. several
 * {@link dev.vexelray.engine.RenderTechnique}s — can be built against the same pass and composite into one
 * colour+depth target (see docs/refactor-decisions.md, Phase 1).
 *
 * <p>What varies between a fullscreen SDF pass and a textured/blended pass (e.g. MSDF text) is captured in
 * {@link Config}: whether there is a vertex buffer (an empty vertex input drives the fullscreen triangle from
 * {@code gl_VertexIndex}; a non-zero stride declares interleaved attributes), which descriptor set layouts the
 * pipeline layout includes (for sampled images etc.), whether alpha blending is on, and which stages the push
 * constant is visible to. The zero-argument {@code pushConstantBytes} constructor keeps the original fullscreen
 * behaviour (empty vertex input, no descriptor sets, no blend, fragment-stage push constant).
 * {@link #close()} destroys the pipeline, its layout, and the shader modules — never the render pass or any
 * descriptor set layout (both outlive it).
 */
public final class GraphicsPipeline implements AutoCloseable {

    /** One interleaved vertex attribute: shader {@code location}, a {@code VK_FORMAT_*}, and its byte {@code offset}. */
    public record VertexAttribute(int location, int format, int offset) {

        /**
         * An attribute of {@code components} consecutive 32-bit floats — the only vertex layout anything in
         * this repository uses.
         *
         * <p>Here because the {@code components -> VK_FORMAT_*} switch had been written four times, once in
         * {@code CanvasTechnique} and once in each of three canvas demos, and every copy had the same three
         * cases and the same {@code default -> throw}. It is Vulkan's mapping, so it belongs beside the type
         * that carries a {@code VK_FORMAT_*}, not in each caller that happens to build one.
         *
         * @throws IllegalArgumentException for a component count with no float format — 3 among them, which
         *         is <em>not</em> an oversight: {@code R32G32B32_SFLOAT} exists but is not guaranteed as a
         *         vertex format on every device, and a vertex layout that works on the author's GPU and not
         *         on the reader's is the failure this refuses rather than risks
         */
        public static VertexAttribute floats(int location, int components, int offset) {
            return new VertexAttribute(location, floatFormat(components), offset);
        }
    }

    /** The {@code VK_FORMAT_*} for {@code components} consecutive 32-bit floats. See {@link VertexAttribute#floats}. */
    public static int floatFormat(int components) {
        return switch (components) {
            case 1 -> Vk.FORMAT_R32_SFLOAT;
            case 2 -> Vk.FORMAT_R32G32_SFLOAT;
            case 4 -> Vk.FORMAT_R32G32B32A32_SFLOAT;
            default -> throw new IllegalArgumentException("no guaranteed float vertex format for " + components
                    + " components; use 1, 2 or 4");
        };
    }

    /**
     * Configurable pipeline state beyond the shaders and extent.
     *
     * @param vertexStride        byte stride of one vertex; {@code 0} means an empty vertex input (fullscreen triangle)
     * @param attributes          interleaved vertex attributes (ignored when {@code vertexStride == 0})
     * @param descriptorSetLayouts {@code VkDescriptorSetLayout} handles the pipeline layout includes (may be empty)
     * @param blendEnable         enable standard src-alpha/one-minus-src-alpha colour blending
     * @param pushConstantStages  {@code VK_SHADER_STAGE_*} flags the push constant is visible to
     * @param pushConstantBytes   push constant size in bytes ({@code 0} for none)
     */
    /**
     * @param dynamicViewport when true, viewport + scissor are dynamic pipeline state — the caller (e.g.
     *                        {@link WindowedPresenter}) must set them each frame via {@code vkCmdSetViewport/Scissor}.
     *                        Enables window resize without a pipeline rebuild. When false, a fixed viewport is baked
     *                        at creation (the offscreen / fixed-size path).
     */
    public record Config(int vertexStride, List<VertexAttribute> attributes, long[] descriptorSetLayouts,
                         boolean blendEnable, int pushConstantStages, int pushConstantBytes,
                         boolean dynamicViewport, Depth depth) {

        /**
         * How this pipeline participates in the shared depth attachment.
         *
         * <p>Three states rather than two booleans, because the pair {@code (test, write)} spells one
         * combination nobody wants — writing depth without testing it — and naming the three that are
         * meaningful is cheaper than documenting why the fourth is not.
         */
        public enum Depth {
            /**
             * No depth test and no depth write. The only legal choice against a render pass with no depth
             * attachment, and the choice a fullscreen pass makes even when depth exists: a technique that
             * covers every pixel unconditionally has nothing to be occluded by.
             */
            NONE,
            /**
             * Test against depth and write the result — opaque geometry, and what makes two techniques occlude
             * each other per pixel instead of in submission order.
             */
            TEST_AND_WRITE,
            /**
             * Test against depth but leave it unchanged. For a technique that must respect what is in front of
             * it without claiming space of its own — a transparent overlay, a marched effect composited over
             * solid geometry.
             */
            TEST_ONLY
        }

        public Config {
            if (depth == null) {
                throw new IllegalArgumentException("depth must not be null; use Depth.NONE");
            }
        }

        /** Config with a fixed (baked) viewport and no depth — the prior 6-arg form. */
        public Config(int vertexStride, List<VertexAttribute> attributes, long[] descriptorSetLayouts,
                      boolean blendEnable, int pushConstantStages, int pushConstantBytes) {
            this(vertexStride, attributes, descriptorSetLayouts, blendEnable, pushConstantStages, pushConstantBytes,
                    false, Depth.NONE);
        }

        /** Config with no depth — the prior 7-arg form, kept so a caller predating depth reads unchanged. */
        public Config(int vertexStride, List<VertexAttribute> attributes, long[] descriptorSetLayouts,
                      boolean blendEnable, int pushConstantStages, int pushConstantBytes,
                      boolean dynamicViewport) {
            this(vertexStride, attributes, descriptorSetLayouts, blendEnable, pushConstantStages, pushConstantBytes,
                    dynamicViewport, Depth.NONE);
        }

        /** This config participating in depth as {@code depth} says — how a technique opts in. */
        public Config withDepth(Depth depth) {
            return new Config(vertexStride, attributes, descriptorSetLayouts, blendEnable, pushConstantStages,
                    pushConstantBytes, dynamicViewport, depth);
        }
    }

    /**
     * {@code VkPipelineDepthStencilStateCreateInfo}. The two {@code VkStencilOpState}s are 28 bytes each and
     * modelled as padding rather than named fields: nothing here tests stencil, the depth format carries none,
     * and a zeroed arena allocation already means "stencil off". Naming them would invite a reader to set them.
     */
    private static final GroupLayout DEPTH_STENCIL_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("depthTestEnable"),
            JAVA_INT.withName("depthWriteEnable"), JAVA_INT.withName("depthCompareOp"),
            JAVA_INT.withName("depthBoundsTestEnable"), JAVA_INT.withName("stencilTestEnable"),
            MemoryLayout.paddingLayout(28), MemoryLayout.paddingLayout(28),
            JAVA_FLOAT.withName("minDepthBounds"), JAVA_FLOAT.withName("maxDepthBounds")
    ).withName("VkPipelineDepthStencilStateCreateInfo");

    private static final GroupLayout SHADER_MODULE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("codeSize"),
            ADDRESS.withName("pCode")).withName("VkShaderModuleCreateInfo");

    private static final GroupLayout SHADER_STAGE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("stage"), JAVA_LONG.withName("module"),
            ADDRESS.withName("pName"), ADDRESS.withName("pSpecializationInfo")
    ).withName("VkPipelineShaderStageCreateInfo");

    private static final GroupLayout VERTEX_BINDING = MemoryLayout.structLayout(
            JAVA_INT.withName("binding"), JAVA_INT.withName("stride"), JAVA_INT.withName("inputRate"),
            MemoryLayout.paddingLayout(4)).withName("VkVertexInputBindingDescription");

    private static final GroupLayout VERTEX_ATTRIBUTE = MemoryLayout.structLayout(
            JAVA_INT.withName("location"), JAVA_INT.withName("binding"), JAVA_INT.withName("format"),
            JAVA_INT.withName("offset")).withName("VkVertexInputAttributeDescription");

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

    private static final GroupLayout VIEWPORT_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("viewportCount"), ADDRESS.withName("pViewports"),
            JAVA_INT.withName("scissorCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pScissors")
    ).withName("VkPipelineViewportStateCreateInfo");

    private static final GroupLayout DYNAMIC_STATE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("dynamicStateCount"),
            ADDRESS.withName("pDynamicStates")
    ).withName("VkPipelineDynamicStateCreateInfo");

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
    private final boolean dynamicViewport;
    private final long vertModule;
    private final long fragModule;
    private final MethodHandle vkDestroyPipeline;
    private final MethodHandle vkDestroyPipelineLayout;
    private final MethodHandle vkDestroyShaderModule;

    /** Fullscreen convenience: empty vertex input, no descriptor sets, no blend, fragment-stage push constant. */
    public GraphicsPipeline(VulkanDevice device, long renderPass, int width, int height,
                            byte[] vertexSpirv, String vertexEntry, byte[] fragmentSpirv, String fragmentEntry,
                            int pushConstantBytes) {
        this(device, renderPass, width, height, vertexSpirv, vertexEntry, fragmentSpirv, fragmentEntry,
                new Config(0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, pushConstantBytes));
    }

    /** Build a pipeline against the shared {@code renderPass} with explicit {@link Config}. */
    public GraphicsPipeline(VulkanDevice device, long renderPass, int width, int height,
                            byte[] vertexSpirv, String vertexEntry, byte[] fragmentSpirv, String fragmentEntry,
                            Config config) {
        Probe.opened(Lane.GPU, "GraphicsPipeline", this);
        this.device = device;
        this.dynamicViewport = config.dynamicViewport();
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
            if (config.vertexStride() > 0) {
                MemorySegment binding = arena.allocate(VERTEX_BINDING);
                si(binding, VERTEX_BINDING, "binding", 0);
                si(binding, VERTEX_BINDING, "stride", config.vertexStride());
                si(binding, VERTEX_BINDING, "inputRate", Vk.VERTEX_INPUT_RATE_VERTEX);
                List<VertexAttribute> attrs = config.attributes();
                MemorySegment attrArr = arena.allocate(VERTEX_ATTRIBUTE, attrs.size());
                for (int k = 0; k < attrs.size(); k++) {
                    MemorySegment a = attrArr.asSlice((long) k * VERTEX_ATTRIBUTE.byteSize(), VERTEX_ATTRIBUTE.byteSize());
                    si(a, VERTEX_ATTRIBUTE, "location", attrs.get(k).location());
                    si(a, VERTEX_ATTRIBUTE, "binding", 0);
                    si(a, VERTEX_ATTRIBUTE, "format", attrs.get(k).format());
                    si(a, VERTEX_ATTRIBUTE, "offset", attrs.get(k).offset());
                }
                si(vertexInput, VERTEX_INPUT_STATE, "vertexBindingDescriptionCount", 1);
                sa(vertexInput, VERTEX_INPUT_STATE, "pVertexBindingDescriptions", binding);
                si(vertexInput, VERTEX_INPUT_STATE, "vertexAttributeDescriptionCount", attrs.size());
                sa(vertexInput, VERTEX_INPUT_STATE, "pVertexAttributeDescriptions", attrArr);
            }

            MemorySegment inputAssembly = arena.allocate(INPUT_ASSEMBLY_STATE);
            si(inputAssembly, INPUT_ASSEMBLY_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO);
            si(inputAssembly, INPUT_ASSEMBLY_STATE, "topology", Vk.PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);

            MemorySegment viewport = arena.allocate(VkStructs.VIEWPORT);
            sf(viewport, VkStructs.VIEWPORT, "width", width);
            sf(viewport, VkStructs.VIEWPORT, "height", height);
            sf(viewport, VkStructs.VIEWPORT, "maxDepth", 1.0f);
            MemorySegment scissor = arena.allocate(VkStructs.RECT_2D);
            si(scissor, VkStructs.RECT_2D, "extent_width", width);
            si(scissor, VkStructs.RECT_2D, "extent_height", height);
            MemorySegment viewportState = arena.allocate(VIEWPORT_STATE);
            si(viewportState, VIEWPORT_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO);
            si(viewportState, VIEWPORT_STATE, "viewportCount", 1);
            sa(viewportState, VIEWPORT_STATE, "pViewports", viewport);
            si(viewportState, VIEWPORT_STATE, "scissorCount", 1);
            sa(viewportState, VIEWPORT_STATE, "pScissors", scissor);

            // Optionally make viewport + scissor dynamic: the present loop then sets them each frame from the
            // swapchain extent, so a window resize needs no pipeline rebuild — only a swapchain recreate. The baked
            // values above stay as the required placeholders (counts stay 1; the pointers are then ignored). When
            // dynamic state is off, the baked fixed viewport is used (offscreen / fixed-size path).
            MemorySegment dynamicState = MemorySegment.NULL;
            if (dynamicViewport) {
                MemorySegment dynamicStates = arena.allocate(JAVA_INT, 2);
                dynamicStates.setAtIndex(JAVA_INT, 0, Vk.DYNAMIC_STATE_VIEWPORT);
                dynamicStates.setAtIndex(JAVA_INT, 1, Vk.DYNAMIC_STATE_SCISSOR);
                dynamicState = arena.allocate(DYNAMIC_STATE);
                si(dynamicState, DYNAMIC_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO);
                si(dynamicState, DYNAMIC_STATE, "dynamicStateCount", 2);
                sa(dynamicState, DYNAMIC_STATE, "pDynamicStates", dynamicStates);
            }

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
            if (config.blendEnable()) {
                si(blendAttachment, COLOR_BLEND_ATTACHMENT, "blendEnable", Vk.VK_TRUE);
                si(blendAttachment, COLOR_BLEND_ATTACHMENT, "srcColorBlendFactor", Vk.BLEND_FACTOR_SRC_ALPHA);
                si(blendAttachment, COLOR_BLEND_ATTACHMENT, "dstColorBlendFactor", Vk.BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
                si(blendAttachment, COLOR_BLEND_ATTACHMENT, "colorBlendOp", Vk.BLEND_OP_ADD);
                si(blendAttachment, COLOR_BLEND_ATTACHMENT, "srcAlphaBlendFactor", Vk.BLEND_FACTOR_ONE);
                si(blendAttachment, COLOR_BLEND_ATTACHMENT, "dstAlphaBlendFactor", Vk.BLEND_FACTOR_ONE_MINUS_SRC_ALPHA);
                si(blendAttachment, COLOR_BLEND_ATTACHMENT, "alphaBlendOp", Vk.BLEND_OP_ADD);
            }
            MemorySegment colorBlend = arena.allocate(COLOR_BLEND_STATE);
            si(colorBlend, COLOR_BLEND_STATE, "sType", Vk.STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO);
            si(colorBlend, COLOR_BLEND_STATE, "attachmentCount", 1);
            sa(colorBlend, COLOR_BLEND_STATE, "pAttachments", blendAttachment);

            MemorySegment layoutInfo = arena.allocate(PIPELINE_LAYOUT_CREATE_INFO);
            si(layoutInfo, PIPELINE_LAYOUT_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO);
            long[] setLayouts = config.descriptorSetLayouts();
            if (setLayouts.length > 0) {
                MemorySegment pSets = arena.allocate(JAVA_LONG, setLayouts.length);
                for (int k = 0; k < setLayouts.length; k++) {
                    pSets.setAtIndex(JAVA_LONG, k, setLayouts[k]);
                }
                si(layoutInfo, PIPELINE_LAYOUT_CREATE_INFO, "setLayoutCount", setLayouts.length);
                sa(layoutInfo, PIPELINE_LAYOUT_CREATE_INFO, "pSetLayouts", pSets);
            }
            if (config.pushConstantBytes() > 0) {
                MemorySegment range = arena.allocate(PUSH_CONSTANT_RANGE);
                si(range, PUSH_CONSTANT_RANGE, "stageFlags", config.pushConstantStages());
                si(range, PUSH_CONSTANT_RANGE, "size", config.pushConstantBytes());
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
            if (config.depth() != Config.Depth.NONE) {
                MemorySegment depthStencil = arena.allocate(DEPTH_STENCIL_STATE);
                si(depthStencil, DEPTH_STENCIL_STATE, "sType",
                        Vk.STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO);
                si(depthStencil, DEPTH_STENCIL_STATE, "depthTestEnable", Vk.VK_TRUE);
                si(depthStencil, DEPTH_STENCIL_STATE, "depthWriteEnable",
                        config.depth() == Config.Depth.TEST_AND_WRITE ? Vk.VK_TRUE : 0);
                // LESS, paired with DepthAttachment.CLEAR_DEPTH of 1.0: nearer wins, and an untouched pixel is
                // as far away as possible. The two constants are one decision and must move together.
                si(depthStencil, DEPTH_STENCIL_STATE, "depthCompareOp", Vk.COMPARE_OP_LESS);
                sf(depthStencil, DEPTH_STENCIL_STATE, "maxDepthBounds", 1.0f);
                sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pDepthStencilState", depthStencil);
            }
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pColorBlendState", colorBlend);
            sa(pipelineInfo, GRAPHICS_PIPELINE_CREATE_INFO, "pDynamicState", dynamicState);
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

    /** Whether viewport + scissor are dynamic (the presenter must set them per frame). */
    public boolean hasDynamicViewport() {
        return dynamicViewport;
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
        Probe.closed(Lane.GPU, "GraphicsPipeline", this);
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyPipeline, dev, pipeline, MemorySegment.NULL);
        invokeVoid(vkDestroyPipelineLayout, dev, pipelineLayout, MemorySegment.NULL);
        invokeVoid(vkDestroyShaderModule, dev, fragModule, MemorySegment.NULL);
        invokeVoid(vkDestroyShaderModule, dev, vertModule, MemorySegment.NULL);
    }

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
}
