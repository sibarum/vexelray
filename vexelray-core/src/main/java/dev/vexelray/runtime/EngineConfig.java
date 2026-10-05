package dev.vexelray.runtime;

/**
 * What the engine must know <em>before a device exists</em>: the application identity Vulkan reports, whether
 * validation layers are loaded, and how many frames the CPU may record ahead of the GPU. Fixed for the engine's
 * lifetime.
 *
 * <h2>What is deliberately not here</h2>
 *
 * <p>Where the frame goes, how big it is, and what format it is in are <b>not</b> engine configuration — they
 * belong to {@link dev.vexelray.target.Target}, which arrives with a pipeline and can differ between two
 * pipelines the same engine runs. This record used to carry a {@code SurfaceTarget} as well, and that was the
 * duplication worth naming: two types describing the surface meant two answers to "how wide is the frame," and
 * nothing in either said which one the runtime believed. An engine that outlives its pipelines cannot own the
 * pipeline's target.
 *
 * <p>The split falls exactly where Vulkan's own does. Instance and device creation need the application name and
 * the validation choice and cannot wait for a target; the swapchain needs the target and cannot be built until
 * one is declared. So this is the pre-device half, and {@code Target} is the post-device half — an ordering
 * constraint the API now states rather than leaves to the caller to respect.
 *
 * @param applicationName reported to Vulkan via {@code VkApplicationInfo}. Not the window title — that is the
 *                        target's, and letting this stand in for it would recreate in miniature the duplication
 *                        the class note above describes
 * @param validation      load the Vulkan validation layers — development on, native-image/release off
 * @param framesInFlight  how many frames the CPU may record ahead of the GPU. <b>Always one</b>; see
 *                        {@link #MAX_FRAMES_IN_FLIGHT} for why it is a rule and not a default
 */
public record EngineConfig(String applicationName, boolean validation, int framesInFlight) {

    /**
     * The most frames any implementation here accepts: one. <b>A frame is not redrawn while the one before it is
     * still being drawn.</b>
     *
     * <p>A rule, not a tuning default, and it was more than one before (2026-10-04). Two or three let the CPU
     * record frame N+1 while the GPU drew frame N, and the presenter's sync was built for that, but every buffer
     * a frame writes through a mapping — the canvas's vertices, a storage buffer's parameters — is one buffer,
     * rewritten each frame. With a second frame in flight the CPU overwrites what the GPU is still reading, and
     * nothing says so: the picture is right almost always, on this driver, at this refresh rate. Making that safe
     * means a copy of every such buffer per frame slot, for throughput this stack has never been short of.
     *
     * <p>Kept as a constant and as a record component, rather than deleted, so a caller asking for more is told
     * why by name instead of finding the method gone.
     */
    public static final int MAX_FRAMES_IN_FLIGHT = 1;

    public EngineConfig {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("applicationName must be non-blank");
        }
        if (framesInFlight != MAX_FRAMES_IN_FLIGHT) {
            throw new IllegalArgumentException("framesInFlight must be " + MAX_FRAMES_IN_FLIGHT + ", got "
                    + framesInFlight + ": a frame is not redrawn while the one before it is still being drawn, "
                    + "because the buffers a frame writes are shared by every frame (EngineConfig.MAX_FRAMES_IN_FLIGHT)");
        }
    }

    /**
     * A development configuration: validation on, one frame in flight — the only number of frames in flight
     * there is (see {@link #MAX_FRAMES_IN_FLIGHT}). It is also what makes a headless capture evidence about a
     * window: both present paths draw one frame at a time.
     */
    public static EngineConfig of(String applicationName) {
        return new EngineConfig(applicationName, true, 1);
    }

    /** This configuration with validation layers off — what a release or native-image build runs. */
    public EngineConfig withoutValidation() {
        return new EngineConfig(applicationName, false, framesInFlight);
    }

    /**
     * This configuration recording {@code frames} ahead of the GPU — which must be one.
     *
     * @throws IllegalArgumentException for any other number, with the reason; see {@link #MAX_FRAMES_IN_FLIGHT}
     */
    public EngineConfig withFramesInFlight(int frames) {
        return new EngineConfig(applicationName, validation, frames);
    }
}
