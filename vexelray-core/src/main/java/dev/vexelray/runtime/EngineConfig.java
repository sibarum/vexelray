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
 * @param framesInFlight  how many frames the CPU may record ahead of the GPU (1 is the simplest correct value)
 */
public record EngineConfig(String applicationName, boolean validation, int framesInFlight) {

    /**
     * The most frames any implementation here accepts. Not a hardware limit — a statement that the sync objects
     * are sized in the runtime and a caller asking for more has misunderstood what this dial does.
     */
    public static final int MAX_FRAMES_IN_FLIGHT = 3;

    public EngineConfig {
        if (applicationName == null || applicationName.isBlank()) {
            throw new IllegalArgumentException("applicationName must be non-blank");
        }
        if (framesInFlight < 1 || framesInFlight > MAX_FRAMES_IN_FLIGHT) {
            throw new IllegalArgumentException(
                    "framesInFlight must be 1.." + MAX_FRAMES_IN_FLIGHT + ", got " + framesInFlight);
        }
    }

    /**
     * A development configuration: validation on, one frame in flight.
     *
     * <p>One, not two, because that is what the runtime does today — {@code WindowedPresenter} waits on a single
     * fence, and a config promising two would be a number the engine quietly ignores. A default that lies about
     * the implementation is worse than a conservative one.
     */
    public static EngineConfig of(String applicationName) {
        return new EngineConfig(applicationName, true, 1);
    }

    /** This configuration with validation layers off — what a release or native-image build runs. */
    public EngineConfig withoutValidation() {
        return new EngineConfig(applicationName, false, framesInFlight);
    }

    /** This configuration recording {@code frames} ahead of the GPU. */
    public EngineConfig withFramesInFlight(int frames) {
        return new EngineConfig(applicationName, validation, frames);
    }
}
