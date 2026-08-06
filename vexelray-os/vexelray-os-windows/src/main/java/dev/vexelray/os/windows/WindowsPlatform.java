package dev.vexelray.os.windows;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.Platform;
import dev.vexelray.os.WindowConfig;

import java.util.List;

/**
 * The Windows {@link NativePlatform}: windows via {@code user32}/{@code kernel32} and a {@code VkSurfaceKHR} via
 * {@code VK_KHR_win32_surface}, all through direct Panama bindings (see {@code docs/native-bindings.md}).
 *
 * <p>Scaffold state: the platform identity and required Vulkan instance extensions are complete (pure data);
 * {@link #createWindow} awaits the {@code user32}/{@code kernel32} bindings and is the next implementation step.
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
        throw new UnsupportedOperationException(
                "Windows windowing not yet implemented — user32/kernel32 bindings pending "
                        + "(see docs/native-bindings.md §4)");
    }
}
