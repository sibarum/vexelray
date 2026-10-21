package dev.vexelray.os;

import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The single service each platform module provides — VexelRay's entire OS surface behind one interface. The
 * engine resolves the active implementation once via {@link #current()}; because the Maven build places exactly
 * one {@code vexelray-os-<platform>} module on the classpath, {@link ServiceLoader} finds exactly one provider.
 *
 * <p>Deliberately tiny: an engine needs a window, its framebuffer size, an event pump, and a way to mint a
 * {@code VkSurfaceKHR}. Everything platform-specific (Win32/X11/Wayland/Cocoa) lives inside the implementation;
 * nothing here names an OS. See {@code docs/native-bindings.md}.
 */
public interface NativePlatform {

    /** Which platform this implementation targets. */
    Platform platform();

    /**
     * The Vulkan <em>instance</em> extensions this platform needs for presentation — e.g. on Windows
     * {@code [VK_KHR_surface, VK_KHR_win32_surface]}. The Vulkan runtime enables exactly what the active
     * platform reports, so it never hard-codes a platform extension.
     */
    List<String> requiredVulkanInstanceExtensions();

    /**
     * Create a native window and, unless the config asked for {@link WindowConfig#hidden()}, put it on screen.
     * The returned window owns its OS resources until {@link NativeWindow#close()}.
     */
    NativeWindow createWindow(WindowConfig config);

    /**
     * Set the mark every window of this process wears unless it asked for its own — the application icon.
     *
     * <p>Two levels rather than one, because applications genuinely have both: nearly every window should look
     * like the application, and the few that should not (a tool palette, a preferences window, a second
     * document of a different type) should be able to say so without every other window repeating the default.
     * A window's own {@link WindowConfig#icon()} or {@link NativeWindow#setIcon} always wins; this fills in
     * behind them.
     *
     * <p>Applies to windows already open as well as to windows opened later, so an application may set it once
     * at startup or change it mid-session (a theme switch, a per-profile mark) and have every window that never
     * chose for itself follow along. Passing {@code null} clears it back to whatever the OS gives an
     * application that names no icon.
     *
     * <p><b>Not the executable icon.</b> What a file browser shows for the program on disk, and what a pinned
     * taskbar shortcut shows before the program runs, are properties of the packaged binary — resources linked
     * into the {@code .exe}, an {@code .app} bundle's {@code Icns} — and no running process can change them.
     * This is the icon of the running application, which is the one an engine can honestly offer.
     *
     * <p>Defaults to a no-op, so a platform that has not implemented icons keeps the OS default rather than
     * failing on a cosmetic request.
     */
    default void setApplicationIcon(Icon icon) {
        // no-op by default
    }

    /**
     * Set the mark the application wears while nothing of it is on screen — the background icon. A window wears it
     * between {@link NativeWindow#hide()} and the next {@link NativeWindow#show()}, whatever icon it wears otherwise;
     * a {@link BackgroundRun background run}'s notification-area icon wears it while every window of the process is
     * hidden, and the application icon again as soon as one is shown.
     *
     * <p>The suite draws one for every application: the primary mark dimmed, inside a dashed rim in the
     * application's hue, so a program still running with no window up is told apart at a glance from one in front
     * of you. A window that is only minimized is not hidden, and keeps its own mark.
     *
     * <p>{@code null}, the default, means there is none: a hidden window and the notification area keep the mark
     * they had. Applies at once to whatever is hidden now. Defaults to a no-op, like {@link #setApplicationIcon}.
     */
    default void setBackgroundIcon(Icon icon) {
        // no-op by default
    }

    /**
     * The usable area of the monitor containing the screen point {@code (x, y)} — its full rectangle minus the
     * taskbar or dock — or of the primary monitor when that point is on no monitor at all. That last case is the
     * whole reason this exists: an application restoring saved window bounds has to ask whether the screen it
     * saved them on is still there.
     *
     * <p>{@link Optional#empty()} means this platform cannot say, and the caller should place the window as it
     * would have without the query. A platform lacking the binding degrades to the old behaviour rather than to
     * a wrong answer.
     */
    default Optional<WorkArea> workArea(int x, int y) {
        return Optional.empty();
    }

    /**
     * The presenter every window on this platform must present through, by its {@code PresenterProvider} name, or
     * empty for a Vulkan swapchain. A platform that names one does not start without it: on Windows that is DXGI,
     * because a swapchain recreated on resize is what DXGI replaces there (on some machines it freezes the desktop).
     */
    default Optional<String> presenter() {
        return Optional.empty();
    }

    /**
     * The arguments {@code main} was handed, as the operating system actually holds them.
     *
     * <p>Exists for one platform's sake: a GraalVM native image on Windows decodes its arguments through the ANSI
     * code page, so a character outside it ({@code ’}, {@code 窯}) arrives as another character, and a path with
     * one in it names another file, or none. The java launcher has no such problem, so this only ever shows in the
     * native build, which is where an application is actually used. Windows reads the command line back in UTF-16;
     * everywhere else, and under the JVM, the arguments come back as given.
     *
     * <p>{@code given} may already have had its blank arguments dropped, as the suite's applications do before
     * starting; the re-read is matched against it with and without blanks, and when neither matches it in number it
     * is not the same arguments, and {@code given} is returned.
     */
    default String[] arguments(String[] given) {
        return given;
    }

    /**
     * The one OS platform on the classpath. Throws if none is present (no {@code vexelray-os-<platform>} module
     * was selected by the build) — a clear, early failure rather than a mysterious later one.
     */
    static NativePlatform current() {
        return ServiceLoader.load(NativePlatform.class).findFirst().orElseThrow(() ->
                new IllegalStateException("no dev.vexelray.os.NativePlatform on the classpath — "
                        + "is a vexelray-os-<platform> module present for this OS?"));
    }
}
