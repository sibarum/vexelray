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

    /** The vertex buffer and set-0 descriptor set every frame binds; see {@link WindowedPresenter#configureDraw}. */
    void configureDraw(long vertexBuffer, long descriptorSet, int vertexCount);

    /** This frame's draws; see {@link WindowedPresenter#setRuns}. */
    void setRuns(List<WindowedPresenter.Run> runs);

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

    @Override
    void close();
}
