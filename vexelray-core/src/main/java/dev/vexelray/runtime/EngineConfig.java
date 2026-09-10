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
 * @param framesInFlight  how many frames the CPU may record ahead of the GPU. Raising it buys throughput —
 *                        the CPU records frame N+1 while the GPU draws frame N — and costs latency, because
 *                        a frame is now that many frames from the screen. It costs GPU memory too: the
 *                        windowed runtime duplicates command buffers and sync per frame, and depth per
 *                        swapchain image. It does <b>not</b> start a thread; see {@code RenderTechnique}'s
 *                        threading contract, which is unaffected. Honoured by the windowed path only — an
 *                        offscreen run is always one, and says so through {@code Diagnostics} if asked for
 *                        more
 */
public record EngineConfig(String applicationName, boolean validation, int framesInFlight) {

    /**
     * The most frames any implementation here accepts. Not a hardware limit — a statement that the sync objects
     * are sized in the runtime and a caller asking for more has misunderstood what this dial does.
     *
     * <p>Three is already past the point of diminishing returns: two is enough to keep the GPU fed while the
     * CPU records, and each further frame buys less throughput for another whole frame of latency.
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
     * <p>Still one now that the runtime honours more, and the reason has changed rather than expired. It used
     * to be that a config promising two would be a number the engine quietly ignored. Now it is that one is
     * the value <em>both</em> present paths do identically: an offscreen run is always one frame in flight,
     * and D23's whole argument for headless capture is that a captured frame is evidence about what a window
     * would show. A default that made the two paths differ in how many frames are in flight would weaken
     * that for every test in the build, in exchange for throughput no test wants.
     *
     * <p>So the choice is deliberate rather than conservative, and it is one call to change:
     * {@code EngineConfig.of("app").withFramesInFlight(2)} is what an interactive application that is
     * CPU-bound on recording should say.
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
