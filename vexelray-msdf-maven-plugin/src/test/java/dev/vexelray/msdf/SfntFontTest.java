package dev.vexelray.msdf;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reader over the two fonts this repository ships, with the atlas msdf-atlas-gen already made from them as
 * the oracle: what the reader says a font maps must be exactly what the generator put in the atlas.
 */
class SfntFontTest {

    static final Path FONTS = Path.of("../vexelray-text/fonts");
    static final Path SANS = FONTS.resolve("NotoSans-Regular.ttf");
    static final Path MONO = FONTS.resolve("NotoSansMono-Regular.ttf");
    private static final Path ATLAS = Path.of("../vexelray-text/src/main/resources/dev/vexelray/text/atlas/primary.json");

    /** The charsets vexelray-text's pom asks for, face by face. */
    private static final int[][] SANS_RANGES = {
            {0x20, 0x7E}, {0xA0, 0xFF}, {0x100, 0x17F}, {0x370, 0x3FF}, {0x400, 0x4FF}, {0x2000, 0x206F},
            {0x20A0, 0x20BF}, {0x2100, 0x214F}, {0x2190, 0x21FF}, {0x2200, 0x22FF}, {0x2500, 0x257F},
            {0x25A0, 0x25FF}, {0x2700, 0x27BF}};
    private static final int[][] MONO_RANGES = {
            {0x20, 0x7E}, {0xA0, 0xFF}, {0x100, 0x17F}, {0x370, 0x3FF}, {0x400, 0x4FF}, {0x2000, 0x206F},
            {0x2190, 0x21FF}, {0x2200, 0x22FF}, {0x2500, 0x25FF}, {0x2700, 0x27BF}};

    @Test
    void aStaticFaceSaysWhatItIs() throws Exception {
        SfntFont sans = SfntFont.read(SANS);
        assertEquals("Noto Sans", sans.family);
        assertEquals(400, sans.weightClass);
        assertEquals(5, sans.widthClass);
        assertFalse(sans.italic);
        assertFalse(sans.isVariable());
        assertEquals("regular", FaceStyle.of(sans).key());

        assertEquals("Noto Sans Mono", SfntFont.read(MONO).family);
    }

    /**
     * Every code point the reader says the fonts map, within the ranges the pom asked for, is exactly the set the
     * generator baked. More and the reader invents glyphs; fewer and a family would silently lose some.
     */
    @Test
    void theCmapAgreesWithTheAtlasTheGeneratorMade() throws Exception {
        SortedSet<Integer> expected = new TreeSet<>();
        expected.addAll(within(SfntFont.read(SANS).codepoints(), SANS_RANGES));
        expected.addAll(within(SfntFont.read(MONO).codepoints(), MONO_RANGES));

        SortedSet<Integer> baked = new TreeSet<>();
        Matcher m = Pattern.compile("\"unicode\":(\\d+)").matcher(Files.readString(ATLAS, StandardCharsets.UTF_8));
        while (m.find()) {
            baked.add(Integer.parseInt(m.group(1)));
        }
        baked.remove(NotdefGlyph.NOTDEF_CODEPOINT); // synthesized, not the font's

        assertEquals(expected, baked);
    }

    @Test
    void theWholeRepertoireIsMoreThanThePomEverAskedFor() throws Exception {
        SortedSet<Integer> all = SfntFont.read(SANS).codepoints();
        assertTrue(all.contains((int) 'A'));
        assertTrue(all.size() > 2000, "Noto Sans maps thousands of code points; read " + all.size());
        assertFalse(all.contains(0x2190), "Noto Sans has no arrows — the case CharsetCoverage was written for");
    }

    private static SortedSet<Integer> within(SortedSet<Integer> cps, int[][] ranges) {
        SortedSet<Integer> out = new TreeSet<>();
        for (int cp : cps) {
            for (int[] r : ranges) {
                if (cp >= r[0] && cp <= r[1]) {
                    out.add(cp);
                }
            }
        }
        return out;
    }
}
