package dev.vexelray.os.windows;

import dev.vexelray.os.BackgroundRun;
import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The background icon is worn from hide() to show(), by the window and, once every window is hidden, by the
 * notification area; and minimizing is not hiding. A background run (the module's surefire configuration asks for
 * one), so nothing of it reaches the screen.
 */
class BackgroundIconTest {

    @Test
    void aHiddenWindowAndAnIdleTrayWearTheBackgroundIcon() {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only");
        assertTrue(BackgroundRun.active(), "the surefire configuration asks for a background run");
        NativePlatform platform = NativePlatform.current();
        Icon primary = solid(0xFF3FC06A);
        Icon background = solid(0x993FC06A);
        platform.setApplicationIcon(primary);
        platform.setBackgroundIcon(background);
        Win32Window window = (Win32Window) platform.createWindow(new WindowConfig("background icon", 320, 240, true));
        try {
            window.show();
            window.pumpEvents();
            assertSame(primary, window.wearing(), "shown: the application icon");
            assertNotSame(background, Win32Tray.wearing(), "shown: the tray is not idle");

            window.hide();
            window.pumpEvents();
            assertSame(background, window.wearing(), "hidden: the background icon");
            assertSame(background, Win32Tray.wearing(), "every window hidden: the tray is idle");

            window.show();
            window.pumpEvents();
            assertSame(primary, window.wearing(), "shown again: the application icon back");
            assertNotSame(background, Win32Tray.wearing(), "shown again: the tray's own icon back");

            window.minimize();
            window.pumpEvents();
            assertSame(primary, window.wearing(), "minimized is not hidden");
            window.restore();
        } finally {
            window.close();
            platform.setBackgroundIcon(null);
            platform.setApplicationIcon(null);
        }
    }

    private static Icon solid(int argb) {
        int[] pixels = new int[16 * 16];
        java.util.Arrays.fill(pixels, argb);
        return Icon.of(new Icon.Image(16, 16, pixels));
    }
}
