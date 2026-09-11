package dev.vexelray.technique.sdf;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.surface.ParamId;
import dev.vexelray.surface.Scalar;
import dev.vexelray.surface.Surface;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.present.StorageBuffer;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P0b on a real device: a design too large for push constants renders, and renders <em>what the values say</em>.
 *
 * <p>Everything else about the buffer road is checked without a GPU — the IR is validated, the two lowerings
 * are compared point by point, {@code spirv-val} accepts the module. None of that can tell a correct buffer
 * layout from one the shader reads at the wrong index and draws as empty space, which is exactly the class of
 * fault the march smokes exist for. So this one renders and counts.
 *
 * <p>The control is the part that makes the count mean something: the same pipeline, the same everything, with
 * the radii written small instead of large. A shader that ignored the buffer would draw the identical picture
 * twice, and a count that did not move would say so.
 */
class BufferRoadSmokeTest {

    private static final int WIDTH = 200;
    private static final int HEIGHT = 150;

    /** Enough spheres that no device's push constants could hold them: 64 parameters, and the cap is 57 here. */
    private static final int SPHERES = 64;

    private static boolean hasGraphicsDevice() {
        try (VulkanInstance instance = new VulkanInstance("VexelRay buffer road probe",
                NativePlatform.current().requiredVulkanInstanceExtensions())) {
            return instance.selectGraphicsDevice().isPresent();
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    /** A row of spheres along X, each with a driven radius — one parameter apiece, and nothing else driven. */
    private static Surface row(List<ParamId> ids) {
        List<Surface> of = new ArrayList<>();
        for (int i = 0; i < SPHERES; i++) {
            Scalar.Param radius = Scalar.Param.over(0.02, 0.45, 0.05);
            ids.add(radius.id());
            of.add(new Surface.Sphere(Scalar.of((i - SPHERES / 2.0) * 0.5), Scalar.of(0), Scalar.of(0),
                    radius));
        }
        return new Surface.Union(of);
    }

    @Test
    @DisplayName("a design past the push-constant cap renders, and the buffer is what it draws from")
    void theBufferRoadDraws() {
        assumeTrue(hasGraphicsDevice(), "no Vulkan graphics device — this renders and counts");

        List<ParamId> radii = new ArrayList<>();
        SdfScene scene = SdfScene.of(row(radii));

        try (VulkanInstance instance = new VulkanInstance("VexelRay buffer road",
                NativePlatform.current().requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice().orElseThrow();
            ParamBacking backing = ParamBacking.on(selection.maxPushConstantBytes());

            // The premise. If some future device reported enough push-constant space for 64 parameters this
            // test would silently stop testing the buffer, so it says so rather than passing quietly.
            assertTrue(backing.usesBuffer(SPHERES),
                    "this device holds " + backing.pushConstantCapacity() + " parameters in push constants, "
                            + "which is enough for " + SPHERES + " — raise SPHERES to keep testing the buffer");

            List<ComposedShader> composed = new SdfComposer(backing).compose(scene);
            ParamBlock values = SdfComposer.paramBlock(scene, backing);

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 StorageBuffer buffer = new StorageBuffer(device, values.size(), 0)) {

                long small = draw(device, composed, scene, values, buffer, radii, 0.02);
                long large = draw(device, composed, scene, values, buffer, radii, 0.45);

                assertTrue(large > 0, "the buffer road drew nothing at all");
                assertTrue(large > small * 2,
                        "writing larger radii drew " + large + " pixels against " + small + " for small ones; "
                                + "a shader that ignored the buffer would have drawn the same twice");
                assertEquals(SdfComposer.FIRST_PARAM_MEMBER * 4, SdfComposer.pushBytes(scene, backing),
                        "on this road the push block is the camera and the lens alone");
            }
        }
    }

    /** Write one radius into every slot, render, and count the pixels that are not sky. */
    private static long draw(VulkanDevice device, List<ComposedShader> composed, SdfScene scene,
                             ParamBlock values, StorageBuffer buffer, List<ParamId> radii, double radius) {
        for (ParamId id : radii) {
            values.write(id, radius);
        }
        buffer.update(values.floats(), values.size());

        Surface.Rgb sky = scene.sky();
        byte[] push = SdfComposer.pushConstantBytes(scene, 0, 0, -14, 0, 0,
                (double) WIDTH / HEIGHT, values, ParamBacking.on(device.maxPushConstantBytes()));
        byte[] rgba = OffscreenRenderer.render(device, WIDTH, HEIGHT,
                composed.get(0).spirv(), "main", composed.get(1).spirv(), "main", 3,
                (float) sky.r(), (float) sky.g(), (float) sky.b(), 1f, push,
                new long[]{buffer.descriptorSetLayout()}, new long[]{buffer.descriptorSet()});
        return countNonSky(rgba, sky);
    }

    private static long countNonSky(byte[] rgba, Surface.Rgb sky) {
        int r = (int) Math.round(sky.r() * 255);
        int g = (int) Math.round(sky.g() * 255);
        int b = (int) Math.round(sky.b() * 255);
        long count = 0;
        for (int i = 0; i + 3 < rgba.length; i += 4) {
            if (Math.abs((rgba[i] & 0xFF) - r) > 6
                    || Math.abs((rgba[i + 1] & 0xFF) - g) > 6
                    || Math.abs((rgba[i + 2] & 0xFF) - b) > 6) {
                count++;
            }
        }
        return count;
    }
}
