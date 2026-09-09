package dev.vexelray.vulkan.present;

import dev.vexelray.os.NativeWindow;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.List;
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

    /**
     * Per-frame hook that records the <em>contents</em> of the render pass — the seam that turns this class from
     * "present one pipeline" into "present whatever a caller draws."
     *
     * <p>Everything this presenter is careful about — the fence, the image acquire, the two semaphores, the
     * submit, the present, the out-of-date rebuild — stays here. What moves out is the eight commands between
     * {@code vkCmdBeginRenderPass} and {@code vkCmdEndRenderPass}. That division is not a refactor for
     * tidiness: it is precisely the line between what every renderer needs identically and what each one needs
     * differently, and until it existed a frame could contain exactly one pipeline, so no two features in this
     * repository could appear in the same window.
     *
     * <p>The command buffer arrives inside a begun render pass with <b>nothing bound</b> — no pipeline, no
     * descriptor sets, no vertex buffer, no dynamic viewport. A recorder binds what it needs, per pipeline it
     * draws with, in the order it wants them composited. It must not begin or end the render pass, submit, or
     * touch the swapchain.
     *
     * <p>{@code width} and {@code height} are this frame's extent, which is not the extent the pipeline was
     * built at: a resize changes it without rebuilding anything. A recorder whose pipelines declared dynamic
     * viewport must set viewport and scissor from these, every frame.
     */
    @FunctionalInterface
    public interface Recorder {
        void record(MemorySegment commandBuffer, int width, int height);
    }
    private final long imageAvailable, renderFinished, inFlight, pool;
    private final MemorySegment cmd;

    private SwapchainFramebuffers framebuffers;
    /** The depth format this presenter's render pass was built with, or {@link VulkanRenderPass#NO_DEPTH}. */
    private final int depthFormat;
    /** The depth image behind {@link #framebuffers}, or null for a colour-only pass. Replaced on resize. */
    private DepthAttachment depth;
    // Teardown happens once. Destroying an already-destroyed VkCommandPool (or fence, or semaphore) is undefined
    // behaviour that corrupts the loader's own heap — the same heap the device dispatch table lives on — so the
    // symptom surfaces much later, as a call through an entry point that is no longer code. Being idempotent is far
    // cheaper than debugging that: a DEP violation inside the driver names neither this class nor the second close.
    private boolean closed;
    private int vertexCount = 3;
    private long vertexBuffer = 0;      // 0 = no vertex buffer (fullscreen triangle from gl_VertexIndex)
    private long descriptorSet = 0;     // 0 = no descriptor set to bind
    private volatile List<Run> runs = List.of();   // empty = one draw of the whole buffer, no set 1

    /**
     * One span of the vertex buffer drawn with {@code descriptorSet1} bound at set 1 — the presenter's view of a
     * {@code Canvas.Run}. The vertex buffer, the pipeline and set 0 are bound once for the frame; only set 1 is
     * rebound between runs, so a frame of N images is one bind + N rebinds + N+1 draws rather than N pipelines.
     *
     * @param descriptorSet1 the set to bind at index 1 before this span (never 0 — pass the placeholder's)
     * @param firstVertex    index of the span's first vertex
     * @param vertexCount    how many vertices it covers
     */
    public record Run(long descriptorSet1, int firstVertex, int vertexCount) {
    }

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
     *
     * <p>Clears any {@link #setRuns runs}: a caller that sets a plain vertex count is drawing one span, and leaving
     * last frame's runs standing would draw the new buffer through the old frame's split.
     */
    public void setVertexCount(int vertexCount) {
        this.vertexCount = vertexCount;
        this.runs = List.of();
    }

    /**
     * Draw this frame as a sequence of {@link Run}s instead of one span — the multi-image path. Call from the
     * per-frame callback after refilling the vertex buffer, in place of {@link #setVertexCount}.
     *
     * <p>An empty list draws nothing, which is the honest reading of a frame with no vertices in it.
     */
    public void setRuns(List<Run> runs) {
        this.runs = List.copyOf(runs);
        int total = 0;
        for (Run r : this.runs) {
            total = Math.max(total, r.firstVertex() + r.vertexCount());
        }
        this.vertexCount = total;
    }

    /**
     * A presenter over a colour-only render pass, drawing one pipeline — the spelling every caller predating
     * depth and techniques uses, and what it still does.
     */
    public WindowedPresenter(VulkanDevice device, VulkanSwapchain swapchain, long renderPass,
                             GraphicsPipeline pipeline, NativeWindow window) {
        this(device, swapchain, renderPass, pipeline, window, VulkanRenderPass.NO_DEPTH);
    }

    /**
     * A presenter that also owns a depth attachment, when {@code depthFormat} is not
     * {@link VulkanRenderPass#NO_DEPTH}.
     *
     * <p>The presenter owns the depth image rather than receiving one, and it must: depth is sized to the
     * extent, so it has to be recreated on every resize, and the only thing here that knows a resize happened
     * is {@link #rebuild()}. Handing in a {@code DepthAttachment} would leave the caller holding an object the
     * presenter has to replace behind its back — which works right up until someone keeps the old handle.
     *
     * <p>{@code depthFormat} must agree with what {@code renderPass} was built with. It is passed separately
     * because the presenter is given a render-pass <em>handle</em>, and a handle cannot be asked.
     */
    /**
     * A presenter with <b>no pipeline of its own</b>, for a caller that always supplies a {@link Recorder}.
     *
     * <p>An engine driving techniques has no single pipeline to name — that is the point of it — and passing
     * null to the constructor above would leave a field that is fine until someone calls a {@code run} overload
     * without a recorder and gets a null dereference inside a command buffer. This spelling says the pipeline
     * is absent, and the recorder-less paths refuse rather than crash.
     */
    public WindowedPresenter(VulkanDevice device, VulkanSwapchain swapchain, long renderPass,
                             NativeWindow window, int depthFormat) {
        this(device, swapchain, renderPass, null, window, depthFormat);
    }

    public WindowedPresenter(VulkanDevice device, VulkanSwapchain swapchain, long renderPass,
                             GraphicsPipeline pipeline, NativeWindow window, int depthFormat) {
        Probe.opened(Lane.GPU, "WindowedPresenter", this);
        this.depthFormat = depthFormat;
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
        this.depth = depthFormat == VulkanRenderPass.NO_DEPTH
                ? null
                : new DepthAttachment(device, swapchain.width(), swapchain.height());
        this.framebuffers = new SwapchainFramebuffers(device, swapchain, renderPass,
                depth == null ? SwapchainFramebuffers.NO_DEPTH_VIEW : depth.view());
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
        run(maxFrames, pushConstantBytes, perFrame, null);
    }

    /**
     * The present loop with the render pass's <em>recording</em> delegated to {@code recorder} rather than done
     * by this class — how several techniques get into one frame. See {@link Recorder}.
     */
    public void run(int maxFrames, int pushConstantBytes, Frame perFrame, Recorder recorder) {
        int frame = 0;
        while ((maxFrames <= 0 || frame < maxFrames) && frame(pushConstantBytes, perFrame, recorder)) {
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
        return frame(pushConstantBytes, perFrame, null);
    }

    /** {@link #frame(int, Frame)} with the pass's recording delegated to {@code recorder}. */
    public boolean frame(int pushConstantBytes, Frame perFrame, Recorder recorder) {
        if (!window.pumpEvents()) {
            return false;
        }
        return render(pushConstantBytes, perFrame, recorder);
    }

    /**
     * {@link #frame} without the pump: acquire → record → submit → present one frame against the window as it
     * currently is. This is the entry point for frames the <em>platform</em> asks for rather than the host loop —
     * a Win32 modal move/resize, a macOS live resize — where the pump is already running one level up, and
     * pumping again from in here would re-enter the OS's own loop ({@link NativeWindow#setFrameSink}).
     *
     * <p>Re-entrancy is refused rather than queued: a render requested while one is in flight returns without
     * drawing, because a platform timer that outruns the frame time must not stack frames inside one another
     * over a single command buffer and fence.
     */
    public boolean render(int pushConstantBytes, Frame perFrame) {
        return render(pushConstantBytes, perFrame, null);
    }

    /** {@link #render(int, Frame)} with the pass's recording delegated to {@code recorder}. */
    public boolean render(int pushConstantBytes, Frame perFrame, Recorder recorder) {
        if (rendering) {
            return true;
        }
        rendering = true;
        try {
            return renderOnce(pushConstantBytes, perFrame, recorder);
        } finally {
            rendering = false;
        }
    }

    private boolean renderOnce(int pushConstantBytes, Frame perFrame, Recorder recorder) {
        try (Zone z = Probe.zone(Lane.GPU, "present frame")) {
            return renderFrame(pushConstantBytes, perFrame, recorder);
        }
    }

    /** The body of {@link #renderOnce}, split out so one probe span covers a whole presented frame. */
    private boolean renderFrame(int pushConstantBytes, Frame perFrame, Recorder recorder) {
        if (state == null) {
            state = new FrameState();
        }
        FrameState s = state;
        if (pushConstantBytes > s.pushCapacity) {
            s.pushSeg = a.allocate(pushConstantBytes);
            s.pushCapacity = pushConstantBytes;
        }
        // The single most diagnostic number in this whole file. One frame is in flight, so this fence is the
        // CPU waiting for the GPU to finish the *previous* frame - and a loop that is GPU-bound spends its
        // budget here and nowhere else. A "driver overload" report with a large fence wait and a small
        // everything-else is not an application problem at all, and this is the line that says so.
        try (Zone w = Probe.zone(Lane.GPU, "wait fence")) {
            check(invoke(waitFences, dev, 1, s.pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        }
        int acq;
        try (Zone w = Probe.zone(Lane.GPU, "acquire image")) {
            acq = invoke(acquire, dev, swapchain.handle(), Long.MAX_VALUE, imageAvailable, 0L, s.pImageIndex);
        }
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
        if (recorder != null) {
            // The pass is begun and nothing is bound. Everything between here and endRp belongs to the caller,
            // which is the whole seam: one render pass, one command buffer, N pipelines bound in turn. This
            // class keeps acquire, sync, submit and present — the parts a technique must never touch.
            recorder.record(cmd, extentW, extentH);
            invokeVoid(endRp, cmd);
            check(invoke(endCmd, cmd), "vkEndCommandBuffer");
            return submitAndPresent(s);
        }
        if (pipeline == null) {
            throw new IllegalStateException("this presenter was built without a pipeline, so every frame needs a "
                    + "Recorder; call a run/frame/render overload that takes one");
        }
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
        List<Run> frameRuns = runs;
        if (frameRuns.isEmpty()) {
            invokeVoid(draw, cmd, vertexCount, 1, 0, 0);
        } else {
            // Rebind set 1 only when the run actually changes it. Runs are contiguous and in submission order, so
            // drawing them back to back is the same picture the single draw would have made — the split is a
            // binding concern, never a layering one.
            long bound = 0;
            for (Run r : frameRuns) {
                if (r.vertexCount() <= 0) {
                    continue;
                }
                if (r.descriptorSet1() != bound) {
                    bound = r.descriptorSet1();
                    s.pDescriptorSet1.set(JAVA_LONG, 0, bound);
                    invokeVoid(bindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(),
                            1, 1, s.pDescriptorSet1, 0, MemorySegment.NULL);
                }
                invokeVoid(draw, cmd, r.vertexCount(), 1, r.firstVertex(), 0);
            }
        }
        invokeVoid(endRp, cmd);
        check(invoke(endCmd, cmd), "vkEndCommandBuffer");
        return submitAndPresent(s);
    }

    /**
     * Submit the recorded command buffer, present it, and handle an out-of-date swapchain — the tail both
     * recording paths share.
     *
     * <p>Extracted rather than duplicated because these are the steps a technique must never own, and two
     * copies of them is two places for a fence or a semaphore to drift out of agreement.
     */
    private boolean submitAndPresent(FrameState s) {
        try (Zone w = Probe.zone(Lane.GPU, "queue submit")) {
            check(invoke(submitCmd, device.queue(), 1, s.submit, inFlight), "vkQueueSubmit");
        }
        s.pSwapchains.set(JAVA_LONG, 0, swapchain.handle());
        int res;
        try (Zone w = Probe.zone(Lane.GPU, "queue present")) {
            res = invoke(present, device.queue(), s.presentInfo);
        }
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
    /** Guards render(): a platform-pulled frame must never nest inside the one already in flight. */
    private boolean rendering;

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
        final MemorySegment pDescriptorSet1 = a.allocate(JAVA_LONG);
        final MemorySegment pViewport = a.allocate(VIEWPORT);
        final MemorySegment pScissor = a.allocate(RECT2D);
        // A VkClearValue is a 16-byte union, and there must be one per attachment the pass clears. With depth
        // that is two: four floats of colour, then the depth float in the first slot of the second union.
        // Under-counting here is not a validation error the loader always catches — it reads whatever follows.
        final MemorySegment clear = a.allocate(JAVA_FLOAT, depthFormat == VulkanRenderPass.NO_DEPTH ? 4 : 8);
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
            if (depthFormat != VulkanRenderPass.NO_DEPTH) {
                clear.setAtIndex(JAVA_FLOAT, 4, DepthAttachment.CLEAR_DEPTH);
                si(rpBegin, RENDER_PASS_BEGIN, "clearValueCount", 2);
            } else {
                si(rpBegin, RENDER_PASS_BEGIN, "clearValueCount", 1);
            }
            sa(rpBegin, RENDER_PASS_BEGIN, "pClearValues", clear);
        }
    }

    private void rebuild() {
        // Counted because swapchain churn is a cost that hides: a drag-resize rebuilds every frame, each one
        // a full device idle, and the frame times it produces look like a rendering problem rather than the
        // resize it actually is.
        try (Zone z = Probe.zone(Lane.GPU, "swapchain rebuild")) {
            device.waitIdle();
            framebuffers.close();
            swapchain.recreate(window.width(), window.height());
            // Depth is sized to the extent, so it goes with the framebuffers. Only when the extent actually
            // changed: a rebuild triggered by OUT_OF_DATE on an unchanged window (which happens) would
            // otherwise destroy and reallocate a full-screen image for nothing.
            if (depth != null && !depth.matches(swapchain.width(), swapchain.height())) {
                depth.close();
                depth = new DepthAttachment(device, swapchain.width(), swapchain.height());
            }
            framebuffers = new SwapchainFramebuffers(device, swapchain, renderPass,
                    depth == null ? SwapchainFramebuffers.NO_DEPTH_VIEW : depth.view());
        }
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
        if (closed) {
            return;
        }
        closed = true;
        // After the idempotence guard, not before: the ledger counts closes, and a second close that returns
        // early has not closed anything.
        Probe.closed(Lane.GPU, "WindowedPresenter", this);
        device.waitIdle();
        invokeVoid(destroyPool, dev, pool, MemorySegment.NULL);
        invokeVoid(destroyFence, dev, inFlight, MemorySegment.NULL);
        invokeVoid(destroySem, dev, renderFinished, MemorySegment.NULL);
        invokeVoid(destroySem, dev, imageAvailable, MemorySegment.NULL);
        framebuffers.close();
        if (depth != null) {
            depth.close();
        }
        a.close();
    }
}
