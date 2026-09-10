package dev.vexelray.engine.vulkan.runtime;

import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.util.Optional;

/**
 * What this runtime hands a technique at realise time: the shape of the shared colour+depth target, the render
 * pass built over it, and the device — {@link VulkanTechniqueContext} as one immutable value.
 *
 * <p>The interface is the contract and lives in {@code vexelray-engine-vulkan-api}, where a technique can
 * depend on it without depending on this engine. This record is one runtime's answer to it, and is
 * package-visible to no technique: a second Vulkan runtime — an offscreen one, or one embedding into somebody
 * else's swapchain — supplies its own and the techniques that already exist do not notice.
 *
 * <p>Built once per realise from the swapchain's extent, which is why {@link #width()} carries the warning it
 * does.
 *
 * @param device      the device every object a technique creates must belong to
 * @param colorFormat the shared colour attachment's engine-level format
 * @param depthFormat the shared depth attachment's format, or empty for a colour-only target
 * @param width       the target's extent at realise time — see the warning below
 * @param height      the target's extent at realise time
 * @param renderPass  the {@code VkRenderPass} a technique's pipeline must be built against
 */
public record SharedTargetContext(VulkanDevice device, AttachmentFormat colorFormat,
                                  Optional<AttachmentFormat> depthFormat, int width, int height,
                                  long renderPass) implements VulkanTechniqueContext {

    public SharedTargetContext {
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
