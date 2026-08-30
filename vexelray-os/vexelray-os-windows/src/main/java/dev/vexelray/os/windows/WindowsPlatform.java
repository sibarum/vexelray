package dev.vexelray.os.windows;

import dev.vexelray.os.Icon;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.Platform;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.WorkArea;
import dev.vexelray.os.ffi.NativeException;
import dev.vexelray.os.windows.sys.User32;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;

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
}
