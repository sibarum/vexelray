package dev.vexelray.engine.vulkan.runtime;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.diag.Diagnostics;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.vulkan.vk.VulkanDebugMessenger;
import dev.vexelray.vulkan.vk.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The runtime honouring {@code EngineConfig.framesInFlight} — and the validation layer as the thing that
 * says whether it honoured it <em>correctly</em>.
 *
 * <h2>Why the check is validation errors and not pixels</h2>
 *
 * <p>Frames in flight changes no picture. Two frames overlapping produces exactly the frames one frame at a
 * time would have produced, or it is broken; there is no output to count and no colour to compare. What it
 * changes is whether the synchronisation is correct, and a wrong answer there does not look like a wrong
 * frame — it looks like a frame that is right almost always, on this driver, at this refresh rate.
 *
 * <p>So the assertion is that the Vulkan validation layer reported nothing new across the run. That is a real
 * measurement and a demanding one: re-recording a command buffer the GPU is still executing, re-signalling a
 * semaphore whose wait has not completed, and drawing into an image a live frame still holds are each an
 * error the layer names, and each is a mistake this arrangement of per-slot and per-image objects exists to
 * avoid.
 *
 * <p><b>It needs the validation layer, and skips without it.</b> A skip is the honest report: with no layer
 * the run proves only that nothing crashed, and saying so is different from passing. Run with
 * {@code -Dvexelray.vulkan.validation} on a machine with the Vulkan SDK.
 *
 * <h2>Enough frames to wrap the slots several times</h2>
 *
 * <p>The interesting moments are all at a slot boundary: the first reuse of a slot, which is the first time
 * the fence wait has anything to wait for, and the first reuse of a swapchain image, which is the first time
 * the in-flight table matters. With three slots against a swapchain that is usually three images, both need
 * more than a handful of frames to come around.
 */
class FramesInFlightTest {

    /** Enough to wrap three slots several times, few enough that the window barely appears. */
    private static final int FRAMES = 24;

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs the pipeline windowed at {@code framesInFlight} and returns how many frames the callback saw.
     *
     * <p>Windowed, because the offscreen path deliberately renders one frame at a time — which is the other
     * half of this feature and is asserted below.
     */
    private static long runWindowed(int framesInFlight) {
        TintTechnique background = new TintTechnique(new float[]{0.10f, 0.12f, 0.16f}, 0.05f);
        TintTechnique foreground = new TintTechnique(new float[]{0.30f, 0.72f, 0.98f}, 0.55f);

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("VexelRay — frames in flight", 480, 320)
                        .color(AttachmentFormat.SWAPCHAIN)
                        .depth(AttachmentFormat.DEPTH32F))
                .technique(background)
                .technique(foreground)
                .build();

        long[] presented = {0};
        try (VexelEngine engine = VexelEngine.create(
                EngineConfig.of("FramesInFlightTest").withFramesInFlight(framesInFlight))) {
            engine.run(pipeline, frame -> {
                presented[0] = frame.frameIndex() + 1;
                return frame.frameIndex() < FRAMES - 1;
            });
        }
        assertEquals(1, background.realizeCount(), "realise must still happen exactly once per run");
        assertEquals(1, foreground.closeCount(), "close must still happen exactly once per run");
        assertTrue(background.recordCount() > 0, "the technique was never recorded");
        return presented[0];
    }

    @Test
    void threeFramesInFlightDrawsWithoutAValidationError() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");
        assumeTrue(VulkanInstance.validationLayerActive(),
                "the validation layer is not active — run with -Dvexelray.vulkan.validation and the Vulkan "
                        + "SDK installed. Without it the error count cannot move and this would pass "
                        + "while measuring nothing.");

        long before = VulkanDebugMessenger.errorCount();
        assertTrue(runWindowed(3) > 0, "the loop never presented a frame");
        VulkanDebugMessenger.failOnError(before);
    }

    /**
     * One frame in flight, through the same code path, must stay just as clean.
     *
     * <p>The control that matters most for a change like this one: the arrangement of slots and per-image
     * objects is new code that <em>every</em> windowed run now goes through, including the default. If this
     * failed and the test above passed, the regression would be in the common case rather than the new one.
     */
    @Test
    void oneFrameInFlightStillDrawsWithoutAValidationError() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");
        assumeTrue(VulkanInstance.validationLayerActive(),
                "the validation layer is not active — run with -Dvexelray.vulkan.validation and the Vulkan "
                        + "SDK installed. Without it the error count cannot move and this would pass "
                        + "while measuring nothing.");

        long before = VulkanDebugMessenger.errorCount();
        assertTrue(runWindowed(1) > 0, "the loop never presented a frame");
        VulkanDebugMessenger.failOnError(before);
    }

    /**
     * An offscreen run renders one frame at a time whatever the config says, and reports that it did.
     *
     * <p>Silence would be the wrong answer twice over: {@code lastFrameRgba()} promises the frame the run
     * finished on, which is only well defined while one frame is in flight, and a config value quietly
     * ignored is the fault {@code Diagnostics} exists for.
     */
    @Test
    void anOffscreenRunSaysItKeepsOneFrameInFlight() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        Diagnostics.reset();
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.offscreen(32, 32).color(AttachmentFormat.RGBA8_UNORM))
                .technique(new SolidTechnique(1, 0, 0, 1))
                .build();

        try (VexelEngine engine = VexelEngine.create(
                EngineConfig.of("FramesInFlightTest").withFramesInFlight(3))) {
            engine.run(pipeline, frame -> false);
            assertEquals(32 * 32 * 4, engine.lastFrameRgba().length);
        }

        assertTrue(Diagnostics.recorded().stream().anyMatch(m -> m.contains("frames in flight")),
                () -> "an offscreen run asked for 3 frames in flight should say it kept 1: "
                        + Diagnostics.recorded());
    }
}
