package dev.vexelray.canvas;

import dev.vexelray.target.ImageHandle;
import dev.vexelray.text.FontSet;
import dev.vexelray.text.TextLayout;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs split by face as well as by image: text in two faces is two runs over two atlases, and everything that is not
 * text — shapes, images — never splits on face, so a canvas in one face is exactly the runs, and the vertices, it
 * was before faces existed.
 */
class CanvasFaceRunTest {

    private static final FontSet SET = FontSet.standard().withFallback("mono");
    private static final TextLayout SANS = new TextLayout(SET.layout(SET.face("sans")));
    private static final TextLayout MONO = new TextLayout(SET.layout(SET.face("mono")));
    private static final TextLayout.TextStyle STYLE = TextLayout.TextStyle.of(20f);

    /** Where a vertex keeps its kind and, for a glyph, its screenPxRange (CanvasVertex OFF_KIND, OFF_SHAPE). */
    private static final int KIND = CanvasVertex.OFF_KIND / Float.BYTES;
    private static final int RANGE = CanvasVertex.OFF_SHAPE / Float.BYTES;

    @Test
    void textInOneFaceIsOneRunWithTheVerticesItAlwaysHad() {
        Canvas canvas = new Canvas(400, 100).begin();
        canvas.fillRect(0, 0, 10, 10, Color.WHITE);
        canvas.text(SANS, "Hello, world", 0, 0, STYLE, Color.WHITE);
        canvas.fillRect(0, 50, 10, 10, Color.WHITE);

        List<Canvas.Run> runs = canvas.runs();
        assertEquals(1, runs.size(), "shapes join the run their text is in");
        assertEquals(SET.face("sans").id(), runs.get(0).face());

        // Each glyph used to take the layout's screenPxRange; it now takes its own quad's, which for one face is
        // the same number computed the same way. Bit-for-bit, so the picture is too.
        float[] v = canvas.toVertexArray();
        int glyphs = 0;
        for (int o = 0; o < v.length; o += CanvasVertex.FLOATS_PER_VERTEX) {
            if (v[o + KIND] == CanvasVertex.KIND_GLYPH) {
                assertEquals(SANS.screenPxRange(STYLE.pixelSize()), v[o + RANGE]);
                glyphs++;
            }
        }
        assertTrue(glyphs > 0);
    }

    /** Noto Sans has no arrows; mono, its fallback, does. The arrow is a run of its own over mono's atlas. */
    @Test
    void aBorrowedGlyphIsARunOverTheAtlasItCameFrom() {
        Canvas canvas = new Canvas(400, 100).begin();
        canvas.text(SANS, "a→b", 0, 0, STYLE, Color.WHITE);

        List<Canvas.Run> runs = canvas.runs();
        assertEquals(List.of(SET.face("sans").id(), SET.face("mono").id(), SET.face("sans").id()),
                runs.stream().map(Canvas.Run::face).toList());
        assertEquals(List.of(6, 6, 6), runs.stream().map(Canvas.Run::vertexCount).toList(), "one quad each");
    }

    /**
     * An image samples no atlas, so between two lines of mono it names mono too — the binding layer has no reason
     * to rebind set 0 for it, and does not.
     */
    @Test
    void anImageRunKeepsTheFaceAlreadyBound() {
        ImageHandle picture = new ImageHandle() {
        };
        Canvas canvas = new Canvas(400, 200).begin();
        canvas.text(MONO, "first", 0, 0, STYLE, Color.WHITE);
        canvas.image(0, 40, 50, 50, picture);
        canvas.text(MONO, "second", 0, 100, STYLE, Color.WHITE);

        List<Canvas.Run> runs = canvas.runs();
        assertEquals(3, runs.size(), "the image still splits");
        int mono = SET.face("mono").id();
        assertEquals(List.of(mono, mono, mono), runs.stream().map(Canvas.Run::face).toList());
    }

    /**
     * A run per change of face, not per draw call: two consecutive mono draws share a run, and a space (which has
     * no quad) inside "a b" splits nothing.
     */
    @Test
    void facesAlternatingIsARunPerChange() {
        Canvas canvas = new Canvas(400, 100).begin();
        canvas.text(SANS, "x", 0, 0, STYLE, Color.WHITE);
        canvas.text(MONO, "y", 20, 0, STYLE, Color.WHITE);
        canvas.text(MONO, "z", 40, 0, STYLE, Color.WHITE);
        canvas.text(SANS, "a b", 60, 0, STYLE, Color.WHITE);

        assertEquals(List.of(0, 1, 0), canvas.runs().stream().map(Canvas.Run::face).toList());
        int at = 0;
        for (Canvas.Run r : canvas.runs()) {
            assertEquals(at, r.firstVertex());
            at += r.vertexCount();
        }
        assertEquals(canvas.vertexCount(), at, "runs still partition the buffer");
    }
}
