package dev.vexelray.engine.vulkan;

import dev.vexelray.engine.EngineProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@link dev.vexelray.engine.RenderTechnique} lifecycle, asserted against the real runtime.
 *
 * <p>{@link EngineSpiTest} covers what can be checked without a GPU — discovery, context invariants, ordering.
 * This is the other half, and it is the half that matters: an SPI is validated by something implementing it and
 * being driven, not by its own javadoc. It opens a window for a handful of frames and then asserts each promise
 * separately, because "realised twice" and "never recorded" are different bugs in different places and a single
 * boolean would not say which.
 *
 * <p>Short on purpose. The manual {@link TwoTechniqueSmoke} runs long enough to watch; this runs long enough to
 * establish that the loop iterates, which is all the lifecycle claim needs.
 */
class TwoTechniqueTest {

    /** Enough frames that recording is plainly repeating, few enough that the window barely appears. */
    private static final int FRAMES = 12;

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void twoTechniquesShareOneFrameAndAreDrivenAsThePromiseSays() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");

        TwoTechniqueSmoke.Result result = TwoTechniqueSmoke.measure(FRAMES);

        assertTrue(result.presented() > 0, "the loop never presented a frame");

        for (TintTechnique technique : new TintTechnique[]{result.background(), result.foreground()}) {
            assertEquals(1, technique.realizeCount(),
                    "realise must happen exactly once per run: more means the runtime re-realised on a resize, "
                            + "which the SPI promises it does not, and zero means a technique in the pipeline "
                            + "was never set up");
            assertEquals(1, technique.closeCount(),
                    "close must happen exactly once: zero is a GPU-object leak the JVM will not report");
            assertTrue(technique.recordCount() > 0, "the technique was never recorded");
            // An upper bound rather than equality: a frame whose acquire came back out of date is rebuilt and
            // skipped without recording, so equality would fail the first time the window was resized.
            assertTrue(technique.recordCount() <= result.presented(),
                    "recorded " + technique.recordCount() + " times in " + result.presented()
                            + " frames — a technique recorded twice in a frame draws twice");
        }
    }
}
