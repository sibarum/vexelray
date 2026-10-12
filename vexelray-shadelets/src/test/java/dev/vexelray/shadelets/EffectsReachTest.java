package dev.vexelray.shadelets;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.LookTable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vexelray.shadelets.EffectsRenderTest.H;
import static dev.vexelray.shadelets.EffectsRenderTest.RED;
import static dev.vexelray.shadelets.EffectsRenderTest.W;
import static dev.vexelray.shadelets.EffectsRenderTest.WITH_EFFECTS;
import static dev.vexelray.shadelets.EffectsRenderTest.canvas;
import static dev.vexelray.shadelets.EffectsRenderTest.instance;
import static dev.vexelray.shadelets.EffectsRenderTest.table;
import static dev.vexelray.shadelets.OffscreenCanvas.channel;
import static dev.vexelray.shadelets.OffscreenCanvas.crossing;
import static dev.vexelray.shadelets.OffscreenCanvas.maxDifference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * What a look's quad must hold: everything its shadelets draw, a modifier's reach and a painter's added, and a
 * morph's as far as its second shape.
 */
class EffectsReachTest {

    static OffscreenCanvas gpu;

    @BeforeAll
    static void open() {
        assumeTrue(OffscreenCanvas.available(), "no Vulkan graphics device: the effects' GPU tests need one");
        gpu = new OffscreenCanvas(WITH_EFFECTS, W, H);
    }

    @AfterAll
    static void close() {
        if (gpu != null) {
            gpu.close();
        }
    }

    /**
     * A weight moves the edge out, and an outline is drawn about the moved edge, so the two reaches add. LookTable
     * takes the largest instead, so the band's outer part is cut off at the quad.
     */
    @Test
    void aWeightAndAnOutlineReachTheirSum() {
        LookTable table = table();
        Look look = new Look(Look.Use.of("box"), List.of(WeightStyleLayout.use("weight", new WeightStyle(3f))),
                List.of(OutlineStyleLayout.use("outline", new OutlineStyle(Color.rgb(1f, 0f, 0f), 8f)),
                        Look.Use.of("fill")));
        Canvas c = canvas(table).box(40, 30, 80, 36, 0, Color.rgb(0f, 0f, 1f), look);   // left edge 40, moved to 37
        byte[] rgba = gpu.draw(table, c);
        // The quad reaches 2 + reach out of the box. 6.5 px out of the drawn box is 3.5 out of the moved edge: on
        // the band's soft outer pixel, 1 - smoothstep(-1, 1, -0.5) of red.
        int red = channel(rgba, W, 33, 48, RED);
        System.out.printf("[reach] weight 3 + outline 8: table reach %.1f, red 6.5 px out of the box %d%n",
                table.reach(look), red);
        assertEquals(Math.round(0.84375 * 255), red, 2, "the band past the moved edge is drawn, not cut by the quad");
        assertEquals(7f, table.reach(look), "3 of weight, then 4 of outline past the moved edge");
    }

    /**
     * A box morphing into a larger box draws past its own box, as far as the second box is larger. BoxMorph has no
     * reach (its fields are all per instance, and reach is a function of shared fields), so the quad is its own box
     * plus the pad, and the larger box is cut off square.
     */
    @Test
    void aBoxMorphingLargerIsNotCutOff() {
        LookTable table = table();
        Look look = Look.of(Look.Use.of("boxMorph"), Look.Use.of("fill"));
        // Own box 60 x 28 about (80, 48); the second 100 x 56 about the same centre.
        byte[] at1 = gpu.draw(table, canvas(table).box(50, 34, 60, 28, 6, Color.WHITE, look,
                instance(new BoxMorphStyle(50, 28, 8, 1f))));
        byte[] larger = gpu.draw(table, canvas(table).fillRoundRect(30, 20, 100, 56, 8, Color.WHITE));
        double left = crossing(at1, W, 48, RED, 80, 0, 255);
        System.out.printf("[reach] morph to a larger box at t=1: left edge %.3f (expected 30), max diff %d%n",
                left, maxDifference(at1, larger, W, 0, 0, W, H));
        assertEquals(30.0, left, 0.5, "the larger box's edge, not the quad's");
        assertEquals(0, maxDifference(at1, larger, W, 0, 0, W, H));
    }
}
