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

import static dev.vexelray.shadelets.OffscreenCanvas.channel;
import static dev.vexelray.shadelets.OffscreenCanvas.countDifferent;
import static dev.vexelray.shadelets.OffscreenCanvas.crossing;
import static dev.vexelray.shadelets.OffscreenCanvas.maxDifference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The core set's soft fill on the GPU: with no softness it is the plain fill, pixel for pixel; a soft edge is spread
 * evenly over its width about where the edge is, reaching half of it past the box, which the quad grows to hold; and
 * two edges soft by different amounts are each their own. Skipped without a Vulkan graphics device.
 */
class SoftFillRenderTest {

    static final Composition CORE = CoreShadelets.composition();
    static final int W = 160;
    static final int H = 64;
    static final int RED = 0;
    static final Look SOFT = Look.of(Look.Use.of(CoreShadelets.BOX.name()),
            Look.Use.of(CoreShadelets.SOFT_FILL.name()));

    static OffscreenCanvas gpu;

    @BeforeAll
    static void open() {
        assumeTrue(OffscreenCanvas.available(), "no Vulkan graphics device: the soft fill's GPU tests need one");
        gpu = new OffscreenCanvas(CORE, W, H);
    }

    @AfterAll
    static void close() {
        if (gpu != null) {
            gpu.close();
        }
    }

    static LookTable table() {
        return CoreLooks.table(CORE);
    }

    static Canvas canvas(LookTable table) {
        return new Canvas(W, H).looks(table).begin();
    }

    @Test
    void withNoSoftnessItIsThePlainFill() {
        LookTable table = table();
        // A pill, as the tab indicator is, and a rounded box: both shapes the radius matters to.
        byte[] soft = gpu.draw(table, canvas(table)
                .box(20, 10, 80, 4, 2, 2, Color.WHITE, SOFT, 0f, 0f, 0f, 0f)
                .box(30, 24, 90, 30, 6, 6, Color.WHITE, SOFT, 0f, 0f, 0f, 0f));
        byte[] plain = gpu.draw(table, canvas(table)
                .fillRoundRect(20, 10, 80, 4, 2, 2, Color.WHITE)
                .fillRoundRect(30, 24, 90, 30, 6, 6, Color.WHITE));
        int diff = maxDifference(soft, plain, W, 0, 0, W, H);
        System.out.printf("[softFill] no softness vs plain: max %d, %d pixels differ%n", diff,
                countDifferent(soft, plain, W, H, 0));
        assertTrue(diff <= 1, "a soft fill with nothing soft draws the plain fill's pixels: " + diff);
    }

    @Test
    void aSoftEdgeIsSpreadEvenlyAboutWhereTheEdgeIs() {
        LookTable table = table();
        float s = 20f;
        assertEquals(s / 2, table.reach(SOFT, new float[]{s, 0f, 0f, 0f}, 40f, 15f), 1e-6f,
                "a soft edge reaches half its width past the box");
        // Square corners, left edge at x = 40, soft over 30..50; the right edge, at 120, as hard as a fill's.
        byte[] rgba = gpu.draw(table, canvas(table).box(40, 17, 80, 30, 0, 0, Color.WHITE, SOFT, s, 0f, 0f, 0f));
        int row = 32;
        double left = crossing(rgba, W, row, RED, 80, 0, 255);
        double right = crossing(rgba, W, row, RED, 80, W - 1, 255);
        int outer = channel(rgba, W, 31, row, RED);   // 1.5 px into the spread from its outer end
        int quarter = channel(rgba, W, 35, row, RED); // a quarter of the way across
        int inner = channel(rgba, W, 48, row, RED);   // 1.5 px short of its inner end
        System.out.printf("[softFill] left half-crossing %.3f, right %.3f; across the spread %d, %d, %d%n",
                left, right, outer, quarter, inner);

        assertEquals(40.0, left, 0.5, "half covered where the edge is: the spread is centred on it");
        assertEquals(120.0, right, 0.5, "the other edge is where it was, and hard");
        assertTrue(outer > 0 && outer < 40, "the spread starts half its width out, past the old 2 px pad: " + outer);
        assertTrue(quarter > 30 && quarter < 100, "and rises across it: " + quarter);
        assertTrue(inner > 215 && inner < 255, "to full half its width in: " + inner);
        assertEquals(0, channel(rgba, W, 28, row, RED), "nothing past the spread");
        assertEquals(255, channel(rgba, W, 60, row, RED), "and the box full inside it");
    }

    @Test
    void eachEdgeIsAsSoftAsItsOwnValue() {
        LookTable table = table();
        // A pill moving right as the indicator does: the leading (right) edge soft over 24, the trailing over 8.
        byte[] rgba = gpu.draw(table, canvas(table).box(40, 30, 80, 4, 2, 2, Color.WHITE, SOFT, 8f, 0f, 24f, 0f));
        int row = 32;
        double left = crossing(rgba, W, row, RED, 80, 0, 255);
        double right = crossing(rgba, W, row, RED, 80, W - 1, 255);
        int leftReach = 0;
        int rightReach = 0;
        for (int x = 0; x < 80; x++) {
            if (channel(rgba, W, x, row, RED) > 0 && leftReach == 0) {
                leftReach = 40 - x;
            }
        }
        for (int x = W - 1; x >= 80; x--) {
            if (channel(rgba, W, x, row, RED) > 0 && rightReach == 0) {
                rightReach = x + 1 - 120;
            }
        }
        System.out.printf("[softFill] pill edges %.3f, %.3f; lit %d px past the left, %d past the right%n",
                left, right, leftReach, rightReach);
        assertEquals(40.0, left, 0.6, "each edge half covered where it is");
        assertEquals(120.0, right, 0.6);
        assertTrue(leftReach >= 3 && leftReach <= 4, "the trailing edge reaches half of 8: " + leftReach);
        assertTrue(rightReach >= 11 && rightReach <= 12, "the leading edge half of 24: " + rightReach);
        // The bar's own height is untouched: its top and bottom edges are a fill's.
        assertEquals(255, channel(rgba, W, 80, 31, RED), "full across the bar's height");
        assertEquals(0, channel(rgba, W, 80, 27, RED), "and nothing above it");
    }
}
