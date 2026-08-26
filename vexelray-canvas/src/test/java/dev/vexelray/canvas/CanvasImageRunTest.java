package dev.vexelray.canvas;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The run split: a canvas is still one vertex buffer in submission order, and {@link Canvas#runs()} only says where
 * the binding layer must rebind the image sampler as it walks it.
 *
 * <p>The invariant these tests defend is that runs <b>partition</b> the buffer — contiguous, gapless, in order, and
 * summing to the vertex count. Anything else silently drops or reorders primitives, which in a z-ordered batch is a
 * picture that is wrong rather than a draw that fails.
 */
class CanvasImageRunTest {

    private static final int VERTS_PER_QUAD = 6;

    /** Vertices in a run, as the binding layer would walk them: contiguous, in order, covering everything once. */
    private static void assertPartitions(List<Canvas.Run> runs, int totalVertices) {
        int at = 0;
        for (Canvas.Run r : runs) {
            assertEquals(at, r.firstVertex(), "runs must be contiguous and in submission order");
            assertTrue(r.vertexCount() > 0, "an empty run would be a draw call for nothing");
            at += r.vertexCount();
        }
        assertEquals(totalVertices, at, "runs must cover every vertex exactly once");
    }

    @Test
    void shapesOnlyAreOneRunWithNoImage() {
        Canvas canvas = new Canvas(200, 100).begin();
        canvas.fillRect(0, 0, 10, 10, Color.WHITE);
        canvas.fillRoundRect(20, 0, 10, 10, 2, Color.WHITE);

        List<Canvas.Run> runs = canvas.runs();
        assertEquals(1, runs.size(), "a canvas with no images is the single draw it always was");
        assertNull(runs.get(0).image());
        assertPartitions(runs, canvas.vertexCount());
    }

    @Test
    void emptyCanvasHasNoRuns() {
        assertEquals(List.of(), new Canvas(10, 10).begin().runs());
    }

    @Test
    void imageBetweenShapesSplitsIntoThreeRuns() {
        Object texture = new Object();
        Canvas canvas = new Canvas(200, 100).begin();
        canvas.fillRect(0, 0, 10, 10, Color.WHITE);      // run 0: no image
        canvas.image(20, 0, 40, 40, texture);            // run 1: the image
        canvas.fillRect(70, 0, 10, 10, Color.WHITE);     // run 2: back to no image

        List<Canvas.Run> runs = canvas.runs();
        assertEquals(3, runs.size());
        assertNull(runs.get(0).image());
        assertSame(texture, runs.get(1).image());
        assertNull(runs.get(2).image());
        assertEquals(VERTS_PER_QUAD, runs.get(1).vertexCount(), "one image quad is six vertices");
        assertPartitions(runs, canvas.vertexCount());
    }

    @Test
    void consecutiveDrawsOfTheSameImageShareOneRun() {
        Object texture = new Object();
        Canvas canvas = new Canvas(200, 100).begin();
        canvas.image(0, 0, 10, 10, texture);
        canvas.image(20, 0, 10, 10, texture);

        List<Canvas.Run> runs = canvas.runs();
        assertEquals(1, runs.size(), "the same handle twice is one bind, not two");
        assertSame(texture, runs.get(0).image());
        assertEquals(2 * VERTS_PER_QUAD, runs.get(0).vertexCount());
    }

    @Test
    void alternatingImagesCostARunEach() {
        Object a = new Object();
        Object b = new Object();
        Canvas canvas = new Canvas(200, 100).begin();
        canvas.image(0, 0, 10, 10, a);
        canvas.image(20, 0, 10, 10, b);
        canvas.image(40, 0, 10, 10, a);

        List<Canvas.Run> runs = canvas.runs();
        assertEquals(3, runs.size());
        assertSame(a, runs.get(0).image());
        assertSame(b, runs.get(1).image());
        assertSame(a, runs.get(2).image());
        assertPartitions(runs, canvas.vertexCount());
    }

    @Test
    void beginClearsRunsFromTheLastFrame() {
        Object texture = new Object();
        Canvas canvas = new Canvas(200, 100).begin();
        canvas.image(0, 0, 10, 10, texture);
        assertEquals(1, canvas.runs().size());

        canvas.begin();
        canvas.fillRect(0, 0, 10, 10, Color.WHITE);
        List<Canvas.Run> runs = canvas.runs();
        assertEquals(1, runs.size());
        assertNull(runs.get(0).image(), "last frame's image must not survive begin()");
        assertPartitions(runs, canvas.vertexCount());
    }

    @Test
    void runsIsNonMutatingSoItCanBeAskedTwice() {
        Object texture = new Object();
        Canvas canvas = new Canvas(200, 100).begin();
        canvas.fillRect(0, 0, 10, 10, Color.WHITE);
        canvas.image(20, 0, 10, 10, texture);

        assertEquals(canvas.runs(), canvas.runs());
        assertPartitions(canvas.runs(), canvas.vertexCount());
    }

    @Test
    void imageVerticesCarryTheImageKind() {
        Canvas canvas = new Canvas(200, 100).begin();
        canvas.image(0, 0, 10, 10, new Object());

        float[] v = canvas.toVertexArray();
        for (int i = 0; i < canvas.vertexCount(); i++) {
            assertEquals(CanvasVertex.KIND_IMAGE,
                    (int) v[i * CanvasVertex.FLOATS_PER_VERTEX + CanvasVertex.OFF_KIND / Float.BYTES]);
        }
    }

    /**
     * The box's own edges must land on the requested UV corners, whatever the quad's AA overhang does. Read the
     * two diagonal corners of the first triangle: they straddle the box, so the interpolated UV at the box edge is
     * recoverable from them.
     */
    @Test
    void uvIsExtrapolatedSoTheBoxEdgesLandOnTheRequestedRegion() {
        float w = 40f;
        float h = 20f;
        Canvas canvas = new Canvas(200, 100).begin();
        canvas.image(0, 0, w, h, 0f, new Object(), 0.25f, 0.5f, 0.75f, 1.0f, Color.WHITE);

        float[] v = canvas.toVertexArray();
        int stride = CanvasVertex.FLOATS_PER_VERTEX;
        int uv = CanvasVertex.OFF_UV / Float.BYTES;
        int local = CanvasVertex.OFF_LOCAL / Float.BYTES;

        // Vertex 0 is the top-left corner of the padded quad, vertex 2 the bottom-right. Undo the overhang by
        // solving the linear map back at local == -w/2 and +w/2.
        float u0 = v[uv];
        float u2 = v[2 * stride + uv];
        float lx0 = v[local];
        float lx2 = v[2 * stride + local];
        float slope = (u2 - u0) / (lx2 - lx0);
        assertEquals(0.25f, u0 + slope * (-w / 2f - lx0), 1e-4f, "left edge of the box");
        assertEquals(0.75f, u0 + slope * (w / 2f - lx0), 1e-4f, "right edge of the box");
    }
}
