package dev.vexelray.canvas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link Canvas#pushRotate} and {@link Canvas#pushScale}: every vertex turns or grows about the pivot, in the frame the
 * translations already pushed make. A turn leaves what each primitive evaluates in its own frame untouched; a scale
 * scales that frame with it. Either way the shape stays exact.
 */
class CanvasTransformTest {

    private static final float EPS = 1e-3f;
    private static final int F = CanvasVertex.FLOATS_PER_VERTEX;
    private static final int SCREEN = CanvasVertex.OFF_CLIPRS / Float.BYTES;
    private static final int LOCAL = CanvasVertex.OFF_LOCAL / Float.BYTES;

    /** Each vertex's screen position, as the clip SDF reads it. */
    private static float[] screen(Canvas c) {
        float[] v = c.toVertexArray();
        float[] out = new float[2 * c.vertexCount()];
        for (int i = 0; i < c.vertexCount(); i++) {
            out[2 * i] = v[i * F + SCREEN];
            out[2 * i + 1] = v[i * F + SCREEN + 1];
        }
        return out;
    }

    /** Each vertex's box-local position, the frame its SDF is evaluated in. */
    private static float[] local(Canvas c) {
        float[] v = c.toVertexArray();
        float[] out = new float[2 * c.vertexCount()];
        for (int i = 0; i < c.vertexCount(); i++) {
            out[2 * i] = v[i * F + LOCAL];
            out[2 * i + 1] = v[i * F + LOCAL + 1];
        }
        return out;
    }

    /** {@code points} turned {@code radians} clockwise about {@code (px, py)}, then moved by {@code (dx, dy)}. */
    private static float[] turned(float[] points, double radians, float px, float py, float dx, float dy) {
        float cos = (float) Math.cos(radians);
        float sin = (float) Math.sin(radians);
        float[] out = new float[points.length];
        for (int i = 0; i < points.length; i += 2) {
            float x = points[i] - px;
            float y = points[i + 1] - py;
            out[i] = px + cos * x - sin * y + dx;
            out[i + 1] = py + sin * x + cos * y + dy;
        }
        return out;
    }

    @Test
    void aTurnMovesTheCornersAndNotTheShapesOwnFrame() {
        Canvas upright = new Canvas(200, 200).begin();
        upright.fillRoundRect(40, 50, 30, 10, 3, Color.WHITE);

        Canvas turned = new Canvas(200, 200).begin();
        turned.pushRotate((float) (Math.PI / 3), 55, 55);
        turned.fillRoundRect(40, 50, 30, 10, 3, Color.WHITE);
        turned.popRotate();

        assertArrayEquals(turned(screen(upright), Math.PI / 3, 55, 55, 0, 0), screen(turned), EPS);
        assertArrayEquals(local(upright), local(turned), EPS, "the SDF's frame is the box's, turned or not");
    }

    @Test
    void aTranslationInsideATurnRunsAlongTheTurnedAxes() {
        Canvas upright = new Canvas(200, 200).begin();
        upright.fillRect(10, 10, 20, 20, Color.WHITE);

        Canvas c = new Canvas(200, 200).begin();
        c.pushTranslate(5, 7);
        c.pushRotate((float) (Math.PI / 2), 20, 20);
        c.pushTranslate(10, 0);
        c.fillRect(10, 10, 20, 20, Color.WHITE);

        // Moved 10 along x, turned a quarter about (20, 20), then moved (5, 7): the inner move points down.
        float[] moved = turned(screen(upright), 0, 0, 0, 10, 0);
        assertArrayEquals(turned(moved, Math.PI / 2, 20, 20, 5, 7), screen(c), EPS);
    }

    @Test
    void aScaleScalesTheCornersAndTheShapesOwnFrameAlike() {
        Canvas upright = new Canvas(200, 200).begin();
        upright.fillRoundRect(40, 50, 30, 10, 3, Color.WHITE);

        Canvas scaled = new Canvas(200, 200).begin();
        scaled.pushRotate(0.5f, 55, 55);
        scaled.pushScale(2.5f, 55, 55);
        scaled.fillRoundRect(40, 50, 30, 10, 3, Color.WHITE);
        scaled.popScale();
        scaled.popRotate();

        float[] at = screen(upright);
        float[] grown = new float[at.length];
        for (int i = 0; i < at.length; i += 2) {
            grown[i] = 55f + 2.5f * (at[i] - 55f);
            grown[i + 1] = 55f + 2.5f * (at[i + 1] - 55f);
        }
        assertArrayEquals(turned(grown, 0.5, 55, 55, 0, 0), screen(scaled), EPS);
        // The box-local frame is in screen px again: every local coordinate, and the half-extents, 2.5 times over.
        float[] l0 = local(upright);
        float[] l1 = local(scaled);
        for (int i = 0; i < l0.length; i++) {
            assertEquals(2.5f * l0[i], l1[i], EPS);
        }
        float[] v0 = upright.toVertexArray();
        float[] v1 = scaled.toVertexArray();
        for (int k = 11; k <= 14; k++) {
            assertEquals(2.5f * v0[k], v1[k], EPS, "half-extents and radii, slot " + k);
        }
    }

    @Test
    void popRestoresTheTransformUnderIt() {
        Canvas upright = new Canvas(200, 200).begin();
        upright.fillRect(0, 0, 10, 10, Color.WHITE);
        float[] plain = screen(upright);

        Canvas c = new Canvas(200, 200).begin();
        c.pushTranslate(3, 4);
        c.pushRotate(1.1f, 80, 90);
        c.popRotate();
        c.fillRect(0, 0, 10, 10, Color.WHITE);
        float[] s = screen(c);
        assertEquals(plain[0] + 3f, s[0], EPS);
        assertEquals(plain[1] + 4f, s[1], EPS);

        c.pushRotate(0.4f, 1, 1);
        c.popTranslate();
        c.popTranslate();
        c.fillRect(0, 0, 10, 10, Color.WHITE);
        float[] after = screen(c);
        assertEquals(plain[0], after[after.length - 12], EPS);
        assertEquals(plain[1], after[after.length - 11], EPS);
    }
}
