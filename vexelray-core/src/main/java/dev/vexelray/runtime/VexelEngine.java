package dev.vexelray.runtime;

import dev.vexelray.pipeline.RenderPipeline;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * The engine facade — the object an application holds. It couples an {@link EngineConfig} to a
 * {@link RuntimeManager} (which owns the Vulkan device), realises a {@link RenderPipeline}, and drives the frame
 * loop. It adds no Vulkan of its own; it orchestrates the manager, so it stays testable against any
 * {@code RuntimeManager} implementation (real device or a headless fake).
 *
 * <p>The {@code RuntimeManager} is injected rather than constructed here so the concrete Vulkan implementation
 * ({@code VulkanRuntimeManager}) and this orchestration evolve independently — and so tests can drive the whole
 * frame loop without a GPU. A convenience factory that wires the real manager lands with that implementation.
 */
public final class VexelEngine implements AutoCloseable {

    private final EngineConfig config;
    private final RuntimeManager runtime;

    public VexelEngine(EngineConfig config, RuntimeManager runtime) {
        this.config = Objects.requireNonNull(config, "config");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public EngineConfig config() {
        return config;
    }

    public RuntimeManager runtime() {
        return runtime;
    }

    /**
     * Realise {@code pipeline} and render frames until {@code keepRunning} returns {@code false} (or the window
     * is closed, which the manager signals by returning no frame). Drains the GPU and releases the realised
     * pipeline before returning; the engine itself stays open for another {@link #run} or reconfiguration.
     */
    public void run(RenderPipeline pipeline, BooleanSupplier keepRunning) {
        try (RuntimeManager.RealizedPipeline realized = runtime.realize(pipeline)) {
            while (keepRunning.getAsBoolean()) {
                runtime.renderFrame(realized);
            }
            runtime.waitIdle();
        }
    }

    /** Render exactly {@code frames} frames of {@code pipeline} — the headless smoke-test / offline-render entry. */
    public void renderFrames(RenderPipeline pipeline, int frames) {
        int[] remaining = {frames};
        run(pipeline, () -> remaining[0]-- > 0);
    }

    @Override
    public void close() {
        runtime.close();
    }
}
