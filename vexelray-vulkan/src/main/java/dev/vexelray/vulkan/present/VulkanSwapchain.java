package dev.vexelray.vulkan.present;

import sibarum.probe.Lane;
import sibarum.probe.Probe;
import dev.vexelray.os.ffi.NativeException;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.vexelray.vulkan.vk.Ffm.check;
import static dev.vexelray.vulkan.vk.Ffm.gi;
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A {@code VkSwapchainKHR} over a window surface: the windowed present target. Chooses a surface format and
 * extent, creates the swapchain, and holds its images. FIFO present mode (always supported, vsync). Recreatable
 * on resize; destroyed on {@link #close()}.
 *
 * <p>Images are exposed by handle; this first cut clears + presents them directly (no image views / render pass),
 * so drawing a pipeline into them is a later step. Owned internally by the runtime — never surfaced to the engine.
 */
public final class VulkanSwapchain implements AutoCloseable {

    private static final GroupLayout SURFACE_CAPABILITIES = MemoryLayout.structLayout(
            JAVA_INT.withName("minImageCount"), JAVA_INT.withName("maxImageCount"),
            JAVA_INT.withName("currentExtent_w"), JAVA_INT.withName("currentExtent_h"),
            JAVA_INT.withName("minExtent_w"), JAVA_INT.withName("minExtent_h"),
            JAVA_INT.withName("maxExtent_w"), JAVA_INT.withName("maxExtent_h"),
            JAVA_INT.withName("maxImageArrayLayers"), JAVA_INT.withName("supportedTransforms"),
            JAVA_INT.withName("currentTransform"), JAVA_INT.withName("supportedCompositeAlpha"),
            JAVA_INT.withName("supportedUsageFlags")).withName("VkSurfaceCapabilitiesKHR");

    private static final GroupLayout SURFACE_FORMAT = MemoryLayout.structLayout(
            JAVA_INT.withName("format"), JAVA_INT.withName("colorSpace")).withName("VkSurfaceFormatKHR");

    private static final GroupLayout SWAPCHAIN_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("surface"),
            JAVA_INT.withName("minImageCount"), JAVA_INT.withName("imageFormat"), JAVA_INT.withName("imageColorSpace"),
            JAVA_INT.withName("imageExtent_w"), JAVA_INT.withName("imageExtent_h"), JAVA_INT.withName("imageArrayLayers"),
            JAVA_INT.withName("imageUsage"), JAVA_INT.withName("imageSharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices"), JAVA_INT.withName("preTransform"),
            JAVA_INT.withName("compositeAlpha"), JAVA_INT.withName("presentMode"), JAVA_INT.withName("clipped"),
            JAVA_LONG.withName("oldSwapchain")).withName("VkSwapchainCreateInfoKHR");

    private final MemorySegment instance;
    private final VulkanDevice device;
    private final long surface;
    private final MethodHandle vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
    private final MethodHandle vkGetPhysicalDeviceSurfaceFormatsKHR;
    private final MethodHandle vkCreateSwapchainKHR;
    private final MethodHandle vkGetSwapchainImagesKHR;
    private final MethodHandle vkDestroySwapchainKHR;

    private long handle;
    private long[] images = new long[0];
    private int format;
    private int colorSpace;
    private int extentWidth;
    private int extentHeight;

    public VulkanSwapchain(MemorySegment instance, VulkanDevice device, long surface, int width, int height) {
        Probe.opened(Lane.GPU, "VulkanSwapchain", this);
        this.instance = instance;
        this.device = device;
        this.surface = surface;
        this.vkGetPhysicalDeviceSurfaceCapabilitiesKHR = VkLoader.instanceCommand(instance,
                "vkGetPhysicalDeviceSurfaceCapabilitiesKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        this.vkGetPhysicalDeviceSurfaceFormatsKHR = VkLoader.instanceCommand(instance,
                "vkGetPhysicalDeviceSurfaceFormatsKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        this.vkCreateSwapchainKHR = device.command("vkCreateSwapchainKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.vkGetSwapchainImagesKHR = device.command("vkGetSwapchainImagesKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, ADDRESS));
        this.vkDestroySwapchainKHR = device.command("vkDestroySwapchainKHR",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        chooseFormat();
        create(width, height, 0L);
    }

    private void chooseFormat() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pCount = temp.allocate(JAVA_INT);
            check(invoke(vkGetPhysicalDeviceSurfaceFormatsKHR, device.physicalDevice(), surface, pCount, MemorySegment.NULL),
                    "vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
            int count = pCount.get(JAVA_INT, 0);
            MemorySegment formats = temp.allocate(SURFACE_FORMAT, count);
            check(invoke(vkGetPhysicalDeviceSurfaceFormatsKHR, device.physicalDevice(), surface, pCount, formats),
                    "vkGetPhysicalDeviceSurfaceFormatsKHR");
            long stride = SURFACE_FORMAT.byteSize();
            MemorySegment first = formats.asSlice(0, stride);
            this.format = gi(first, SURFACE_FORMAT, "format");          // fall back to the first reported pair
            this.colorSpace = gi(first, SURFACE_FORMAT, "colorSpace");
            for (int i = 0; i < count; i++) {
                MemorySegment entry = formats.asSlice(i * stride, stride);
                int f = gi(entry, SURFACE_FORMAT, "format");
                int cs = gi(entry, SURFACE_FORMAT, "colorSpace");
                if ((f == Vk.FORMAT_B8G8R8A8_UNORM || f == Vk.FORMAT_B8G8R8A8_SRGB)
                        && cs == Vk.COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    this.format = f;
                    this.colorSpace = cs;
                    break;
                }
            }
        }
    }

    private void create(int width, int height, long oldSwapchain) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment caps = temp.allocate(SURFACE_CAPABILITIES);
            check(invoke(vkGetPhysicalDeviceSurfaceCapabilitiesKHR, device.physicalDevice(), surface, caps),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");

            int currentW = gi(caps, SURFACE_CAPABILITIES, "currentExtent_w");
            if (currentW != Vk.EXTENT_UNDEFINED) {
                this.extentWidth = currentW;
                this.extentHeight = gi(caps, SURFACE_CAPABILITIES, "currentExtent_h");
            } else {
                this.extentWidth = clamp(width, gi(caps, SURFACE_CAPABILITIES, "minExtent_w"),
                        gi(caps, SURFACE_CAPABILITIES, "maxExtent_w"));
                this.extentHeight = clamp(height, gi(caps, SURFACE_CAPABILITIES, "minExtent_h"),
                        gi(caps, SURFACE_CAPABILITIES, "maxExtent_h"));
            }

            int minCount = gi(caps, SURFACE_CAPABILITIES, "minImageCount") + 1;
            int maxCount = gi(caps, SURFACE_CAPABILITIES, "maxImageCount");
            if (maxCount > 0 && minCount > maxCount) {
                minCount = maxCount;
            }

            MemorySegment info = temp.allocate(SWAPCHAIN_CREATE_INFO);
            si(info, SWAPCHAIN_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            sl(info, SWAPCHAIN_CREATE_INFO, "surface", surface);
            si(info, SWAPCHAIN_CREATE_INFO, "minImageCount", minCount);
            si(info, SWAPCHAIN_CREATE_INFO, "imageFormat", format);
            si(info, SWAPCHAIN_CREATE_INFO, "imageColorSpace", colorSpace);
            si(info, SWAPCHAIN_CREATE_INFO, "imageExtent_w", extentWidth);
            si(info, SWAPCHAIN_CREATE_INFO, "imageExtent_h", extentHeight);
            si(info, SWAPCHAIN_CREATE_INFO, "imageArrayLayers", 1);
            si(info, SWAPCHAIN_CREATE_INFO, "imageUsage",
                    Vk.IMAGE_USAGE_COLOR_ATTACHMENT_BIT | Vk.IMAGE_USAGE_TRANSFER_DST_BIT);
            si(info, SWAPCHAIN_CREATE_INFO, "imageSharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            si(info, SWAPCHAIN_CREATE_INFO, "preTransform", gi(caps, SURFACE_CAPABILITIES, "currentTransform"));
            si(info, SWAPCHAIN_CREATE_INFO, "compositeAlpha", Vk.COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            si(info, SWAPCHAIN_CREATE_INFO, "presentMode", Vk.PRESENT_MODE_FIFO_KHR);
            si(info, SWAPCHAIN_CREATE_INFO, "clipped", Vk.VK_TRUE);
            sl(info, SWAPCHAIN_CREATE_INFO, "oldSwapchain", oldSwapchain);

            MemorySegment pSwapchain = temp.allocate(JAVA_LONG);
            check(invoke(vkCreateSwapchainKHR, device.handle(), info, MemorySegment.NULL, pSwapchain),
                    "vkCreateSwapchainKHR");
            this.handle = pSwapchain.get(JAVA_LONG, 0);

            MemorySegment pImageCount = temp.allocate(JAVA_INT);
            check(invoke(vkGetSwapchainImagesKHR, device.handle(), handle, pImageCount, MemorySegment.NULL),
                    "vkGetSwapchainImagesKHR(count)");
            int imageCount = pImageCount.get(JAVA_INT, 0);
            MemorySegment pImages = temp.allocate(JAVA_LONG, imageCount);
            check(invoke(vkGetSwapchainImagesKHR, device.handle(), handle, pImageCount, pImages),
                    "vkGetSwapchainImagesKHR");
            this.images = new long[imageCount];
            for (int i = 0; i < imageCount; i++) {
                this.images[i] = pImages.getAtIndex(JAVA_LONG, i);
            }
        }
    }

    /** Rebuild at a new size after a resize / out-of-date surface, reusing the old swapchain for a smooth handoff. */
    public void recreate(int width, int height) {
        device.waitIdle();
        long old = handle;
        create(width, height, old);
        destroyHandle(old);
    }

    public long handle() {
        return handle;
    }

    public long[] images() {
        return images;
    }

    public int format() {
        return format;
    }

    public int width() {
        return extentWidth;
    }

    public int height() {
        return extentHeight;
    }

    private void destroyHandle(long swapchain) {
        if (swapchain != 0L) {
            try {
                vkDestroySwapchainKHR.invokeExact(device.handle(), swapchain, MemorySegment.NULL);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkDestroySwapchainKHR", t);
            }
        }
    }

    @Override
    public void close() {
        Probe.closed(Lane.GPU, "VulkanSwapchain", this);
        destroyHandle(handle);
        handle = 0L;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
