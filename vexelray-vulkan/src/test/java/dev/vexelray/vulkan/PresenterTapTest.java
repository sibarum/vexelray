package dev.vexelray.vulkan;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VkLoader;
import dev.supirvast.vulkan.VulkanDebugMessenger;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.vulkan.present.FrameTapRing;
import dev.vexelray.vulkan.present.Recorder;
import dev.vexelray.vulkan.present.TappedFrame;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;

import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A frame tap on the Vulkan swapchain presenter, the one Linux presents through: every presented frame copied out of
 * the swapchain image in the frame's own command buffer, handed over once the GPU is done, in order, at the size it was
 * presented, and given back. On a real window, so the image's {@code PRESENT_SRC_KHR} round trip and the swapchain's
 * {@code TRANSFER_SRC} usage are the driver's, not a stand-in's. With {@code -Dvexelray.vulkan.validation} and the SDK,
 * a validation error anywhere fails it.
 *
 * <p>Each frame is cleared to its own colour with {@code vkCmdClearAttachments}, so a frame handed over can be told from
 * its neighbours by its pixels.
 */
class PresenterTapTest {

    private static final int WIDTH = 320;
    private static final int HEIGHT = 200;
    private static final float[][] COLOURS = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};

    private static final GroupLayout CLEAR_ATTACHMENT = MemoryLayout.structLayout(
            JAVA_INT.withName("aspectMask"), JAVA_INT.withName("colorAttachment"),
            MemoryLayout.sequenceLayout(4, JAVA_FLOAT).withName("color"));
    private static final GroupLayout CLEAR_RECT = MemoryLayout.structLayout(
            JAVA_INT.withName("x"), JAVA_INT.withName("y"), JAVA_INT.withName("w"), JAVA_INT.withName("h"),
            JAVA_INT.withName("baseArrayLayer"), JAVA_INT.withName("layerCount"));

    @Test
    void handsOverEveryFrameInOrderAtItsSizeAndNeverStallsOnAHeldOne() {
        NativePlatform platform;
        try {
            platform = NativePlatform.current();
        } catch (RuntimeException | Error e) {
            assumeTrue(false, "no OS backend for this platform");
            return;
        }
        long errors = VulkanDebugMessenger.errorCount();

        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay — frame tap", WIDTH, HEIGHT, true));
             VulkanInstance instance = new VulkanInstance("PresenterTapTest",
                     platform.requiredVulkanInstanceExtensions())) {
            long surface = window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
            try {
                VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                        .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
                try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                     VulkanSwapchain swapchain = new VulkanSwapchain(instance.handle(), device, surface,
                             window.width(), window.height());
                     VulkanRenderPass renderPass = new VulkanRenderPass(device, swapchain.format(),
                             Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR);
                     WindowedPresenter presenter = new WindowedPresenter(device, swapchain, renderPass.handle(),
                             window, VulkanRenderPass.NO_DEPTH, 1);
                     Arena arena = Arena.ofConfined()) {
                    assumeTrue(swapchain.readable(), "this surface does not allow TRANSFER_SRC: nothing to tap");
                    boolean bgra = swapchain.format() == Vk.FORMAT_B8G8R8A8_UNORM
                            || swapchain.format() == Vk.FORMAT_B8G8R8A8_SRGB;
                    MethodHandle clearAttachments = device.command("vkCmdClearAttachments",
                            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
                    MemorySegment attachment = arena.allocate(CLEAR_ATTACHMENT);
                    MemorySegment rect = arena.allocate(CLEAR_RECT);
                    int[] drawn = {0};
                    Recorder paint = (cmd, w, h) -> {
                        float[] c = COLOURS[drawn[0] % COLOURS.length];
                        attachment.set(JAVA_INT, 0, Vk.IMAGE_ASPECT_COLOR_BIT);
                        for (int i = 0; i < 3; i++) {
                            attachment.set(JAVA_FLOAT, 8 + i * 4L, c[i]);
                        }
                        attachment.set(JAVA_FLOAT, 20, 1f);
                        rect.set(JAVA_INT, 8, w);
                        rect.set(JAVA_INT, 12, h);
                        rect.set(JAVA_INT, 20, 1);
                        invokeVoid(clearAttachments, cmd, 1, attachment, 1, rect);
                        drawn[0]++;
                    };

                    // Released as they come: every frame drawn is handed over, in order, with its own colour.
                    List<int[]> got = new ArrayList<>();      // {colour index, width, height}
                    List<Long> times = new ArrayList<>();
                    assertTrue(presenter.tap(f -> {
                        got.add(new int[] {colourOf(f, bgra), f.width(), f.height()});
                        times.add(f.presentedNanos());
                        f.release();
                    }));
                    for (int i = 0; i < 9; i++) {
                        presenter.frame(0, null, paint);
                    }
                    int widthBefore = swapchain.width();    // the drawable width: the window's frame takes some
                    window.setBounds(60, 60, WIDTH + 80, HEIGHT + 40);
                    for (int i = 0; i < 6; i++) {
                        presenter.frame(0, null, paint);
                    }
                    presenter.tap(null);    // the copies still pending are handed over before the tap goes
                    int frames = drawn[0];
                    assertEquals(frames, got.size(), "every frame drawn was handed over");
                    for (int i = 0; i < frames; i++) {
                        assertEquals(i % COLOURS.length, got.get(i)[0], "frame " + i + " is its own colour, in order");
                        assertTrue(i == 0 || times.get(i) > times.get(i - 1), "times rise");
                    }
                    assertEquals(widthBefore, got.getFirst()[1]);
                    assertEquals(swapchain.width(), got.getLast()[1], "the frame after a resize is the new size");
                    assertEquals(swapchain.height(), got.getLast()[2]);
                    assertTrue(got.getLast()[1] != widthBefore, "the resize happened, so its frame was tapped at its size");

                    // Held, never released: once every buffer is held, frames are skipped rather than waited for.
                    List<TappedFrame> held = new ArrayList<>();
                    presenter.tap(held::add);
                    for (int i = 0; i < FrameTapRing.SLOTS + 4; i++) {
                        presenter.frame(0, null, paint);
                    }
                    presenter.tap(null);
                    assertEquals(FrameTapRing.SLOTS, held.size(), "as many as there are buffers, and no more");
                    held.forEach(TappedFrame::release);
                }
            } finally {
                instance.destroySurface(surface);
            }
        }
        VulkanDebugMessenger.failOnError(errors);
    }

    /** Which of {@link #COLOURS} the frame's centre is, or -1. */
    private static int colourOf(TappedFrame f, boolean bgra) {
        long p = ((long) (f.height() / 2) * f.width() + f.width() / 2) * 4;
        int b0 = Byte.toUnsignedInt(f.pixels().get(JAVA_BYTE, p));
        int b1 = Byte.toUnsignedInt(f.pixels().get(JAVA_BYTE, p + 1));
        int b2 = Byte.toUnsignedInt(f.pixels().get(JAVA_BYTE, p + 2));
        int r = bgra ? b2 : b0;
        int g = b1;
        int b = bgra ? b0 : b2;
        for (int i = 0; i < COLOURS.length; i++) {
            if (Math.abs(r - COLOURS[i][0] * 255) < 8 && Math.abs(g - COLOURS[i][1] * 255) < 8
                    && Math.abs(b - COLOURS[i][2] * 255) < 8) {
                return i;
            }
        }
        return -1;
    }
}
