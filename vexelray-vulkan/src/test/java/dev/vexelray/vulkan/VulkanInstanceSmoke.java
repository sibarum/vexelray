package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.vulkan.vk.VulkanInstance;

import java.util.List;

/**
 * Manual smoke check (not a unit test): creates a real VkInstance through the hand-rolled Panama Vulkan loader,
 * enabling the active platform's required surface extensions, and lists the physical devices. Run explicitly —
 * it needs a Vulkan loader/driver and {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class VulkanInstanceSmoke {

    public static void main(String[] args) {
        NativePlatform platform = NativePlatform.current();
        List<String> extensions = platform.requiredVulkanInstanceExtensions();
        System.out.println("platform: " + platform.platform() + "  extensions: " + extensions);

        try (VulkanInstance instance = new VulkanInstance("VexelRay smoke", extensions)) {
            System.out.println("VkInstance created: 0x" + Long.toHexString(instance.handleAddress()));
            List<String> devices = instance.physicalDeviceNames();
            System.out.println("physical devices (" + devices.size() + "):");
            devices.forEach(name -> System.out.println("  - " + name));
        }
        System.out.println("instance destroyed cleanly");
    }
}
