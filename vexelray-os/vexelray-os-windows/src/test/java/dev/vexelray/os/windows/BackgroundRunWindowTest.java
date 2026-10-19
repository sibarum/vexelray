package dev.vexelray.os.windows;

import dev.supirvast.ffi.Ffi;
import dev.vexelray.os.BackgroundRun;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A background run's window, measured against what it promises the person at the desk: whatever it is asked to do,
 * the foreground stays where it was, the compositor never draws it, and no screen point is over it, so no real click
 * can land on it. The module's surefire configuration asks for a background run, so this runs in every build.
 */
class BackgroundRunWindowTest {

    private static final SymbolLookup USER32 = Ffi.library("user32");
    private static final SymbolLookup DWMAPI = Ffi.library("dwmapi");
    private static final MethodHandle GetForegroundWindow =
            Ffi.downcall(USER32, "GetForegroundWindow", FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle WindowFromPoint =
            Ffi.downcall(USER32, "WindowFromPoint", FunctionDescriptor.of(ADDRESS, JAVA_LONG));
    private static final MethodHandle GetAncestor =
            Ffi.downcall(USER32, "GetAncestor", FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle DwmGetWindowAttribute = Ffi.downcall(DWMAPI, "DwmGetWindowAttribute",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final int DWMWA_CLOAKED = 14;
    private static final int GA_ROOT = 2;

    @Test
    void nothingItIsAskedToDoTakesTheForegroundOrPutsItOnTheScreen() throws Throwable {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"), "Windows only");
        assertTrue(BackgroundRun.active(), "the surefire configuration asks for a background run");
        long before = foreground();
        NativeWindow window = NativePlatform.current().createWindow(
                new WindowConfig("background", 320, 240, true).at(200, 200));
        try {
            window.pumpEvents();
            check(window, before, "created");
            window.focus();
            window.pumpEvents();
            check(window, before, "focused");
            window.maximize();
            window.pumpEvents();
            check(window, before, "maximized");
            window.restore();
            window.pumpEvents();
            check(window, before, "restored");
            window.minimize();
            window.pumpEvents();
            assertEquals(before, foreground(), "minimized: the foreground moved");
            assertTrue(window.isMinimized(), "minimized: it should say so");
            window.restore();
            window.pumpEvents();
            check(window, before, "restored from minimized");
            assertFalse(window.isMinimized(), "restored from minimized: it should say so");
            window.fullscreen(true);
            window.pumpEvents();
            check(window, before, "full screen");
            window.fullscreen(false);
            window.pumpEvents();
            check(window, before, "back from full screen");
        } finally {
            window.close();
        }
    }

    private static void check(NativeWindow window, long before, String step) throws Throwable {
        assertTrue(window.isVisible(), step + ": the window should be shown, as far as Windows knows");
        assertEquals(before, foreground(), step + ": the foreground moved (this window is " + window.osHandle() + ")");
        assertNotEquals(0, cloaked(window.osHandle()), step + ": the compositor would draw it");
        int x = window.screenX() + window.outerWidth() / 2;
        int y = window.screenY() + window.outerHeight() / 2;
        assertNotEquals(window.osHandle(), rootAt(x, y), step + ": a click at its centre would land on it");
    }

    private static long foreground() throws Throwable {
        return ((MemorySegment) GetForegroundWindow.invokeExact()).address();
    }

    private static long rootAt(int x, int y) throws Throwable {
        long point = (x & 0xFFFFFFFFL) | ((long) y << 32);
        MemorySegment hit = (MemorySegment) WindowFromPoint.invokeExact(point);
        if (hit.address() == 0L) {
            return 0L;
        }
        return ((MemorySegment) GetAncestor.invokeExact(hit, GA_ROOT)).address();
    }

    private static int cloaked(long hwnd) throws Throwable {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment out = temp.allocate(JAVA_INT);
            int hr = (int) DwmGetWindowAttribute.invokeExact(MemorySegment.ofAddress(hwnd), DWMWA_CLOAKED, out, 4);
            assertEquals(0, hr, "DwmGetWindowAttribute(DWMWA_CLOAKED)");
            return out.get(JAVA_INT, 0);
        }
    }
}
