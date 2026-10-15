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
import dev.vexelray.technique.sdf.SdfRaymarchTechnique;
import dev.vexelray.technique.sdf.SdfScene;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.ServiceLoader;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>A mesh and a marched surface occlude each other, per pixel</b> — the first technique with real geometry,
 * cross-occluding the march through the shared {@code ClipDepth}.
 *
 * <p>The arrangement is {@code PanelOcclusionTest}'s, with a polygon in place of the panel: a square pitched so it
 * cuts through a sphere, its near half in front of the sphere's surface and its far half behind. Submission order
 * can give the shared region to one or the other; only a per-pixel depth test gives each of them some of it. Both
 * are unlit, so a pixel is exactly one albedo or the other and can be counted by equality.
 */
class MeshOcclusionTest {

    private static final int SIZE = 160;

    private static final double FOCAL = 1.4;
    private static final double NEAR = 0.05;

    private static final SdfScene SCENE = new SdfScene(new Surface.Sphere(0, 0, 6, 1.2), Shadings.unlit(),
            MarchSettings.DEFAULT, new Surface.Rgb(0, 0, 1), new Surface.Rgb(0, 0, 0), FOCAL, NEAR);

    /** A 4x4 square in the mesh's own xy plane. */
    private static final Mesh SQUARE = Mesh.builder()
            .colour(0, 1, 0)
            .polygon(-2, -2, 0, 2, -2, 0, 2, 2, 0, -2, 2, 0)
            .build();

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    private static SdfRaymarchTechnique sphere() {
        SdfRaymarchTechnique technique = new SdfRaymarchTechnique(SCENE);
        technique.camera(0, 0, 0, 0, 0);
        return technique;
    }

    /** The square centred in the sphere and pitched one radian: it spans view depths 4.3 to 7.7. */
    private static MeshTechnique square() {
        MeshTechnique technique = new MeshTechnique(SCENE.lens(), Shadings.unlit());
        technique.add(SQUARE, new Placement(0, 0, 6, 0, 1.0, 0, 1));
        technique.camera(0, 0, 0, 0, 0);
        return technique;
    }

    @Test
    void eachTakesPixelsFromTheOther() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        int sphereAlone = count(capture(true, sphere()), 0, 0, 255);
        int squareAlone = count(capture(true, square()), 0, 255, 0);
        assertTrue(sphereAlone > 0, "the sphere did not render at all");
        assertTrue(squareAlone > 0, "the square did not render at all");

        byte[] both = capture(true, sphere(), square());
        int sphereBoth = count(both, 0, 0, 255);
        int squareBoth = count(both, 0, 255, 0);

        assertTrue(sphereBoth > 0, "the square hid the entire sphere — that is painting, not occluding");
        assertTrue(squareBoth > 0, "the sphere hid the entire square — that is painting, not occluding");
        assertTrue(sphereBoth < sphereAlone,
                "the sphere kept all " + sphereAlone + " of its pixels; the square's near half did not occlude it");
        assertTrue(squareBoth < squareAlone,
                "the square kept all " + squareAlone + " of its pixels; the sphere did not occlude its far half");
        // Every pixel is one of the two or the background: nothing blended, nothing lost.
        assertEquals(SIZE * SIZE, sphereBoth + squareBoth + count(both, 0, 0, 0));
    }

    /** Depth decides, so reversing the pipeline's list changes not one byte. */
    @Test
    void orderDoesNotChangeWhatOccludesWhat() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        assertArrayEquals(capture(true, sphere(), square()), capture(true, square(), sphere()),
                "the frame depends on the order, so it is being painted rather than occluded");
    }

    /** The control: without a depth attachment the technique recorded last keeps every pixel it covers. */
    @Test
    void withoutDepthTheLastTechniquePaintsOverTheFirst() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        int squareAlone = count(capture(false, square()), 0, 255, 0);
        int squareLast = count(capture(false, sphere(), square()), 0, 255, 0);
        assertEquals(squareAlone, squareLast);
    }

    /**
     * Lit by the scene's model: a box turned to show three faces under a key light comes out as three distinct
     * colours, because the three faces' normals differ — and only as three, because each face is flat.
     */
    @Test
    void aBoxUnderAKeyLightShowsItsFacesApart() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        MeshTechnique box = new MeshTechnique(SCENE.lens(), Shadings.defaultKeyLight());
        box.add(Mesh.box(1.5, 1.5, 1.5), new Placement(0, 0, 5, 0.6, -0.5, 0, 1)).tint(0.8, 0.8, 0.8);
        box.camera(0, 0, 0, 0, 0);

        byte[] frame = capture(true, box);
        Set<Integer> colours = new HashSet<>();
        int covered = 0;
        for (int i = 0; i < frame.length; i += 4) {
            int rgb = (frame[i] & 0xFF) << 16 | (frame[i + 1] & 0xFF) << 8 | (frame[i + 2] & 0xFF);
            if (rgb != 0) {
                colours.add(rgb);
                covered++;
            }
        }
        assertTrue(covered > SIZE * SIZE / 20, "the box covered only " + covered + " pixels");
        assertEquals(3, colours.size(), "expected one flat colour per visible face, got " + colours.size());
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
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("MeshOcclusionTest"))) {
            engine.run(pipeline.build(), frame -> false);
            return engine.lastFrameRgba();
        }
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
