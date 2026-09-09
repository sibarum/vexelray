package dev.vexelray.engine.vulkan;

import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.util.Optional;

/**
 * The {@link TechniqueContext} this runtime hands to a technique at realise time — the Vulkan-bearing subtype
 * {@code RenderTechnique}'s javadoc tells techniques to cast to (docs/refactor-decisions.md D3).
 *
 * <p>The split is deliberate and worth stating from this side of it. {@link TechniqueContext} carries formats,
 * an extent, and a render-pass handle as plain values, so a technique that only needs to build a pipeline
 * compiles against {@code vexelray-engine-api} and never sees Vulkan. A technique that must <em>create</em>
 * objects — a buffer, a descriptor set, a sampled image — needs the device, and there is no honest way to hand
 * that over without naming Vulkan. So it is named here, in the runtime, rather than smuggled into the public
 * contract as an opaque {@code long} that only one implementation could interpret.
 *
 * <p>A technique that casts has coupled itself to this runtime, and that is the intended trade rather than a
 * leak: it is what "a backend abstraction is deferred — YAGNI until a second backend exists" costs, paid at the
 * point where it is visible.
 *
 * @param device      the device every object a technique creates must belong to
 * @param colorFormat the shared colour attachment's engine-level format
 * @param depthFormat the shared depth attachment's format, or empty for a colour-only target
 * @param width       the target's extent at realise time — see the warning below
 * @param height      the target's extent at realise time
 * @param renderPass  the {@code VkRenderPass} a technique's pipeline must be built against
 */
public record VulkanTechniqueContext(VulkanDevice device, AttachmentFormat colorFormat,
                                     Optional<AttachmentFormat> depthFormat, int width, int height,
                                     long renderPass) implements TechniqueContext {

    public VulkanTechniqueContext {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        if (colorFormat == null) {
            throw new IllegalArgumentException("colorFormat must not be null");
        }
        depthFormat = depthFormat == null ? Optional.empty() : depthFormat;
        if (renderPass == 0L) {
            throw new IllegalArgumentException("renderPass must not be 0 for a graphics technique");
        }
    }

    /**
     * The extent at the moment this context was made — <b>not</b> the extent the frame being drawn has.
     *
     * <p>Stated as an override with its own note because reading it and keeping it is the resize bug
     * {@code RenderTechnique} warns about: a window resize changes the extent every frame and does not re-realise
     * anything, so a pipeline sized from here draws to the wrong rectangle forever after the first drag. Use it
     * to size resources that genuinely cannot be dynamic; take the drawing extent from {@code FrameContext}.
     */
    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }
}
