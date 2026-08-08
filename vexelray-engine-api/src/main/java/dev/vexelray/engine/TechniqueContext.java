package dev.vexelray.engine;

import dev.vexelray.pipeline.AttachmentFormat;
import dev.vexelray.resource.ResourceManager;

import java.util.Optional;

/**
 * What a {@link RenderTechnique} is given at {@link RenderTechnique#realize realise} time: the shape of the shared
 * {@link Target} it must build against, the handle of the render pass the runtime created for that target, and the
 * engine's {@link ResourceManager}. Everything here is enough to build a pipeline compatible with the shared
 * target without the technique owning the render pass, framebuffers, or sync — the runtime owns those.
 *
 * <p>Vulkan handles are exposed as JDK primitives ({@code long}) so this contract stays free of any Vulkan
 * binding (see docs/refactor-decisions.md D2). A technique that needs the {@code VulkanDevice} to create objects
 * casts this to the runtime-provided Vulkan-bearing subtype (D3); this base interface is all a handle-only or
 * backend-agnostic technique needs.
 */
public interface TechniqueContext {

    /** The shared colour attachment format — the format the technique's pipeline must be compatible with. */
    AttachmentFormat colorFormat();

    /** The shared depth attachment format, or empty if the target has no depth buffer. */
    Optional<AttachmentFormat> depthFormat();

    /** Target width in pixels at realise time. */
    int width();

    /** Target height in pixels at realise time. */
    int height();

    /**
     * The {@code VkRenderPass} handle the runtime created for the shared target. A technique builds its graphics
     * pipeline against this handle so its draws composite into the shared colour+depth attachments. Zero for a
     * technique kind that does not use a render pass (e.g. pure compute).
     */
    long renderPass();

    /** The engine's sole GPU allocator — where a technique allocates its buffers/images. */
    ResourceManager resources();
}
