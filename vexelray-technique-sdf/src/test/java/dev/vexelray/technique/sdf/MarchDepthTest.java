package dev.vexelray.technique.sdf;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>The march writes a depth that means something</b> — two marched scenes, in one render pass, occluding
 * each other at the geometry rather than at a plane.
 *
 * <h2>What this is testing that the engine's own depth test is not</h2>
 *
 * <p>{@code DepthInterleaveTest} proves the plumbing: that {@code Builtin.FRAG_DEPTH} lowers, that
 * {@code DepthReplacing} reaches the driver, and that a shared attachment resolves two pipelines per pixel.
 * It does that with depths written as literals, so it would pass unchanged if the march's own depth were
 * nonsense.
 *
 * <p>This is the other half: that the number {@link SdfComposer} derives from a hit distance is the number a
 * depth buffer wanted. Two spheres are placed so that one is unambiguously in front of the other and their
 * silhouettes overlap, and what is asserted is the shape of the result rather than a screenshot of it.
 *
 * <h2>The arrangement, and why these numbers</h2>
 *
 * <p>Camera at the origin looking along {@code +z}. The near sphere spans view-space depths 3 to 3.75 (centre
 * to silhouette), the far sphere 4 to 4.8 — <b>disjoint ranges</b>, so the near one wins every pixel the two
 * share and there is no tie anywhere for a rounding error to decide. Both are well inside the frame and well
 * beyond the near plane, and they are offset sideways so each keeps a region the other does not reach.
 *
 * <p>Unlit shading, so a sphere is exactly its albedo and a pixel can be classified by equality rather than
 * by a tolerance on a lit gradient.
 *
 * <h2>Both scenes share a sky colour, deliberately</h2>
 *
 * <p>A fullscreen march writes the far plane where it hits nothing, so with two of them in one pass the
 * second technique's sky loses the depth test against the first's — equal depths, and the test is
 * <em>less than</em>. Whichever is drawn first therefore owns the background. That is correct, and it means
 * two marched scenes with different skies produce order-dependent backgrounds; giving both the same sky keeps
 * this test measuring depth rather than that.
 */
class MarchDepthTest {

    private static final int SIZE = 128;

    private static final Surface.Rgb NEAR_ALBEDO = new Surface.Rgb(0, 0, 1);
    private static final Surface.Rgb FAR_ALBEDO = new Surface.Rgb(1, 0, 0);
    private static final Surface.Rgb SKY = new Surface.Rgb(0, 0, 0);

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    /** Centred at {@code z = 4}, radius 1: view-space depths 3 (centre) through 3.75 (silhouette). */
    private static SdfRaymarchTechnique nearSphere() {
        return technique(new Surface.Sphere(0.4, 0, 4, 1), NEAR_ALBEDO);
    }

    /** Centred at {@code z = 5}, radius 1: view-space depths 4 through 4.8 — entirely behind the near one. */
    private static SdfRaymarchTechnique farSphere() {
        return technique(new Surface.Sphere(-0.4, 0, 5, 1), FAR_ALBEDO);
    }

    private static SdfRaymarchTechnique technique(Surface surface, Surface.Rgb albedo) {
        SdfScene scene = new SdfScene(surface, Shadings.unlit(), MarchSettings.DEFAULT,
                albedo, SKY, 1.4, 0.05);
        SdfRaymarchTechnique technique = new SdfRaymarchTechnique(scene);
        technique.camera(0, 0, 0, 0, 0);
        return technique;
    }

    @Test
    void theNearerMarchedSurfaceWinsEveryPixelTheyShare() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        int nearAlone = count(capture(true, nearSphere()), NEAR_ALBEDO);
        int farAlone = count(capture(true, farSphere()), FAR_ALBEDO);
        assertTrue(nearAlone > 0, "the near sphere did not render at all");
        assertTrue(farAlone > 0, "the far sphere did not render at all");

        byte[] both = capture(true, farSphere(), nearSphere());
        int nearBoth = count(both, NEAR_ALBEDO);
        int farBoth = count(both, FAR_ALBEDO);

        // The near sphere loses nothing: its depth range is entirely in front of the far one's, so there is
        // no pixel where the far sphere is nearer, and no pixel it should give up.
        assertEquals(nearAlone, nearBoth,
                "the nearer sphere lost pixels to one that is behind it everywhere");

        // The far sphere loses exactly its overlap. Both bounds matter: unchanged would mean the depth test
        // did nothing, and zero would mean it lost pixels the near sphere never covered.
        assertTrue(farBoth < farAlone,
                "the far sphere kept all " + farAlone + " of its pixels — nothing occluded it");
        assertTrue(farBoth > 0,
                "the far sphere lost every pixel, including the ones outside the near sphere's silhouette");

        // Nothing else is on screen, so the two silhouettes and the sky must account for the whole frame —
        // which is also how a stray colour from a botched shading path would show up.
        int sky = count(both, SKY);
        assertEquals(SIZE * SIZE, nearBoth + farBoth + sky,
                "some pixel is neither sphere nor sky");
    }

    /**
     * The same frame with the pipeline's list reversed, which depth must not notice.
     *
     * <p>The assertion that separates occlusion from painting. If the picture above came from the near sphere
     * simply being drawn last, listing it first would put the far sphere on top of it; if depth is deciding,
     * the two frames are the same bytes.
     */
    @Test
    void orderDoesNotChangeWhatOccludesWhat() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        byte[] farFirst = capture(true, farSphere(), nearSphere());
        byte[] nearFirst = capture(true, nearSphere(), farSphere());

        for (int i = 0; i < farFirst.length; i++) {
            int at = i;
            assertEquals(farFirst[i], nearFirst[i],
                    () -> "byte " + at + " differs — the frame depends on the order, so it is being painted "
                            + "rather than occluded");
        }
    }

    /**
     * The control: the same two techniques against a target with no depth attachment.
     *
     * <p>Now order <em>is</em> the composition, so the sphere drawn last covers the other one completely —
     * its sky writes over the earlier sphere. Without this, the test above could be passing because the near
     * sphere happens to hide the far one in both orders for some reason unrelated to depth.
     */
    @Test
    void withoutDepthTheLastMarchPaintsOverTheFirst() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        byte[] nearLast = capture(false, farSphere(), nearSphere());
        assertEquals(0, count(nearLast, FAR_ALBEDO),
                "with no depth attachment the last technique's sky should have covered the first's sphere");
        assertTrue(count(nearLast, NEAR_ALBEDO) > 0, "the last technique drew nothing");
    }

    private static byte[] capture(boolean depth, SdfRaymarchTechnique... techniques) {
        Target.Builder target = Target.offscreen(SIZE, SIZE).color(AttachmentFormat.RGBA8_UNORM);
        if (depth) {
            target.depth(AttachmentFormat.DEPTH32F);
        }
        RenderPipeline.Builder pipeline = RenderPipeline.builder().target(target.build());
        for (SdfRaymarchTechnique technique : techniques) {
            pipeline.technique(technique);
        }
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("MarchDepthTest"))) {
            engine.run(pipeline.build(), frame -> false);
            return engine.lastFrameRgba();
        }
    }

    /** Pixels exactly equal to {@code colour} — exact because the scenes are unlit, so albedo is the output. */
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
