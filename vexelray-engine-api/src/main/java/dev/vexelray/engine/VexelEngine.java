package dev.vexelray.engine;

/**
 * The engine facade an application holds — the front door of the public API. It owns the Vulkan runtime (instance,
 * device, swapchain/offscreen, sync) and the frame loop; the application only composes a {@link RenderPipeline}
 * and supplies per-frame logic. Everything below the pipeline — instance, device, swapchain, sync — is the
 * engine's, never the app's (architecture.md §4).
 *
 * <pre>{@code
 * try (VexelEngine engine = VexelEngine.create(EngineConfig.windowed("Fathom", 800, 600))) {
 *     engine.run(pipeline, frame -> {
 *         // read input + advance CPU sim, then feed per-frame data to techniques via their own APIs
 *         sdf.camera(camX, camY, camZ);
 *     });
 * }
 * }</pre>
 *
 * <p>The concrete implementation (a Vulkan runtime manager) lives in {@code vexelray-engine} and is obtained via
 * {@code create(...)} once that module lands; this interface fixes the contract the pipeline/technique layers and
 * client apps compile against first (see docs/refactor-decisions.md, Phase 2).
 */
public interface VexelEngine extends AutoCloseable {

    /**
     * Per-frame application hook, invoked once before each frame is recorded. Runs input handling and CPU
     * simulation, then pushes the results to techniques through their own content APIs (never raw GPU bytes — see
     * D5). Returning {@code false} requests an orderly shutdown of the run loop.
     *
     * @param frame the frame about to be rendered (index, clock, extent)
     * @return {@code true} to render this frame and continue; {@code false} to stop the loop
     */
    @FunctionalInterface
    interface FrameCallback {
        boolean onFrame(FrameInfo frame);
    }

    /**
     * Lightweight per-frame info handed to the {@link FrameCallback} before recording. Distinct from
     * {@link FrameContext} (which is the GPU recording surface handed to techniques): this is the CPU-side
     * simulation tick the application drives.
     *
     * @param frameIndex   monotonically increasing frame count since {@link #run} began
     * @param timeSeconds  seconds since {@link #run} began
     * @param deltaSeconds seconds since the previous frame
     * @param width        current framebuffer width
     * @param height       current framebuffer height
     */
    record FrameInfo(long frameIndex, double timeSeconds, double deltaSeconds, int width, int height) {
    }

    /**
     * Realise {@code pipeline} and render frames, invoking {@code onFrame} before each, until the callback returns
     * {@code false} or the window is closed. Drains the GPU and releases the realised pipeline before returning;
     * the engine stays open for another {@link #run}.
     */
    void run(RenderPipeline pipeline, FrameCallback onFrame);

    @Override
    void close();
}
