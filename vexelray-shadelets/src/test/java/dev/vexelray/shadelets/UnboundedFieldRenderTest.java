package dev.vexelray.shadelets;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.LookTable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vexelray.shadelets.CondenseRenderTest.FIELD;
import static dev.vexelray.shadelets.CondenseRenderTest.SIDE;
import static dev.vexelray.shadelets.CondenseRenderTest.SPREAD;
import static dev.vexelray.shadelets.OffscreenCanvas.channel;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Condense#MTSDF_UNBOUNDED} under {@link Condense#CONTOURS}, on the GPU: the disc pair of
 * {@link CondenseRenderTest} drawn across an element far wider than its cell, lit everywhere. The lines fall every 6
 * design units of true distance from the disc out past the trusted disc and past the cell itself, where every sample
 * is a pulled one. Skipped without a Vulkan graphics device.
 */
class UnboundedFieldRenderTest {

    static final int W = 325;
    static final int H = 96;
    /** Pixels per texel: the shape's 32-texel square is 104 px, so a design unit is a pixel. */
    static final float PX = 3.25f;
    /** The disc's middle on the canvas, and its radius in pixels (12 texels). */
    static final float CX = 80f;
    static final float CY = 48f;
    static final float RADIUS = 12f * PX;

    static OffscreenCanvas gpu;

    @BeforeAll
    static void open() {
        assumeTrue(OffscreenCanvas.available(), "no Vulkan graphics device: the effects' GPU tests need one");
        gpu = new OffscreenCanvas(EffectsRenderTest.WITH_EFFECTS, W, H);
    }

    @AfterAll
    static void close() {
        if (gpu != null) {
            gpu.close();
        }
    }

    @Test
    void theLinesKeepTheirSpacingFarPastTheCell() {
        OffscreenCanvas.Handle handle = new OffscreenCanvas.Handle();
        OffscreenCanvas.Texture pair = new OffscreenCanvas.Texture(handle, SIDE, SIDE, CondenseRenderTest.pair());
        LookTable table = CoreLooks.table(EffectsRenderTest.WITH_EFFECTS);
        byte[] rgba = gpu.draw(table, backdrop(table, handle), pair);

        // The cell reaches 104 px right of the disc's middle, to x = 184; the pull starts at 18 texels, 58.5 px. The
        // lines are at 3 units and every 6 after.
        int onA = channel(rgba, W, line(75.5f), (int) CY, 0);
        int onB = channel(rgba, W, line(111.5f), (int) CY, 0);
        int offA = channel(rgba, W, line(72.5f), (int) CY, 0);
        int offB = channel(rgba, W, line(108.5f), (int) CY, 0);
        System.out.printf("[unbounded] on the lines at 75 and 111 units: %d, %d; between them: %d, %d%n",
                onA, onB, offA, offB);
        assertTrue(onA > 120 && onB > 120, "a line falls every sixth unit, out past the cell: " + onA + ", " + onB);
        assertTrue(offA < 20 && offB < 20, "and nothing between: " + offA + ", " + offB);
    }

    /** The pixel whose centre is {@code distance} design units right of the disc's edge. */
    private static int line(float distance) {
        return Math.round(CX + RADIUS + distance - 0.5f);
    }

    /** The disc's field across the whole canvas, its contours lit everywhere, nothing of the disc's own drawn. */
    private static Canvas backdrop(LookTable table, OffscreenCanvas.Handle handle) {
        Look look = new Look(UnboundedMtsdfStyleLayout.use("mtsdfUnbounded", new UnboundedMtsdfStyle(MtsdfStyle.BAKED,
                0f, 0f, 0f, 0f, 0f, 0f, 0f)), List.of(),
                List.of(ContoursStyleLayout.use("contours", new ContoursStyle(Color.rgb(1f, 0.7f, 0.3f), 1e4f,
                        0f, 0f, 0f, 0f, 0f))));
        float cellU = (float) FIELD / SIDE;
        float cellPx = FIELD * PX;
        float cellX = CX - cellPx / 2f;
        float cellY = CY - cellPx / 2f;
        // An element as wide as the canvas, as many texels across as its pixels say, with the pair's spread round it.
        int side = Math.round(W / PX) + 2 * SPREAD;
        float margin = (float) SPREAD / (side - 2 * SPREAD);
        float qx0 = -W * margin;
        float qx1 = W + W * margin;
        float qy0 = -H * margin;
        float qy1 = H + H * margin;
        float u0 = (qx0 - cellX) / cellPx * cellU;
        float u1 = (qx1 - cellX) / cellPx * cellU;
        float v0 = (qy0 - cellY) / cellPx * cellU;
        float v1 = (qy1 - cellY) / cellPx * cellU;

        UnboundedMtsdfStyle source = new UnboundedMtsdfStyle(MtsdfStyle.BAKED, 0f, 0f, cellU, cellU,
                18f / FIELD, 7.6f / FIELD, (float) FIELD / (2 * SPREAD));
        ContoursStyle contours = new ContoursStyle(Color.rgb(1f, 0.7f, 0.3f), 1e4f, 1f, 0f, 0f, 1e5f, 1f);
        int sourceFloats = UnboundedMtsdfStyleLayout.LAYOUT.instanceFloats();
        float[] instance = new float[sourceFloats + ContoursStyleLayout.LAYOUT.instanceFloats()];
        UnboundedMtsdfStyleLayout.instance(source, instance, 0);
        ContoursStyleLayout.instance(contours, instance, sourceFloats);
        return new Canvas(W, H).looks(table).begin().field(0, 0, W, H, handle, u0, v0, u1, v1, side, SPREAD, margin,
                Color.WHITE, look, instance);
    }
}
