package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;
import dev.vexelray.vulkan.vk.VkLoader;

/**
 * Manual smoke check (not a unit test): the full OS↔Vulkan loop — open a window, create an instance, mint a
 * Vulkan surface from the window, pick a graphics+present device, create the logical device and its queue, then
 * tear everything down in order. Run explicitly with {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class SurfaceDeviceSmoke {

    public static void main(String[] args) {
        NativePlatform platform = NativePlatform.current();

        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay surface", 800, 600, true));
             VulkanInstance instance = new VulkanInstance("VexelRay smoke", platform.requiredVulkanInstanceExtensions())) {

            long surface = window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
            System.out.println("surface created: 0x" + Long.toHexString(surface));
            try {
                VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                        .orElseThrow(() -> new IllegalStateException("no graphics+present device for this surface"));
                System.out.println("selected: " + selection.deviceName()
                        + " (queue family " + selection.queueFamilyIndex() + ")");

                try (VulkanDevice device = new VulkanDevice(instance.handle(), selection)) {
                    System.out.println("VkDevice created: 0x" + Long.toHexString(device.handle().address())
                            + "  queue: 0x" + Long.toHexString(device.queue().address()));
                    device.waitIdle();
                }
            } finally {
                instance.destroySurface(surface);
            }
        }
        System.out.println("surface + device torn down cleanly");
    }
}
