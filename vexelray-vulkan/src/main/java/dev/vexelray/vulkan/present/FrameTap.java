package dev.vexelray.vulkan.present;

/**
 * Where a presenter hands every frame it puts on the screen, read back without the render loop waiting for it: what a
 * video recorder, a still-per-redraw sink or a spike detector watches a window through ({@link WindowPresenter#tap}).
 *
 * <p><b>A frame or more late, never in the way.</b> The copy out of the presented image is recorded into the frame's
 * own command buffer, into one of a few host-visible buffers ({@link FrameTapRing}), and handed over once the GPU has
 * finished it, which the presenter learns at a later frame's fence. So the frames arrive in order, each with the time
 * it was presented, a frame or two after it was shown. A frame presented while every buffer is still held is not
 * copied at all, and never arrives: a tap that keeps frames too long loses some, and the window is not slowed.
 *
 * <p>Called on the thread that renders. It must return quickly: it is inside a frame.
 */
@FunctionalInterface
public interface FrameTap {

    /**
     * A presented frame. Its pixels are the tap's until it calls {@link TappedFrame#release}, from any thread, and the
     * buffer they are in is not copied into again before that.
     */
    void frame(TappedFrame frame);
}
