package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.vulkan.vk.VulkanDebugMessenger;
import dev.vexelray.vulkan.vk.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The {@code VK_EXT_debug_utils} plumbing, end to end, without needing the Vulkan SDK installed.
 *
 * <p>The messenger extension comes from the loader, so it is present on any machine that can run Vulkan at all,
 * while {@code VK_LAYER_KHRONOS_validation} ships with the SDK and usually is not — which would otherwise leave
 * the callback, the struct layouts and the {@code const char*} reads completely untested on a developer box.
 * {@code vkSubmitDebugUtilsMessageEXT} closes that gap: it pushes a message through the real messenger chain
 * exactly as a layer would, so a wrong layout or a misbound upcall surfaces here rather than as garbage inside
 * somebody's validation output.
 *
 * <p>Needs {@code -Dvexelray.vulkan.validation}, and skips without it. A skip is the honest report: the
 * plumbing was not exercised, and saying so is different from passing. It was a {@code main} before, which
 * reported neither.
 */
class DebugMessengerTest {

    /**
     * An instance with a messenger, or empty when this machine or this run cannot provide one.
     *
     * <p>The instance is returned alive and the caller closes it — a messenger outliving its instance is a
     * dangling upcall, which is exactly the class of fault this test exists to catch rather than commit.
     */
    private static Optional<VulkanInstance> instanceWithMessenger() {
        List<String> extensions;
        try {
            extensions = NativePlatform.current().requiredVulkanInstanceExtensions();
        } catch (RuntimeException | Error e) {
            return Optional.empty();
        }
        VulkanInstance instance;
        try {
            instance = new VulkanInstance("VexelRay debug messenger", extensions);
        } catch (RuntimeException | Error e) {
            return Optional.empty();
        }
        if (instance.debugMessenger().isEmpty()) {
            instance.close();
            return Optional.empty();
        }
        return Optional.of(instance);
    }

    @Test
    void aMessageRoutedThroughTheMessengerIsCountedBySeverity() {
        Optional<VulkanInstance> maybe = instanceWithMessenger();
        assumeTrue(maybe.isPresent(),
                "no debug messenger — needs a Vulkan loader and -Dvexelray.vulkan.validation");

        try (VulkanInstance instance = maybe.get()) {
            VulkanDebugMessenger messenger = instance.debugMessenger().orElseThrow();
            long before = VulkanDebugMessenger.errorCount();

            messenger.submit(VulkanDebugMessenger.WARNING, "VexelRaySelfCheck",
                    "a warning routed through the messenger");
            assertEquals(before, VulkanDebugMessenger.errorCount(),
                    "a warning must not be counted as an error, or every warning becomes a build failure");

            messenger.submit(VulkanDebugMessenger.ERROR, "VexelRaySelfCheck",
                    "an error routed through the messenger");
            assertEquals(before + 1, VulkanDebugMessenger.errorCount(),
                    "an error that is not counted is an error failOnError will never see");

            assertThrows(RuntimeException.class, () -> VulkanDebugMessenger.failOnError(before),
                    "failOnError must throw once an error has been reported since the baseline");
        }
    }
}
