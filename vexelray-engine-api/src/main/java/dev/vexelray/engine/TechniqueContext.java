package dev.vexelray.engine;

import dev.vexelray.target.AttachmentFormat;

import java.util.Optional;

/**
 * What a {@link RenderTechnique} is given at {@link RenderTechnique#realize realise} time: the shape of the shared
 * target it must build against, and the handle of the render pass the runtime created for it. Everything here is
 * enough to build a pipeline compatible with the shared target without the technique owning the render pass,
 * framebuffers, or sync — the runtime owns those.
 *
 * <p>Vulkan handles are exposed as JDK primitives ({@code long}) so this contract stays free of any Vulkan
 * binding (see docs/refactor-decisions.md D2). A technique that needs the {@code VulkanDevice} to create objects
 * casts this to the runtime-provided Vulkan-bearing subtype (D3); this base interface is all a handle-only or
 * backend-agnostic technique needs.
 *
 * <p><b>No allocator, for now.</b> This interface used to hand out a {@code ResourceManager}, and nothing
 * implements that interface — so the accessor could not have been honoured by any runtime, and the first one
 * built would have had to return null or invent an allocator to satisfy a signature. Until a pooled allocator
 * exists, a technique allocates through the Vulkan-bearing subtype, which is what {@code StorageBuffer} and
 * {@code VertexBuffer} already take. Adding it back is a one-line change to this interface on the day there is
 * something to return.
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

    /**
     * Whether the shared target has a depth attachment, and therefore whether a pipeline built against
     * {@link #renderPass()} must declare depth-stencil state.
     *
     * <p>Derivable from {@link #depthFormat()}, and here anyway because the two silent failures it prevents are
     * both invisible: a pipeline with no depth state inside a pass that has depth draws in submission order,
     * which looks exactly like a technique-ordering mistake, and one with depth state inside a pass without it
     * is invalid usage the loader does not always report.
     */
    default boolean hasDepth() {
        return depthFormat().isPresent();
    }
}
