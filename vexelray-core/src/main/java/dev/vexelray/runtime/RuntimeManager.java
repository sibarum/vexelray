package dev.vexelray.runtime;

import dev.vexelray.pipeline.RenderPipeline;
import dev.vexelray.resource.ResourceManager;

/**
 * Owns VexelRay's Vulkan runtime: the instance, physical/logical device, queues, and (for a windowed target) the
 * swapchain and present loop. This is the deliberate departure from SupirVast's monolithic hosts — a single
 * device that many passes share, so a hybrid frame graph can raster polygons and ray-march SDFs into one coherent
 * frame. Everything with a GPU lifetime hangs off the manager and is torn down deterministically on {@link #close()}.
 *
 * <p>Lifecycle: construct with an {@link EngineConfig} (creates the device + surface), {@link #realize} a
 * {@link RenderPipeline} into GPU objects (render passes, framebuffers, graphics/compute pipelines built from
 * runtime-composed shaders), then {@link #renderFrame} per frame. The {@link ResourceManager} it exposes is the
 * one place buffers/images are allocated against this device.
 *
 * <p>API-design-only: the reference implementation ({@code VulkanRuntimeManager}) will wire LWJGL Vulkan behind
 * this interface. The contract here fixes the seam so the pipeline/resource/shader layers can be built and unit-
 * tested against it first.
 */
public interface RuntimeManager extends AutoCloseable {

    /** The resource manager bound to this device — the sole allocator of GPU buffers/images for the engine. */
    ResourceManager resources();

    /**
     * Build a configured pipeline into concrete GPU objects: allocate its attachments, create its render passes
     * and framebuffers, and create a pipeline per pass from its runtime-composed shaders. Returns a handle the
     * frame loop draws with. Realising is done once (or on a config change), not per frame.
     */
    RealizedPipeline realize(RenderPipeline pipeline);

    /**
     * Acquire, record, submit, and present one frame of {@code pipeline}. Blocks per the target's present model
     * (vsync FIFO for a window). Returns the {@link Frame} that was rendered, or {@code null} if the frame was
     * skipped (e.g. a swapchain rebuild after resize).
     */
    Frame renderFrame(RealizedPipeline pipeline);

    /** Wait until the GPU has finished all outstanding work. Call before tearing down realised pipelines. */
    void waitIdle();

    @Override
    void close();

    /** Opaque handle to a {@link RenderPipeline} that has been built into GPU objects by {@link #realize}. */
    interface RealizedPipeline extends AutoCloseable {
        RenderPipeline source();

        @Override
        void close();
    }
}
