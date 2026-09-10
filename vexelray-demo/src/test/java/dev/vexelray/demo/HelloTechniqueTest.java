package dev.vexelray.demo;

import dev.vexelray.engine.EngineProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * That the worked example still works.
 *
 * <p>A reference implementation nothing runs is documentation that rots quietly, and the failure mode is
 * specific: the example keeps compiling long after the API it demonstrates has moved on, so the first person
 * to follow it is the one who discovers it is wrong. This is cheap insurance against that.
 *
 * <p>The device-free half is the more valuable of the two, because it is the half that runs in CI: composing
 * and lowering the fragment exercises {@code CoreCheck}, which is what stands between a mistyped IR expression
 * and a driver fault that takes the JVM with it.
 */
class HelloTechniqueTest {

    private static final int FRAMES = 8;

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void theExampleFragmentComposesAndTypeChecks() {
        // No device, no window: this runs everywhere. A SPIR-V module is a header of five words followed by
        // instructions, so anything shorter than that never reached the lowerer.
        byte[] spirv = HelloTechnique.fragmentSpirv();
        assertTrue(spirv.length > 20, "the example's fragment lowered to " + spirv.length
                + " bytes, which is not a SPIR-V module");
    }

    @Test
    void theExampleRunsThroughTheEngine() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device and a window");

        assertTrue(HelloTechnique.run(FRAMES) > 0,
                "the technique every third party is told to copy did not present a frame");
    }
}
