package dev.vexelray.os.windows;

import dev.vexelray.os.Decorations;
import dev.vexelray.os.HitRegions;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.windows.sys.User32;

import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * Manual smoke check (not a unit test, per the convention in {@link Win32WindowSmoke}): opens a real window with
 * application-drawn chrome and interrogates it the way Windows does.
 *
 * <p>{@link HitRegionsTest} in the API module proves the region <em>policy</em> in pure Java. What only a live
 * window can prove is the Win32 half: that {@code WM_NCCALCSIZE} really did hand the whole window to the client
 * area, and that {@code WM_NCHITTEST} really answers from the pushed regions. Both are asked here by sending the
 * window the same messages the window manager sends it, so the answers come from the actual window procedure.
 *
 * <p>Run explicitly — needs a desktop session and {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class Win32ClientChromeSmoke {

    private static final int W = 500;
    private static final int H = 360;
    private static final int BAR_H = 32;
    private static final int BUTTON_W = 46;
    /** Comfortably above Windows' own metric, so an answer of exactly these came from the config. */
    private static final int MIN_W = 320;
    private static final int MIN_H = 240;

    private static int failures;

    public static void main(String[] args) {
        NativePlatform platform = NativePlatform.current();
        WindowConfig config = new WindowConfig("VexelRay chrome smoke", W, H, true)
                .at(200, 200)
                .decorations(Decorations.CLIENT)
                .minSize(MIN_W, MIN_H);

        try (NativeWindow window = platform.createWindow(config)) {
            window.pumpEvents();

            // 1. WM_NCCALCSIZE: the client area is the whole window, so there is no system title bar left to see.
            check("client width == outer width", window.width(), window.outerWidth());
            check("client height == outer height", window.height(), window.outerHeight());

            // 2. WM_NCHITTEST, asked of the live window procedure at points in its own client space.
            int right = window.width();
            window.setHitRegions(new HitRegions(
                    List.of(new HitRegions.Rect(0, 0, right, BAR_H)),
                    List.of(new HitRegions.Rect(right - BUTTON_W, 0, BUTTON_W, BAR_H)),
                    null,
                    8));

            check("title bar drags the window", hitTest(window, right / 2, 16), User32.HTCAPTION);
            check("a button on it does not", hitTest(window, right - 20, 16), User32.HTCLIENT);
            check("content is content", hitTest(window, right / 2, 200), User32.HTCLIENT);
            check("the top edge still resizes", hitTest(window, right / 2, 2), User32.HTTOP);
            check("and so does the top-left corner", hitTest(window, 2, 2), User32.HTTOPLEFT);
            check("and the bottom-right corner", hitTest(window, right - 2, window.height() - 2),
                    User32.HTBOTTOMRIGHT);

            // 3. The window commands an application-drawn button would call.
            check("not maximized to begin with", window.isMaximized(), false);
            window.maximize();
            window.pumpEvents();
            check("maximize took", window.isMaximized(), true);
            check("maximized client stays on the monitor", window.height() > 0 && window.height() < 4000, true);
            window.restore();
            window.pumpEvents();
            check("restore took", window.isMaximized(), false);
            check("restored to the size it had", window.outerWidth(), W);

            // 4. WM_GETMINMAXINFO: how small the window manager will let this window be dragged, and the only
            // place it can be told -- a drag runs inside Windows' own loop, so a size refused after the fact is
            // one the user has already seen. Asked the same way the hit test is, by sending the real message.
            try (java.lang.foreign.Arena temp = java.lang.foreign.Arena.ofConfined()) {
                MemorySegment info = User32.allocMinMaxInfo(temp);
                User32.sendMessageW(MemorySegment.ofAddress(window.osHandle()),
                        User32.WM_GETMINMAXINFO, 0L, info.address());
                check("the minimum width was answered", User32.minTrackWidth(info), MIN_W);
                check("the minimum height was answered", User32.minTrackHeight(info), MIN_H);
            }
            // And the consequence, which is what the minimum is for: a resize below it is clamped, and clamped
            // by the window manager rather than by anything here.
            window.setBounds(200, 200, MIN_W / 2, MIN_H / 2);
            window.pumpEvents();
            check("a resize below it is clamped", window.outerWidth(), MIN_W);
            check("on both axes", window.outerHeight(), MIN_H);

            // 5. requestClose travels the ordinary route, so the pump reports it.
            window.requestClose();
            check("close is observed by the pump", window.pumpEvents(), false);
        }

        System.out.println(failures == 0 ? "client chrome OK" : failures + " CHECK(S) FAILED");
        if (failures > 0) {
            System.exit(1);
        }
    }

    /** Ask the window what is at client {@code (x, y)}, in the coordinates Windows would use: screen space. */
    private static long hitTest(NativeWindow window, int x, int y) {
        int sx = window.screenX() + x;
        int sy = window.screenY() + y;
        long lParam = ((long) (sy & 0xFFFF) << 16) | (sx & 0xFFFF);
        return User32.sendMessageW(MemorySegment.ofAddress(window.osHandle()), User32.WM_NCHITTEST, 0L, lParam);
    }

    private static void check(String what, Object actual, Object expected) {
        boolean ok = String.valueOf(actual).equals(String.valueOf(expected));
        if (!ok) {
            failures++;
        }
        System.out.println((ok ? "  ok   " : "  FAIL ") + what
                + (ok ? "" : " — expected " + expected + ", got " + actual));
    }
}
