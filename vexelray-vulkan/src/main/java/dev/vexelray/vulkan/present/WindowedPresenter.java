package dev.vexelray.vulkan.present;

import dev.vexelray.os.NativeWindow;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkStructs;
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
 * Drives the windowed present loop for a {@link GraphicsPipeline}: owns the per-image framebuffers and depth,
 * the frame synchronisation, and the command buffers, and runs acquire → render pass → draw → submit →
 * present each frame until the window closes (or a frame cap is hit). Recreates the swapchain and everything
 * sized to it on resize. This is the reusable present machinery a client app drives instead of hand-rolling
 * Vulkan.
 *
 * <h2>Frames in flight</h2>
 *
 * <p>The presenter records {@code framesInFlight} frames ahead of the GPU. What that changes is one number —
 * how old the frame is that the fence at the top of a frame waits for — and what it costs is a duplicated set
 * of GPU objects, described where they are declared below. <b>It does not change the threading contract.</b>
 * Every frame is still recorded on the thread that called in, one at a time; what overlaps is the CPU's
 * recording of frame N+1 with the GPU's execution of frame N, and that overlap is the GPU's, not a second
 * thread's.
 *
 * <p>Three kinds of object come in sets, and which set an object belongs to is the whole of the design:
 *
 * <ul>
 *   <li><b>Per slot</b> ({@code framesInFlight} of them) — command buffer, in-flight fence, and the
 *       image-available semaphore. These are what a frame holds for as long as it is unfinished.</li>
 *   <li><b>Per swapchain image</b> — depth attachment, framebuffer, and the render-finished semaphore. Two
 *       frames in flight are drawing into two different images, so anything hung off an image has to come in
 *       image-many copies or the later frame stamps on the earlier.</li>
 *   <li><b>Shared</b> — the native structs. Every Vulkan call reads its host memory during the call, so one
 *       {@code VkSubmitInfo} rewritten per frame is not a hazard; the objects it points at are.</li>
 * </ul>
 *
 * <p>The two mistakes this arrangement exists to avoid are both silent. A render-finished semaphore per slot
 * rather than per image can be re-signalled while a present is still waiting on it, because a present's wait
 * has no fence to observe it. And an acquire may return an image whose previous frame is still running, which
 * the acquire semaphore covers for the swapchain image and not at all for the depth image beside it.
 *
 * <p>v0 draws a fixed vertex count with no per-frame data (the fullscreen triangle); per-frame push constants
 * (camera, time) arrive with the pipeline-layout work in a later step.
 */
public final class WindowedPresenter implements AutoCloseable {

    /**
     * The one struct here that no other class needs: presenting is what this presenter is for.
     *
     * <p>Everything else it fills — the pool, the command buffer, the render-pass begin, the submit, the
     * viewport and scissor — comes from {@link VkStructs}, which is where the layouts that had been
     * transcribed three times now live. This one stays because a shared home for a layout with a single
     * caller is not sharing, it is indirection.
     */
    private static final GroupLayout PRESENT = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            JAVA_INT.withName("swapchainCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pSwapchains"),
            ADDRESS.withName("pImageIndices"), ADDRESS.withName("pResults")).withName("VkPresentInfoKHR");

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
    /** Kept because {@link #rebuild()} makes a fresh render-finished semaphore per swapchain image. */
    private final MethodHandle createSem;

    /**
     * How many frames the CPU may record ahead of the GPU — the number of <em>slots</em> below.
     *
     * <p>At one, the fence wait at the top of a frame is the CPU waiting for the GPU to finish the previous
     * frame, and the two never overlap. At two or three, the wait is for the frame <em>N slots</em> ago, so
     * the CPU can be recording frame N+1 while the GPU is still drawing frame N. What that buys is
     * throughput; what it costs is latency, because a frame the CPU records is now one or two frames from
     * being on screen. It never costs a thread: everything here still happens on the caller's.
     */
    private final int framesInFlight;

    // --- per slot: the objects a frame owns for as long as it is in flight -------------------------------
    //
    // A command buffer must not be re-recorded while the GPU is still executing it, and a fence cannot
    // describe two frames at once. So there is one of each per slot, and slot = frameCounter % framesInFlight.
    // The imageAvailable semaphore is per slot too, because it is signalled by the acquire that begins a
    // frame and waited by that frame's own submit.
    private final long[] inFlight;
    private final long[] imageAvailable;
    private final MemorySegment[] cmds;
    private final long pool;

    /** Which slot the next frame uses; the only reason this class counts frames at all. */
    private long frameCounter;

    // --- per swapchain image: rebuilt with the swapchain ---------------------------------------------------

    private SwapchainFramebuffers framebuffers;

    /**
     * One depth image per swapchain image, or null for a colour-only pass.
     *
     * <p>Per image rather than per slot, which is the arrangement that keeps framebuffers one-to-one with
     * images: a framebuffer names its attachments, so a depth image shared by fewer objects than there are
     * images would need a framebuffer per (image, slot) pair. One depth image per image is one more image
     * than the minimum and a great deal less bookkeeping.
     */
    private DepthAttachment[] depths;

    /**
     * The semaphore each image's render signals and its present waits on — <b>per image, not per slot.</b>
     *
     * <p>The subtle one. A semaphore may not be signalled again until the wait on it has completed, and
     * {@code vkQueuePresentKHR}'s wait completes at a moment the application has no way to observe: there is
     * no fence for it. With a per-slot semaphore and more images than slots, a later frame can reach its
     * submit while an earlier present on the same semaphore is still outstanding, which is a validation
     * error on a good day and a hang on a bad one. Tying the semaphore to the image makes the acquire that
     * returns the image the proof that its previous present has finished.
     */
    private long[] renderFinished;

    /**
     * For each swapchain image, the fence of the frame currently using it, or 0.
     *
     * <p>Acquire is free to hand back an image whose previous frame is still in flight — with three images
     * and two slots it is uncommon, but nothing forbids it, and a mailbox present mode makes it ordinary.
     * The acquire semaphore orders access to the swapchain image itself; it says nothing about the depth
     * image and framebuffer this class hung off it. So before drawing into an image, wait for whoever had it
     * last.
     */
    private long[] imageInFlight;

    /** The depth format this presenter's render pass was built with, or {@link VulkanRenderPass#NO_DEPTH}. */
    private final int depthFormat;
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
        this(device, swapchain, renderPass, null, window, depthFormat, 1);
    }

    /**
     * The same, recording {@code framesInFlight} frames ahead of the GPU.
     *
     * <p>Separate from the constructor above rather than defaulted into it, because every caller that does
     * not say has a reason not to: the single-pipeline demos drive one frame at a time deliberately, and an
     * engine passes what its {@code EngineConfig} was given.
     */
    public WindowedPresenter(VulkanDevice device, VulkanSwapchain swapchain, long renderPass,
                             NativeWindow window, int depthFormat, int framesInFlight) {
        this(device, swapchain, renderPass, null, window, depthFormat, framesInFlight);
    }

    public WindowedPresenter(VulkanDevice device, VulkanSwapchain swapchain, long renderPass,
                             GraphicsPipeline pipeline, NativeWindow window, int depthFormat) {
        this(device, swapchain, renderPass, pipeline, window, depthFormat, 1);
    }

    public WindowedPresenter(VulkanDevice device, VulkanSwapchain swapchain, long renderPass,
                             GraphicsPipeline pipeline, NativeWindow window, int depthFormat,
                             int framesInFlight) {
        if (framesInFlight < 1) {
            throw new IllegalArgumentException("framesInFlight must be at least 1, got " + framesInFlight);
        }
        Probe.opened(Lane.GPU, "WindowedPresenter", this);
        this.framesInFlight = framesInFlight;
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

        this.pool = createPool(createPool);
        this.inFlight = new long[framesInFlight];
        this.imageAvailable = new long[framesInFlight];
        this.cmds = new MemorySegment[framesInFlight];
        for (int slot = 0; slot < framesInFlight; slot++) {
            // Signalled, so the first frame in every slot waits on a fence that is already done rather than
            // one nothing has submitted against.
            inFlight[slot] = createFenceSignaled(createFence);
            imageAvailable[slot] = createSemaphore(createSem);
            cmds[slot] = allocateCommandBuffer(allocCmd);
        }
        this.createSem = createSem;
        buildPerImage();
    }

    /**
     * Create everything sized to the swapchain: a depth image and a render-finished semaphore per image, the
     * framebuffers over them, and the empty in-flight table.
     *
     * <p>Called from the constructor and again from {@link #rebuild()}, because a recreated swapchain may
     * hand back a different number of images — so the arrays are not merely re-filled, they are re-sized.
     */
    private void buildPerImage() {
        int images = swapchain.images().length;
        this.renderFinished = new long[images];
        for (int i = 0; i < images; i++) {
            renderFinished[i] = createSemaphore(createSem);
        }
        this.imageInFlight = new long[images];
        if (depthFormat == VulkanRenderPass.NO_DEPTH) {
            this.depths = null;
            this.framebuffers = new SwapchainFramebuffers(device, swapchain, renderPass);
            return;
        }
        this.depths = new DepthAttachment[images];
        long[] depthViews = new long[images];
        for (int i = 0; i < images; i++) {
            depths[i] = new DepthAttachment(device, swapchain.width(), swapchain.height());
            depthViews[i] = depths[i].view();
        }
        this.framebuffers = new SwapchainFramebuffers(device, swapchain, renderPass, depthViews);
    }

    /** Destroy what {@link #buildPerImage} made. The caller must have waited for the device to go idle. */
    private void destroyPerImage() {
        framebuffers.close();
        if (depths != null) {
            for (DepthAttachment d : depths) {
                d.close();
            }
            depths = null;
        }
        for (long semaphore : renderFinished) {
            invokeVoid(destroySem, dev, semaphore, MemorySegment.NULL);
        }
        renderFinished = new long[0];
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
    public void run(int maxFrames, int pushConstantBytes, FrameUpdate perFrame) {
        run(maxFrames, pushConstantBytes, perFrame, null);
    }

    /**
     * The present loop with the render pass's <em>recording</em> delegated to {@code recorder} rather than done
     * by this class — how several techniques get into one frame. See {@link Recorder}.
     */
    public void run(int maxFrames, int pushConstantBytes, FrameUpdate perFrame, Recorder recorder) {
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
    public boolean frame(int pushConstantBytes, FrameUpdate perFrame) {
        return frame(pushConstantBytes, perFrame, null);
    }

    /** {@link #frame(int, FrameUpdate)} with the pass's recording delegated to {@code recorder}. */
    public boolean frame(int pushConstantBytes, FrameUpdate perFrame, Recorder recorder) {
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
    public boolean render(int pushConstantBytes, FrameUpdate perFrame) {
        return render(pushConstantBytes, perFrame, null);
    }

    /** {@link #render(int, FrameUpdate)} with the pass's recording delegated to {@code recorder}. */
    public boolean render(int pushConstantBytes, FrameUpdate perFrame, Recorder recorder) {
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

    private boolean renderOnce(int pushConstantBytes, FrameUpdate perFrame, Recorder recorder) {
        try (Zone z = Probe.zone(Lane.GPU, "present frame")) {
            return renderFrame(pushConstantBytes, perFrame, recorder);
        }
    }

    /** The body of {@link #renderOnce}, split out so one probe span covers a whole presented frame. */
    private boolean renderFrame(int pushConstantBytes, FrameUpdate perFrame, Recorder recorder) {
        if (state == null) {
            state = new FrameState();
        }
        FrameState s = state;
        if (pushConstantBytes > s.pushCapacity) {
            s.pushSeg = a.allocate(pushConstantBytes);
            s.pushCapacity = pushConstantBytes;
        }
        int slot = (int) (frameCounter % framesInFlight);
        MemorySegment cmd = cmds[slot];

        // The single most diagnostic number in this whole file. This is the CPU waiting for the GPU to
        // finish the frame *framesInFlight ago* - so at one frame in flight it is the previous frame, and a
        // loop that is GPU-bound spends its budget here and nowhere else. A "driver overload" report with a
        // large fence wait and a small everything-else is not an application problem at all, and this is the
        // line that says so. Raising framesInFlight is precisely the move that shrinks this number, by
        // giving the CPU an older frame to wait on.
        s.pFence.set(JAVA_LONG, 0, inFlight[slot]);
        try (Zone w = Probe.zone(Lane.GPU, "wait fence")) {
            check(invoke(waitFences, dev, 1, s.pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        }
        int acq;
        try (Zone w = Probe.zone(Lane.GPU, "acquire image")) {
            acq = invoke(acquire, dev, swapchain.handle(), Long.MAX_VALUE, imageAvailable[slot], 0L,
                    s.pImageIndex);
        }
        if (acq == Vk.ERROR_OUT_OF_DATE_KHR) {
            rebuild();
            // Not counted: the slot's fence is still signalled and its imageAvailable semaphore was never
            // waited on, so the next frame must reuse this slot rather than move past it. Advancing here
            // would leave a semaphore signalled with nothing to consume it.
            return true;   // skip this frame; the window is still open
        }
        int imageIndex = s.pImageIndex.get(JAVA_INT, 0);

        // This image may still belong to a frame that has not finished. The acquire semaphore orders the
        // swapchain image itself; it says nothing about the depth image and framebuffer hung off it here.
        if (imageInFlight[imageIndex] != 0 && imageInFlight[imageIndex] != inFlight[slot]) {
            s.pImageFence.set(JAVA_LONG, 0, imageInFlight[imageIndex]);
            try (Zone w = Probe.zone(Lane.GPU, "wait image")) {
                check(invoke(waitFences, dev, 1, s.pImageFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
            }
        }
        imageInFlight[imageIndex] = inFlight[slot];

        // Reset after every wait above, and only once this frame is certain to submit: a fence reset on a
        // frame that then returned early would never be signalled again, and the next visit to this slot
        // would wait on it for ever.
        check(invoke(resetFences, dev, 1, s.pFence), "vkResetFences");
        s.pCmd.set(ADDRESS, 0, cmd);
        s.waitSems.set(JAVA_LONG, 0, imageAvailable[slot]);
        s.signalSems.set(JAVA_LONG, 0, renderFinished[imageIndex]);
        frameCounter++;

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
        si(s.rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "area_extent_width", extentW);
        si(s.rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "area_extent_height", extentH);
        sl(s.rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "framebuffer", framebuffers.framebuffer(imageIndex));
        invokeVoid(beginRp, cmd, s.rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
        if (recorder != null) {
            // The pass is begun and nothing is bound except the frame's viewport and scissor. Everything
            // between here and endRp belongs to the caller, which is the whole seam: one render pass, one
            // command buffer, N pipelines bound in turn. This class keeps acquire, sync, submit and present —
            // the parts a technique must never touch.
            //
            // Unconditionally, unlike the single-pipeline path below: there is no pipeline here to ask
            // hasDynamicViewport() of, and setting state a later static-viewport pipeline ignores is legal and
            // free, whereas leaving it unset makes every dynamic-viewport recorder draw nothing.
            setFullViewport(s, cmd, extentW, extentH);
            recorder.record(cmd, extentW, extentH);
            invokeVoid(endRp, cmd);
            check(invoke(endCmd, cmd), "vkEndCommandBuffer");
            return submitAndPresent(s, inFlight[slot]);
        }
        if (pipeline == null) {
            throw new IllegalStateException("this presenter was built without a pipeline, so every frame needs a "
                    + "Recorder; call a run/frame/render overload that takes one");
        }
        invokeVoid(bindPipe, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
        // Only set dynamic viewport/scissor when the pipeline declared them dynamic; a fixed-viewport pipeline
        // must not receive these commands.
        if (pipeline.hasDynamicViewport()) {
            setFullViewport(s, cmd, extentW, extentH);
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
        return submitAndPresent(s, inFlight[slot]);
    }

    /**
     * Submit the recorded command buffer, present it, and handle an out-of-date swapchain — the tail both
     * recording paths share.
     *
     * <p>Extracted rather than duplicated because these are the steps a technique must never own, and two
     * copies of them is two places for a fence or a semaphore to drift out of agreement.
     */
    /**
     * Set the dynamic viewport and scissor to the whole of this frame's extent.
     *
     * <p>Only width and height are written: the other fields of both structs were filled once when
     * {@link FrameState} was built ({@code x}, {@code y} and {@code minDepth} zero, {@code maxDepth} one,
     * scissor offset zero) and nothing since changes them.
     */
    private void setFullViewport(FrameState s, MemorySegment cmd, int extentW, int extentH) {
        sf(s.pViewport, VkStructs.VIEWPORT, "width", extentW);
        sf(s.pViewport, VkStructs.VIEWPORT, "height", extentH);
        invokeVoid(setViewport, cmd, 0, 1, s.pViewport);
        si(s.pScissor, VkStructs.RECT_2D, "extent_width", extentW);
        si(s.pScissor, VkStructs.RECT_2D, "extent_height", extentH);
        invokeVoid(setScissor, cmd, 0, 1, s.pScissor);
    }

    private boolean submitAndPresent(FrameState s, long fence) {
        try (Zone w = Probe.zone(Lane.GPU, "queue submit")) {
            check(invoke(submitCmd, device.queue(), 1, s.submit, fence), "vkQueueSubmit");
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
        /** This slot's fence, rewritten each frame — the structs are shared, the objects are not. */
        final MemorySegment pFence = a.allocate(JAVA_LONG);
        /** The fence of whoever last used this frame's image, when there is one to wait for. */
        final MemorySegment pImageFence = a.allocate(JAVA_LONG);
        final MemorySegment waitSems = a.allocate(JAVA_LONG);
        final MemorySegment signalSems = a.allocate(JAVA_LONG);
        final MemorySegment waitStages = a.allocate(JAVA_INT);
        final MemorySegment pCmd = a.allocate(ADDRESS);
        final MemorySegment pSwapchains = a.allocate(JAVA_LONG);
        final MemorySegment submit = a.allocate(VkStructs.SUBMIT_INFO);
        final MemorySegment presentInfo = a.allocate(PRESENT);
        final MemorySegment pVertexBuffers = a.allocate(JAVA_LONG);
        final MemorySegment pVertexOffsets = a.allocate(JAVA_LONG);
        final MemorySegment pDescriptorSet = a.allocate(JAVA_LONG);
        final MemorySegment pDescriptorSet1 = a.allocate(JAVA_LONG);
        final MemorySegment pViewport = a.allocate(VkStructs.VIEWPORT);
        final MemorySegment pScissor = a.allocate(VkStructs.RECT_2D);
        // A VkClearValue is a 16-byte union, and there must be one per attachment the pass clears. With depth
        // that is two: four floats of colour, then the depth float in the first slot of the second union.
        // Under-counting here is not a validation error the loader always catches — it reads whatever follows.
        final MemorySegment clear = a.allocate(JAVA_FLOAT, depthFormat == VulkanRenderPass.NO_DEPTH ? 4 : 8);
        final MemorySegment beginInfo = a.allocate(VkStructs.COMMAND_BUFFER_BEGIN_INFO);
        final MemorySegment rpBegin = a.allocate(VkStructs.RENDER_PASS_BEGIN_INFO);
        MemorySegment pushSeg = MemorySegment.NULL;
        int pushCapacity = 0;
        long previousNanos = System.nanoTime();
        boolean shown = false;

        FrameState() {
            // pFence, waitSems, signalSems and pCmd are deliberately *not* filled here: they name the
            // slot's fence, the slot's semaphore, the image's semaphore and the slot's command buffer, none
            // of which is known until a frame has picked a slot and acquired an image. Sharing one set of
            // structs across slots is safe because every Vulkan call reads its host memory during the call
            // and nothing here is threaded; what cannot be shared is the objects they point at.
            waitStages.set(JAVA_INT, 0, Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            pVertexOffsets.set(JAVA_LONG, 0, 0L);

            si(submit, VkStructs.SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, VkStructs.SUBMIT_INFO, "waitSemaphoreCount", 1);
            sa(submit, VkStructs.SUBMIT_INFO, "pWaitSemaphores", waitSems);
            sa(submit, VkStructs.SUBMIT_INFO, "pWaitDstStageMask", waitStages);
            si(submit, VkStructs.SUBMIT_INFO, "commandBufferCount", 1);
            sa(submit, VkStructs.SUBMIT_INFO, "pCommandBuffers", pCmd);
            si(submit, VkStructs.SUBMIT_INFO, "signalSemaphoreCount", 1);
            sa(submit, VkStructs.SUBMIT_INFO, "pSignalSemaphores", signalSems);

            si(presentInfo, PRESENT, "sType", Vk.STRUCTURE_TYPE_PRESENT_INFO_KHR);
            si(presentInfo, PRESENT, "waitSemaphoreCount", 1);
            sa(presentInfo, PRESENT, "pWaitSemaphores", signalSems);
            si(presentInfo, PRESENT, "swapchainCount", 1);
            sa(presentInfo, PRESENT, "pSwapchains", pSwapchains);
            sa(presentInfo, PRESENT, "pImageIndices", pImageIndex);

            sf(pViewport, VkStructs.VIEWPORT, "maxDepth", 1.0f);

            si(beginInfo, VkStructs.COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            sl(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "renderPass", renderPass);
            if (depthFormat != VulkanRenderPass.NO_DEPTH) {
                clear.setAtIndex(JAVA_FLOAT, 4, DepthAttachment.CLEAR_DEPTH);
                si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "clearValueCount", 2);
            } else {
                si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "clearValueCount", 1);
            }
            sa(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "pClearValues", clear);
        }
    }

    private void rebuild() {
        // Counted because swapchain churn is a cost that hides: a drag-resize rebuilds every frame, each one
        // a full device idle, and the frame times it produces look like a rendering problem rather than the
        // resize it actually is.
        try (Zone z = Probe.zone(Lane.GPU, "swapchain rebuild")) {
            // Everything per-image is destroyed and remade, not patched: a recreated swapchain may return a
            // different number of images, which changes the length of three arrays and the identity of
            // every semaphore in one of them. The previous version kept one depth image and reallocated it
            // only when the extent changed; that saving is gone with per-image depth, and paying it back on
            // a resize — an operation that already does a full device idle — is the cheap half of the
            // trade.
            device.waitIdle();
            destroyPerImage();
            swapchain.recreate(window.width(), window.height());
            buildPerImage();
        }
    }

    private long createSemaphore(MethodHandle create) {
        MemorySegment info = a.allocate(VkStructs.CREATE_INFO);
        si(info, VkStructs.CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(create, dev, info, MemorySegment.NULL, p), "vkCreateSemaphore");
        return p.get(JAVA_LONG, 0);
    }

    private long createFenceSignaled(MethodHandle create) {
        MemorySegment info = a.allocate(VkStructs.CREATE_INFO);
        si(info, VkStructs.CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
        si(info, VkStructs.CREATE_INFO, "flags", 0x1);
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(create, dev, info, MemorySegment.NULL, p), "vkCreateFence");
        return p.get(JAVA_LONG, 0);
    }

    private long createPool(MethodHandle create) {
        MemorySegment info = a.allocate(VkStructs.COMMAND_POOL_CREATE_INFO);
        si(info, VkStructs.COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
        si(info, VkStructs.COMMAND_POOL_CREATE_INFO, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
        si(info, VkStructs.COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(create, dev, info, MemorySegment.NULL, p), "vkCreateCommandPool");
        return p.get(JAVA_LONG, 0);
    }

    private MemorySegment allocateCommandBuffer(MethodHandle allocCmd) {
        MemorySegment info = a.allocate(VkStructs.COMMAND_BUFFER_ALLOCATE_INFO);
        si(info, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        sl(info, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
        si(info, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
        si(info, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
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
        for (int slot = 0; slot < framesInFlight; slot++) {
            invokeVoid(destroyFence, dev, inFlight[slot], MemorySegment.NULL);
            invokeVoid(destroySem, dev, imageAvailable[slot], MemorySegment.NULL);
        }
        destroyPerImage();
        a.close();
    }
}
