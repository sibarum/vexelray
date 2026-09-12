package dev.vexelray.technique.panel;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>A drawn line has no fixed pixel width, and no fixed pixel softness either</b> — the property this module
 * exists for, measured rather than looked at.
 *
 * <h2>What is being measured</h2>
 *
 * <p>One line, 32 canvas units thick, on a panel whose scale makes that 0.32 world units. The same panel is
 * rendered at two distances, and the line's height is counted in a single column of pixels.
 *
 * <p>Two numbers come out of that, and they are different claims:
 *
 * <ul>
 *   <li><b>The width doubles when the distance halves.</b> A pinhole camera projects a length {@code L} at depth
 *       {@code z} to {@code halfHeightPx * focal * L / z} pixels, so the near line must be twice the far one and
 *       both must match that formula. This is the claim that a canvas unit is a measurement on a surface rather
 *       than a number of pixels — and it is asserted against the predicted absolute values, not only against
 *       each other, because two wrong widths can still have the right ratio.</li>
 *   <li><b>The edge stays one pixel soft.</b> The count of partially covered pixels — neither background nor
 *       full line colour — is the width of the anti-aliasing ramp in pixels. It must stay at about two (one per
 *       edge) at <em>both</em> distances. This is the claim the closed-form Jacobian in {@code PanelShader} is
 *       there to make good on, and the one a textured quad could not: magnifying an image stretches its blurred
 *       edge along with everything else, so the near edge would be softer in exactly the proportion the line is
 *       wider.</li>
 * </ul>
 *
 * <h2>Why a column rather than an area</h2>
 *
 * <p>Area would confound the two axes — a line drawn at half scale is shorter as well as thinner, so its pixel
 * count falls with the square and a bug in either axis hides in the same number. One column measures the
 * thickness alone, which is the quantity the claim is about.
 */
class PanelScaleTest {

    private static final int SIZE = 256;
    private static final double FOCAL = 1.4;

    private static final int CANVAS = 256;

    /** 0.01 world units per canvas unit: a 2.56-unit sheet, comfortably inside the frame at both distances. */
    private static final double UNITS_PER_PIXEL = 0.01;

    private static final float THICKNESS = 32;

    private static final double NEAR_Z = 4;
    private static final double FAR_Z = 8;

    /** {@code halfHeightPx * focal * worldThickness / z}, the pinhole's own answer. */
    private static double predictedWidth(double z) {
        return (SIZE * 0.5) * FOCAL * (THICKNESS * UNITS_PER_PIXEL) / z;
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
    void theLineIsTwiceAsWideFromHalfTheDistance() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        byte[] near = capture(NEAR_Z);
        byte[] far = capture(FAR_Z);

        double nearWidth = litRows(near);
        double farWidth = litRows(far);

        assertTrue(nearWidth > 0, "nothing was drawn at " + NEAR_Z + " units");
        assertTrue(farWidth > 0, "nothing was drawn at " + FAR_Z + " units");

        // The claim, in its weakest form first: the width is not a constant of the drawing.
        assertTrue(nearWidth > farWidth + 2,
                "the line is " + nearWidth + " pixels near and " + farWidth + " far — it has a fixed pixel width");

        // And in its strongest: both widths are the ones the projection predicts, to within a pixel of
        // anti-aliasing at each edge.
        assertEquals(predictedWidth(NEAR_Z), nearWidth, 2.0,
                "the near line is not the width the pinhole predicts");
        assertEquals(predictedWidth(FAR_Z), farWidth, 2.0,
                "the far line is not the width the pinhole predicts");
    }

    /**
     * The edge is one pixel soft at both distances — the property a sampled texture on a quad cannot have.
     *
     * <p>Counted as pixels that are neither background nor saturated line colour. Two per column is the ideal
     * (one ramp at each edge); the bound is loose enough for a ramp that straddles a pixel boundary and tight
     * enough to fail the moment softness starts scaling with the line.
     */
    @Test
    void theEdgeStaysOnePixelSoftHoweverCloseItIs() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        int nearPartial = partialRows(capture(NEAR_Z));
        int farPartial = partialRows(capture(FAR_Z));

        assertTrue(nearPartial <= 4,
                "the near line has " + nearPartial + " partially covered pixels down its column; its edge is "
                        + "blurring as it is approached");
        assertTrue(farPartial <= 4,
                "the far line has " + farPartial + " partially covered pixels down its column");
    }

    /**
     * One frame: a white line across the middle of a panel {@code z} units away, over an empty canvas.
     *
     * <p>No depth attachment — this is a measurement of size and softness, and the occlusion test is elsewhere.
     */
    private static byte[] capture(double z) {
        Canvas canvas = new Canvas(CANVAS, CANVAS);
        PanelTechnique panel = new PanelTechnique(canvas,
                Panel.at(0, 0, z, UNITS_PER_PIXEL), FOCAL, ClipDepth.DEFAULT);
        panel.camera(0, 0, 0, 0, 0);
        panel.draw(c -> c.strokeLine(0, CANVAS / 2f, CANVAS, CANVAS / 2f, THICKNESS, Color.WHITE));

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.offscreen(SIZE, SIZE).color(AttachmentFormat.RGBA8_UNORM).build())
                .technique(panel)
                .build();
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("PanelScaleTest"))) {
            engine.run(pipeline, frame -> false);
            return engine.lastFrameRgba();
        }
    }

    /**
     * The line's thickness in the centre column, counting a partially covered pixel as the fraction it is
     * covered — which is what makes the measurement continuous rather than quantised to whole pixels, and so
     * comparable against a prediction that is not a whole number.
     */
    private static double litRows(byte[] rgba) {
        double total = 0;
        for (int y = 0; y < SIZE; y++) {
            total += (rgba[(y * SIZE + SIZE / 2) * 4] & 0xFF) / 255.0;
        }
        return total;
    }

    /** Pixels in the centre column that are neither background nor saturated: the anti-aliasing ramp. */
    private static int partialRows(byte[] rgba) {
        int partial = 0;
        for (int y = 0; y < SIZE; y++) {
            int v = rgba[(y * SIZE + SIZE / 2) * 4] & 0xFF;
            if (v > 2 && v < 253) {
                partial++;
            }
        }
        return partial;
    }
}
