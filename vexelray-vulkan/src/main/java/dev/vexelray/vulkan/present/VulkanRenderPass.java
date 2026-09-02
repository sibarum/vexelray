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
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.invokeVoid;
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A {@code VkRenderPass} for a single colour attachment (clear → store), owned independently of any pipeline —
 * the shared target a runtime creates once and hands to every technique to build its pipeline against, and to the
 * framebuffers that back it. Parameterised by the colour {@code format} and the attachment's {@code finalLayout}
 * so one class serves both present targets: {@code PRESENT_SRC_KHR} for the windowed swapchain,
 * {@code TRANSFER_SRC_OPTIMAL} for offscreen readback.
 *
 * <p>This was extracted from {@code GraphicsPipeline} so the render pass no longer belongs to a single pipeline:
 * multiple pipelines (techniques) can be created against one shared render pass, which is what lets techniques
 * composite into one colour+depth target (see docs/refactor-decisions.md, Phase 1). Depth is not yet an
 * attachment here; it lands with the depth-target work in Phase 1/2.
 */
public final class VulkanRenderPass implements AutoCloseable {

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

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);

    private final VulkanDevice device;
    private final long handle;
    private final MethodHandle vkDestroyRenderPass;

    public VulkanRenderPass(VulkanDevice device, int colorFormat, int finalLayout) {
        Probe.opened(Lane.GPU, "VulkanRenderPass", this);
        this.device = device;
        MemorySegment dev = device.handle();
        MethodHandle vkCreateRenderPass = device.command("vkCreateRenderPass", C4);
        this.vkDestroyRenderPass = device.command("vkDestroyRenderPass", D_LONG);

        // For a swapchain present target one start dependency suffices; an offscreen target adds a second so the
        // follow-up work sees the colour writes — a copy (TRANSFER_SRC) or a later sample (SHADER_READ_ONLY).
        boolean present = finalLayout == Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR;
        boolean sampled = finalLayout == Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment attachment = arena.allocate(ATTACHMENT_DESCRIPTION);
            si(attachment, ATTACHMENT_DESCRIPTION, "format", colorFormat);
            si(attachment, ATTACHMENT_DESCRIPTION, "samples", Vk.SAMPLE_COUNT_1_BIT);
            si(attachment, ATTACHMENT_DESCRIPTION, "loadOp", Vk.ATTACHMENT_LOAD_OP_CLEAR);
            si(attachment, ATTACHMENT_DESCRIPTION, "storeOp", Vk.ATTACHMENT_STORE_OP_STORE);
            si(attachment, ATTACHMENT_DESCRIPTION, "stencilLoadOp", Vk.ATTACHMENT_LOAD_OP_DONT_CARE);
            si(attachment, ATTACHMENT_DESCRIPTION, "stencilStoreOp", Vk.ATTACHMENT_STORE_OP_DONT_CARE);
            si(attachment, ATTACHMENT_DESCRIPTION, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
            si(attachment, ATTACHMENT_DESCRIPTION, "finalLayout", finalLayout);

            MemorySegment colorRef = arena.allocate(ATTACHMENT_REFERENCE);
            si(colorRef, ATTACHMENT_REFERENCE, "attachment", 0);
            si(colorRef, ATTACHMENT_REFERENCE, "layout", Vk.IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            MemorySegment subpass = arena.allocate(SUBPASS_DESCRIPTION);
            si(subpass, SUBPASS_DESCRIPTION, "pipelineBindPoint", Vk.PIPELINE_BIND_POINT_GRAPHICS);
            si(subpass, SUBPASS_DESCRIPTION, "colorAttachmentCount", 1);
            sa(subpass, SUBPASS_DESCRIPTION, "pColorAttachments", colorRef);

            int depCount = present ? 1 : 2;
            MemorySegment deps = arena.allocate(SUBPASS_DEPENDENCY, depCount);
            MemorySegment dep0 = deps.asSlice(0, SUBPASS_DEPENDENCY.byteSize());
            si(dep0, SUBPASS_DEPENDENCY, "srcSubpass", Vk.SUBPASS_EXTERNAL);
            si(dep0, SUBPASS_DEPENDENCY, "dstSubpass", 0);
            si(dep0, SUBPASS_DEPENDENCY, "srcStageMask", Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            si(dep0, SUBPASS_DEPENDENCY, "dstStageMask", Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            si(dep0, SUBPASS_DEPENDENCY, "srcAccessMask", 0);
            si(dep0, SUBPASS_DEPENDENCY, "dstAccessMask", Vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            if (!present) {
                // Make the colour writes visible to the follow-up stage: a transfer copy for a TRANSFER_SRC target,
                // or a fragment-shader sample for a SHADER_READ_ONLY target (rendering a 2D surface to be textured).
                MemorySegment dep1 = deps.asSlice(SUBPASS_DEPENDENCY.byteSize(), SUBPASS_DEPENDENCY.byteSize());
                si(dep1, SUBPASS_DEPENDENCY, "srcSubpass", 0);
                si(dep1, SUBPASS_DEPENDENCY, "dstSubpass", Vk.SUBPASS_EXTERNAL);
                si(dep1, SUBPASS_DEPENDENCY, "srcStageMask", Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                si(dep1, SUBPASS_DEPENDENCY, "dstStageMask",
                        sampled ? Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT : Vk.PIPELINE_STAGE_TRANSFER_BIT);
                si(dep1, SUBPASS_DEPENDENCY, "srcAccessMask", Vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                si(dep1, SUBPASS_DEPENDENCY, "dstAccessMask",
                        sampled ? Vk.ACCESS_SHADER_READ_BIT : Vk.ACCESS_TRANSFER_READ_BIT);
            }

            MemorySegment rpInfo = arena.allocate(RENDER_PASS_CREATE_INFO);
            si(rpInfo, RENDER_PASS_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
            si(rpInfo, RENDER_PASS_CREATE_INFO, "attachmentCount", 1);
            sa(rpInfo, RENDER_PASS_CREATE_INFO, "pAttachments", attachment);
            si(rpInfo, RENDER_PASS_CREATE_INFO, "subpassCount", 1);
            sa(rpInfo, RENDER_PASS_CREATE_INFO, "pSubpasses", subpass);
            si(rpInfo, RENDER_PASS_CREATE_INFO, "dependencyCount", depCount);
            sa(rpInfo, RENDER_PASS_CREATE_INFO, "pDependencies", deps);
            MemorySegment pRenderPass = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateRenderPass, dev, rpInfo, MemorySegment.NULL, pRenderPass), "vkCreateRenderPass");
            this.handle = pRenderPass.get(JAVA_LONG, 0);
        }
    }

    /** The {@code VkRenderPass} handle — passed to pipelines built against this pass and to its framebuffers. */
    public long handle() {
        return handle;
    }

    @Override
    public void close() {
        Probe.closed(Lane.GPU, "VulkanRenderPass", this);
        invokeVoid(vkDestroyRenderPass, device.handle(), handle, MemorySegment.NULL);
    }
}
