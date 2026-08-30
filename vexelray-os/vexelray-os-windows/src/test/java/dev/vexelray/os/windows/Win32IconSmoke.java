package dev.vexelray.os.windows;

import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.windows.sys.User32;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;

/**
 * Manual smoke check (not a unit test): opens three windows in one process and gives each a different mark —
 * one inheriting the application icon, one overriding it at creation, one changing it while it is on screen.
 * Run explicitly; it needs a desktop session and {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p>What to look for, because none of it can be asserted from inside the process: the three taskbar buttons and
 * the three title bars show three different marks, the third window's mark changes after a couple of seconds,
 * and the marks have soft edges rather than a halo (which is what a lost alpha channel looks like).
 *
 * <p>Pass image files as arguments to use real artwork — {@code Win32IconSmoke icon-16.png icon-32.png} — or
 * none to use the generated marks below, so the check runs with no assets to hand.
 */
public final class Win32IconSmoke {

    public static void main(String[] args) throws InterruptedException {
        NativePlatform platform = NativePlatform.current();

        Icon appIcon = args.length > 0 ? filesToIcon(args) : generated(0xFF3DDC84);   // green
        Icon toolIcon = generated(0xFFE8A33D);   // amber
        Icon alertIcon = generated(0xFFE05252);  // red

        platform.setApplicationIcon(appIcon);

        try (NativeWindow main = platform.createWindow(new WindowConfig("Inherits the app icon", 480, 320, true));
             NativeWindow tool = platform.createWindow(
                     WindowConfig.of("Its own icon", 360, 240).at(560, 80).icon(toolIcon));
             NativeWindow live = platform.createWindow(
                     WindowConfig.of("Changes its icon shortly", 360, 240).at(560, 380))) {

            System.out.println("three windows open — compare their taskbar buttons and title bars");
            report("main", main);
            report("tool", tool);
            report("live", live);
            pumpFor(2_000, main, tool, live);

            long before = iconHandle(live, User32.ICON_BIG);
            live.setIcon(alertIcon);
            report("live (re-iconed)", live);
            System.out.println("  big icon changed: " + (iconHandle(live, User32.ICON_BIG) != before));
            pumpFor(4_000, main, tool, live);
        }
        System.out.println("closed cleanly");
    }

    /** {@code WM_GETICON} — asks a window for the handle in one of its two slots. Read-back only, for this check. */
    private static final int WM_GETICON = 0x007F;

    /**
     * Print what the window itself says it is wearing. The pixels can only be judged by eye, but "did an icon
     * reach the window at all" is a fact the process can check, and it is the half that fails silently: a
     * WM_SETICON that never happened looks exactly like the OS default.
     */
    private static void report(String label, NativeWindow window) {
        System.out.printf("%-16s big=0x%s small=0x%s%n", label,
                Long.toHexString(iconHandle(window, User32.ICON_BIG)),
                Long.toHexString(iconHandle(window, User32.ICON_SMALL)));
    }

    private static long iconHandle(NativeWindow window, int which) {
        return User32.sendMessageW(MemorySegment.ofAddress(window.osHandle()), WM_GETICON, which, 0);
    }

    private static void pumpFor(long millis, NativeWindow... windows) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            for (NativeWindow window : windows) {
                window.pumpEvents();
            }
            Thread.sleep(8);
        }
    }

    private static Icon filesToIcon(String[] args) {
        Path[] paths = new Path[args.length];
        for (int i = 0; i < args.length; i++) {
            paths[i] = Path.of(args[i]);
        }
        return Icon.fromFiles(paths);
    }

    /**
     * A drawn-at-this-size mark in two sizes: a filled disc with a hole, which is enough to show whether the
     * alpha channel survived (the corners and the hole must be the desktop, not black).
     */
    private static Icon generated(int argb) {
        return Icon.of(disc(16, argb), disc(32, argb));
    }

    private static Icon.Image disc(int size, int argb) {
        int[] pixels = new int[size * size];
        double centre = (size - 1) / 2.0;
        double outer = size * 0.46;
        double inner = size * 0.20;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                double dx = x - centre;
                double dy = y - centre;
                double r = Math.sqrt(dx * dx + dy * dy);
                // One pixel of falloff on both edges, so the check can tell partial alpha from none at all.
                double coverage = Math.min(clamp(outer - r), clamp(r - inner));
                int alpha = (int) Math.round(coverage * 255);
                pixels[y * size + x] = alpha << 24 | (argb & 0x00FFFFFF);
            }
        }
        return new Icon.Image(size, size, pixels);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private Win32IconSmoke() {
    }
}
