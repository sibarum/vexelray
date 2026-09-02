package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.vulkan.vk.VulkanDebugMessenger;
import dev.vexelray.vulkan.vk.VulkanInstance;

import java.util.List;

/**
 * Manual smoke check (not a unit test): proves the {@code VK_EXT_debug_utils} plumbing end to end without
 * needing the Vulkan SDK installed. The messenger extension comes from the loader, so it is present on any
 * machine that can run Vulkan at all, while {@code VK_LAYER_KHRONOS_validation} ships with the SDK and usually
 * is not — which would otherwise leave the callback, the struct layouts, and the {@code const char*} reads
 * completely untested on a developer box.
 *
 * <p>{@code vkSubmitDebugUtilsMessageEXT} closes that gap: it pushes a message through the real messenger chain
 * exactly as a layer would. If the layouts are wrong or the upcall is misbound, this crashes or prints garbage
 * rather than the two lines below.
 *
 * <p>Run with {@code -Dvexelray.vulkan.validation --enable-native-access=ALL-UNNAMED}.
 */
public final class DebugMessengerSmoke {

    public static void main(String[] args) {
        NativePlatform platform = NativePlatform.current();
        List<String> extensions = platform.requiredVulkanInstanceExtensions();

        try (VulkanInstance instance = new VulkanInstance("VexelRay debug messenger", extensions)) {
            VulkanDebugMessenger messenger = instance.debugMessenger().orElseThrow(() -> new IllegalStateException(
                    "no debug messenger — run with -Dvexelray.vulkan.validation"));

            long before = VulkanDebugMessenger.errorCount();

            System.out.println("-- submitting a warning (should print, must not count as an error)");
            messenger.submit(VulkanDebugMessenger.WARNING, "VexelRaySelfCheck", "a warning routed through the messenger");
            if (VulkanDebugMessenger.errorCount() != before) {
                throw new IllegalStateException("a warning was counted as an error");
            }

            System.out.println("-- submitting an error (should print and increment the error count)");
            messenger.submit(VulkanDebugMessenger.ERROR, "VexelRaySelfCheck", "an error routed through the messenger");
            if (VulkanDebugMessenger.errorCount() != before + 1) {
                throw new IllegalStateException("the error was not counted: "
                        + before + " -> " + VulkanDebugMessenger.errorCount());
            }

            System.out.println("-- failOnError must now throw");
            try {
                VulkanDebugMessenger.failOnError(before);
                throw new IllegalStateException("failOnError did not throw after an error was reported");
            } catch (RuntimeException expected) {
                System.out.println("   threw as expected: " + expected.getMessage());
            }
        }
        System.out.println("debug messenger plumbing verified; instance destroyed cleanly");
    }
}
