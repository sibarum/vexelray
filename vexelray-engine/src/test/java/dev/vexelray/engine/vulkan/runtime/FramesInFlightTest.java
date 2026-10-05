package dev.vexelray.engine.vulkan.runtime;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.supirvast.vulkan.VulkanDebugMessenger;
import dev.supirvast.vulkan.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * One frame in flight, as a rule: a frame is not redrawn while the one before it is still being drawn.
 *
 * <p>This used to drive the engine at three frames in flight. That was taken away on purpose (2026-10-04,
 * {@code EngineConfig.MAX_FRAMES_IN_FLIGHT}): the presenter's per-slot sync was sound, but the mapped buffers a
 * frame writes are one buffer each, so a second frame in flight overwrote what the GPU was still reading. What is
 * left to prove is that the one remaining number still draws cleanly through the slot machinery, and that asking
 * for more is refused rather than quietly honoured or quietly ignored.
 *
 * <h2>Why the drawing check is validation errors and not pixels</h2>
 *
 * <p>Synchronisation changes no picture. A wrong answer does not look like a wrong frame — it looks like a frame
 * that is right almost always, on this driver, at this refresh rate. So the assertion is that the Vulkan
 * validation layer reported nothing new across the run, and the test <b>skips without the layer</b>: with none,
 * the run proves only that nothing crashed. Run with {@code -Dvexelray.vulkan.validation} and the Vulkan SDK.
 */
class FramesInFlightTest {

    /** Enough frames for every swapchain image to come round more than once. */
    private static final int FRAMES = 24;

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    /** Runs the pipeline windowed at the one permitted frame in flight and returns how many frames it saw. */
    private static long runWindowed() {
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
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("FramesInFlightTest"))) {
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
    void oneFrameInFlightDrawsWithoutAValidationError() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");
        assumeTrue(VulkanInstance.validationLayerActive(),
                "the validation layer is not active — run with -Dvexelray.vulkan.validation and the Vulkan "
                        + "SDK installed. Without it the error count cannot move and this would pass "
                        + "while measuring nothing.");

        long before = VulkanDebugMessenger.errorCount();
        assertTrue(runWindowed() > 0, "the loop never presented a frame");
        VulkanDebugMessenger.failOnError(before);
    }

    /**
     * Asking for more is refused where it is asked, before any device exists, and says why. Needs no GPU: the
     * refusal is in the configuration, which is the point — an engine that took the number and drew one frame at a
     * time anyway would be the quietly-ignored setting {@code Diagnostics} exists to prevent.
     */
    @Test
    void moreThanOneFrameInFlightIsRefusedBeforeAnythingIsBuilt() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> EngineConfig.of("FramesInFlightTest").withFramesInFlight(3));
        assertTrue(refused.getMessage().contains("MAX_FRAMES_IN_FLIGHT"),
                () -> "the refusal should name where the reason is written down: " + refused.getMessage());
    }
}
