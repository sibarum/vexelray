package dev.vexelray.os.linux;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.Platform;
import dev.vexelray.os.WindowConfig;

import java.util.List;

/**
 * The Linux {@link NativePlatform}: X11 windowing via {@code libX11} and a {@code VkSurfaceKHR} via
 * {@code VK_KHR_xlib_surface} (Wayland/{@code VK_KHR_wayland_surface} is a later runtime variant).
 *
 * <p>Skeleton — present from day one so the multi-platform contract holds, with the binding implementation to
 * follow per {@code docs/native-bindings.md}. Reports its surface extensions (pure data) already.
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
        throw new UnsupportedOperationException(
                "Linux windowing not yet implemented — libX11 bindings pending (see docs/native-bindings.md §4)");
    }
}
