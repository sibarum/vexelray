package dev.vexelray.os.windows;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.Platform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the ServiceLoader wiring and the platform's static contract. Pure Java — no native calls — so it runs
 * on any host: it proves the provider is discoverable and reports the right Vulkan surface extensions, which is
 * the selection mechanism the engine relies on.
 */
class WindowsPlatformTest {

    @Test
    void providerIsDiscoverableViaServiceLoader() {
        NativePlatform platform = NativePlatform.current();
        assertEquals(Platform.WINDOWS, platform.platform());
    }

    @Test
    void reportsWin32SurfaceExtensions() {
        NativePlatform platform = NativePlatform.current();
        assertTrue(platform.requiredVulkanInstanceExtensions().contains("VK_KHR_surface"));
        assertTrue(platform.requiredVulkanInstanceExtensions().contains("VK_KHR_win32_surface"));
    }
}
