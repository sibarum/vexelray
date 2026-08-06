package dev.vexelray.pipeline;

/**
 * A format for a render-target or depth attachment, abstract over the concrete {@code VkFormat} the runtime
 * resolves it to. Kept small and intent-named so pipeline configuration reads in engine terms; the runtime maps
 * each to a device-supported Vulkan format (and {@link #SWAPCHAIN} to whatever the surface presents).
 */
public enum AttachmentFormat {
    /** Match the swapchain's surface format — the final presented colour target. */
    SWAPCHAIN,
    /** 8-bit UNORM RGBA colour. */
    RGBA8_UNORM,
    /** 16-bit float RGBA — an HDR intermediate for lighting/tone-mapping before a post pass. */
    RGBA16F,
    /** 32-bit float depth. */
    DEPTH32F,
    /** 24-bit depth + 8-bit stencil. */
    DEPTH24_STENCIL8;

    public boolean isDepth() {
        return this == DEPTH32F || this == DEPTH24_STENCIL8;
    }
}
