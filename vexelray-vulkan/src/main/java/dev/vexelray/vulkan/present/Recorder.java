package dev.vexelray.vulkan.present;

import java.lang.foreign.MemorySegment;

/**
 * Per-frame hook that records the <em>contents</em> of the render pass — the seam that turns a presenter from
 * "present one pipeline" into "present whatever a caller draws."
 *
 * <p>Everything a presenter is careful about — the fence, the image acquire, the semaphores, the submit, the
 * present, the out-of-date rebuild — stays with the presenter. What moves out is the handful of commands
 * between {@code vkCmdBeginRenderPass} and {@code vkCmdEndRenderPass}. That division is not a refactor for
 * tidiness: it is precisely the line between what every renderer needs identically and what each one needs
 * differently, and until it existed a frame could contain exactly one pipeline, so no two features in this
 * repository could appear in the same window.
 *
 * <p>The command buffer arrives inside a begun render pass with <b>nothing bound</b> — no pipeline, no
 * descriptor sets, no vertex buffer. A recorder binds what it needs, per pipeline it draws with, in the order
 * it wants them composited. It must not begin or end the render pass, submit, or touch the swapchain.
 *
 * <p>{@code width} and {@code height} are this frame's extent, which is not the extent the pipeline was built
 * at: a resize changes it without rebuilding anything.
 *
 * <p><b>The dynamic viewport and scissor are already set to that extent.</b> They are the one piece of state a
 * presenter sets before handing the buffer over, because they are the only thing every recorder needs
 * identically and derives from a number only the presenter knows — and because the alternative was observed:
 * four recorders each carrying the same {@code VkViewport} fill and the same two {@code vkCmdSet*} calls, where
 * forgetting them draws nothing and reading a stale extent draws the previous size. A recorder that wants a
 * sub-rectangle overrides them and is responsible for restoring them before the next pipeline that assumes the
 * whole frame.
 *
 * <p>Top-level rather than nested in {@link WindowedPresenter}, because {@link OffscreenPresenter} takes the
 * same recorder and the engine hands the same lambda to both. That is the property the whole headless path
 * rests on: a pipeline of techniques renders to a window and to an image through <em>one</em> body of
 * recording code, so a captured frame is evidence about the presented one rather than about a second
 * implementation that happens to look similar.
 */
@FunctionalInterface
public interface Recorder {

    /**
     * Record this frame's draws into {@code commandBuffer}, inside the already-begun render pass.
     *
     * @param commandBuffer the frame's command buffer, in a begun pass with nothing bound but viewport/scissor
     * @param width         this frame's extent, which may differ from the extent anything was built at
     * @param height        this frame's extent
     */
    void record(MemorySegment commandBuffer, int width, int height);
}
