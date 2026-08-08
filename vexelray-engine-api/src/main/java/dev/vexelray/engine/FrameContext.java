package dev.vexelray.engine;

import java.lang.foreign.MemorySegment;

/**
 * What a {@link RenderTechnique} is given at {@link RenderTechnique#record record} time: the command buffer the
 * runtime has already put into a begun render pass, plus the frame's clock and extent. The technique binds its
 * pipeline into this command buffer, pushes its per-frame data, and draws; it does not begin/end the render pass,
 * acquire images, or submit — the runtime frames every technique's recording.
 *
 * <p>The command buffer is a {@code MemorySegment} (the Panama handle) so this contract carries no Vulkan-binding
 * type (see docs/refactor-decisions.md D2). Per-frame application state (camera, time-driven inputs) does not flow
 * through here — it reaches a technique via the technique's own API from the {@link VexelEngine} run callback
 * (D5); this context is the drawing surface, not the data channel.
 *
 * @param commandBuffer the recording command buffer, inside a render pass the runtime has begun
 * @param frameIndex    monotonically increasing frame count since the pipeline started running
 * @param timeSeconds   seconds since the pipeline started running, for animation
 * @param deltaSeconds  seconds elapsed since the previous frame
 * @param width         current framebuffer width in pixels (may change across frames on resize)
 * @param height        current framebuffer height in pixels
 */
public record FrameContext(MemorySegment commandBuffer, long frameIndex, double timeSeconds,
                           double deltaSeconds, int width, int height) {

    public FrameContext {
        if (commandBuffer == null) {
            throw new IllegalArgumentException("commandBuffer must not be null");
        }
    }
}
