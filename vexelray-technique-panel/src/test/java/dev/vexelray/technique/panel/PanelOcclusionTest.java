package dev.vexelray.technique.panel;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
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

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>A panel and a marched surface occlude each other, per pixel</b> — the claim that compositing into one
 * shared depth buffer buys something ordering cannot.
 *
 * <h2>The arrangement, and why it is a measurement</h2>
 *
 * <p>A sphere, and a panel pitched so that it <em>cuts through</em> it: the near half of the sheet is in front
 * of the sphere's surface and the far half is behind it. Both therefore survive in the same frame, each having
 * lost pixels to the other.
 *
 * <p>That is what makes this a measurement rather than a picture. Submission order can produce "all panel" or
 * "all sphere" in the region they share; it cannot produce <em>both losing some of it</em>, which is the
 * assertion below. The control at the end shows the difference directly: with no depth attachment, the same two
 * techniques in the same order give the overlap entirely to whichever drew last.
 *
 * <h2>Why the panel is opaque and unlit</h2>
 *
 * <p>Unlit shading means a marched pixel is exactly its albedo and a panel pixel is exactly its fill, so pixels
 * can be classified by equality rather than by a tolerance on a gradient. The anti-aliased edges of the panel's
 * own rectangle are blends and are counted as neither, which is why the totals below are bounded rather than
 * exact.
 */
class PanelOcclusionTest {

    private static final int SIZE = 160;

    private static final double FOCAL = 1.4;
    private static final double NEAR = 0.05;

    private static final Surface.Rgb SPHERE_ALBEDO = new Surface.Rgb(0, 0, 1);
    private static final Surface.Rgb SKY = new Surface.Rgb(0, 0, 0);
    private static final Color PANEL_FILL = new Color(0, 1, 0, 1);

    /** Radius 1.2 at z = 6, so it spans view depths 4.8 through 7.2. */
    private static final Surface SPHERE = new Surface.Sphere(0, 0, 6, 1.2);

    private static final SdfScene SCENE =
            new SdfScene(SPHERE, Shadings.unlit(), MarchSettings.DEFAULT, SPHERE_ALBEDO, SKY, FOCAL, NEAR);

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

    /**
     * A 4-metre sheet centred inside the sphere and pitched about 57 degrees, so it enters at view depth 4.3 and
     * leaves at 7.7 — through the sphere from front to back.
     *
     * <p>{@link PanelTechnique#occluding()}, because this panel is opaque and the test asks it to hold its
     * ground against a technique recorded <em>after</em> it. A panel that only tests depth is occluded by
     * marched geometry in front of it but does not stop anything drawn later — which is the right default for
     * translucent chrome and the wrong one for a solid sheet, and is measured on its own below.
     */
    private static PanelTechnique panel() {
        Canvas canvas = new Canvas(256, 256);
        PanelTechnique technique = new PanelTechnique(canvas,
                new Panel(0, 0, 6, 0, 1.0, 0, 4.0 / 256), FOCAL, SCENE.clipDepth());
        technique.occluding();
        technique.camera(0, 0, 0, 0, 0);
        technique.draw(c -> c.fillRect(0, 0, 256, 256, PANEL_FILL));
        return technique;
    }

    /** The same sheet with the default depth state: tests, does not write. */
    private static PanelTechnique testOnlyPanel() {
        Canvas canvas = new Canvas(256, 256);
        PanelTechnique technique = new PanelTechnique(canvas,
                new Panel(0, 0, 6, 0, 1.0, 0, 4.0 / 256), FOCAL, SCENE.clipDepth());
        technique.camera(0, 0, 0, 0, 0);
        technique.draw(c -> c.fillRect(0, 0, 256, 256, PANEL_FILL));
        return technique;
    }

    @Test
    void eachTakesPixelsFromTheOther() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        int sphereAlone = count(capture(true, sphere()), 0, 0, 255);
        int panelAlone = count(capture(true, panel()), 0, 255, 0);
        assertTrue(sphereAlone > 0, "the sphere did not render at all");
        assertTrue(panelAlone > 0, "the panel did not render at all");

        byte[] both = capture(true, sphere(), panel());
        int sphereBoth = count(both, 0, 0, 255);
        int panelBoth = count(both, 0, 255, 0);

        // Neither is gone, and neither is whole. Ordering can produce either of those; only a per-pixel depth
        // test produces both at once.
        assertTrue(sphereBoth > 0, "the panel hid the entire sphere — that is painting, not occluding");
        assertTrue(panelBoth > 0, "the sphere hid the entire panel — that is painting, not occluding");
        assertTrue(sphereBoth < sphereAlone,
                "the sphere kept all " + sphereAlone + " of its pixels; the panel's near half did not occlude it");
        assertTrue(panelBoth < panelAlone,
                "the panel kept all " + panelAlone + " of its pixels; the sphere did not occlude its far half");
    }

    /**
     * The same frame with the pipeline's list reversed, which depth must not notice.
     *
     * <p>The assertion that separates occlusion from painting outright: if the picture above came from drawing
     * order, reversing it changes the picture. If depth is deciding, the two frames are the same bytes.
     */
    @Test
    void orderDoesNotChangeWhatOccludesWhat() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        byte[] marchFirst = capture(true, sphere(), panel());
        byte[] panelFirst = capture(true, panel(), sphere());

        for (int i = 0; i < marchFirst.length; i++) {
            int at = i;
            assertEquals(marchFirst[i], panelFirst[i],
                    () -> "byte " + at + " differs — the frame depends on the order, so it is being painted "
                            + "rather than occluded");
        }
    }

    /**
     * The default state, and what it costs: a panel that tests depth without writing it is occluded by what is
     * already in the frame, and occludes nothing recorded after it.
     *
     * <p>Worth a test of its own because it is the difference between the two configurations and the reason
     * {@link PanelTechnique#occluding()} exists. Drawn first, the panel puts its near half in front of the
     * sphere and then declines to say so; the march that follows passes the depth test everywhere its sphere
     * is nearer than the <em>cleared</em> buffer, which is everywhere its sphere is — so it takes back every
     * pixel the two share. Reverse the order and the same panel is occluded correctly, because by then the
     * depth it needed had been written by somebody.
     */
    @Test
    void aPanelThatDoesNotWriteDepthDoesNotOccludeWhatFollowsIt() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        int sphereAlone = count(capture(true, sphere()), 0, 0, 255);

        int sphereAfter = count(capture(true, testOnlyPanel(), sphere()), 0, 0, 255);
        assertEquals(sphereAlone, sphereAfter,
                "a panel that does not write depth still took pixels from a technique recorded after it");

        int sphereBefore = count(capture(true, sphere(), testOnlyPanel()), 0, 0, 255);
        assertTrue(sphereBefore < sphereAlone,
                "the same panel recorded after the march failed to occlude it — testing depth did nothing");
    }

    /**
     * <b>A translucent panel composites with what is behind it</b> — the alpha channel, against a marched frame
     * rather than a cleared one.
     *
     * <p>A half-transparent white sheet, upright and three units in front of the sphere, so every pixel of the
     * sphere it covers must come out at half way between white and the sphere's blue. Asserted as a colour
     * rather than as "something changed", because a panel that was accidentally opaque, or that blended against
     * the clear colour instead of against the frame, both change something.
     */
    @Test
    void aTranslucentPanelBlendsWithWhatIsBehindIt() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        Canvas canvas = new Canvas(256, 256);
        PanelTechnique glass = new PanelTechnique(canvas,
                Panel.at(0, 0, 3, 4.0 / 256), FOCAL, SCENE.clipDepth());
        glass.camera(0, 0, 0, 0, 0);
        glass.draw(c -> c.fillRect(0, 0, 256, 256, new Color(1, 1, 1, 0.5f)));

        byte[] frame = capture(true, sphere(), glass);

        // Half white over the sphere's blue, and half white over the black background: both must appear, or the
        // panel is compositing against something other than the frame it was drawn into.
        assertTrue(near(frame, 128, 128, 255) > 200,
                "no pixel came out half way between the panel's white and the sphere's blue");
        assertTrue(near(frame, 128, 128, 128) > 200,
                "no pixel came out half way between the panel's white and the background");
        assertEquals(0, count(frame, 0, 0, 255),
                "some of the sphere survived at full strength behind a sheet covering the whole frame");
    }

    /**
     * The control: the same two techniques against a target with no depth attachment.
     *
     * <p>Now order is the whole composition, and the panel drawn last keeps every pixel it covers — including
     * the ones where the sphere is three metres in front of it. Without this, the test above could be passing
     * for some reason that has nothing to do with the depth buffer.
     */
    @Test
    void withoutDepthTheLastTechniquePaintsOverTheFirst() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        int panelAlone = count(capture(false, panel()), 0, 255, 0);
        int panelLast = count(capture(false, sphere(), panel()), 0, 255, 0);

        assertEquals(panelAlone, panelLast,
                "with no depth attachment the panel drawn last should have kept every pixel it covers");
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
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("PanelOcclusionTest"))) {
            engine.run(pipeline.build(), frame -> false);
            return engine.lastFrameRgba();
        }
    }

    /** Pixels within a couple of levels of this colour — a blend lands on a rounding, not on an integer. */
    private static int near(byte[] rgba, int r, int g, int b) {
        int found = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            if (Math.abs((rgba[i] & 0xFF) - r) <= 2
                    && Math.abs((rgba[i + 1] & 0xFF) - g) <= 2
                    && Math.abs((rgba[i + 2] & 0xFF) - b) <= 2) {
                found++;
            }
        }
        return found;
    }

    /** Pixels exactly this colour — exact, because both the march and the fill are flat. */
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
