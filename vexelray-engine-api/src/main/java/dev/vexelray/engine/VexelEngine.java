package dev.vexelray.engine;

import dev.vexelray.runtime.EngineConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

/**
 * The engine facade an application holds — the front door of the public API. It owns the runtime (instance,
 * device, swapchain/offscreen, sync) and the frame loop; the application only composes a {@link RenderPipeline}
 * and supplies per-frame logic. Everything below the pipeline — instance, device, swapchain, sync — is the
 * engine's, never the app's (architecture.md §4).
 *
 * <pre>{@code
 * try (VexelEngine engine = VexelEngine.create(EngineConfig.of("Fathom"))) {
 *     engine.run(pipeline, frame -> {
 *         // read input + advance CPU sim, then feed per-frame data to techniques via their own APIs
 *         sdf.camera(camX, camY, camZ);
 *     });
 * }
 * }</pre>
 *
 * <p>Note what the example does <em>not</em> say: which runtime is running. {@link #create} resolves an
 * {@link EngineProvider} through {@link ServiceLoader}, so this module names no implementation and an
 * application depending on it drags in no Vulkan. That is the property that makes "techniques are open" true of
 * the runtime as well as of the content.
 */
public interface VexelEngine extends AutoCloseable {

    /**
     * Open the engine served by the {@link EngineProvider} on the module path.
     *
     * @throws IllegalStateException if no provider is present, or none reports itself available — the message
     *                               names the providers that <em>were</em> found, because "no engine" and "an
     *                               engine that declined this machine" are different faults with the same
     *                               symptom, and a message that cannot tell them apart sends the reader to the
     *                               wrong half of the stack
     */
    static VexelEngine create(EngineConfig config) {
        List<String> found = new ArrayList<>();
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            found.add(provider.name());
            if (provider.isAvailable()) {
                return provider.create(config);
            }
        }
        throw new IllegalStateException(found.isEmpty()
                ? "no EngineProvider on the module path; add a runtime such as vexelray-engine"
                : "no EngineProvider reported itself available; found but declined: " + found);
    }

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

    /**
     * The OS handle of the window this engine is presenting to, or {@code 0} when it is not running a windowed
     * pipeline.
     *
     * <p>The one thing an application legitimately needs back from the runtime it handed a target to. Input is
     * not the engine's — devices come from Tactroller, events from Atchung — but focus gating and pointer lock
     * are properties of a <em>window</em>, and the engine is what created it. Without this an application can
     * either not gate input on focus (so a game keeps walking while the user types in another window) or create
     * a second window of its own, which is worse.
     *
     * <p>Valid only while {@link #run} is executing, because that is when the window exists — which is why it is
     * a method here rather than a field on {@link FrameInfo} or {@code EngineConfig}. The config predates the
     * window; a per-frame record would invite reading it every frame, when the correct number of times is once.
     * Read it on the first frame:
     *
     * <pre>{@code
     * engine.run(pipeline, frame -> {
     *     if (frame.frameIndex() == 0) {
     *         input.attach(NativeWindow.ofHwnd(engine.windowHandle()));
     *     }
     *     ...
     * });
     * }</pre>
     */
    long windowHandle();

    @Override
    void close();
}
