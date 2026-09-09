package dev.vexelray.vulkan.present;

import sibarum.probe.Lane;
import sibarum.probe.Probe;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.vexelray.vulkan.vk.Ffm.check;
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.invokeVoid;
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Image views + framebuffers over a {@link VulkanSwapchain}'s images for a given render pass — one framebuffer
 * per swapchain image, which is what the windowed present loop draws into. Rebuilt whenever the swapchain is
 * recreated (resize). {@link #close()} destroys both sets.
 *
 * <p>When the render pass has a depth attachment, every framebuffer attaches the <b>same</b> depth view
 * alongside its own colour view — one depth image serving all swapchain images. That is correct only while a
 * single frame is in flight; {@link DepthAttachment} carries the argument and the hazard. The depth view is
 * owned by the caller, not here: it outlives individual framebuffer rebuilds when only the extent is unchanged,
 * and this class destroys only the views it made itself.
 */
public final class SwapchainFramebuffers implements AutoCloseable {

    private static final GroupLayout IMAGE_VIEW_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("image"),
            JAVA_INT.withName("viewType"), JAVA_INT.withName("format"),
            JAVA_INT.withName("c_r"), JAVA_INT.withName("c_g"), JAVA_INT.withName("c_b"), JAVA_INT.withName("c_a"),
            JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"), JAVA_INT.withName("sr_levelCount"),
            JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageViewCreateInfo");

    private static final GroupLayout FRAMEBUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("renderPass"),
            JAVA_INT.withName("attachmentCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pAttachments"),
            JAVA_INT.withName("width"), JAVA_INT.withName("height"), JAVA_INT.withName("layers"),
            MemoryLayout.paddingLayout(4)).withName("VkFramebufferCreateInfo");

    /** Passed as {@code depthView} for colour-only framebuffers over a render pass that has no depth. */
    public static final long NO_DEPTH_VIEW = 0L;

    private final VulkanDevice device;
    private final MethodHandle vkDestroyImageView;
    private final MethodHandle vkDestroyFramebuffer;
    private final long[] views;
    private final long[] framebuffers;

    /** Colour-only framebuffers — the spelling every caller predating depth uses. */
    public SwapchainFramebuffers(VulkanDevice device, VulkanSwapchain swapchain, long renderPass) {
        this(device, swapchain, renderPass, NO_DEPTH_VIEW);
    }

    /**
     * Framebuffers attaching {@code depthView} at slot 1 behind each swapchain image's colour view, or
     * colour-only when it is {@link #NO_DEPTH_VIEW}.
     *
     * <p>The count and order here must match the render pass's attachment list exactly — colour at 0, depth at
     * 1 — and mismatching them is undefined behaviour rather than an error the loader reports, so a pass built
     * with depth must be given a view and one built without must not.
     */
    public SwapchainFramebuffers(VulkanDevice device, VulkanSwapchain swapchain, long renderPass, long depthView) {
        Probe.opened(Lane.GPU, "SwapchainFramebuffers", this);
        this.device = device;
        MemorySegment dev = device.handle();
        MethodHandle vkCreateImageView = device.command("vkCreateImageView",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkCreateFramebuffer = device.command("vkCreateFramebuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.vkDestroyImageView = device.command("vkDestroyImageView",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        this.vkDestroyFramebuffer = device.command("vkDestroyFramebuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));

        long[] images = swapchain.images();
        this.views = new long[images.length];
        this.framebuffers = new long[images.length];
        try (Arena arena = Arena.ofConfined()) {
            for (int i = 0; i < images.length; i++) {
                MemorySegment viewInfo = arena.allocate(IMAGE_VIEW_CREATE_INFO);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                sl(viewInfo, IMAGE_VIEW_CREATE_INFO, "image", images[i]);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "format", swapchain.format());
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_levelCount", 1);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
                MemorySegment pView = arena.allocate(JAVA_LONG);
                check(invoke(vkCreateImageView, dev, viewInfo, MemorySegment.NULL, pView), "vkCreateImageView");
                views[i] = pView.get(JAVA_LONG, 0);

                int attachmentCount = depthView == NO_DEPTH_VIEW ? 1 : 2;
                MemorySegment pAttach = arena.allocate(JAVA_LONG, attachmentCount);
                pAttach.setAtIndex(JAVA_LONG, 0, views[i]);
                if (attachmentCount == 2) {
                    pAttach.setAtIndex(JAVA_LONG, 1, depthView);
                }
                MemorySegment fbInfo = arena.allocate(FRAMEBUFFER_CREATE_INFO);
                si(fbInfo, FRAMEBUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
                sl(fbInfo, FRAMEBUFFER_CREATE_INFO, "renderPass", renderPass);
                si(fbInfo, FRAMEBUFFER_CREATE_INFO, "attachmentCount", attachmentCount);
                sa(fbInfo, FRAMEBUFFER_CREATE_INFO, "pAttachments", pAttach);
                si(fbInfo, FRAMEBUFFER_CREATE_INFO, "width", swapchain.width());
                si(fbInfo, FRAMEBUFFER_CREATE_INFO, "height", swapchain.height());
                si(fbInfo, FRAMEBUFFER_CREATE_INFO, "layers", 1);
                MemorySegment pFb = arena.allocate(JAVA_LONG);
                check(invoke(vkCreateFramebuffer, dev, fbInfo, MemorySegment.NULL, pFb), "vkCreateFramebuffer");
                framebuffers[i] = pFb.get(JAVA_LONG, 0);
            }
        }
    }

    public long framebuffer(int imageIndex) {
        return framebuffers[imageIndex];
    }

    @Override
    public void close() {
        Probe.closed(Lane.GPU, "SwapchainFramebuffers", this);
        MemorySegment dev = device.handle();
        for (long fb : framebuffers) {
            invokeVoid(vkDestroyFramebuffer, dev, fb, MemorySegment.NULL);
        }
        for (long view : views) {
            invokeVoid(vkDestroyImageView, dev, view, MemorySegment.NULL);
        }
    }
}
