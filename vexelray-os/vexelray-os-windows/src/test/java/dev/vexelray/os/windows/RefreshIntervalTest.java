package dev.vexelray.os.windows;

import dev.vexelray.os.windows.sys.Dwmapi;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The display's refresh interval, as the compositor reports it. The range is the only thing asserted: a value is not
 * checked against a particular panel, but 24 Hz to 1 kHz covers every display there is, and what would fail it is the
 * mistake that was made once already, reading the struct at the wrong offsets, which gives nonsense.
 */
class RefreshIntervalTest {

    @Test
    void theCompositorReportsAnIntervalOfARealDisplay() {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only");
        long nanos = Dwmapi.refreshIntervalNanos();
        Assumptions.assumeTrue(nanos != 0L, "no compositor to ask: " + Dwmapi.timingFailure());
        assertTrue(nanos >= 1_000_000L && nanos <= 41_700_000L,
                "a refresh of " + nanos + " ns is not a display's");
    }
}
