package dev.vexelray.os.windows;

import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.windows.sys.User32;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A wake from sleep and a change of displays hand the window its icon again, as new handles: the taskbar rebuilds
 * its buttons then, and one it could not get an answer from stays generic until the icon changes. The mark itself
 * does not change. Read back with {@code WM_GETICON}, which is what the taskbar asks.
 */
class IconAfterWakeTest {

    private static final int WM_GETICON = 0x007F;
    private static final int PBT_APMSUSPEND = 0x0004;

    @Test
    void wakingAndAChangeOfDisplaysHandTheIconOverAgain() {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only");
        NativePlatform platform = NativePlatform.current();
        Icon mark = solid(0xFF3FC06A);
        platform.setApplicationIcon(mark);
        Win32Window window = (Win32Window) platform.createWindow(new WindowConfig("icon after wake", 320, 240, true));
        try {
            MemorySegment hwnd = MemorySegment.ofAddress(window.osHandle());
            long before = small(hwnd);
            assertNotEquals(0L, before, "the window has an icon to begin with");

            User32.sendMessageW(hwnd, User32.WM_POWERBROADCAST, PBT_APMSUSPEND, 0);
            assertEquals(before, small(hwnd), "going to sleep changes nothing");

            User32.sendMessageW(hwnd, User32.WM_POWERBROADCAST, User32.PBT_APMRESUMEAUTOMATIC, 0);
            long afterWake = small(hwnd);
            assertNotEquals(0L, afterWake, "awake: still an icon");
            assertNotEquals(before, afterWake, "awake: handed over again, as a new handle");
            assertSame(mark, window.wearing(), "awake: the same mark");

            User32.sendMessageW(hwnd, User32.WM_DISPLAYCHANGE, 32, 0);
            assertNotEquals(afterWake, small(hwnd), "displays changed: handed over again");
            assertSame(mark, window.wearing(), "displays changed: the same mark");
        } finally {
            window.close();
            platform.setApplicationIcon(null);
        }
    }

    private static long small(MemorySegment hwnd) {
        return User32.sendMessageW(hwnd, WM_GETICON, User32.ICON_SMALL, 0);
    }

    private static Icon solid(int argb) {
        int[] pixels = new int[16 * 16];
        java.util.Arrays.fill(pixels, argb);
        return Icon.of(new Icon.Image(16, 16, pixels));
    }
}
