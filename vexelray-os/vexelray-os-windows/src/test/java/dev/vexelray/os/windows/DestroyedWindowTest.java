package dev.vexelray.os.windows;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A window that has been closed answers what it last was and ignores commands, rather than calling Win32 on a
 * handle that no longer names anything. The calculator's settings window found this the hard way: a per-frame
 * reader still held the window a frame after the user closed it, {@code GetWindowRect} failed, and the frame
 * loop went down with it. The window is never shown, so this needs a desktop session but puts nothing on it.
 */
class DestroyedWindowTest {

    @Test
    void aClosedWindowAnswersWhatItLastWasAndIgnoresCommands() {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only");
        NativeWindow window = NativePlatform.current().createWindow(new WindowConfig("destroyed", 320, 240, true));
        int x = window.screenX();
        int y = window.screenY();
        int width = window.outerWidth();
        int height = window.outerHeight();

        window.close();

        assertEquals(x, window.screenX());
        assertEquals(y, window.screenY());
        assertEquals(width, window.outerWidth());
        assertEquals(height, window.outerHeight());
        assertFalse(window.isMinimized());
        assertFalse(window.isMaximized());
        assertFalse(window.isVisible());
        assertFalse(window.isFocused());
        assertFalse(window.pumpEvents());
        // Each of these reached Win32 with a dead handle before; none of them may throw now.
        window.setBounds(10, 10, 200, 200);
        window.setPosition(20, 20);
        window.show();
        window.hide();
        window.focus();
        window.minimize();
        window.maximize();
        window.restore();
        window.setEnabled(false);
        window.requestClose();
        window.postWake();
        window.waitEvents(1_000_000L);
        window.close();
        assertEquals(width, window.outerWidth());
        assertThrows(IllegalStateException.class, () -> window.createVulkanSurface(0L, null));
    }
}
