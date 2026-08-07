package dev.vexelray.vulkan.present;

import dev.vexelray.os.NativeWindow;
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
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Drives the windowed present loop for a {@link GraphicsPipeline}: owns the per-image framebuffers, the frame
 * synchronisation (one frame in flight), and a command buffer, and runs acquire → render pass → draw → submit →
 * present each frame until the window closes (or a frame cap is hit). Recreates the swapchain + framebuffers on
 * resize. This is the reusable present machinery a client app (or the future {@code RuntimeManager}) drives
 * instead of hand-rolling Vulkan.
 *
 * <p>v0 draws a fixed vertex count with no per-frame data (the fullscreen triangle); per-frame push constants
 * (camera, time) arrive with the pipeline-layout work in a later step.
 */
public final class WindowedPresenter implements AutoCloseable {

    private static final GroupLayout CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("CreateInfo");

    private static final GroupLayout POOL_CI = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("queueFamilyIndex")).withName("VkCommandPoolCreateInfo");

    private static final GroupLayout CMD_ALLOC = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("commandPool"), JAVA_INT.withName("level"), JAVA_INT.withName("commandBufferCount")
    ).withName("VkCommandBufferAllocateInfo");

    private static final GroupLayout CMD_BEGIN = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pInheritanceInfo")
    ).withName("VkCommandBufferBeginInfo");

    private static final GroupLayout RENDER_PASS_BEGIN = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("renderPass"), JAVA_LONG.withName("framebuffer"),
            JAVA_INT.withName("area_x"), JAVA_INT.withName("area_y"),
            JAVA_INT.withName("area_w"), JAVA_INT.withName("area_h"),
            JAVA_INT.withName("clearValueCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pClearValues")
    ).withName("VkRenderPassBeginInfo");

    private static final GroupLayout SUBMIT = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private static final GroupLayout PRESENT = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            JAVA_INT.withName("swapchainCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pSwapchains"),
            ADDRESS.withName("pImageIndices"), ADDRESS.withName("pResults")).withName("VkPresentInfoKHR");

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor DL = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);

    private final VulkanDevice device;
    private final MemorySegment dev;
    private final VulkanSwapchain swapchain;
    private final GraphicsPipeline pipeline;
    private final NativeWindow window;
    private final Arena a = Arena.ofShared();

    private final MethodHandle waitFences, resetFences, acquire, present, submitCmd;
    private final MethodHandle beginCmd, endCmd, beginRp, bindPipe, draw, endRp;
    private final MethodHandle destroySem, destroyFence, destroyPool;
    private final long imageAvailable, renderFinished, inFlight, pool;
    private final MemorySegment cmd;

    private SwapchainFramebuffers framebuffers;
    private int vertexCount = 3;

    public WindowedPresenter(VulkanDevice device, VulkanSwapchain swapchain, GraphicsPipeline pipeline,
                             NativeWindow window) {
        this.device = device;
        this.dev = device.handle();
        this.swapchain = swapchain;
        this.pipeline = pipeline;
        this.window = window;

        MethodHandle createSem = device.command("vkCreateSemaphore", C4);
        MethodHandle createFence = device.command("vkCreateFence", C4);
        MethodHandle createPool = device.command("vkCreateCommandPool", C4);
        MethodHandle allocCmd = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        this.waitFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        this.resetFences = device.command("vkResetFences", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.acquire = device.command("vkAcquireNextImageKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, ADDRESS));
        this.present = device.command("vkQueuePresentKHR", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.submitCmd = device.command("vkQueueSubmit", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        this.beginCmd = device.command("vkBeginCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.endCmd = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.beginRp = device.command("vkCmdBeginRenderPass", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        this.bindPipe = device.command("vkCmdBindPipeline", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
        this.draw = device.command("vkCmdDraw", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        this.endRp = device.command("vkCmdEndRenderPass", FunctionDescriptor.ofVoid(ADDRESS));
        this.destroySem = device.command("vkDestroySemaphore", DL);
        this.destroyFence = device.command("vkDestroyFence", DL);
        this.destroyPool = device.command("vkDestroyCommandPool", DL);

        this.imageAvailable = createSemaphore(createSem);
        this.renderFinished = createSemaphore(createSem);
        this.inFlight = createFenceSignaled(createFence);
        this.pool = createPool(createPool);
        this.cmd = allocateCommandBuffer(allocCmd);
        this.framebuffers = new SwapchainFramebuffers(device, swapchain, pipeline.renderPass());
    }

    /** Run until the window closes, or until {@code maxFrames} presented if {@code maxFrames > 0}. */
    public void run(int maxFrames) {
        MemorySegment pImageIndex = a.allocate(JAVA_INT);
        MemorySegment pFence = a.allocate(JAVA_LONG);
        pFence.set(JAVA_LONG, 0, inFlight);
        MemorySegment waitSems = a.allocate(JAVA_LONG);
        waitSems.set(JAVA_LONG, 0, imageAvailable);
        MemorySegment signalSems = a.allocate(JAVA_LONG);
        signalSems.set(JAVA_LONG, 0, renderFinished);
        MemorySegment waitStages = a.allocate(JAVA_INT);
        waitStages.set(JAVA_INT, 0, Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
        MemorySegment pCmd = a.allocate(ADDRESS);
        pCmd.set(ADDRESS, 0, cmd);
        MemorySegment pSwapchains = a.allocate(JAVA_LONG);

        MemorySegment submit = a.allocate(SUBMIT);
        si(submit, SUBMIT, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
        si(submit, SUBMIT, "waitSemaphoreCount", 1);
        sa(submit, SUBMIT, "pWaitSemaphores", waitSems);
        sa(submit, SUBMIT, "pWaitDstStageMask", waitStages);
        si(submit, SUBMIT, "commandBufferCount", 1);
        sa(submit, SUBMIT, "pCommandBuffers", pCmd);
        si(submit, SUBMIT, "signalSemaphoreCount", 1);
        sa(submit, SUBMIT, "pSignalSemaphores", signalSems);

        MemorySegment presentInfo = a.allocate(PRESENT);
        si(presentInfo, PRESENT, "sType", Vk.STRUCTURE_TYPE_PRESENT_INFO_KHR);
        si(presentInfo, PRESENT, "waitSemaphoreCount", 1);
        sa(presentInfo, PRESENT, "pWaitSemaphores", signalSems);
        si(presentInfo, PRESENT, "swapchainCount", 1);
        sa(presentInfo, PRESENT, "pSwapchains", pSwapchains);
        sa(presentInfo, PRESENT, "pImageIndices", pImageIndex);

        MemorySegment clear = a.allocate(JAVA_FLOAT, 4);
        MemorySegment beginInfo = a.allocate(CMD_BEGIN);
        si(beginInfo, CMD_BEGIN, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        MemorySegment rpBegin = a.allocate(RENDER_PASS_BEGIN);
        si(rpBegin, RENDER_PASS_BEGIN, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
        sl(rpBegin, RENDER_PASS_BEGIN, "renderPass", pipeline.renderPass());
        si(rpBegin, RENDER_PASS_BEGIN, "area_w", swapchain.width());
        si(rpBegin, RENDER_PASS_BEGIN, "area_h", swapchain.height());
        si(rpBegin, RENDER_PASS_BEGIN, "clearValueCount", 1);
        sa(rpBegin, RENDER_PASS_BEGIN, "pClearValues", clear);

        int frame = 0;
        while (window.pumpEvents() && (maxFrames <= 0 || frame < maxFrames)) {
            check(invoke(waitFences, dev, 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
            int acq = invoke(acquire, dev, swapchain.handle(), Long.MAX_VALUE, imageAvailable, 0L, pImageIndex);
            if (acq == Vk.ERROR_OUT_OF_DATE_KHR) {
                rebuild();
                continue;
            }
            check(invoke(resetFences, dev, 1, pFence), "vkResetFences");
            int imageIndex = pImageIndex.get(JAVA_INT, 0);

            check(invoke(beginCmd, cmd, beginInfo), "vkBeginCommandBuffer");
            sl(rpBegin, RENDER_PASS_BEGIN, "framebuffer", framebuffers.framebuffer(imageIndex));
            invokeVoid(beginRp, cmd, rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
            invokeVoid(bindPipe, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
            invokeVoid(draw, cmd, vertexCount, 1, 0, 0);
            invokeVoid(endRp, cmd);
            check(invoke(endCmd, cmd), "vkEndCommandBuffer");

            check(invoke(submitCmd, device.queue(), 1, submit, inFlight), "vkQueueSubmit");
            pSwapchains.set(JAVA_LONG, 0, swapchain.handle());
            int res = invoke(present, device.queue(), presentInfo);
            if (res == Vk.ERROR_OUT_OF_DATE_KHR || res == Vk.SUBOPTIMAL_KHR) {
                rebuild();
            }
            frame++;
        }
        device.waitIdle();
    }

    private void rebuild() {
        device.waitIdle();
        framebuffers.close();
        swapchain.recreate(window.width(), window.height());
        framebuffers = new SwapchainFramebuffers(device, swapchain, pipeline.renderPass());
    }

    private long createSemaphore(MethodHandle create) {
        MemorySegment info = a.allocate(CREATE_INFO);
        si(info, CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(create, dev, info, MemorySegment.NULL, p), "vkCreateSemaphore");
        return p.get(JAVA_LONG, 0);
    }

    private long createFenceSignaled(MethodHandle create) {
        MemorySegment info = a.allocate(CREATE_INFO);
        si(info, CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
        si(info, CREATE_INFO, "flags", 0x1);
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(create, dev, info, MemorySegment.NULL, p), "vkCreateFence");
        return p.get(JAVA_LONG, 0);
    }

    private long createPool(MethodHandle create) {
        MemorySegment info = a.allocate(POOL_CI);
        si(info, POOL_CI, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
        si(info, POOL_CI, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
        si(info, POOL_CI, "queueFamilyIndex", device.queueFamilyIndex());
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(create, dev, info, MemorySegment.NULL, p), "vkCreateCommandPool");
        return p.get(JAVA_LONG, 0);
    }

    private MemorySegment allocateCommandBuffer(MethodHandle allocCmd) {
        MemorySegment info = a.allocate(CMD_ALLOC);
        si(info, CMD_ALLOC, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        sl(info, CMD_ALLOC, "commandPool", pool);
        si(info, CMD_ALLOC, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
        si(info, CMD_ALLOC, "commandBufferCount", 1);
        MemorySegment p = a.allocate(ADDRESS);
        check(invoke(allocCmd, dev, info, p), "vkAllocateCommandBuffers");
        return p.get(ADDRESS, 0);
    }

    @Override
    public void close() {
        device.waitIdle();
        invokeVoid(destroyPool, dev, pool, MemorySegment.NULL);
        invokeVoid(destroyFence, dev, inFlight, MemorySegment.NULL);
        invokeVoid(destroySem, dev, renderFinished, MemorySegment.NULL);
        invokeVoid(destroySem, dev, imageAvailable, MemorySegment.NULL);
        framebuffers.close();
        a.close();
    }
}
