package dev.vexelray.shadelets;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.LookTable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vexelray.shadelets.OffscreenCanvas.channel;
import static dev.vexelray.shadelets.OffscreenCanvas.countDifferent;
import static dev.vexelray.shadelets.OffscreenCanvas.crossing;
import static dev.vexelray.shadelets.OffscreenCanvas.litPixels;
import static dev.vexelray.shadelets.OffscreenCanvas.maxDifference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The effects drawn on the GPU, each against a reference the canvas draws the ordinary way: a weight moves the edge
 * by its amount, an outline is a band of its colour as wide as it says and is not cut off by the quad, a morph is its
 * first box at 0 and its second at 1, and a field morph its first cell at 0 and its second at 1. Skipped without a
 * Vulkan graphics device.
 */
class EffectsRenderTest {

    static final Composition WITH_EFFECTS = CoreShadelets.composition().with(Effects.ALL);
    static final int W = 160;
    static final int H = 96;
    static final int RED = 0;
    static final int BLUE = 2;

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

    static LookTable table() {
        return CoreLooks.table(WITH_EFFECTS);
    }

    static Canvas canvas(LookTable table) {
        return new Canvas(W, H).looks(table).begin();
    }

    // --- weight ---

    @Test
    void aWeightMovesEachEdgeOutByItsAmount() {
        float weight = 3.5f;
        LookTable table = table();
        Look weighted = new Look(Look.Use.of("box"), List.of(WeightStyleLayout.use("weight", new WeightStyle(weight))),
                List.of(Look.Use.of("fill")));
        Canvas c = canvas(table);
        c.fillRoundRect(40, 10, 80, 30, 4, Color.WHITE);                 // rows 10..40, centre row 25
        c.box(40, 50, 80, 30, 4, Color.WHITE, weighted);                 // rows 50..80, centre row 65
        byte[] rgba = gpu.draw(table, c);

        double plainLeft = crossing(rgba, W, 25, RED, 80, 0, 255);
        double plainRight = crossing(rgba, W, 25, RED, 80, W - 1, 255);
        double left = crossing(rgba, W, 65, RED, 80, 0, 255);
        double right = crossing(rgba, W, 65, RED, 80, W - 1, 255);
        System.out.printf("[weight] plain edges %.3f, %.3f; weighted (w=%.1f) %.3f, %.3f%n",
                plainLeft, plainRight, weight, left, right);

        assertEquals(40.0, plainLeft, 0.5, "the plain box's left edge is where it was drawn");
        assertEquals(120.0, plainRight, 0.5, "and its right");
        assertEquals(weight, plainLeft - left, 0.5, "the weighted box reaches w further left");
        assertEquals(weight, right - plainRight, 0.5, "and w further right");
    }

    // --- outline ---

    @Test
    void anOutlineIsABandOfItsColourAboutTheEdgeAndIsNotCutOff() {
        float width = 8f;   // half of it, 4, is past the quad's own 2 px pad
        LookTable table = table();
        Look outlined = new Look(Look.Use.of("box"), List.of(),
                List.of(OutlineStyleLayout.use("outline", new OutlineStyle(Color.rgb(1f, 0f, 0f), width)),
                        Look.Use.of("fill")));
        assertEquals(width / 2, table.reach(outlined), "the outline reaches half its width past the edge");
        Canvas c = canvas(table);
        c.box(40, 30, 80, 36, 0, Color.rgb(0f, 0f, 1f), outlined);   // left edge at x = 40, centre row 48
        byte[] rgba = gpu.draw(table, c);
        int row = 48;

        // Pixel x's centre is x + 0.5, so pixel 40 is half a pixel inside the edge.
        int[] edge = rgb(rgba, 40, row);
        int[] inside = rgb(rgba, 60, row);
        int[] outside = rgb(rgba, 34, row);            // 5.5 px out: past 4 + one pixel of soft edge
        int[] pastPad = rgb(rgba, 37, row);            // 2.5 px out: inside the band, outside a 2 px pad
        int[] softEdge = rgb(rgba, 36, row);           // 3.5 px out: on the band's own soft edge
        System.out.printf("[outline] edge %s inside %s 2.5 out %s 3.5 out %s 5.5 out %s%n",
                str(edge), str(inside), str(pastPad), str(softEdge), str(outside));

        assertPixel(new int[]{255, 0, 0}, edge, 1, "at the edge, the outline's colour");
        assertPixel(new int[]{0, 0, 255}, inside, 1, "well inside, the fill's");
        assertPixel(new int[]{0, 0, 0}, outside, 1, "past half the width and its soft pixel, the background");
        assertPixel(new int[]{255, 0, 0}, pastPad, 1,
                "2.5 px out is in the band: the quad grew by the outline's reach, or this would be background");
        // 1 - smoothstep(-1, 1, -0.5) = 0.84375 of red, over black.
        assertEquals(Math.round(0.84375 * 255), softEdge[0], 2, "the band's outer edge is one pixel soft");
    }

    // --- box morph ---

    static final Look BOX_MORPH = Look.of(Look.Use.of("boxMorph"), Look.Use.of("fill"));

    @Test
    void aBoxMorphIsItsFirstBoxAtZeroAndItsSecondAtOne() {
        LookTable table = table();
        // The element's own box: 100 x 56 about (80, 48), radius 8. The second: 60 x 28 about the same centre, radius
        // 6. Smaller, so it lies inside the element's quad.
        Canvas morph0 = canvas(table).box(30, 20, 100, 56, 8, Color.WHITE, BOX_MORPH,
                instance(new BoxMorphStyle(30, 14, 6, 0f)));
        byte[] at0 = gpu.draw(table, morph0);
        byte[] first = gpu.draw(table, canvas(table).fillRoundRect(30, 20, 100, 56, 8, Color.WHITE));
        int diff0 = maxDifference(at0, first, W, 0, 0, W, H);
        System.out.printf("[boxMorph] t=0 vs plain first box: max channel difference %d, %d pixels differ%n",
                diff0, countDifferent(at0, first, W, H, 0));
        assertTrue(litPixels(first, W, H) > 4000, "the reference drew something");
        assertEquals(0, diff0, "at t = 0 the morph is its own box, pixel for pixel");

        byte[] at1 = gpu.draw(table, canvas(table).box(30, 20, 100, 56, 8, Color.WHITE, BOX_MORPH, instance(
                new BoxMorphStyle(30, 14, 6, 1f))));
        byte[] second = gpu.draw(table, canvas(table).fillRoundRect(50, 34, 60, 28, 6, Color.WHITE));
        int diff1 = maxDifference(at1, second, W, 0, 0, W, H);
        System.out.printf("[boxMorph] t=1 vs plain second box: max channel difference %d, %d pixels differ%n",
                diff1, countDifferent(at1, second, W, H, 0));
        assertTrue(diff1 <= 1, "at t = 1 the morph is the second box, drawn the ordinary way: " + diff1);

        byte[] half = gpu.draw(table, canvas(table).box(30, 20, 100, 56, 8, Color.WHITE, BOX_MORPH, instance(
                new BoxMorphStyle(30, 14, 6, 0.5f))));
        double left = crossing(half, W, 48, RED, 80, 0, 255);
        double right = crossing(half, W, 48, RED, 80, W - 1, 255);
        System.out.printf("[boxMorph] t=0.5 edges %.3f, %.3f (first box 30, 130; second 50, 110)%n", left, right);
        assertTrue(left > 30 && left < 50, "half way, the left edge is between the two boxes': " + left);
        assertTrue(right > 110 && right < 130, "and the right: " + right);
        assertEquals(40.0, left, 0.5, "two true distances mixed half and half: the edge half way");
        assertEquals(120.0, right, 0.5);
    }

    @Test
    void twoElementsOfOneLookCarryTheirOwnValuesInOneFrame() {
        LookTable table = table();
        Canvas c = canvas(table);
        c.box(10, 20, 60, 56, 8, Color.WHITE, BOX_MORPH, instance(new BoxMorphStyle(18, 14, 4, 0f)));
        c.box(90, 20, 60, 56, 8, Color.WHITE, BOX_MORPH, instance(new BoxMorphStyle(18, 14, 4, 1f)));
        assertEquals(10, c.recordCount(), "two records, each its look and four values");
        assertEquals(c.records()[0], c.records()[5], "both name the one look");
        byte[] both = gpu.draw(table, c);

        Canvas reference = canvas(table);
        reference.fillRoundRect(10, 20, 60, 56, 8, Color.WHITE);        // the left at t = 0: its own box
        reference.fillRoundRect(102, 34, 36, 28, 4, Color.WHITE);       // the right at t = 1: the second, about (120, 48)
        byte[] expected = gpu.draw(table, reference);
        int diff = maxDifference(both, expected, W, 0, 0, W, H);
        System.out.printf("[two morphs] max channel difference %d, %d pixels differ%n", diff,
                countDifferent(both, expected, W, H, 0));
        assertTrue(diff <= 1, "each element drew with its own t: " + diff);
        assertTrue(litPixels(both, W / 2, H) < litPixels(expected, W, H), "sanity: the right box is the smaller");
    }

    // --- field morph ---

    static final int INNER = 32;
    static final int SPREAD = 8;
    static final int SIDE = INNER + 2 * SPREAD;   // 48 texels a cell

    @Test
    void aFieldMorphIsItsFirstCellAtZeroAndItsSecondAtOne() {
        // A 2 x 2 atlas of 48-texel cells (square, so the field's one V-per-unit holds on both axes): a disc in the
        // top left, a square in the top right, the bottom row empty.
        OffscreenCanvas.Handle handle = new OffscreenCanvas.Handle();
        OffscreenCanvas.Texture atlas = new OffscreenCanvas.Texture(handle, 2 * SIDE, 2 * SIDE, twoCells());
        LookTable table = table();
        Look fieldMorph = Look.of(Look.Use.of("fieldMorph"), Look.Use.of("fieldFill"));
        Color tint = Color.WHITE;
        float x = 48;
        float y = 16;
        float size = 64;   // twice the field's own size
        float margin = (float) SPREAD / INNER;

        byte[] cellA = gpu.draw(table, canvas(table).field(x, y, size, size, handle, 0f, 0f, 0.5f, 0.5f, SIDE,
                SPREAD, margin, tint), atlas);
        byte[] cellB = gpu.draw(table, canvas(table).field(x, y, size, size, handle, 0.5f, 0f, 1f, 0.5f, SIDE,
                SPREAD, margin, tint), atlas);
        byte[] at0 = gpu.draw(table, canvas(table).field(x, y, size, size, handle, 0f, 0f, 0.5f, 0.5f, SIDE,
                SPREAD, margin, tint, fieldMorph, instance(new FieldMorphStyle(0.5f, 0f, 0f))), atlas);
        byte[] at1 = gpu.draw(table, canvas(table).field(x, y, size, size, handle, 0f, 0f, 0.5f, 0.5f, SIDE,
                SPREAD, margin, tint, fieldMorph, instance(new FieldMorphStyle(0.5f, 0f, 1f))), atlas);
        byte[] half = gpu.draw(table, canvas(table).field(x, y, size, size, handle, 0f, 0f, 0.5f, 0.5f, SIDE,
                SPREAD, margin, tint, fieldMorph, instance(new FieldMorphStyle(0.5f, 0f, 0.5f))), atlas);

        int litA = litPixels(cellA, W, H);
        int litB = litPixels(cellB, W, H);
        int diff0 = maxDifference(at0, cellA, W, 0, 0, W, H);
        int diff1 = maxDifference(at1, cellB, W, 0, 0, W, H);
        System.out.printf("[fieldMorph] lit: disc %d, square %d, half way %d; t=0 vs disc max %d (%d px), "
                        + "t=1 vs square max %d (%d px)%n", litA, litB, litPixels(half, W, H), diff0,
                countDifferent(at0, cellA, W, H, 0), diff1, countDifferent(at1, cellB, W, H, 0));

        assertTrue(litA > 1000 && litB > litA, "the references drew a disc and a larger square");
        assertEquals(0, diff0, "at t = 0 the morph is its own cell: mix(a, b, 0) is a");
        assertTrue(diff1 <= 2, "at t = 1 it is the second cell, read from the first's UV moved by (du, dv): "
                + diff1);
        int litHalf = litPixels(half, W, H);
        assertTrue(litHalf > litA && litHalf < litB, "half way, between the two in area: " + litHalf);
    }

    /** The two cells' fields, encoded as {@link dev.vexelray.canvas.DistanceField} does: {@code 0.5 + d / 2spread}. */
    static byte[] twoCells() {
        int side = 2 * SIDE;
        byte[] rgba = new byte[side * side * 4];
        double centre = SIDE / 2.0;
        for (int ty = 0; ty < side; ty++) {
            for (int tx = 0; tx < side; tx++) {
                double d;   // texels, positive inside
                double px = (tx % SIDE) + 0.5 - centre;
                double py = (ty % SIDE) + 0.5 - centre;
                if (ty >= SIDE) {
                    d = -SPREAD;   // empty cells: far outside
                } else if (tx < SIDE) {
                    d = 12.0 - Math.hypot(px, py);                                    // a disc, radius 12
                } else {
                    double qx = Math.abs(px) - 13.0;
                    double qy = Math.abs(py) - 13.0;                                  // a square, 26 across
                    double out = Math.hypot(Math.max(qx, 0), Math.max(qy, 0));
                    d = -(out + Math.min(Math.max(qx, qy), 0));
                }
                int v = (int) Math.round(Math.max(0, Math.min(1, 0.5 + d / (2.0 * SPREAD))) * 255);
                int o = (ty * side + tx) * 4;
                rgba[o] = (byte) v;
                rgba[o + 1] = (byte) v;
                rgba[o + 2] = (byte) v;
                rgba[o + 3] = (byte) 255;
            }
        }
        return rgba;
    }

    // --- helpers ---

    static float[] instance(BoxMorphStyle s) {
        float[] out = new float[BoxMorphStyleLayout.LAYOUT.instanceFloats()];
        BoxMorphStyleLayout.instance(s, out, 0);
        return out;
    }

    static float[] instance(FieldMorphStyle s) {
        float[] out = new float[FieldMorphStyleLayout.LAYOUT.instanceFloats()];
        FieldMorphStyleLayout.instance(s, out, 0);
        return out;
    }

    static int[] rgb(byte[] rgba, int x, int y) {
        return new int[]{channel(rgba, W, x, y, 0), channel(rgba, W, x, y, 1), channel(rgba, W, x, y, 2)};
    }

    static String str(int[] p) {
        return "(" + p[0] + "," + p[1] + "," + p[2] + ")";
    }

    static void assertPixel(int[] expected, int[] actual, int tolerance, String what) {
        for (int c = 0; c < 3; c++) {
            int k = c;
            assertEquals(expected[c], actual[c], tolerance, () -> what + ": expected " + str(expected) + ", got "
                    + str(actual) + " (channel " + k + ")");
        }
    }
}
