package dev.vexelray.os.windows;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Full screen is a state, not a placement: the window covers its monitor, the placement queries still answer for the
 * window it goes back to, and going back puts it where it was. The window is never shown, so this needs a desktop
 * session but puts nothing on it.
 */
class FullscreenWindowTest {

    @Test
    void coversTheMonitorAndGoesBackWhereItWas() {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only");
        NativeWindow window = NativePlatform.current().createWindow(
                new WindowConfig("fullscreen", 320, 240, true).at(100, 120));
        try {
            int x = window.screenX();
            int y = window.screenY();
            int width = window.outerWidth();
            int height = window.outerHeight();
            int clientWidth = window.width();

            window.fullscreen(true);
            window.pumpEvents();

            assertTrue(window.isFullscreen());
            assertTrue(window.width() > clientWidth, "the client area is the monitor's: " + window.width());
            assertEquals(x, window.screenX(), "placement answers for the window it goes back to");
            assertEquals(y, window.screenY());
            assertEquals(width, window.outerWidth());
            assertEquals(height, window.outerHeight());
            assertFalse(window.isMaximized());
            window.fullscreen(true);   // already: nothing
            assertTrue(window.isFullscreen());

            window.fullscreen(false);
            window.pumpEvents();

            assertFalse(window.isFullscreen());
            assertEquals(x, window.screenX());
            assertEquals(y, window.screenY());
            assertEquals(width, window.outerWidth());
            assertEquals(height, window.outerHeight());
            assertEquals(clientWidth, window.width());
        } finally {
            window.close();
        }
    }

    @Test
    void aWindowClosedFullScreenRemembersTheWindowItWas() {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only");
        NativeWindow window = NativePlatform.current().createWindow(
                new WindowConfig("fullscreen", 320, 240, true).at(140, 160));
        int width = window.outerWidth();
        window.fullscreen(true);
        window.close();
        assertEquals(140, window.screenX());
        assertEquals(160, window.screenY());
        assertEquals(width, window.outerWidth());
        window.fullscreen(false);   // a dead handle: nothing
    }
}
