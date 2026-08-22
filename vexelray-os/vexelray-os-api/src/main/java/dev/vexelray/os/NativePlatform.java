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

    /** Create and show a native window. The returned window owns its OS resources until {@link NativeWindow#close()}. */
    NativeWindow createWindow(WindowConfig config);

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
     * The one OS platform on the classpath. Throws if none is present (no {@code vexelray-os-<platform>} module
     * was selected by the build) — a clear, early failure rather than a mysterious later one.
     */
    static NativePlatform current() {
        return ServiceLoader.load(NativePlatform.class).findFirst().orElseThrow(() ->
                new IllegalStateException("no dev.vexelray.os.NativePlatform on the classpath — "
                        + "is a vexelray-os-<platform> module present for this OS?"));
    }
}
