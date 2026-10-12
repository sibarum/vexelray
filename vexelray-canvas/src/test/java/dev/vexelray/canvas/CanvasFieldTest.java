package dev.vexelray.canvas;

import dev.vexelray.target.ImageHandle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A distance field's quad: it reaches over the margin, and carries its scale and effects in screen px. */
class CanvasFieldTest {

    private static final int F = CanvasVertex.FLOATS_PER_VERTEX;

    @Test
    void theQuadCoversTheMarginAndCarriesItsScale() {
        Canvas c = new Canvas(400, 400).begin();
        ImageHandle field = new ImageHandle() {
        };
        // A 32 px shape, a 48-texel field with 8 texels of spread: a quarter of the shape's size round it.
        c.field(100, 100, 32, 32, field, 48, 8, 0.25f, new FieldStyle(Color.WHITE, 4f, 2f), 1f);
        float[] v = c.toVertexArray();
        assertEquals(6, c.vertexCount());
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        for (int i = 0; i < 6; i++) {
            assertEquals(CanvasVertex.KIND_FIELD, v[i * F + 8], 0f);
            minX = Math.min(minX, v[i * F + 19]);
            maxX = Math.max(maxX, v[i * F + 19]);
            // 48 px across 48 texels: one px a texel, so a unit of the value is 2 * 8 px.
            assertEquals(16f, v[i * F + 23], 1e-4f);
            assertEquals(1f / 48f, v[i * F + 24], 1e-6f);
            assertEquals(4f, v[i * F + 25], 1e-6f);
            assertEquals(2f, v[i * F + 26], 1e-6f);
        }
        // The quad is the shape's square grown by the margin, plus the canvas's antialiasing pad either side.
        assertEquals(48f + 2f * (Canvas.AA + 1f), maxX - minX, 1e-3f);
    }

    @Test
    void aScaleScalesTheFieldAndItsEffects() {
        Canvas c = new Canvas(400, 400).begin();
        c.pushScale(2f, 0, 0);
        c.field(10, 10, 32, 32, new ImageHandle() {
        }, 48, 8, 0.25f, new FieldStyle(Color.WHITE, 4f, 2f), 1f);
        float[] v = c.toVertexArray();
        assertEquals(32f, v[23], 1e-4f, "twice the px per unit");
        assertEquals(8f, v[25], 1e-6f, "and twice the glow");
    }

    @Test
    void anImageDrawnAfterAFieldCarriesNothing() {
        Canvas c = new Canvas(400, 400).begin();
        ImageHandle h = new ImageHandle() {
        };
        c.field(0, 0, 32, 32, h, 48, 8, 0.25f, FieldStyle.plain(Color.WHITE), 1f);
        c.image(0, 0, 10, 10, h);
        float[] v = c.toVertexArray();
        assertEquals(CanvasVertex.KIND_IMAGE, v[6 * F + 8], 0f);
        assertEquals(0f, v[6 * F + 23], 0f);
    }
}
