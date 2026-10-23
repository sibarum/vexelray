package dev.vexelray.os.linux;

import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.Platform;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.WorkArea;
import dev.vexelray.os.linux.sys.X11;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The Linux {@link NativePlatform}: X11 windowing via {@code libX11} and a {@code VkSurfaceKHR} via
 * {@code VK_KHR_xlib_surface}. Under a Wayland session it runs on XWayland; a native Wayland variant
 * ({@code VK_KHR_wayland_surface}) is later work.
 */
public final class LinuxPlatform implements NativePlatform {

    @Override
    public Platform platform() {
        return Platform.LINUX;
    }

    @Override
    public List<String> requiredVulkanInstanceExtensions() {
        return List.of("VK_KHR_surface", "VK_KHR_xlib_surface");
    }

    @Override
    public NativeWindow createWindow(WindowConfig config) {
        return new X11Window(config);
    }

    @Override
    public void setApplicationIcon(Icon icon) {
        X11Window.setApplicationIcon(icon);
    }

    /**
     * {@code _NET_WORKAREA} of the current desktop. X11 reports one work area spanning every monitor, so this is
     * the same answer for any point; a point outside it gets it too, which is the "primary monitor" fallback the
     * contract asks for.
     */
    @Override
    public Optional<WorkArea> workArea(int x, int y) {
        var c = X11Window.connection();
        long atom = X11.internAtom(c.display(), "_NET_WORKAREA");
        long[] area = X11.getLongProperty(c.display(), c.root(), atom, X11.XA_CARDINAL, 4);
        if (area.length < 4 || area[2] <= 0 || area[3] <= 0) {
            return Optional.empty();
        }
        return Optional.of(new WorkArea((int) area[0], (int) area[1], (int) area[2], (int) area[3]));
    }

    /** The desktop's animations turned off, as the XDG desktop portal reports it for GNOME or KDE. */
    @Override
    public Optional<Boolean> reducedMotion() {
        return MotionPreference.reducedMotion();
    }

    /**
     * {@code $XDG_DATA_HOME}, or {@code ~/.local/share} where it is unset or empty: the XDG base directory rule, and
     * the one {@code vexelray-installer}'s {@code install.sh} applies ({@code ${XDG_DATA_HOME:-$HOME/.local/share}}).
     */
    @Override
    public Optional<Path> userDataDir() {
        return Optional.of(userDataDir(System::getenv, System.getProperty("user.home")));
    }

    /** As {@link #userDataDir()}, with the environment and the home folder given. */
    static Path userDataDir(Function<String, String> env, String home) {
        String data = env.apply("XDG_DATA_HOME");
        return data != null && !data.isBlank() ? Path.of(data) : Path.of(home, ".local", "share");
    }
}
