package dev.vexelray.text;

import dev.vexelray.text.FontSet.Face;
import dev.vexelray.text.FontSet.Slope;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The font set the build bakes, against the primary atlas it replaces, and the two rules the set adds: asking for a
 * style always gets a face, and a glyph a face lacks comes from the nearest face that has it.
 */
class FontSetTest {

    private static final AtlasData PRIMARY = AtlasData.loadFromResource("/dev/vexelray/text/atlas/primary.json");
    private static final float PX = 17f;
    /** A private-use code point no shipped face maps: it draws the missing-glyph box. */
    private static final int UNMAPPED = 0xE000;

    /** The ranges vexelray-text's pom bakes into the primary atlas's mono face. */
    private static final int[][] MONO_RANGES = {
            {0x20, 0x7E}, {0xA0, 0xFF}, {0x100, 0x17F}, {0x370, 0x3FF}, {0x400, 0x4FF}, {0x2000, 0x206F},
            {0x2190, 0x21FF}, {0x2200, 0x22FF}, {0x2500, 0x25FF}, {0x2700, 0x27BF}};

    // --- the standard set against the atlas it replaces ----------------------------------------------------

    @Test
    void theStandardSetIsTheTwoShippedFamilies() {
        FontSet set = FontSet.standard();
        assertEquals(List.of("sans", "mono"), set.families());
        assertEquals("regular", set.face("sans").style());
        assertEquals(0, set.face("sans").id());
        assertEquals(1, set.face("mono").id());
    }

    /**
     * Every code point the primary atlas's sans face has measures the same in the font set's sans face, one by one
     * and as a string. The new set has more glyphs; it must not have different ones.
     */
    @Test
    void sansMeasuresAsThePrimaryAtlasDid() {
        GlyphLayout before = new GlyphLayout(PRIMARY);
        FontSet set = FontSet.standard();
        GlyphLayout after = set.layout(set.face("sans"));

        StringBuilder all = new StringBuilder();
        for (int cp : PRIMARY.glyphs().keySet()) {
            if (cp == AtlasData.NOTDEF_CODEPOINT) {
                continue;
            }
            assertEquals(before.advance(cp, PX), after.advance(cp, PX), String.format("U+%04X", cp));
            all.appendCodePoint(cp);
        }
        assertEquals(before.measure(all.toString(), PX), after.measure(all.toString(), PX), 1e-3f);
        assertEquals(before.ascent(PX), after.ascent(PX));
        assertEquals(before.descent(PX), after.descent(PX));
        assertEquals(before.advance(UNMAPPED, PX), after.advance(UNMAPPED, PX), "the box is synthesized the same way");
    }

    /** Mono's own glyphs measure as the primary atlas's mono face did. */
    @Test
    void monoMeasuresAsThePrimaryAtlasDid() {
        GlyphLayout before = new GlyphLayout(PRIMARY.face(1));
        FontSet set = FontSet.standard().withFallback("sans");
        Face mono = set.face("mono");
        GlyphLayout after = set.layout(mono);

        int compared = 0;
        for (int[] r : MONO_RANGES) {
            for (int cp = r[0]; cp <= r[1]; cp++) {
                if (mono.atlas().glyph(cp) != null) {
                    assertEquals(before.advance(cp, PX), after.advance(cp, PX), String.format("U+%04X", cp));
                    compared++;
                }
            }
        }
        assertTrue(compared > 1000, "compared " + compared);
    }

    // --- fallback ------------------------------------------------------------------------------------------

    /**
     * Noto Sans has no arrows and Noto Sans Mono does. Without a fallback the arrow is sans's box; with mono as the
     * fallback it is mono's arrow, at mono's advance, and its quad names mono's atlas.
     */
    @Test
    void aGlyphTheFaceLacksComesFromTheFallback() {
        String arrow = "→";
        FontSet alone = FontSet.standard();
        Face sans = alone.face("sans");
        Face mono = alone.face("mono");
        assertEquals(null, sans.atlas().glyph(0x2192));

        assertEquals(alone.layout(sans).advance(UNMAPPED, PX), alone.layout(sans).measure(arrow, PX));

        FontSet withMono = alone.withFallback("mono");
        GlyphLayout layout = withMono.layout(withMono.face("sans"));
        assertEquals(mono.atlas().glyph(0x2192).advance() * PX, layout.measure(arrow, PX));
        List<GlyphQuad> quads = layout.layout("a" + arrow, 0, 20, PX);
        assertEquals(sans.id(), quads.get(0).face());
        assertEquals(mono.id(), quads.get(1).face(), "the arrow's UVs are into mono's atlas");
    }

    // --- style matching, over a set built by hand ----------------------------------------------------------

    @Test
    void anAbsentStyleGetsTheNearestFaceTheWayCssWould() {
        FontSet set = handMade();
        assertEquals("bold", set.face("ui", 600, Slope.NORMAL).style(), "semibold: heavier first above 500");
        assertEquals("regular", set.face("ui", 500, Slope.NORMAL).style(), "medium: lighter first at 400-500");
        assertEquals("regular", set.face("ui", 300, Slope.NORMAL).style(), "light: heavier when nothing lighter");
        assertEquals("italic", set.face("ui", 300, Slope.OBLIQUE).style(),
                "slope is matched before weight: upright regular is no nearer for being the same weight");
        assertEquals("bold-italic", set.face("ui", 900, Slope.ITALIC).style(), "the heaviest italic there is");
        assertEquals("italic", set.face("ui", 400, Slope.OBLIQUE).style(), "oblique asked: italic before upright");
    }

    /**
     * A face borrows from its own family in the same order a style request is matched: slope before weight, so
     * bold-italic asks italic, then bold, then regular. Neither italic has 'x' here, so it comes from bold.
     */
    @Test
    void aFaceBorrowsFromItsNearestSibling() {
        FontSet set = handMade();
        Face boldItalic = set.face("ui", 700, Slope.ITALIC);
        assertEquals("bold-italic", boldItalic.style());
        assertEquals(List.of("bold-italic", "italic", "bold", "regular"),
                set.chain(boldItalic).stream().map(Face::style).toList());

        GlyphLayout layout = set.layout(boldItalic);
        assertEquals(set.face("ui", 700, Slope.NORMAL).id(), layout.layout("x", 0, 0, PX).get(0).face());
        assertEquals(0.7f * PX, layout.advance('x', PX), "at the bold advance");
    }

    @Test
    void anUnknownFamilyIsAProgrammingError() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> FontSet.standard().face("serif"));
        assertTrue(e.getMessage().contains("[sans, mono]"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> FontSet.standard().withFallback("serif"));
    }

    @Test
    void pixelsAreAFaceSizedImage() {
        FontSet set = FontSet.standard();
        for (Face f : set.faces()) {
            AtlasPixels p = set.pixels(f);
            assertEquals(f.atlas().info().width(), p.width(), f.family());
            assertEquals(f.atlas().info().height(), p.height(), f.family());
        }
    }

    @Test
    void theChainIsTheFamilyThenTheFallbacks() {
        FontSet set = FontSet.standard().withFallback("mono");
        Face sans = set.face("sans");
        assertEquals(List.of(sans, set.face("mono")), set.chain(sans));
        assertSame(set.face("mono"), set.chain(set.face("mono")).get(0));
        assertEquals(1, set.chain(set.face("mono")).size(), "a family is not its own fallback");
    }

    /** Family "ui": regular, italic, bold and bold-italic, where both italics lack 'x'. */
    private static FontSet handMade() {
        Map<String, String> files = new HashMap<>();
        files.put("", """
                {"version": 1, "bytes": 0, "families": [{"name": "ui", "fontFamily": "UI", "faces": [
                  {"style": "regular", "weight": 400, "stretch": 5, "slope": "normal", "metrics": "r.json", "pixels": "r.rgba"},
                  {"style": "italic", "weight": 400, "stretch": 5, "slope": "italic", "metrics": "i.json", "pixels": "i.rgba"},
                  {"style": "bold", "weight": 700, "stretch": 5, "slope": "normal", "metrics": "b.json", "pixels": "b.rgba"},
                  {"style": "bold-italic", "weight": 700, "stretch": 5, "slope": "italic", "metrics": "bi.json", "pixels": "bi.rgba"}
                ]}]}""");
        files.put("r.json", atlas(0.5f, true));
        files.put("i.json", atlas(0.5f, false));
        files.put("b.json", atlas(0.7f, true));
        files.put("bi.json", atlas(0.7f, false));
        return FontSet.read(path -> {
            String s = files.get(path);
            return s == null ? null : (InputStream) new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
        });
    }

    private static String atlas(float advance, boolean hasX) {
        String x = hasX ? ",{\"unicode\":120,\"advance\":" + advance
                + ",\"planeBounds\":{\"left\":0,\"bottom\":0,\"right\":0.5,\"top\":0.5}"
                + ",\"atlasBounds\":{\"left\":0,\"bottom\":0,\"right\":8,\"top\":8}}" : "";
        return "{\"atlas\":{\"type\":\"msdf\",\"distanceRange\":4,\"size\":32,\"width\":64,\"height\":64,"
                + "\"yOrigin\":\"bottom\"},\"metrics\":{\"emSize\":1,\"lineHeight\":1.2,\"ascender\":0.9,"
                + "\"descender\":-0.3,\"underlineY\":-0.1,\"underlineThickness\":0.05},\"glyphs\":["
                + "{\"unicode\":32,\"advance\":0.25}" + x + "],\"kerning\":[]}";
    }
}
