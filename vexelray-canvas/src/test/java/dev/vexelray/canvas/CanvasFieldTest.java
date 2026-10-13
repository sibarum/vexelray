package dev.vexelray.canvas;

import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.target.ImageHandle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A distance field's quad: it reaches over the margin, and carries its scale in screen px. */
class CanvasFieldTest {

    private static final int F = CanvasVertex.FLOATS_PER_VERTEX;
    private static final int SCREEN_X = CanvasVertex.OFF_CLIPRS / Float.BYTES;
    private static final int FILTER = CanvasVertex.OFF_FILTER / Float.BYTES;
    private static final int ELEMENT = CanvasVertex.OFF_ELEMENT / Float.BYTES;

    @Test
    void theQuadCoversTheMarginAndCarriesItsScale() {
        Canvas c = new Canvas(400, 400).begin();
        ImageHandle field = new ImageHandle() {
        };
        // A 32 px shape, a 48-texel field with 8 texels of spread: a quarter of the shape's size round it.
        c.field(100, 100, 32, 32, field, 48, 8, 0.25f, Color.WHITE);
        float[] v = c.toVertexArray();
        assertEquals(6, c.vertexCount());
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            assertEquals(CoreLooks.record(CoreLooks.Primitive.FIELD), v[i * F + ELEMENT], 0f);
            minX = Math.min(minX, v[i * F + SCREEN_X]);
            maxX = Math.max(maxX, v[i * F + SCREEN_X]);
            // 48 px across 48 texels: one px a texel, so a unit of the value is 2 * 8 px.
            assertEquals(16f, v[i * F + FILTER], 1e-4f);
            assertEquals(1f / 48f, v[i * F + FILTER + 1], 1e-6f);
            // A field's effects are a look's, not the vertex's.
            assertEquals(0f, v[i * F + FILTER + 2], 0f);
            assertEquals(0f, v[i * F + FILTER + 3], 0f);
        }
        // The quad is the shape's square grown by the margin, plus the canvas's antialiasing pad either side.
        assertEquals(48f + 2f * (Canvas.AA + 1f), maxX - minX, 1e-3f);
    }

    @Test
    void aScaleScalesTheField() {
        Canvas c = new Canvas(400, 400).begin();
        c.pushScale(2f, 0, 0);
        c.field(10, 10, 32, 32, new ImageHandle() {
        }, 48, 8, 0.25f, Color.WHITE);
        float[] v = c.toVertexArray();
        assertEquals(32f, v[FILTER], 1e-4f, "twice the px per unit");
    }

    @Test
    void anImageDrawnAfterAFieldCarriesNothing() {
        Canvas c = new Canvas(400, 400).begin();
        ImageHandle h = new ImageHandle() {
        };
        c.field(0, 0, 32, 32, h, 48, 8, 0.25f, Color.WHITE);
        c.image(0, 0, 10, 10, h);
        float[] v = c.toVertexArray();
        assertEquals(CoreLooks.record(CoreLooks.Primitive.IMAGE), v[6 * F + ELEMENT], 0f);
        assertEquals(0f, v[6 * F + FILTER], 0f);
    }
}
