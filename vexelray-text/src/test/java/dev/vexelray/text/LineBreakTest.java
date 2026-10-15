package dev.vexelray.text;

import dev.vexelray.text.TextLayout.LineSpan;
import dev.vexelray.text.TextLayout.WrapMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where word wrapping may break a line, over a monospace of one px per character so widths are character counts.
 */
class LineBreakTest {

    private static List<String> lines(String text, float width) {
        List<String> out = new ArrayList<>();
        for (LineSpan span : TextLayout.breakLineSpans(text, (index, cp) -> 1f, width, WrapMode.WORD_CHAR)) {
            out.add(text.substring(span.start(), span.end()).strip());
        }
        return out;
    }

    @Test
    void aCommaNeverStartsALine() {
        // "close first" fills the 11 px line exactly; the comma after it must take "first" down with it.
        List<String> got = lines("close first, and open again", 11f);
        assertEquals("close", got.get(0));
        assertEquals("first, and", got.get(1));
        for (String line : got) {
            assertFalse(line.startsWith(","), () -> "a line starts with a comma: " + got);
        }
    }

    @Test
    void closingPunctuationStaysWithItsWord() {
        for (String text : List.of("one two.", "one two!", "one two?", "one two;", "one (two)", "one two…")) {
            for (String line : lines(text, 7f)) {
                char first = line.charAt(0);
                assertTrue(Character.isLetterOrDigit(first) || first == '(', () -> text + " -> " + lines(text, 7f));
            }
        }
    }

    @Test
    void anOpeningBracketNeverEndsALine() {
        assertFalse(TextLayout.canBreakBetween("(note", 1));
        assertFalse(TextLayout.canBreakBetween("first,", 5));
        // The secondary breaks a long unbroken run relies on are still there.
        assertTrue(TextLayout.canBreakBetween("path/to", 5));
        assertTrue(TextLayout.canBreakBetween("abc123", 3));
    }
}
