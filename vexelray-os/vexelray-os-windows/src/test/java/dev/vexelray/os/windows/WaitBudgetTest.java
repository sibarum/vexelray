package dev.vexelray.os.windows;

import dev.vexelray.os.windows.sys.User32;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How a nanosecond sleep budget becomes the millisecond timeout Win32 will take.
 *
 * <p>Small enough to look like it needs no test, and it has two failure modes that are invisible until
 * something is wrong at 3 a.m.: rounding up misses a deadline, and rounding a sub-millisecond budget to
 * zero turns the wait back into the spin it exists to remove.
 */
class WaitBudgetTest {

    @Test
    @DisplayName("an indefinite budget becomes INFINITE, not a very long finite wait")
    void foreverIsInfinite() {
        assertEquals(User32.INFINITE, Win32Window.millisFor(Long.MAX_VALUE));
    }

    @Test
    @DisplayName("rounding is down, because waking early costs a pass and waking late costs a deadline")
    void roundsDown() {
        assertEquals(16, Win32Window.millisFor(16_999_999L));
        assertEquals(33, Win32Window.millisFor(33_333_333L));   // one 30 Hz frame
    }

    @Test
    @DisplayName("a sub-millisecond budget never rounds to zero, which would be a spin")
    void neverZero() {
        assertEquals(1, Win32Window.millisFor(1L));
        assertEquals(1, Win32Window.millisFor(999_999L));
    }
}
