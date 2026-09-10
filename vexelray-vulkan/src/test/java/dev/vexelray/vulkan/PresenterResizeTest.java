package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.vulkan.present.DepthAttachment;
import dev.vexelray.vulkan.present.Recorder;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDebugMessenger;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Resizing a window with several frames in flight — the path where the per-image objects are destroyed and
 * remade while frames are still moving through the slots.
 *
 * <h2>Why this needs its own test</h2>
 *
 * <p>{@code FramesInFlightTest} drives the engine at three frames in flight and asserts the validation layer
 * stays quiet, but it never resizes, so it only ever exercises one set of per-image objects. A rebuild is
 * where that set changes: a recreated swapchain may return a different number of images, so the depth
 * array, the render-finished semaphores and the in-flight table are not refilled but <em>re-sized</em>, and
 * every semaphore in one of them is a new object. Destroying one a present still waits on, or keeping a
 * stale fence in the in-flight table, is a use-after-free that no picture would show.
 *
 * <p>It is at the presenter's level rather than the engine's because the engine hands back an OS handle and
 * not a {@link NativeWindow}, and this test needs to call {@code setBounds} on the window mid-run. That is
 * also the more direct place for it: {@code WindowedPresenter} is what owns the arrays.
 *
 * <p>Nothing is drawn. The recorder records no commands at all, which still exercises the whole frame —
 * acquire, begin pass, clear, end pass, submit, present — and leaves nothing for a pipeline to be blamed
 * for when the layer does complain.
 */
class PresenterResizeTest {

    private static final int FRAMES_BEFORE = 8;
    private static final int FRAMES_AFTER = 12;
    private static final int WIDTH = 480;
    private static final int HEIGHT = 320;
    private static final int RESIZED_WIDTH = 560;
    private static final int RESIZED_HEIGHT = 400;

    /** Three, so slots wrap several times over both halves of the run and outnumber neither image count. */
    private static final int FRAMES_IN_FLIGHT = 3;

    @Test
    void aResizeRebuildsThePerImageObjectsWithoutAValidationError() {
        assumeTrue(VulkanInstance.validationLayerActive(),
                "the validation layer is not active — run with -Dvexelray.vulkan.validation and the Vulkan "
                        + "SDK installed. Without it nothing here could report a use-after-free, and this "
                        + "would pass while measuring nothing.");

        NativePlatform platform;
        try {
            platform = NativePlatform.current();
        } catch (RuntimeException | Error e) {
            assumeTrue(false, "no OS backend for this platform");
            return;
        }

        long before = VulkanDebugMessenger.errorCount();

        try (NativeWindow window = platform.createWindow(
                new WindowConfig("VexelRay — presenter resize", WIDTH, HEIGHT, true));
             VulkanInstance instance = new VulkanInstance("PresenterResizeTest",
                     platform.requiredVulkanInstanceExtensions())) {

            long surface = window.createVulkanSurface(instance.handleAddress(),
                    VkLoader.getInstanceProcAddrPointer());
            try {
                VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                        .orElseThrow(() -> new IllegalStateException("no graphics+present device"));

                try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                     VulkanSwapchain swapchain = new VulkanSwapchain(instance.handle(), device, surface,
                             window.width(), window.height());
                     // With depth, because depth is the per-image object a rebuild reallocates and the one
                     // a colour-only pass would not have exercised at all.
                     VulkanRenderPass renderPass = new VulkanRenderPass(device, swapchain.format(),
                             Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR, DepthAttachment.FORMAT);
                     WindowedPresenter presenter = new WindowedPresenter(device, swapchain,
                             renderPass.handle(), window, DepthAttachment.FORMAT, FRAMES_IN_FLIGHT)) {

                    Recorder nothing = (cmd, w, h) -> { };
                    for (int i = 0; i < FRAMES_BEFORE; i++) {
                        presenter.frame(0, null, nothing);
                    }
                    int widthBefore = swapchain.width();

                    window.setBounds(80, 80, RESIZED_WIDTH, RESIZED_HEIGHT);
                    for (int i = 0; i < FRAMES_AFTER; i++) {
                        presenter.frame(0, null, nothing);
                    }

                    // The rebuild has to have actually happened, or this test is a slow way of drawing
                    // twenty blank frames. The swapchain's own extent is the evidence: only rebuild()
                    // changes it.
                    assertNotEquals(widthBefore, swapchain.width(),
                            "the window was resized and the swapchain never followed — no rebuild ran, so "
                                    + "the path this test exists for was not taken");
                    assertTrue(swapchain.width() > 0 && swapchain.height() > 0);
                }
            } finally {
                instance.destroySurface(surface);
            }
        }

        VulkanDebugMessenger.failOnError(before);
    }
}
