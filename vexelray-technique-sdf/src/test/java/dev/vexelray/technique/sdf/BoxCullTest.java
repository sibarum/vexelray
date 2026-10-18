package dev.vexelray.technique.sdf;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Bounds;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>A march culled to its own box (D31)</b> draws the frame the unculled march draws: the box contains the scene,
 * so neither the pixels it leaves out nor the stretches of ray it strides over could have hit anything.
 *
 * <p>Two comparisons, because there are two changes and they are not equally exact:
 *
 * <ul>
 *   <li>against the <b>same box drawn as a plain D30 mask</b> — the same pixels, the march not culled along the
 *       ray — the frame is byte-identical. That is the slab test and the strides adding nothing at all;</li>
 *   <li>against the <b>unmasked march</b>, every pixel is lit or unlit alike, so nothing is clipped, and a few
 *       pixels differ by a level or two in a channel. That is D30's screen position — the mask's clip position
 *       divided per fragment, against the fullscreen triangle's interpolated {@code vUv} — the same ray to within
 *       a rounding, and the IR has no {@code gl_FragCoord} that would make the two one expression.</li>
 * </ul>
 *
 * <p>Lit, so a march that converged somewhere slightly different would show it in the shading rather than hide it
 * in a flat albedo; with depth, because that is the composite a culled march is for — a miss writes the far plane
 * and leaves the pixel alone, culled or not.
 */
class BoxCullTest {

    private static final int SIZE = 200;

    /** How many pixels may differ from the unmasked march: a percent. A large surface close up has the most. */
    private static final int ROUNDING_PIXELS = SIZE * SIZE / 100;

    /**
     * And by how much. A ray a rounding off converges a rounding off, and the shading at a grazing angle turns that
     * into a level or two; a clipped or added pixel is a whole colour, and the coverage check catches it first.
     */
    private static final int ROUNDING_LEVELS = 4;

    /** Two spheres and a box — bounded, and not a box. */
    private static final Surface BODIES = Surface.union(
            new Surface.Sphere(-1.6, 0.2, 6.5, 0.9),
            new Surface.Sphere(1.4, -0.4, 7.5, 1.1),
            new Surface.Box(0.0, 1.2, 8.0, 0.7, 0.4, 0.6));

    private static SdfScene scene(Surface surface) {
        return SdfScene.of(surface).withShading(Shadings.defaultKeyLight())
                .withAlbedo(new Surface.Rgb(0.78, 0.80, 0.86));
    }

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void seenFromOutsideTheBoxTheFrameIsTheUnculledOne() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        Frames frames = frames(scene(BODIES), 0, 0.3, 0, 0.05, -0.04);
        assertTrue(lit(frames.plain) > SIZE * SIZE / 20, "the scene barely appears; the test is not looking at it");
        frames.assertCulledIsUnculled("from outside the box");
    }

    /**
     * The eye inside the box: the box's back faces surround it and cover the screen, and the march starts at the
     * eye. Looked at in four directions, so the faces crossing the eye plane are clipped every way.
     */
    @Test
    void fromInsideTheBoxTheFrameIsTheUnculledOne() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        Surface around = Surface.union(
                new Surface.Sphere(-3.0, 0.0, 0.5, 1.0),
                new Surface.Sphere(3.0, 0.5, -0.5, 1.2),
                new Surface.Sphere(0.0, -0.4, 4.0, 1.0),
                new Surface.Sphere(0.3, 0.2, -4.0, 1.4));
        assertTrue(Bounds.of(around).orElseThrow().contains(0, 0, 0));

        for (double yaw : new double[]{0, Math.PI / 2, Math.PI, -Math.PI / 2 + 0.3}) {
            Frames frames = frames(scene(around), 0, 0, 0, yaw, 0.1);
            assertTrue(lit(frames.plain) > 0, "nothing in view at yaw " + yaw);
            frames.assertCulledIsUnculled("from inside the box, yaw " + yaw);
        }
    }

    /**
     * A body further than the step budget reaches — {@code 128 × 0.4} — is not drawn by the unculled march, and
     * must not be drawn by the culled one: the strides through empty space count against the budget exactly as the
     * steps they replace. Starting the march at the box would have reached it.
     */
    @Test
    void aBodyBeyondTheBudgetStaysUnseen() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        Surface far = Surface.union(new Surface.Sphere(0, 0, 6, 1), new Surface.Sphere(4, 0, 70, 8));
        Frames frames = frames(scene(far), 0, 0, 0, 0, 0);
        frames.assertCulledIsUnculled("with a body beyond the budget");
    }

    /** A plane reaches forever, so the scene has no box, and the march stays fullscreen — the same frame. */
    @Test
    void anUnboundedSceneStaysFullscreen() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        SdfScene scene = scene(Surface.union(BODIES, new Surface.Plane(0, 1, 0, 1.0)));
        SdfRaymarchTechnique culled = new SdfRaymarchTechnique(scene).cullToBounds();
        assertFalse(culled.culled(), "a scene with a ground plane was given a box");
        assertFalse(culled.masked());

        byte[] plain = capture(true, new SdfRaymarchTechnique(scene).camera(0, 0.3, 0, 0, -0.1));
        assertArrayEquals(plain, capture(true, culled.camera(0, 0.3, 0, 0, -0.1)));
    }

    /**
     * That the box really does cull: with no depth a miss paints the sky, so the sky appears exactly where the
     * march ran — inside the box's silhouette, and not over the rest of the frame.
     */
    @Test
    void theMarchRunsOnlyInsideTheBoxsSilhouette() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        SdfScene lit = scene(BODIES);
        SdfScene scene = new SdfScene(lit.surface(), lit.shading(), lit.march(), lit.albedo(),
                new Surface.Rgb(1, 0, 1), lit.focalLength(), lit.nearPlane());
        byte[] whole = capture(false, new SdfRaymarchTechnique(scene).camera(0, 0.3, 0, 0, 0));
        byte[] culled = capture(false, new SdfRaymarchTechnique(scene).cullToBounds().camera(0, 0.3, 0, 0, 0));

        int marched = SIZE * SIZE - count(culled, 0, 0, 0);
        assertEquals(0, count(whole, 0, 0, 0), "the fullscreen march left pixels unmarched");
        assertTrue(marched < SIZE * SIZE / 2, "the culled march ran on " + marched + " of " + SIZE * SIZE + " pixels");
        assertTrue(count(culled, 255, 0, 255) > 0, "no miss inside the box — the box is no larger than the scene?");
    }

    @Test
    void aBoxAndAMaskDoNotMix() {
        SdfScene scene = scene(BODIES);
        float[] triangle = {0, 0, 1, 1, 0, 1, 0, 1, 1};
        assertThrows(IllegalStateException.class,
                () -> new SdfRaymarchTechnique(scene).cullToBounds().mask(triangle));
        assertThrows(IllegalStateException.class,
                () -> new SdfRaymarchTechnique(scene).mask(triangle).cullToBounds());
    }

    /** The boxed pair is well-typed core IR — {@code lower} runs {@code CoreCheck} first. */
    @Test
    void theBoxedShadersLower() {
        assertEquals(2, new SdfComposer().composeBoxed(scene(BODIES)).size());
    }

    /** The unmasked march, the same box as a plain mask, and the culled march, from one camera. */
    private record Frames(byte[] plain, byte[] boxMask, byte[] culled) {

        void assertCulledIsUnculled(String where) {
            assertArrayEquals(boxMask, culled, where + ": the slab test and the strides changed the picture");
            int differing = 0;
            int worstOfAll = 0;
            for (int i = 0; i < plain.length; i += 4) {
                boolean plainLit = isLit(plain, i);
                assertEquals(plainLit, isLit(culled, i), where + ": pixel " + i / 4 + " was "
                        + (plainLit ? "clipped" : "added"));
                int worst = 0;
                for (int c = 0; c < 4; c++) {
                    worst = Math.max(worst, Math.abs((plain[i + c] & 0xFF) - (culled[i + c] & 0xFF)));
                }
                assertTrue(worst <= ROUNDING_LEVELS, where + ": pixel " + i / 4 + " differs by " + worst + " levels");
                differing += worst > 0 ? 1 : 0;
                worstOfAll = Math.max(worstOfAll, worst);
            }
            assertTrue(differing <= ROUNDING_PIXELS,
                    where + ": " + differing + " pixels differ, by up to " + worstOfAll + " levels");
            System.out.println("BoxCullTest " + where + ": " + differing + " pixels of " + plain.length / 4
                    + " differ from the unmasked march, by up to " + worstOfAll + " levels");
        }
    }

    private static Frames frames(SdfScene scene, double x, double y, double z, double yaw, double pitch) {
        Bounds box = SdfRaymarchTechnique.padded(Bounds.of(scene.surface()).orElseThrow());
        SdfRaymarchTechnique culled = new SdfRaymarchTechnique(scene).cullToBounds();
        assertTrue(culled.culled());
        return new Frames(
                capture(true, new SdfRaymarchTechnique(scene).camera(x, y, z, yaw, pitch)),
                capture(true, new SdfRaymarchTechnique(scene).mask(SdfRaymarchTechnique.boxTriangles(box))
                        .camera(x, y, z, yaw, pitch)),
                capture(true, culled.camera(x, y, z, yaw, pitch)));
    }

    private static boolean isLit(byte[] rgba, int i) {
        return rgba[i] != 0 || rgba[i + 1] != 0 || rgba[i + 2] != 0;
    }

    private static int lit(byte[] rgba) {
        return SIZE * SIZE - count(rgba, 0, 0, 0);
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

    private static byte[] capture(boolean depth, SdfRaymarchTechnique march) {
        Target.Builder target = Target.offscreen(SIZE, SIZE).color(AttachmentFormat.RGBA8_UNORM);
        if (depth) {
            target.depth(AttachmentFormat.DEPTH32F);
        }
        RenderPipeline pipeline = RenderPipeline.builder().target(target.build()).technique(march).build();
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("BoxCullTest"))) {
            engine.run(pipeline, frame -> false);
            return engine.lastFrameRgba();
        }
    }
}
