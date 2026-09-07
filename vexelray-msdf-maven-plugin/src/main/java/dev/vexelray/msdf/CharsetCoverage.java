package dev.vexelray.msdf;

import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What the charset asked for, what the font actually had, and the difference — reported at build time.
 *
 * <h2>Why this exists</h2>
 *
 * <p>A charset is a request. {@code msdf-atlas-gen} bakes the glyphs the font has and silently omits the rest,
 * so a range the font does not cover produces an atlas that is smaller than expected and an application that
 * draws {@code x □ (Re, Im)} where it wrote {@code x → (Re, Im)}. Nothing in the chain says a word: the build
 * succeeds, the atlas loads, the text lays out, and the box is a runtime substitution three layers away from
 * the pom that asked for the arrow.
 *
 * <p>The whole answer is a set difference over data the plugin already holds — it writes the charset, and it
 * reads the JSON. That makes this the cheapest permanent fix available to any consumer of this framework, which
 * is why it is here rather than in the application that hit it. The primary atlas in this repository is the
 * worked example: its charset requests arrows and dingbats, its comment claims them, and Noto Sans has neither.
 *
 * <h2>Union, not per-face</h2>
 *
 * <p>A codepoint is compared against every glyph the atlas produced, not against the face that requested it,
 * because that is the question with consequences: faces fall back to the primary at run time, so a codepoint
 * the mono face lacks but the primary has still renders. Missing from the union is missing from the screen.
 */
final class CharsetCoverage {

    /** One range or literal in the charset syntax; anything not matched by these is reported as unparsed. */
    private static final Pattern TOKEN = Pattern.compile(
            "\\[\\s*(0[xX][0-9a-fA-F]+|\\d+)\\s*,\\s*(0[xX][0-9a-fA-F]+|\\d+)\\s*]"   // [0x20, 0x7E]
                    + "|\"((?:[^\"\\\\]|\\\\.)*)\""                                    // "abc"
                    + "|'((?:[^'\\\\]|\\\\.)*)'"                                       // 'a'
                    + "|(0[xX][0-9a-fA-F]+|\\d+)");                                    // 0x41

    /** Every {@code "unicode": N} in the atlas JSON, across all variants. */
    private static final Pattern UNICODE = Pattern.compile("\"unicode\"\\s*:\\s*(\\d+)");

    /**
     * A charset the build asked for, measured against the atlas it produced.
     *
     * @param unparsed tokens the charset syntax here does not understand. Reported rather than ignored: a
     *                 coverage check that quietly skipped half its input would answer "all covered" about an
     *                 atlas it never looked at, which is the failure this class exists to stop.
     */
    record Coverage(SortedSet<Integer> requested, SortedSet<Integer> produced, List<String> unparsed) {

        SortedSet<Integer> missing() {
            SortedSet<Integer> missing = new TreeSet<>(requested);
            missing.removeAll(produced);
            return missing;
        }

        /**
         * The missing codepoints as contiguous runs — {@code U+2190..U+21FF (112)} rather than 112 lines.
         *
         * <p>Readability is the point of the whole report. A build log that lists several hundred codepoints
         * one per line is one nobody reads, and an unread warning is the state this started in.
         */
        List<String> missingRanges() {
            List<String> out = new ArrayList<>();
            Integer start = null;
            Integer previous = null;
            for (int cp : missing()) {
                if (start == null) {
                    start = cp;
                } else if (cp != previous + 1) {
                    out.add(describe(start, previous));
                    start = cp;
                }
                previous = cp;
            }
            if (start != null) {
                out.add(describe(start, previous));
            }
            return out;
        }

        private static String describe(int from, int to) {
            return from == to
                    ? String.format("U+%04X", from)
                    : String.format("U+%04X..U+%04X (%d)", from, to, to - from + 1);
        }
    }

    private CharsetCoverage() {
    }

    /** Measure {@code charsetContent} — the text handed to {@code -charset} — against the produced atlas JSON. */
    static Coverage measure(String charsetContent, String atlasJson) {
        List<String> unparsed = new ArrayList<>();
        return new Coverage(requested(charsetContent, unparsed), produced(atlasJson), unparsed);
    }

    /**
     * The codepoints a charset names: {@code [a, b]} ranges, bare codepoints, and quoted literals, in
     * {@code msdf-atlas-gen}'s syntax. Whatever is left over after those goes to {@code unparsed}.
     */
    private static SortedSet<Integer> requested(String content, List<String> unparsed) {
        SortedSet<Integer> out = new TreeSet<>();
        Matcher m = TOKEN.matcher(content);
        int consumed = 0;
        while (m.find()) {
            noteGap(content, consumed, m.start(), unparsed);
            consumed = m.end();
            if (m.group(1) != null) {
                int from = number(m.group(1));
                int to = number(m.group(2));
                for (int cp = Math.min(from, to); cp <= Math.max(from, to); cp++) {
                    out.add(cp);
                }
            } else if (m.group(3) != null) {
                m.group(3).codePoints().forEach(out::add);
            } else if (m.group(4) != null) {
                m.group(4).codePoints().forEach(out::add);
            } else {
                out.add(number(m.group(5)));
            }
        }
        noteGap(content, consumed, content.length(), unparsed);
        return out;
    }

    /** Anything between tokens that is not separator punctuation is input this check did not understand. */
    private static void noteGap(String content, int from, int to, List<String> unparsed) {
        String gap = content.substring(from, to).replaceAll("[\\s,]+", "");
        if (!gap.isEmpty()) {
            unparsed.add(gap);
        }
    }

    private static int number(String token) {
        return token.startsWith("0x") || token.startsWith("0X")
                ? Integer.parseInt(token.substring(2), 16)
                : Integer.parseInt(token);
    }

    /** Every codepoint the atlas actually carries, read the way the rest of this plugin reads its JSON. */
    private static SortedSet<Integer> produced(String atlasJson) {
        SortedSet<Integer> out = new TreeSet<>();
        Matcher m = UNICODE.matcher(atlasJson);
        while (m.find()) {
            out.add(Integer.parseInt(m.group(1)));
        }
        return out;
    }
}
