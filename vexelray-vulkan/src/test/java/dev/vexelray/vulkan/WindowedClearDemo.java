package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

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
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Manual smoke check (not a unit test): opens a window and runs the full windowed present loop — acquire an image
 * from the swapchain, clear it to an animated colour on the GPU, submit with wait/signal semaphores, and present.
 * Single frame in flight (fence-serialised); resize is handled by recreating the swapchain. Run explicitly with
 * {@code --enable-native-access=ALL-UNNAMED}; a window animates for a few seconds then closes.
 */
public final class WindowedClearDemo {

    private static final GroupLayout SEMAPHORE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("VkSemaphoreCreateInfo");

    private static final GroupLayout FENCE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("VkFenceCreateInfo");

    private static final GroupLayout COMMAND_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("queueFamilyIndex")).withName("VkCommandPoolCreateInfo");

    private static final GroupLayout COMMAND_BUFFER_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("commandPool"), JAVA_INT.withName("level"), JAVA_INT.withName("commandBufferCount")
    ).withName("VkCommandBufferAllocateInfo");

    private static final GroupLayout COMMAND_BUFFER_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pInheritanceInfo")
    ).withName("VkCommandBufferBeginInfo");

    private static final GroupLayout IMAGE_MEMORY_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("oldLayout"), JAVA_INT.withName("newLayout"),
            JAVA_INT.withName("srcQueueFamilyIndex"), JAVA_INT.withName("dstQueueFamilyIndex"),
            JAVA_LONG.withName("image"),
            JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"), JAVA_INT.withName("sr_levelCount"),
            JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageMemoryBarrier");

    private static final GroupLayout SUBRESOURCE_RANGE = MemoryLayout.structLayout(
            JAVA_INT.withName("aspectMask"), JAVA_INT.withName("baseMipLevel"), JAVA_INT.withName("levelCount"),
            JAVA_INT.withName("baseArrayLayer"), JAVA_INT.withName("layerCount")).withName("VkImageSubresourceRange");

    private static final GroupLayout SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private static final GroupLayout PRESENT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            JAVA_INT.withName("swapchainCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pSwapchains"),
            ADDRESS.withName("pImageIndices"), ADDRESS.withName("pResults")).withName("VkPresentInfoKHR");

    public static void main(String[] args) {
        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 200;
        NativePlatform platform = NativePlatform.current();

        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay present", 800, 600, true));
             VulkanInstance instance = new VulkanInstance("VexelRay present",
                     platform.requiredVulkanInstanceExtensions())) {

            long surface = window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                    .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
            System.out.println("device: " + selection.deviceName());

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 VulkanSwapchain swapchain = new VulkanSwapchain(instance.handle(), device, surface,
                         window.width(), window.height());
                 Arena a = Arena.ofShared()) {

                System.out.println("swapchain: " + swapchain.images().length + " images @ "
                        + swapchain.width() + "x" + swapchain.height() + " format " + swapchain.format());

                Loop loop = new Loop(device, a);
                int presented = loop.run(swapchain, window, maxFrames);
                device.waitIdle();
                loop.destroy();
                System.out.println("presented " + presented + " frames, then " +
                        (window.pumpEvents() ? "hit frame cap" : "window closed"));
            }
            instance.destroySurface(surface);
        }
        System.out.println("clean shutdown");
    }

    /** The present loop and its per-run Vulkan objects (sync + command buffer). */
    private static final class Loop {
        private final VulkanDevice device;
        private final MemorySegment dev;
        private final MethodHandle waitForFences, resetFences, acquire, present, queueSubmit;
        private final MethodHandle beginCmd, endCmd, cmdBarrier, cmdClear;
        private final MethodHandle destroySemaphore, destroyFence, destroyPool;
        private final long imageAvailable, renderFinished, inFlight, pool;
        private final MemorySegment cmd;
        private final Arena a;

        Loop(VulkanDevice device, Arena a) {
            this.device = device;
            this.dev = device.handle();
            this.a = a;
            MethodHandle createSemaphore = device.command("vkCreateSemaphore", C4);
            MethodHandle createFence = device.command("vkCreateFence", C4);
            MethodHandle createPool = device.command("vkCreateCommandPool", C4);
            MethodHandle allocCmd = device.command("vkAllocateCommandBuffers",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            this.waitForFences = device.command("vkWaitForFences",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
            this.resetFences = device.command("vkResetFences",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            this.acquire = device.command("vkAcquireNextImageKHR",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
            this.present = device.command("vkQueuePresentKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            this.queueSubmit = device.command("vkQueueSubmit",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
            this.beginCmd = device.command("vkBeginCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            this.endCmd = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            this.cmdBarrier = device.command("vkCmdPipelineBarrier", FunctionDescriptor.ofVoid(
                    ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            this.cmdClear = device.command("vkCmdClearColorImage",
                    FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
            this.destroySemaphore = device.command("vkDestroySemaphore", D_LONG);
            this.destroyFence = device.command("vkDestroyFence", D_LONG);
            this.destroyPool = device.command("vkDestroyCommandPool", D_LONG);

            this.imageAvailable = createSemaphore(createSemaphore);
            this.renderFinished = createSemaphore(createSemaphore);
            this.inFlight = createFenceSignaled(createFence);
            this.pool = createPool(createPool);
            this.cmd = allocateCommandBuffer(allocCmd);
        }

        int run(VulkanSwapchain swapchain, NativeWindow window, int maxFrames) {
            MemorySegment pImageIndex = a.allocate(JAVA_INT);
            MemorySegment pFence = a.allocate(JAVA_LONG);
            pFence.set(JAVA_LONG, 0, inFlight);
            MemorySegment waitSems = a.allocate(JAVA_LONG);
            waitSems.set(JAVA_LONG, 0, imageAvailable);
            MemorySegment signalSems = a.allocate(JAVA_LONG);
            signalSems.set(JAVA_LONG, 0, renderFinished);
            MemorySegment waitStages = a.allocate(JAVA_INT);
            waitStages.set(JAVA_INT, 0, Vk.PIPELINE_STAGE_TRANSFER_BIT);
            MemorySegment pCmd = a.allocate(ADDRESS);
            pCmd.set(ADDRESS, 0, cmd);
            MemorySegment pSwapchains = a.allocate(JAVA_LONG);

            MemorySegment submit = a.allocate(SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "waitSemaphoreCount", 1);
            sa(submit, SUBMIT_INFO, "pWaitSemaphores", waitSems);
            sa(submit, SUBMIT_INFO, "pWaitDstStageMask", waitStages);
            si(submit, SUBMIT_INFO, "commandBufferCount", 1);
            sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmd);
            si(submit, SUBMIT_INFO, "signalSemaphoreCount", 1);
            sa(submit, SUBMIT_INFO, "pSignalSemaphores", signalSems);

            MemorySegment presentInfo = a.allocate(PRESENT_INFO);
            si(presentInfo, PRESENT_INFO, "sType", Vk.STRUCTURE_TYPE_PRESENT_INFO_KHR);
            si(presentInfo, PRESENT_INFO, "waitSemaphoreCount", 1);
            sa(presentInfo, PRESENT_INFO, "pWaitSemaphores", signalSems);
            si(presentInfo, PRESENT_INFO, "swapchainCount", 1);
            sa(presentInfo, PRESENT_INFO, "pSwapchains", pSwapchains);
            sa(presentInfo, PRESENT_INFO, "pImageIndices", pImageIndex);

            MemorySegment color = a.allocate(JAVA_FLOAT, 4);
            MemorySegment range = a.allocate(SUBRESOURCE_RANGE);
            si(range, SUBRESOURCE_RANGE, "aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(range, SUBRESOURCE_RANGE, "levelCount", 1);
            si(range, SUBRESOURCE_RANGE, "layerCount", 1);
            MemorySegment beginInfo = a.allocate(COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            MemorySegment toDst = a.allocate(IMAGE_MEMORY_BARRIER);
            MemorySegment toPresent = a.allocate(IMAGE_MEMORY_BARRIER);

            int frame = 0;
            while (window.pumpEvents() && frame < maxFrames) {
                check(invoke(waitForFences, dev, 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");

                int acq = invoke(acquire, dev, swapchain.handle(), Long.MAX_VALUE, imageAvailable, 0L, pImageIndex);
                if (acq == Vk.ERROR_OUT_OF_DATE_KHR) {
                    swapchain.recreate(window.width(), window.height());
                    continue;
                }
                check(invoke(resetFences, dev, 1, pFence), "vkResetFences");
                int imageIndex = pImageIndex.get(JAVA_INT, 0);
                long image = swapchain.images()[imageIndex];

                float t = frame * 0.03f;
                color.setAtIndex(JAVA_FLOAT, 0, 0.5f + 0.5f * (float) Math.sin(t));
                color.setAtIndex(JAVA_FLOAT, 1, 0.5f + 0.5f * (float) Math.sin(t + 2.094f));
                color.setAtIndex(JAVA_FLOAT, 2, 0.5f + 0.5f * (float) Math.sin(t + 4.188f));
                color.setAtIndex(JAVA_FLOAT, 3, 1.0f);

                check(invoke(beginCmd, cmd, beginInfo), "vkBeginCommandBuffer");
                barrier(toDst, image, 0, Vk.ACCESS_TRANSFER_WRITE_BIT,
                        Vk.IMAGE_LAYOUT_UNDEFINED, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                invokeVoid(cmdBarrier, cmd, Vk.PIPELINE_STAGE_TOP_OF_PIPE_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT, 0,
                        0, MemorySegment.NULL, 0, MemorySegment.NULL, 1, toDst);
                invokeVoid(cmdClear, cmd, image, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, color, 1, range);
                barrier(toPresent, image, Vk.ACCESS_TRANSFER_WRITE_BIT, 0,
                        Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR);
                invokeVoid(cmdBarrier, cmd, Vk.PIPELINE_STAGE_TRANSFER_BIT, Vk.PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0,
                        0, MemorySegment.NULL, 0, MemorySegment.NULL, 1, toPresent);
                check(invoke(endCmd, cmd), "vkEndCommandBuffer");

                check(invoke(queueSubmit, device.queue(), 1, submit, inFlight), "vkQueueSubmit");

                pSwapchains.set(JAVA_LONG, 0, swapchain.handle());
                int res = invoke(present, device.queue(), presentInfo);
                if (res == Vk.ERROR_OUT_OF_DATE_KHR || res == Vk.SUBOPTIMAL_KHR) {
                    device.waitIdle();
                    swapchain.recreate(window.width(), window.height());
                }
                frame++;
            }
            return frame;
        }

        private void barrier(MemorySegment b, long image, int srcAccess, int dstAccess, int oldLayout, int newLayout) {
            si(b, IMAGE_MEMORY_BARRIER, "sType", Vk.STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            si(b, IMAGE_MEMORY_BARRIER, "srcAccessMask", srcAccess);
            si(b, IMAGE_MEMORY_BARRIER, "dstAccessMask", dstAccess);
            si(b, IMAGE_MEMORY_BARRIER, "oldLayout", oldLayout);
            si(b, IMAGE_MEMORY_BARRIER, "newLayout", newLayout);
            si(b, IMAGE_MEMORY_BARRIER, "srcQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
            si(b, IMAGE_MEMORY_BARRIER, "dstQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
            sl(b, IMAGE_MEMORY_BARRIER, "image", image);
            si(b, IMAGE_MEMORY_BARRIER, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(b, IMAGE_MEMORY_BARRIER, "sr_levelCount", 1);
            si(b, IMAGE_MEMORY_BARRIER, "sr_layerCount", 1);
        }

        private long createSemaphore(MethodHandle createSemaphore) {
            MemorySegment info = a.allocate(SEMAPHORE_CREATE_INFO);
            si(info, SEMAPHORE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            MemorySegment p = a.allocate(JAVA_LONG);
            check(invoke(createSemaphore, dev, info, MemorySegment.NULL, p), "vkCreateSemaphore");
            return p.get(JAVA_LONG, 0);
        }

        private long createFenceSignaled(MethodHandle createFence) {
            MemorySegment info = a.allocate(FENCE_CREATE_INFO);
            si(info, FENCE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
            si(info, FENCE_CREATE_INFO, "flags", 0x1);   // VK_FENCE_CREATE_SIGNALED_BIT: first frame's wait passes
            MemorySegment p = a.allocate(JAVA_LONG);
            check(invoke(createFence, dev, info, MemorySegment.NULL, p), "vkCreateFence");
            return p.get(JAVA_LONG, 0);
        }

        private long createPool(MethodHandle createPool) {
            MemorySegment info = a.allocate(COMMAND_POOL_CREATE_INFO);
            si(info, COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            si(info, COMMAND_POOL_CREATE_INFO, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            si(info, COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
            MemorySegment p = a.allocate(JAVA_LONG);
            check(invoke(createPool, dev, info, MemorySegment.NULL, p), "vkCreateCommandPool");
            return p.get(JAVA_LONG, 0);
        }

        private MemorySegment allocateCommandBuffer(MethodHandle allocCmd) {
            MemorySegment info = a.allocate(COMMAND_BUFFER_ALLOCATE_INFO);
            si(info, COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            sl(info, COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
            si(info, COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
            si(info, COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
            MemorySegment p = a.allocate(ADDRESS);
            check(invoke(allocCmd, dev, info, p), "vkAllocateCommandBuffers");
            return p.get(ADDRESS, 0);
        }

        void destroy() {
            invokeVoid(destroyPool, dev, pool, MemorySegment.NULL);
            invokeVoid(destroyFence, dev, inFlight, MemorySegment.NULL);
            invokeVoid(destroySemaphore, dev, renderFinished, MemorySegment.NULL);
            invokeVoid(destroySemaphore, dev, imageAvailable, MemorySegment.NULL);
        }

        private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    }
}
