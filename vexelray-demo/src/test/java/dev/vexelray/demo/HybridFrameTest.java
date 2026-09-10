package dev.vexelray.demo;

import dev.vexelray.engine.EngineProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * That a marched SDF scene and a canvas batch composite in one frame — the claim the whole pipeline model exists
 * to support, in the build.
 *
 * <p>This is the only test in the repository that puts two <em>different first-party techniques</em> in one
 * pipeline. {@code TwoTechniqueTest} proves the lifecycle with two copies of one synthetic technique, which
 * establishes that the runtime drives a list; it cannot establish that two independently-authored pipelines,
 * with different vertex inputs, different descriptor set layouts and different push-constant layouts, coexist
 * in one render pass and one command buffer. That is a different question, and it is the one that was
 * impossible to ask before the engine existed.
 *
 * <p>Short on purpose — the manual {@code main} runs long enough to watch the camera orbit and the chrome
 * animate, and this runs long enough to prove both techniques survive being driven.
 */
class HybridFrameTest {

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
    void twoDifferentTechniquesCompositeInOneFrame() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");

        assertTrue(HybridFrameSmoke.measure(FRAMES) > 0,
                "an SDF march and a canvas batch did not survive being driven together; if either passes alone "
                        + "the fault is in how they share the render pass, not in either technique");
    }
}
