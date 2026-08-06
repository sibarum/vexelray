package dev.vexelray.runtime;

/**
 * Per-frame context handed to render callbacks: which in-flight slot and swapchain image this frame uses, the
 * current framebuffer extent, and the animation clock. A pass reads {@code timeSeconds}/{@code extent} to fill
 * the standard per-frame uniforms (SupirVast's {@code Fullscreen} resolution+time block), and the runtime uses
 * {@code imageIndex}/{@code frameSlot} to pick the right command buffer and sync objects.
 *
 * @param frameIndex   monotonically increasing count since engine start
 * @param frameSlot    the in-flight slot in {@code [0, framesInFlight)}
 * @param imageIndex   the acquired swapchain image index (0 for offscreen)
 * @param width,height the current framebuffer extent in pixels
 * @param timeSeconds  seconds since engine start, for animation
 */
public record Frame(long frameIndex, int frameSlot, int imageIndex, int width, int height, double timeSeconds) {
}
