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
import static dev.vexelray.vulkan.vk.Ffm.sf;
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

    private static final GroupLayout VIEWPORT = MemoryLayout.structLayout(
            JAVA_FLOAT.withName("x"), JAVA_FLOAT.withName("y"), JAVA_FLOAT.withName("width"),
            JAVA_FLOAT.withName("height"), JAVA_FLOAT.withName("minDepth"), JAVA_FLOAT.withName("maxDepth")
    ).withName("VkViewport");

    private static final GroupLayout RECT2D = MemoryLayout.structLayout(
            JAVA_INT.withName("offset_x"), JAVA_INT.withName("offset_y"),
            JAVA_INT.withName("extent_w"), JAVA_INT.withName("extent_h")).withName("VkRect2D");

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor DL = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor SET_VS = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    private final VulkanDevice device;
    private final MemorySegment dev;
    private final VulkanSwapchain swapchain;
    private final GraphicsPipeline pipeline;
    private final long renderPass;
    private final NativeWindow window;
    private final Arena a = Arena.ofShared();

    private final MethodHandle waitFences, resetFences, acquire, present, submitCmd;
    private final MethodHandle beginCmd, endCmd, beginRp, bindPipe, draw, endRp, pushConstants;
    private final MethodHandle bindVertexBuffers, bindDescriptorSets, setViewport, setScissor;
    private final MethodHandle destroySem, destroyFence, destroyPool;

    /** Per-frame hook: fill {@code pushConstants} (camera etc.) given the elapsed time; run input/sim here. */
    @FunctionalInterface
    public interface Frame {
        void update(double dtSeconds, MemorySegment pushConstants);
    }
    private final long imageAvailable, renderFinished, inFlight, pool;
    private final MemorySegment cmd;

    private SwapchainFramebuffers framebuffers;
    private int vertexCount = 3;
    private long vertexBuffer = 0;      // 0 = no vertex buffer (fullscreen triangle from gl_VertexIndex)
    private long descriptorSet = 0;     // 0 = no descriptor set to bind

    /**
     * Switch from the default fullscreen draw to a vertex-buffer draw: bind {@code vertexBuffer} at binding 0 and,
     * if non-zero, {@code descriptorSet} at set 0 (against the pipeline's layout), then draw {@code vertexCount}
     * vertices each frame. Call once before {@link #run}. Pass {@code descriptorSet == 0} for a vertex-buffer draw
     * that needs no descriptors.
     */
    public void configureDraw(long vertexBuffer, long descriptorSet, int vertexCount) {
        this.vertexBuffer = vertexBuffer;
        this.descriptorSet = descriptorSet;
        this.vertexCount = vertexCount;
    }

    /**
     * Set the number of vertices to draw for subsequent frames — call from the per-frame callback after refilling
     * a dynamic vertex buffer (immediate-mode UI rebuilt each frame).
     */
    public void setVertexCount(int vertexCount) {
        this.vertexCount = vertexCount;
    }

    public WindowedPresenter(VulkanDevice device, VulkanSwapchain swapchain, long renderPass,
                             GraphicsPipeline pipeline, NativeWindow window) {
        this.device = device;
        this.dev = device.handle();
        this.swapchain = swapchain;
        this.renderPass = renderPass;
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
        this.pushConstants = device.command("vkCmdPushConstants",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        this.bindVertexBuffers = device.command("vkCmdBindVertexBuffers",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        this.bindDescriptorSets = device.command("vkCmdBindDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.setViewport = device.command("vkCmdSetViewport", SET_VS);
        this.setScissor = device.command("vkCmdSetScissor", SET_VS);
        this.destroySem = device.command("vkDestroySemaphore", DL);
        this.destroyFence = device.command("vkDestroyFence", DL);
        this.destroyPool = device.command("vkDestroyCommandPool", DL);

        this.imageAvailable = createSemaphore(createSem);
        this.renderFinished = createSemaphore(createSem);
        this.inFlight = createFenceSignaled(createFence);
        this.pool = createPool(createPool);
        this.cmd = allocateCommandBuffer(allocCmd);
        this.framebuffers = new SwapchainFramebuffers(device, swapchain, renderPass);
    }

    /** Run until the window closes, or until {@code maxFrames} presented if {@code maxFrames > 0}. No push constants. */
    public void run(int maxFrames) {
        run(maxFrames, 0, null);
    }

    /**
     * Run the present loop, filling {@code pushConstantBytes} of push constants each frame via {@code perFrame}
     * (camera, time, input-driven sim). Runs until the window closes, or {@code maxFrames} if positive.
     *
     * <p>Single-window convenience over {@link #frame}: a multi-window host owns the loop itself and calls
     * {@code frame} on each of its presenters per iteration instead.
     */
    public void run(int maxFrames, int pushConstantBytes, Frame perFrame) {
        int frame = 0;
        while ((maxFrames <= 0 || frame < maxFrames) && frame(pushConstantBytes, perFrame)) {
            frame++;
        }
        device.waitIdle();
    }

    /**
     * One step of the present loop: pump this window's events, then acquire → record → submit → present a single
     * frame. Returns {@code false} once the window has been asked to close (nothing is presented for that call).
     * The first successful frame reveals the window ({@link NativeWindow#show()}), so it appears already painted.
     *
     * <p>This is the multi-window seam: a host that owns several windows on one thread drives each presenter one
     * {@code frame(...)} per loop iteration — every presenter pumps only its own window and touches only its own
     * swapchain, so presenters on a shared {@link VulkanDevice} interleave safely on the calling thread.
     */
    public boolean frame(int pushConstantBytes, Frame perFrame) {
        if (state == null) {
            state = new FrameState();
        }
        FrameState s = state;
        if (!window.pumpEvents()) {
            return false;
        }
        if (pushConstantBytes > s.pushCapacity) {
            s.pushSeg = a.allocate(pushConstantBytes);
            s.pushCapacity = pushConstantBytes;
        }
        check(invoke(waitFences, dev, 1, s.pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        int acq = invoke(acquire, dev, swapchain.handle(), Long.MAX_VALUE, imageAvailable, 0L, s.pImageIndex);
        if (acq == Vk.ERROR_OUT_OF_DATE_KHR) {
            rebuild();
            return true;   // skip this frame; the window is still open
        }
        check(invoke(resetFences, dev, 1, s.pFence), "vkResetFences");
        int imageIndex = s.pImageIndex.get(JAVA_INT, 0);

        long now = System.nanoTime();
        double dt = (now - s.previousNanos) / 1_000_000_000.0;
        s.previousNanos = now;
        // Called every frame when set — carries the push-constant segment (NULL when there are no push
        // constants). The callback may also refill a dynamic vertex buffer and call setVertexCount(...).
        if (perFrame != null) {
            perFrame.update(dt, s.pushSeg);
        }

        // Track the live swapchain extent (it changes on resize/rebuild) for the render area and dynamic
        // viewport/scissor this frame. Draw bindings are re-read each frame so a late configureDraw takes effect.
        int extentW = swapchain.width();
        int extentH = swapchain.height();
        s.pVertexBuffers.set(JAVA_LONG, 0, vertexBuffer);
        s.pDescriptorSet.set(JAVA_LONG, 0, descriptorSet);

        check(invoke(beginCmd, cmd, s.beginInfo), "vkBeginCommandBuffer");
        si(s.rpBegin, RENDER_PASS_BEGIN, "area_w", extentW);
        si(s.rpBegin, RENDER_PASS_BEGIN, "area_h", extentH);
        sl(s.rpBegin, RENDER_PASS_BEGIN, "framebuffer", framebuffers.framebuffer(imageIndex));
        invokeVoid(beginRp, cmd, s.rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
        invokeVoid(bindPipe, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
        // Only set dynamic viewport/scissor when the pipeline declared them dynamic; a fixed-viewport pipeline
        // must not receive these commands.
        if (pipeline.hasDynamicViewport()) {
            sf(s.pViewport, VIEWPORT, "width", extentW);
            sf(s.pViewport, VIEWPORT, "height", extentH);
            invokeVoid(setViewport, cmd, 0, 1, s.pViewport);
            si(s.pScissor, RECT2D, "extent_w", extentW);
            si(s.pScissor, RECT2D, "extent_h", extentH);
            invokeVoid(setScissor, cmd, 0, 1, s.pScissor);
        }
        if (descriptorSet != 0) {
            invokeVoid(bindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(),
                    0, 1, s.pDescriptorSet, 0, MemorySegment.NULL);
        }
        if (vertexBuffer != 0) {
            invokeVoid(bindVertexBuffers, cmd, 0, 1, s.pVertexBuffers, s.pVertexOffsets);
        }
        if (pushConstantBytes > 0) {
            invokeVoid(pushConstants, cmd, pipeline.pipelineLayout(), Vk.SHADER_STAGE_FRAGMENT_BIT, 0,
                    pushConstantBytes, s.pushSeg);
        }
        invokeVoid(draw, cmd, vertexCount, 1, 0, 0);
        invokeVoid(endRp, cmd);
        check(invoke(endCmd, cmd), "vkEndCommandBuffer");

        check(invoke(submitCmd, device.queue(), 1, s.submit, inFlight), "vkQueueSubmit");
        s.pSwapchains.set(JAVA_LONG, 0, swapchain.handle());
        int res = invoke(present, device.queue(), s.presentInfo);
        if (res == Vk.ERROR_OUT_OF_DATE_KHR || res == Vk.SUBOPTIMAL_KHR) {
            rebuild();
        }
        if (!s.shown) {
            // First frame is on screen — reveal the (until-now hidden) window already painted, so slow Vulkan
            // bring-up never shows a blank/unresponsive window.
            window.show();
            s.shown = true;
        }
        return true;
    }

    private FrameState state;

    /** The loop-invariant native structs, built once on the first {@link #frame} and reused every frame. */
    private final class FrameState {
        final MemorySegment pImageIndex = a.allocate(JAVA_INT);
        final MemorySegment pFence = a.allocate(JAVA_LONG);
        final MemorySegment waitSems = a.allocate(JAVA_LONG);
        final MemorySegment signalSems = a.allocate(JAVA_LONG);
        final MemorySegment waitStages = a.allocate(JAVA_INT);
        final MemorySegment pCmd = a.allocate(ADDRESS);
        final MemorySegment pSwapchains = a.allocate(JAVA_LONG);
        final MemorySegment submit = a.allocate(SUBMIT);
        final MemorySegment presentInfo = a.allocate(PRESENT);
        final MemorySegment pVertexBuffers = a.allocate(JAVA_LONG);
        final MemorySegment pVertexOffsets = a.allocate(JAVA_LONG);
        final MemorySegment pDescriptorSet = a.allocate(JAVA_LONG);
        final MemorySegment pViewport = a.allocate(VIEWPORT);
        final MemorySegment pScissor = a.allocate(RECT2D);
        final MemorySegment clear = a.allocate(JAVA_FLOAT, 4);
        final MemorySegment beginInfo = a.allocate(CMD_BEGIN);
        final MemorySegment rpBegin = a.allocate(RENDER_PASS_BEGIN);
        MemorySegment pushSeg = MemorySegment.NULL;
        int pushCapacity = 0;
        long previousNanos = System.nanoTime();
        boolean shown = false;

        FrameState() {
            pFence.set(JAVA_LONG, 0, inFlight);
            waitSems.set(JAVA_LONG, 0, imageAvailable);
            signalSems.set(JAVA_LONG, 0, renderFinished);
            waitStages.set(JAVA_INT, 0, Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            pCmd.set(ADDRESS, 0, cmd);
            pVertexOffsets.set(JAVA_LONG, 0, 0L);

            si(submit, SUBMIT, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, SUBMIT, "waitSemaphoreCount", 1);
            sa(submit, SUBMIT, "pWaitSemaphores", waitSems);
            sa(submit, SUBMIT, "pWaitDstStageMask", waitStages);
            si(submit, SUBMIT, "commandBufferCount", 1);
            sa(submit, SUBMIT, "pCommandBuffers", pCmd);
            si(submit, SUBMIT, "signalSemaphoreCount", 1);
            sa(submit, SUBMIT, "pSignalSemaphores", signalSems);

            si(presentInfo, PRESENT, "sType", Vk.STRUCTURE_TYPE_PRESENT_INFO_KHR);
            si(presentInfo, PRESENT, "waitSemaphoreCount", 1);
            sa(presentInfo, PRESENT, "pWaitSemaphores", signalSems);
            si(presentInfo, PRESENT, "swapchainCount", 1);
            sa(presentInfo, PRESENT, "pSwapchains", pSwapchains);
            sa(presentInfo, PRESENT, "pImageIndices", pImageIndex);

            sf(pViewport, VIEWPORT, "maxDepth", 1.0f);

            si(beginInfo, CMD_BEGIN, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(rpBegin, RENDER_PASS_BEGIN, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            sl(rpBegin, RENDER_PASS_BEGIN, "renderPass", renderPass);
            si(rpBegin, RENDER_PASS_BEGIN, "clearValueCount", 1);
            sa(rpBegin, RENDER_PASS_BEGIN, "pClearValues", clear);
        }
    }

    private void rebuild() {
        device.waitIdle();
        framebuffers.close();
        swapchain.recreate(window.width(), window.height());
        framebuffers = new SwapchainFramebuffers(device, swapchain, renderPass);
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
