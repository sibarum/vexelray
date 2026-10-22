package dev.vexelray.os.windows;

import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.Platform;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.WorkArea;
import dev.supirvast.ffi.NativeException;
import dev.vexelray.os.windows.sys.Kernel32;
import dev.vexelray.os.windows.sys.User32;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The Windows {@link NativePlatform}: windows via {@code user32}/{@code kernel32} and a {@code VkSurfaceKHR} via
 * {@code VK_KHR_win32_surface}, all through direct Panama bindings (see {@code docs/native-bindings.md}).
 *
 * <p>{@link #createWindow} is backed by {@link Win32Window} (user32/kernel32 + VK_KHR_win32_surface).
 */
public final class WindowsPlatform implements NativePlatform {

    @Override
    public Platform platform() {
        return Platform.WINDOWS;
    }

    /** DXGI, always: see {@link NativePlatform#presenter()}. {@code vexelray-present-dxgi} provides it. */
    @Override
    public Optional<String> presenter() {
        return Optional.of("dxgi");
    }

    @Override
    public List<String> requiredVulkanInstanceExtensions() {
        return List.of("VK_KHR_surface", "VK_KHR_win32_surface");
    }

    @Override
    public NativeWindow createWindow(WindowConfig config) {
        return new Win32Window(config);
    }

    /**
     * The default mark for this process, realised per window as a pair of {@code HICON}s.
     *
     * <p>Windows has no process-level icon to set — the icon lives on the window, which is precisely why
     * per-window customization is available at all — so "the application's icon" is a VexelRay-level fact kept
     * here and pushed to each window that never chose its own.
     */
    @Override
    public void setApplicationIcon(Icon icon) {
        Win32Window.setApplicationIcon(icon);
    }

    /** Kept beside the application icon, and worn in its place by every hidden window and by an idle tray. */
    @Override
    public void setBackgroundIcon(Icon icon) {
        Win32Window.setBackgroundIcon(icon);
    }

    /**
     * In a native image, the arguments re-read from the UTF-16 command line ({@link CommandLine}): the image's own
     * decoding goes through the ANSI code page and loses whatever is outside it. Under the JVM, as given.
     */
    @Override
    public String[] arguments(String[] given) {
        return CommandLine.arguments(given);
    }

    /**
     * {@code MonitorFromRect} + {@code GetMonitorInfoW}: the work area of the monitor nearest a one-pixel rect at
     * {@code (x, y)}. Going through a rect rather than {@code MonitorFromPoint} keeps the call in terms of a
     * pointer to a struct this binding already models, instead of passing one by value.
     *
     * <p>Both the point asked about and the area returned are physical screen pixels, the same space
     * {@link NativeWindow#screenX()} reports, so nothing here needs to know about DPI scaling.
     */
    @Override
    public Optional<WorkArea> workArea(int x, int y) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment probe = User32.allocRect(temp, x, y, 1, 1);
            MemorySegment monitor = User32.monitorFromRect(probe);
            if (monitor.equals(MemorySegment.NULL)) {
                return Optional.empty();   // MONITOR_DEFAULTTONEAREST should prevent this; believe it anyway
            }
            MemorySegment info = temp.allocate(User32.MONITORINFO);
            if (!User32.getMonitorInfoW(monitor, info)) {
                return Optional.empty();
            }
            MemorySegment work = User32.monitorWorkRect(info);
            return Optional.of(new WorkArea(User32.rectLeft(work), User32.rectTop(work),
                    User32.rectSpanX(work), User32.rectSpanY(work)));
        } catch (NativeException e) {
            // Placement is a convenience: an application must not fail to launch over a monitor query.
            return Optional.empty();
        }
    }

    /**
     * {@code GetStdHandle} says whether there is one; if not, {@code CreateFileW} opens {@code file} for appending
     * and {@code SetStdHandle} makes it the standard error. A native image's runtime asks {@code GetStdHandle} each
     * time it writes a crash report, so the report lands there. The file is shared for reading, writing and
     * deletion, so it can be read and deleted while this process holds it, and it is held until the process ends.
     */
    @Override
    public boolean redirectStandardError(Path file) {
        MemorySegment current = Kernel32.getStdHandle(Kernel32.STD_ERROR_HANDLE);
        if (!current.equals(MemorySegment.NULL) && !current.equals(Kernel32.INVALID_HANDLE_VALUE)) {
            return false;
        }
        try (Arena temp = Arena.ofConfined()) {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            MemorySegment handle = Kernel32.createFileW(
                    temp.allocateFrom(file.toAbsolutePath().toString(), StandardCharsets.UTF_16LE),
                    Kernel32.FILE_APPEND_DATA, Kernel32.FILE_SHARE_ALL, Kernel32.OPEN_ALWAYS,
                    Kernel32.FILE_ATTRIBUTE_NORMAL);
            if (handle.equals(Kernel32.INVALID_HANDLE_VALUE)) {
                return false;
            }
            return Kernel32.setStdHandle(Kernel32.STD_ERROR_HANDLE, handle);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * {@code %LOCALAPPDATA%}, where Windows says per-user data goes; {@code AppData\Local} under the home folder when
     * it is not set.
     */
    @Override
    public Optional<Path> userDataDir() {
        return Optional.of(userDataDir(System::getenv, System.getProperty("user.home")));
    }

    /** As {@link #userDataDir()}, with the environment and the home folder given. */
    static Path userDataDir(Function<String, String> env, String home) {
        String local = env.apply("LOCALAPPDATA");
        return local != null && !local.isBlank() ? Path.of(local) : Path.of(home, "AppData", "Local");
    }
}
