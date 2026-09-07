package dev.vexelray.diag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsTest {

    @BeforeEach
    void silence() {
        Diagnostics.reset();
    }

    @Test
    void aDropIsRecordedWithBothHalvesOfWhatItSays() {
        Diagnostics.dropped("k", "per-vertex colour", "this technique reads no albedo from the buffer");
        List<String> recorded = Diagnostics.recorded();
        assertEquals(1, recorded.size());
        assertTrue(recorded.get(0).contains("per-vertex colour"), "the message must say what was dropped");
        assertTrue(recorded.get(0).contains("no albedo"), "and why, or it sends a reader to the wrong file");
    }

    @Test
    void oneKeyWarnsOnce() {
        for (int i = 0; i < 100; i++) {
            Diagnostics.dropped("seam", "a capability", "a reason");
        }
        assertEquals(1, Diagnostics.recorded().size(),
                "these sit at seams that run per frame; a warning that repeats is one that gets filtered out");
    }

    @Test
    void separateKeysEachWarn() {
        Diagnostics.dropped("one", "a", "r");
        Diagnostics.dropped("two", "b", "r");
        assertEquals(2, Diagnostics.recorded().size(), "warn-once is per key, not global");
    }

    @Test
    void aDataDerivedKeyIsCappedAndSaysSo() {
        for (int i = 0; i < Diagnostics.KEY_LIMIT + 50; i++) {
            Diagnostics.dropped("surface-" + i, "a capability", "a reason");
        }
        List<String> recorded = Diagnostics.recorded();
        assertEquals(Diagnostics.KEY_LIMIT + 1, recorded.size(),
                "the cap holds, and costs exactly one further message");
        assertTrue(recorded.get(recorded.size() - 1).contains("identify a call site"),
                "hitting the cap means a key is data-derived, so the overflow message must say that");
    }

    @Test
    void recordingSurvivesSilencing() {
        String previous = System.setProperty("vexelray.diag", "off");
        try {
            Diagnostics.dropped("k", "a capability", "a reason");
            assertEquals(1, Diagnostics.recorded().size(),
                    "a suite that silences the channel to keep its output clean must still be able to prove"
                            + " the warnings happen");
        } finally {
            if (previous == null) {
                System.clearProperty("vexelray.diag");
            } else {
                System.setProperty("vexelray.diag", previous);
            }
        }
    }

    @Test
    void aKeylessDropIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Diagnostics.dropped("  ", "a", "r"),
                "without a key there is nothing to warn once by, and the call would repeat every frame");
    }
}
