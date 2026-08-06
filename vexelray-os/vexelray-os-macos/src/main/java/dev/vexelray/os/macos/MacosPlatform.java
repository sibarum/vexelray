package dev.vexelray.os.macos;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.Platform;
import dev.vexelray.os.WindowConfig;

import java.util.List;

/**
 * The macOS {@link NativePlatform}: an {@code NSWindow} backed by a {@code CAMetalLayer} (AppKit/QuartzCore) and
 * a {@code VkSurfaceKHR} via {@code VK_EXT_metal_surface} on MoltenVK.
 *
 * <p>Skeleton — present from day one so the multi-platform contract holds, with the binding implementation to
 * follow per {@code docs/native-bindings.md}. Reports its surface extensions (pure data) already.
 */
public final class MacosPlatform implements NativePlatform {

    @Override
    public Platform platform() {
        return Platform.MACOS;
    }

    @Override
    public List<String> requiredVulkanInstanceExtensions() {
        return List.of("VK_KHR_surface", "VK_EXT_metal_surface");
    }

    @Override
    public NativeWindow createWindow(WindowConfig config) {
        throw new UnsupportedOperationException(
                "macOS windowing not yet implemented — AppKit/QuartzCore bindings pending "
                        + "(see docs/native-bindings.md §4)");
    }
}
