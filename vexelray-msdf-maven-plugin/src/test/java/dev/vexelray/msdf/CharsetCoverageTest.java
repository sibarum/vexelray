package dev.vexelray.msdf;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CharsetCoverageTest {

    /** An atlas JSON in the shape msdf-atlas-gen writes, cut down to the one field this reads. */
    private static String atlas(int... codepoints) {
        StringBuilder json = new StringBuilder("{\"atlas\":{\"width\":64},\"glyphs\":[");
        for (int i = 0; i < codepoints.length; i++) {
            json.append(i == 0 ? "" : ",")
                    .append("{\"unicode\":").append(codepoints[i]).append(",\"advance\":0.5}");
        }
        return json.append("]}").toString();
    }

    @Test
    void aRangeTheFontCoversIsReportedAsComplete() {
        CharsetCoverage.Coverage c = CharsetCoverage.measure("[0x41, 0x43]\n", atlas(0x41, 0x42, 0x43));
        assertTrue(c.missing().isEmpty());
        assertEquals(3, c.requested().size());
    }

    @Test
    void aRangeTheFontDoesNotCoverIsTheWholeRange() {
        // The FN-16 shape: the charset asks for arrows, the font has none, the build says nothing.
        CharsetCoverage.Coverage c = CharsetCoverage.measure("[0x2190, 0x2193]\n", atlas(0x41));
        assertEquals(4, c.missing().size());
        assertEquals(List.of("U+2190..U+2193 (4)"), c.missingRanges());
    }

    @Test
    void missingCodepointsAreCollapsedIntoRuns() {
        CharsetCoverage.Coverage c = CharsetCoverage.measure("[0x41, 0x48]", atlas(0x43, 0x44, 0x45));
        assertEquals(List.of("U+0041..U+0042 (2)", "U+0046..U+0048 (3)"), c.missingRanges(),
                "a build log listing hundreds of codepoints one per line is one nobody reads");
    }

    @Test
    void aSingleMissingCodepointReadsAsOneRatherThanARange() {
        CharsetCoverage.Coverage c = CharsetCoverage.measure("[0x41, 0x42]", atlas(0x41));
        assertEquals(List.of("U+0042"), c.missingRanges());
    }

    @Test
    void bareCodepointsAndLiteralsAreUnderstood() {
        CharsetCoverage.Coverage c = CharsetCoverage.measure("0x41, 66 \"CD\" 'E'", atlas(0x41, 0x43));
        assertEquals(5, c.requested().size(), "A, B, C, D and E were all asked for");
        assertEquals(List.of("U+0042", "U+0044..U+0045 (2)"), c.missingRanges());
        assertTrue(c.unparsed().isEmpty());
    }

    @Test
    void tokensTheSyntaxDoesNotUnderstandAreReportedRatherThanSkipped() {
        CharsetCoverage.Coverage c = CharsetCoverage.measure("[0x41, 0x42] @@nonsense@@", atlas(0x41, 0x42));
        assertTrue(c.missing().isEmpty(), "what was understood is genuinely covered");
        assertEquals(List.of("@@nonsense@@"), c.unparsed(),
                "a check that quietly skipped half its input would answer 'all covered' about an atlas it"
                        + " never looked at");
    }

    @Test
    void glyphsFromEveryVariantCount() {
        // Faces fall back to the primary at run time, so the union is the question with consequences.
        String multi = "{\"variants\":[{\"glyphs\":[{\"unicode\":65}]},{\"glyphs\":[{\"unicode\":66}]}]}";
        CharsetCoverage.Coverage c = CharsetCoverage.measure("[0x41, 0x42]", multi);
        assertTrue(c.missing().isEmpty());
    }
}
