package dev.vexelray.technique.sdf;

import dev.vexelray.engine.EngineProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * That an {@link SdfScene} reaches the screen through the engine rather than through hand-wired Vulkan.
 *
 * <p>Thinner than {@link MarchSmokeTest}, and covering something that test cannot: the march smokes render
 * offscreen through {@code OffscreenRenderer}, which builds its own pipeline and never touches a swapchain, a
 * technique, or the frame loop. This drives the whole runtime — realise, record per frame, close — with a real
 * scene, so a fault in {@code SdfRaymarchTechnique}'s pipeline config, its push-constant layout, or its
 * interaction with the shared render pass surfaces here and nowhere else.
 *
 * <p>It asserts that frames were presented, not what they contained. Counting pixels needs a readback the
 * windowed path does not do, so what is established is that the runtime drove a real technique to completion
 * without throwing — which is exactly the claim, and less than the picture.
 */
class SdfEngineTest {

    /** Enough to prove the loop iterates and the camera is pushed more than once. */
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
    void anSdfSceneMarchesThroughTheEngine() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");

        assertTrue(SdfEngineSmoke.measure(FRAMES) > 0,
                "the engine presented no frame for a scene that composes and lowers, so the fault is in the "
                        + "technique or the runtime rather than in the field");
    }
}
