package dev.vexelray.vulkan.present;

import java.util.List;

/**
 * What a window's owner needs of the thing that gets its frames onto the screen: the extent a frame is drawn at, a
 * frame, the draw bindings, and teardown.
 *
 * <p>{@link WindowedPresenter} is the Vulkan-swapchain one. A platform module may offer another through
 * {@link PresenterProvider} — on Windows, the one that presents through DXGI, so that a window resize is the OS's
 * own buffer resize instead of a new Vulkan swapchain.
 */
public interface WindowPresenter extends AutoCloseable {

    /** The width the next (or current) frame is drawn at, in pixels — what a canvas lays out to. */
    int width();

    /** The height the next (or current) frame is drawn at. */
    int height();

    /**
     * One frame without pumping events: bring the target to the window's size, call {@code perFrame} to fill the
     * vertex buffer and runs, record, submit, present. Returns false once nothing more can be presented.
     */
    boolean render(int pushConstantBytes, FrameUpdate perFrame);

    /**
     * Whether {@link #render} would draw now without the CPU waiting on the GPU or the compositor: the frame before is
     * done with what the next one rewrites, and there is somewhere to present it. Never blocks. A thread that takes
     * input as well as drawing asks this first, and when it is false keeps taking input and is told by
     * {@link #whenReady} when to draw, instead of sleeping inside {@link #render} while the GPU finishes.
     *
     * <p>{@link #render} still waits for whatever it needs, so a caller that does not ask (a screenshot) is correct, only
     * slower. A presenter that cannot tell says true, and its {@link #render} waits as it always did.
     *
     * <p>On the thread that renders.
     */
    default boolean ready() {
        return true;
    }

    /**
     * Run {@code wake}, once, on any thread, when {@link #ready} next turns true, or when the presenter would draw
     * anyway (a compositor that stops answering, as one does for a hidden window, is waited for a bounded time, as
     * {@link #render} waits for it). For after {@link #ready} said false: {@code wake} only tells the drawing thread to
     * look again, and should post to it rather than draw. Asked again before the last one ran, it replaces it.
     *
     * <p>On the thread that renders. The default runs {@code wake} at once, which is right for a presenter that is
     * always ready.
     */
    default void whenReady(Runnable wake) {
        wake.run();
    }

    /** The vertex buffer and set-0 descriptor set every frame binds; see {@link WindowedPresenter#configureDraw}. */
    void configureDraw(long vertexBuffer, long descriptorSet, int vertexCount);

    /** The set-2 descriptor set every frame binds, or 0 for none; see {@link WindowedPresenter#configureSet2}. */
    void configureSet2(long descriptorSet);

    /** This frame's draws; see {@link WindowedPresenter#setRuns}. */
    void setRuns(List<WindowedPresenter.Run> runs);

    /**
     * Work recorded into each frame's command buffer before its render pass begins: passes into targets the frame then
     * samples, such as an SDG group drawn apart (vexelray-canvas's {@code Canvas.GroupTarget}). Null for none. Read
     * every frame, on the thread that renders; a target's pass ends in a layout its sampling reads, so the main pass
     * needs no barrier of its own.
     */
    void prePass(Recorder recorder);

    /**
     * Let a resize during a live drag wait for its rebuild ({@link WindowedPresenter#throttleResize}). A presenter
     * whose resize is cheap ignores it.
     */
    default void throttleResize(boolean on) {
    }

    /**
     * The last frame this presenter put on the screen, read back from the image it was presented from: tightly packed
     * R8G8B8A8, opaque, row-major, top-to-bottom, {@link #width()}×{@link #height()}. Null when there is no frame yet,
     * or when this presenter cannot read its frames back; a caller then has to draw the picture some other way, and
     * that picture shows nothing of how this presenter draws.
     *
     * <p>A screenshot should come from here. A frame drawn again for the camera goes through code the screen never
     * sees, and agrees with the screen only for as long as the two are kept in step by hand.
     *
     * <p>On the thread that renders, between frames.
     */
    default byte[] readFrame() {
        return null;
    }

    /**
     * Hand every frame presented from now on to {@code tap}, read back a frame or more later without the loop waiting
     * for it ({@link FrameTap}); {@code null} stops, after the frames already copied for the old tap are handed to it.
     * False when this presenter cannot read its frames back, and nothing will come.
     *
     * <p>On the thread that renders, between frames.
     */
    default boolean tap(FrameTap tap) {
        return false;
    }

    @Override
    void close();
}
