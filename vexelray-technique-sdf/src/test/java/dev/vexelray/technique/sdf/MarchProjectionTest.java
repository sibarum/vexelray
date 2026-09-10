package dev.vexelray.technique.sdf;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>The march's depth is planar, not radial</b> — the one part of the projection convention that no
 * comparison between two marched surfaces can check.
 *
 * <h2>The error this exists to catch</h2>
 *
 * <p>A sphere-tracer's {@code t} is the distance along a unit ray. A depth buffer holds the distance to the
 * plane through the camera's forward axis. They agree only at the exact centre of the screen and diverge by
 * {@code 1/cos} with the angle off that axis — at the corner of this frame, a factor of about 1.42.
 *
 * <p>Delete the cosine from {@link SdfComposer} and {@code MarchDepthTest} still passes, because both of its
 * spheres are scaled by the same factor at the same pixel and their comparison is unchanged. What the error
 * does is bend the marched world into a bowl relative to <em>anything that is not marched</em>: a flat wall
 * bulges toward the camera at the edges of the screen and pushes through geometry genuinely in front of it.
 *
 * <h2>The experiment</h2>
 *
 * <p>A marched plane perpendicular to the view axis at {@code z = 5}, and a {@link ConstantDepthTechnique}
 * wall — planar by construction — a little behind it at {@code z = 5.4}. Correct behaviour is that the
 * marched plane wins the entire frame, corners included, because it is nearer everywhere.
 *
 * <p>With the cosine dropped, the marched plane's apparent depth at the corner would be that of a surface at
 * {@code 5 / 0.70 ≈ 7.1} — behind the reference wall — so the corners would flip to the wall's colour while
 * the centre stayed marched. That is a frame this test cannot produce and the assertion below would fail on
 * it, which is precisely what it is for.
 */
class MarchProjectionTest {

    private static final int SIZE = 128;

    /** The reference wall sits behind the marched plane by enough to be unambiguous, and far less than 1/cos. */
    private static final double MARCHED_PLANE_Z = 5.0;
    private static final double REFERENCE_WALL_Z = 5.4;

    private static final Surface.Rgb MARCHED = new Surface.Rgb(0, 1, 0);
    private static final Surface.Rgb WALL = new Surface.Rgb(1, 0, 1);
    private static final Surface.Rgb SKY = new Surface.Rgb(0, 0, 0);

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * A plane perpendicular to the view axis at {@code z = MARCHED_PLANE_Z}, as a very large sphere would not
     * do: {@code Surface.Plane} with a {@code +z} normal is exactly the constant-view-depth surface whose
     * marched depth must come out constant.
     */
    private static SdfRaymarchTechnique marchedWall() {
        Surface plane = new Surface.Plane(0, 0, -1, MARCHED_PLANE_Z);
        SdfScene scene = new SdfScene(plane, Shadings.unlit(), MarchSettings.DEFAULT,
                MARCHED, SKY, 1.4, 0.05);
        SdfRaymarchTechnique technique = new SdfRaymarchTechnique(scene);
        technique.camera(0, 0, 0, 0, 0);
        return technique;
    }

    @Test
    void aMarchedWallIsNearerThanAPlanarOneBehindIt_atTheCornersToo() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        ClipDepth convention = marchedWall().scene().clipDepth();
        byte[] rgba = capture(marchedWall(),
                new ConstantDepthTechnique(convention, REFERENCE_WALL_Z, WALL));

        // Every pixel, not a sample: the failure is a ring at the edge of the frame, and a check that looked
        // only at the middle would report success on exactly the frame this test exists to reject.
        assertEquals(SIZE * SIZE, count(rgba, MARCHED),
                "the marched plane should own the whole frame — a corner going to the reference wall's "
                        + "colour is the radial-vs-planar error, and it is what ClipDepth.ofRadial's cosine "
                        + "argument prevents");
    }

    /**
     * The same pair with the reference wall moved in front of the marched plane, which must then win
     * everywhere.
     *
     * <p>Without this, the assertion above would also pass if the reference wall had failed to draw at all —
     * a shader that did not compile, a technique never recorded, a depth write that went nowhere. Here the
     * wall must be visible, and visible over the whole frame.
     */
    @Test
    void theReferenceWallWinsWhenItIsInFront() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        ClipDepth convention = marchedWall().scene().clipDepth();
        byte[] rgba = capture(marchedWall(),
                new ConstantDepthTechnique(convention, MARCHED_PLANE_Z - 1.0, WALL));

        assertEquals(SIZE * SIZE, count(rgba, WALL),
                "a planar wall one unit in front of the marched plane should cover all of it");
    }

    private static byte[] capture(RenderTechnique... techniques) {
        RenderPipeline.Builder pipeline = RenderPipeline.builder()
                .target(Target.offscreen(SIZE, SIZE)
                        .color(AttachmentFormat.RGBA8_UNORM)
                        .depth(AttachmentFormat.DEPTH32F)
                        .build());
        for (RenderTechnique technique : techniques) {
            pipeline.technique(technique);
        }
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("MarchProjectionTest"))) {
            engine.run(pipeline.build(), frame -> false);
            return engine.lastFrameRgba();
        }
    }

    private static int count(byte[] rgba, Surface.Rgb colour) {
        int r = (int) Math.round(colour.r() * 255);
        int g = (int) Math.round(colour.g() * 255);
        int b = (int) Math.round(colour.b() * 255);
        int found = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            if ((rgba[i] & 0xFF) == r && (rgba[i + 1] & 0xFF) == g && (rgba[i + 2] & 0xFF) == b) {
                found++;
            }
        }
        return found;
    }
}
