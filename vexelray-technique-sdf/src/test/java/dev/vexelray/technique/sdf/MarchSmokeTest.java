package dev.vexelray.technique.sdf;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.vulkan.vk.VulkanInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The two march smokes, run by the build.
 *
 * <h2>Why these are tests now</h2>
 *
 * <p>{@link ConeMarchSmoke} drew nothing for two commits and nobody noticed, because it was a {@code main} and
 * surefire does not run those. It is the only kind of check in this repository that <b>renders and counts</b> —
 * every other test proves that IR is valid, or that SPIR-V is well-formed, or that a packing is correct, and
 * none of them can tell a correct buffer layout from one the march reads at the wrong offset and renders as
 * empty space. An instrument that valuable being outside the build was the actual fault; the focal-length bug it
 * failed to report was only the consequence.
 *
 * <p>They need a GPU, which is why they were mains in the first place. That is handled by skipping rather than
 * by staying out of the build: on a machine with no Vulkan loader or no graphics device these report as skipped
 * and say so, and on a developer's desktop — which is where this project runs — they execute. A skipped test is
 * visible; an unrun {@code main} is not, and that difference is the whole change.
 *
 * <p>Both write their render into a temporary directory rather than the working tree. The picture is for a human
 * who is already looking; the pixel counts are what pass or fail.
 */
class MarchSmokeTest {

    /**
     * Whether this machine can run a march at all.
     *
     * <p>Checked by actually creating an instance and selecting a device, because that is the thing that has to
     * work — a loader that dlopens and a device that can present are two different questions, and guessing from
     * the OS name answers neither.
     */
    private static boolean hasGraphicsDevice() {
        try (VulkanInstance instance = new VulkanInstance("VexelRay march smoke probe",
                NativePlatform.current().requiredVulkanInstanceExtensions())) {
            return instance.selectGraphicsDevice().isPresent();
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    @Test
    void aStrokeCompiledIntoItsOwnShaderDraws(@TempDir Path out) throws IOException {
        assumeTrue(hasGraphicsDevice(), "no Vulkan graphics device — the march smokes need one");

        Smoke smoke = StrokeMarchSmoke.measure(out.resolve("stroke-march.png").toString());

        assertTrue(smoke.verdict(),
                "the compiled-field lane drew nothing, or drew the same with the eye inside the world box, "
                        + "which means the count is not measuring the geometry");
    }

    @Test
    void aFieldReadFromABufferDraws(@TempDir Path out) throws IOException {
        assumeTrue(hasGraphicsDevice(), "no Vulkan graphics device — the march smokes need one");

        Smoke smoke = ConeMarchSmoke.measure(out.resolve("cone-march.png").toString());

        assertTrue(smoke.verdict(),
                "the buffer-driven lane drew nothing, or drew the same with an empty buffer, which means the "
                        + "picture is not coming from the buffer");
    }
}
