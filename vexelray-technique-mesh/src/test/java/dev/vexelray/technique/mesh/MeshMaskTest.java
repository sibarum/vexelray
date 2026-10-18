package dev.vexelray.technique.mesh;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.technique.sdf.MarchSettings;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfRaymarchTechnique;
import dev.vexelray.technique.sdf.SdfScene;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>A mesh as the render mask of a marched object</b>: the scene appears exactly inside the mesh's silhouette
 * and nowhere else, with the mesh's hard edge, and the mesh itself is never drawn.
 *
 * <p>A blue unlit sphere filling the middle of the frame, and a small square mask in front of it. Unlit, so every
 * marched pixel is exactly the albedo and can be counted by equality; the mask is axis-aligned and faces the
 * camera, so its projected footprint is a square whose pixel bounds the CPU can compute from the pinhole.
 */
class MeshMaskTest {

    private static final int SIZE = 160;
    private static final double FOCAL = 1.4;

    private static final SdfScene SCENE = new SdfScene(new Surface.Sphere(0, 0, 6, 1.2), Shadings.unlit(),
            MarchSettings.DEFAULT, new Surface.Rgb(0, 0, 1), new Surface.Rgb(0, 0, 0), FOCAL, 0.05);

    /** A square in the mesh's own xy plane, {@code size} on a side. */
    private static Mesh square(double size) {
        double h = size / 2;
        return Mesh.polygon(-h, -h, 0, h, -h, 0, h, h, 0, -h, h, 0);
    }

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    private static SdfRaymarchTechnique sphere() {
        return new SdfRaymarchTechnique(SCENE).camera(0, 0, 0, 0, 0);
    }

    private static SdfRaymarchTechnique maskedSphere(Mesh mask, Placement at) {
        return sphere().mask(mask.worldPositions(at));
    }

    @Test
    void theSceneAppearsOnlyInsideTheMasksSilhouette() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        // 0.6 wide at depth 4: half-width focal*0.3/4 in device units, times SIZE/2 pixels per unit.
        double halfPx = FOCAL * 0.3 / 4 * SIZE / 2;
        byte[] frame = capture(true, maskedSphere(square(0.6), Placement.at(0, 0, 4)));
        byte[] whole = capture(true, sphere());

        int inside = 0;
        int outside = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                if (!isBlue(frame, x, y)) {
                    continue;
                }
                double dx = Math.abs(x + 0.5 - SIZE / 2.0);
                double dy = Math.abs(y + 0.5 - SIZE / 2.0);
                if (dx <= halfPx + 1 && dy <= halfPx + 1) {
                    inside++;
                } else {
                    outside++;
                }
            }
        }
        assertEquals(0, outside, "the sphere was drawn outside the mask's silhouette");
        assertTrue(inside > 0, "nothing was drawn inside the mask");
        assertTrue(count(whole, 0, 0, 255) > 4 * inside, "the mask did not restrict the sphere at all");

        // A hard edge: the square's footprint, give or take its boundary row of pixels, and no blends.
        double area = 4 * halfPx * halfPx;
        double perimeter = 8 * halfPx;
        assertTrue(Math.abs(inside - area) <= perimeter,
                "the masked area is " + inside + " pixels; the square's footprint is about " + (int) area);
        assertEquals(SIZE * SIZE, inside + count(frame, 0, 0, 0), "some pixel is neither scene nor background");
    }

    /**
     * A silhouette, not a portal: the mask's own depth plays no part, so a mask behind the sphere masks it exactly
     * as one in front does — given that it covers the same pixels, which a square twice as far and twice as big does.
     */
    @Test
    void theMasksDepthDoesNotMatter() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        byte[] near = capture(true, maskedSphere(square(0.6), Placement.at(0, 0, 4)));
        byte[] far = capture(true, maskedSphere(square(0.6), Placement.at(0, 0, 4).scaled(5).moved(0, 0, 20)));
        assertArrayEquals(near, far, "a mask behind the object masked it differently from one in front");
    }

    /**
     * Where a masked ray misses, what was already in the frame stays: the miss writes the far plane, which never
     * passes a {@code LESS} test, so the mask does not paint sky over the scene behind it.
     */
    @Test
    void aMissInsideTheMaskLeavesThePixelAlone() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        MeshTechnique backdrop = new MeshTechnique(SCENE.lens(), Shadings.unlit());
        backdrop.add(Mesh.builder().colour(0, 1, 0).polygon(-40, -40, 0, 40, -40, 0, 40, 40, 0, -40, 40, 0).build(),
                Placement.at(0, 0, 30));
        backdrop.camera(0, 0, 0, 0, 0);

        // A mask much wider than the sphere, so most of it is misses.
        byte[] frame = capture(true, backdrop, maskedSphere(square(3), Placement.at(0, 0, 3)));
        assertEquals(0, count(frame, 0, 0, 0), "a miss inside the mask painted the sky over the backdrop");
        assertTrue(count(frame, 0, 0, 255) > 0, "the sphere did not draw through the mask");
        assertEquals(SIZE * SIZE, count(frame, 0, 0, 255) + count(frame, 0, 255, 0));
    }

    @Test
    void aLaterMaskMustFitTheFirst() {
        SdfRaymarchTechnique march = maskedSphere(square(1), Placement.ORIGIN);
        assertTrue(march.masked());
        assertThrows(IllegalStateException.class, () -> march.mask(Mesh.box(1, 1, 1).worldPositions(Placement.ORIGIN)));
        assertThrows(IllegalArgumentException.class, () -> march.mask(new float[]{0, 0, 0}));
    }

    /** The masked pair is well-typed core IR — {@code lower} runs {@code CoreCheck} first. */
    @Test
    void theMaskedShadersLower() {
        assertEquals(2, new SdfComposer().composeMasked(SCENE).size());
    }

    @Test
    void worldPositionsArePlaced() {
        float[] p = square(2).worldPositions(new Placement(1, 2, 3, 0, 0, 0, 2));
        assertEquals(6 * 3, p.length);
        for (int i = 0; i < p.length; i += 3) {
            assertEquals(2.0, Math.abs(p[i] - 1), 1e-6);
            assertEquals(2.0, Math.abs(p[i + 1] - 2), 1e-6);
            assertEquals(3.0, p[i + 2], 1e-6);
        }
    }

    private static byte[] capture(boolean depth, RenderTechnique... techniques) {
        Target.Builder target = Target.offscreen(SIZE, SIZE).color(AttachmentFormat.RGBA8_UNORM);
        if (depth) {
            target.depth(AttachmentFormat.DEPTH32F);
        }
        RenderPipeline.Builder pipeline = RenderPipeline.builder().target(target.build());
        for (RenderTechnique technique : techniques) {
            pipeline.technique(technique);
        }
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("MeshMaskTest"))) {
            engine.run(pipeline.build(), frame -> false);
            return engine.lastFrameRgba();
        }
    }

    private static boolean isBlue(byte[] rgba, int x, int y) {
        int i = (y * SIZE + x) * 4;
        return (rgba[i] & 0xFF) == 0 && (rgba[i + 1] & 0xFF) == 0 && (rgba[i + 2] & 0xFF) == 255;
    }

    private static int count(byte[] rgba, int r, int g, int b) {
        int found = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            if ((rgba[i] & 0xFF) == r && (rgba[i + 1] & 0xFF) == g && (rgba[i + 2] & 0xFF) == b) {
                found++;
            }
        }
        return found;
    }
}
