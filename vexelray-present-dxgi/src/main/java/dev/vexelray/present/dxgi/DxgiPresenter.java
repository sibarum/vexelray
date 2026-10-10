package dev.vexelray.present.dxgi;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VkStructs;
import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.vulkan.present.FrameUpdate;
import dev.vexelray.vulkan.offscreen.OffscreenReadback;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.RunRecorder;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.WindowPresenter;
import dev.vexelray.vulkan.present.WindowedPresenter;
import sibarum.dxgi.DxgiContext;
import sibarum.dxgi.DxgiSwapchain;
import sibarum.dxgi.SharedImage;
import sibarum.dxgi.VkInterop;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.sa;
import static dev.supirvast.vulkan.Ffm.sf;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A window presented through DXGI. Vulkan draws the frame — the same pipeline, vertex buffer and runs a
 * {@link WindowedPresenter} would — into the image {@link DxgiSwapchain} shares with D3D12, and the D3D12 queue
 * copies the window-sized corner of it into the back buffer and presents.
 *
 * <p>A resize is {@link DxgiSwapchain#resize} — {@code ResizeBuffers} — and, only when the window outgrows the
 * shared image, a new image and the one framebuffer over it. No Vulkan swapchain exists, so nothing here can be
 * out of date, and every frame of a drag is drawn at the window's size.
 *
 * <p>Ordering, all on the shared timeline: the Vulkan submit waits for the last copy out of the image
 * ({@link DxgiSwapchain#lastCopyValue}) before drawing over it, and signals a fresh value; the D3D12 queue waits for
 * that value before copying. One frame in flight: the fence at the top waits for the previous Vulkan submit, so its
 * command buffer and the mapped vertex buffer are never rewritten under the GPU.
 */
final class DxgiPresenter implements WindowPresenter {

    /** How long a frame waits on the swapchain's frame-latency waitable before drawing anyway. */
    private static final int LATENCY_WAIT_MILLIS = 100;
    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor DL = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor SET_VS = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    private final VulkanDevice device;
    private final MemorySegment dev;
    private final DxgiContext context;
    private final DxgiSwapchain swapchain;
    private final NativeWindow window;
    private final long renderPass;
    private final GraphicsPipeline pipeline;
    private final Arena arena = Arena.ofShared();

    private final MethodHandle waitFences, resetFences, submit, beginCmd, endCmd, beginRp, endRp, bindPipe;
    private final MethodHandle pushConstants, bindVertexBuffers, setViewport, setScissor;
    /** Records the runs: their sets and draws, by the same rules as every other path that draws them. */
    private final RunRecorder runRecorder;
    private final MethodHandle createFramebuffer, destroyFramebuffer, destroyFence, destroyPool;

    private final long pool;
    private final MemorySegment cmd;
    private final long inFlight;

    private long framebuffer;
    private int extentWidth;
    private int extentHeight;

    private long vertexBuffer;
    private long descriptorSet;
    private int vertexCount;
    private volatile List<WindowedPresenter.Run> runs = List.of();

    /** The in-flight fence as the one-element array {@code vkWaitForFences} takes. */
    private final MemorySegment pInFlight;
    private MemorySegment push = MemorySegment.NULL;
    private int pushCapacity;
    private long previousNanos = System.nanoTime();
    private boolean rendering;
    private boolean shown;
    private boolean closed;

    DxgiPresenter(VulkanDevice device, DxgiContext context, NativeWindow window, long renderPass,
                  GraphicsPipeline pipeline) {
        Probe.opened(Lane.GPU, "DxgiPresenter", this);
        this.device = device;
        this.dev = device.handle();
        this.context = context;
        this.window = window;
        this.renderPass = renderPass;
        this.pipeline = pipeline;
        this.extentWidth = Math.max(1, window.width());
        this.extentHeight = Math.max(1, window.height());

        this.waitFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        this.resetFences = device.command("vkResetFences", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.submit = device.command("vkQueueSubmit", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        this.beginCmd = device.command("vkBeginCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.endCmd = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.beginRp = device.command("vkCmdBeginRenderPass", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        this.endRp = device.command("vkCmdEndRenderPass", FunctionDescriptor.ofVoid(ADDRESS));
        this.bindPipe = device.command("vkCmdBindPipeline", FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
        this.runRecorder = new RunRecorder(device, arena);
        this.pushConstants = device.command("vkCmdPushConstants",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        this.bindVertexBuffers = device.command("vkCmdBindVertexBuffers",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        this.setViewport = device.command("vkCmdSetViewport", SET_VS);
        this.setScissor = device.command("vkCmdSetScissor", SET_VS);
        this.createFramebuffer = device.command("vkCreateFramebuffer", C4);
        this.destroyFramebuffer = device.command("vkDestroyFramebuffer", DL);
        this.destroyFence = device.command("vkDestroyFence", DL);
        this.destroyPool = device.command("vkDestroyCommandPool", DL);

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment poolInfo = temp.allocate(VkStructs.COMMAND_POOL_CREATE_INFO);
            si(poolInfo, VkStructs.COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            si(poolInfo, VkStructs.COMMAND_POOL_CREATE_INFO, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            si(poolInfo, VkStructs.COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
            MemorySegment pPool = temp.allocate(JAVA_LONG);
            check(invoke(device.command("vkCreateCommandPool", C4), dev, poolInfo, MemorySegment.NULL, pPool),
                    "vkCreateCommandPool");
            this.pool = pPool.get(JAVA_LONG, 0);

            MemorySegment alloc = temp.allocate(VkStructs.COMMAND_BUFFER_ALLOCATE_INFO);
            si(alloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            sl(alloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
            si(alloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
            si(alloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
            MemorySegment pCmd = temp.allocate(ADDRESS);
            check(invoke(device.command("vkAllocateCommandBuffers", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS,
                    ADDRESS)), dev, alloc, pCmd), "vkAllocateCommandBuffers");
            this.cmd = pCmd.get(ADDRESS, 0);

            MemorySegment fenceInfo = temp.allocate(VkStructs.CREATE_INFO);
            si(fenceInfo, VkStructs.CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
            si(fenceInfo, VkStructs.CREATE_INFO, "flags", 0x1);   // signalled: the first frame has nothing to wait for
            MemorySegment pFence = temp.allocate(JAVA_LONG);
            check(invoke(device.command("vkCreateFence", C4), dev, fenceInfo, MemorySegment.NULL, pFence),
                    "vkCreateFence");
            this.inFlight = pFence.get(JAVA_LONG, 0);
        }
        this.pInFlight = arena.allocate(JAVA_LONG);
        pInFlight.set(JAVA_LONG, 0, inFlight);
        this.swapchain = DxgiSwapchain.create(context, MemorySegment.ofAddress(window.osHandle()),
                extentWidth, extentHeight, true);
        rebuildFramebuffer();
    }

    @Override
    public int width() {
        return extentWidth;
    }

    @Override
    public int height() {
        return extentHeight;
    }

    @Override
    public void configureDraw(long vertexBuffer, long descriptorSet, int vertexCount) {
        this.vertexBuffer = vertexBuffer;
        this.descriptorSet = descriptorSet;
        this.vertexCount = vertexCount;
    }

    @Override
    public void setRuns(List<WindowedPresenter.Run> runs) {
        this.runs = List.copyOf(runs);
        int total = 0;
        for (WindowedPresenter.Run r : this.runs) {
            total = Math.max(total, r.firstVertex() + r.vertexCount());
        }
        this.vertexCount = total;
    }

    @Override
    public boolean render(int pushConstantBytes, FrameUpdate perFrame) {
        if (rendering || closed) {
            return true;   // a platform-pulled frame never nests inside the one in flight
        }
        rendering = true;
        try (Zone z = Probe.zone(Lane.GPU, "present frame")) {
            return renderFrame(pushConstantBytes, perFrame);
        } finally {
            rendering = false;
        }
    }

    private boolean renderFrame(int pushConstantBytes, FrameUpdate perFrame) {
        if (window.isMinimized() || window.width() <= 0 || window.height() <= 0) {
            return true;
        }
        try (Zone w = Probe.zone(Lane.GPU, "dxgi wait")) {
            swapchain.beginFrame(LATENCY_WAIT_MILLIS);
        }
        // The previous submit is done with the command buffer and with everything the frame's callback rewrites.
        try (Zone w = Probe.zone(Lane.GPU, "wait fence")) {
            check(invoke(waitFences, dev, 1, pInFlight, Vk.VK_TRUE, Long.MAX_VALUE),
                    "vkWaitForFences");
        }

        int w = window.width();
        int h = window.height();
        if (w != swapchain.width() || h != swapchain.height()) {
            try (Zone z = Probe.zone(Lane.GPU, "dxgi resize")) {
                swapchain.resize(w, h);
                if (swapchain.ensureCapacity(w, h)) {
                    rebuildFramebuffer();
                }
            }
        }
        extentWidth = w;
        extentHeight = h;

        if (pushConstantBytes > pushCapacity) {
            push = arena.allocate(pushConstantBytes);
            pushCapacity = pushConstantBytes;
        }
        long now = System.nanoTime();
        double dt = (now - previousNanos) / 1_000_000_000.0;
        previousNanos = now;
        if (perFrame != null) {
            perFrame.update(dt, push);
        }

        record(w, h, pushConstantBytes);

        long rendered = context.nextValue();
        try (Zone z = Probe.zone(Lane.GPU, "queue submit")) {
            submit(rendered);
        }
        try (Zone z = Probe.zone(Lane.GPU, "dxgi copy+present")) {
            swapchain.present(rendered, w, h);
        }
        if (!shown) {
            window.show();   // first frame on the glass: reveal the window already painted
            shown = true;
        }
        return true;
    }

    private void record(int w, int h, int pushConstantBytes) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment begin = temp.allocate(VkStructs.COMMAND_BUFFER_BEGIN_INFO);
            si(begin, VkStructs.COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            check(invoke(beginCmd, cmd, begin), "vkBeginCommandBuffer");

            MemorySegment clear = temp.allocate(JAVA_FLOAT, 4);   // transparent black, as the swapchain path clears
            MemorySegment rp = temp.allocate(VkStructs.RENDER_PASS_BEGIN_INFO);
            si(rp, VkStructs.RENDER_PASS_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            sl(rp, VkStructs.RENDER_PASS_BEGIN_INFO, "renderPass", renderPass);
            sl(rp, VkStructs.RENDER_PASS_BEGIN_INFO, "framebuffer", framebuffer);
            si(rp, VkStructs.RENDER_PASS_BEGIN_INFO, "area_extent_width", w);
            si(rp, VkStructs.RENDER_PASS_BEGIN_INFO, "area_extent_height", h);
            si(rp, VkStructs.RENDER_PASS_BEGIN_INFO, "clearValueCount", 1);
            sa(rp, VkStructs.RENDER_PASS_BEGIN_INFO, "pClearValues", clear);
            invokeVoid(beginRp, cmd, rp, Vk.SUBPASS_CONTENTS_INLINE);

            invokeVoid(bindPipe, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
            if (pipeline.hasDynamicViewport()) {
                MemorySegment viewport = temp.allocate(VkStructs.VIEWPORT);
                sf(viewport, VkStructs.VIEWPORT, "width", w);
                sf(viewport, VkStructs.VIEWPORT, "height", h);
                sf(viewport, VkStructs.VIEWPORT, "maxDepth", 1.0f);
                invokeVoid(setViewport, cmd, 0, 1, viewport);
                MemorySegment scissor = temp.allocate(VkStructs.RECT_2D);
                si(scissor, VkStructs.RECT_2D, "extent_width", w);
                si(scissor, VkStructs.RECT_2D, "extent_height", h);
                invokeVoid(setScissor, cmd, 0, 1, scissor);
            }
            if (vertexBuffer != 0) {
                MemorySegment buffers = temp.allocate(JAVA_LONG);
                buffers.set(JAVA_LONG, 0, vertexBuffer);
                MemorySegment offsets = temp.allocate(JAVA_LONG);
                invokeVoid(bindVertexBuffers, cmd, 0, 1, buffers, offsets);
            }
            if (pushConstantBytes > 0) {
                invokeVoid(pushConstants, cmd, pipeline.pipelineLayout(), Vk.SHADER_STAGE_FRAGMENT_BIT, 0,
                        pushConstantBytes, push);
            }
            runRecorder.record(cmd, pipeline.pipelineLayout(), descriptorSet, runs, vertexCount);
            invokeVoid(endRp, cmd);
            check(invoke(endCmd, cmd), "vkEndCommandBuffer");
        }
    }

    /** Submit, waiting on the last copy out of the image and signalling {@code rendered} on the shared timeline. */
    private void submit(long rendered) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment waitValues = temp.allocate(JAVA_LONG);
            waitValues.set(JAVA_LONG, 0, swapchain.lastCopyValue());
            MemorySegment signalValues = temp.allocate(JAVA_LONG);
            signalValues.set(JAVA_LONG, 0, rendered);
            MemorySegment timeline = temp.allocate(VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO);
            si(timeline, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "sType",
                    VkInterop.STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO);
            si(timeline, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "waitSemaphoreValueCount", 1);
            sa(timeline, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "pWaitSemaphoreValues", waitValues);
            si(timeline, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "signalSemaphoreValueCount", 1);
            sa(timeline, VkInterop.TIMELINE_SEMAPHORE_SUBMIT_INFO, "pSignalSemaphoreValues", signalValues);

            MemorySegment semaphores = temp.allocate(JAVA_LONG);
            semaphores.set(JAVA_LONG, 0, context.timelineSemaphore());
            MemorySegment stages = temp.allocate(JAVA_INT);
            stages.set(JAVA_INT, 0, Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
            MemorySegment cmds = temp.allocate(ADDRESS);
            cmds.set(ADDRESS, 0, cmd);
            MemorySegment info = temp.allocate(VkStructs.SUBMIT_INFO);
            si(info, VkStructs.SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            sa(info, VkStructs.SUBMIT_INFO, "pNext", timeline);
            si(info, VkStructs.SUBMIT_INFO, "waitSemaphoreCount", 1);
            sa(info, VkStructs.SUBMIT_INFO, "pWaitSemaphores", semaphores);
            sa(info, VkStructs.SUBMIT_INFO, "pWaitDstStageMask", stages);
            si(info, VkStructs.SUBMIT_INFO, "commandBufferCount", 1);
            sa(info, VkStructs.SUBMIT_INFO, "pCommandBuffers", cmds);
            si(info, VkStructs.SUBMIT_INFO, "signalSemaphoreCount", 1);
            sa(info, VkStructs.SUBMIT_INFO, "pSignalSemaphores", semaphores);

            // Reset here, with nothing that can throw between it and the submit that signals it again.
            MemorySegment pFence = temp.allocate(JAVA_LONG);
            pFence.set(JAVA_LONG, 0, inFlight);
            check(invoke(resetFences, dev, 1, pFence), "vkResetFences");
            check(invoke(submit, device.queue(), 1, info, inFlight), "vkQueueSubmit");
        }
    }

    /**
     * The top-left {@link #width()}×{@link #height()} of the shared image: the corner {@link DxgiSwapchain#present}
     * copies into the back buffer, so these are the pixels of the frame on the screen, not a second drawing of it.
     * BGRA as drawn, returned as RGBA. Alpha is set opaque, because the swapchain ignores it ({@code ALPHA_MODE_IGNORE})
     * and so does the screen.
     */
    @Override
    public byte[] readFrame() {
        if (closed || !shown) {
            return null;
        }
        // The last submit has drawn the frame; the copy below waits for nothing else. D3D12 may still be copying the
        // same corner out, and two reads of one image do not race. The next frame cannot start drawing over it,
        // because it is drawn on this thread, after this returns.
        check(invoke(waitFences, dev, 1, pInFlight, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        int w = extentWidth;
        int h = extentHeight;
        byte[] pixels = OffscreenReadback.readImage(device, swapchain.image().image(),
                VulkanRenderPass.IMAGE_LAYOUT_GENERAL, w, h,
                Vk.PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, Vk.ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
        for (int i = 0; i < pixels.length; i += 4) {
            byte b = pixels[i];
            pixels[i] = pixels[i + 2];
            pixels[i + 2] = b;
            pixels[i + 3] = (byte) 0xFF;
        }
        return pixels;
    }

    /** The one framebuffer, over the shared image's view at its full capacity. The device must be idle on the old one. */
    private void rebuildFramebuffer() {
        if (framebuffer != 0L) {
            device.waitIdle();
            long old = framebuffer;
            framebuffer = 0L;
            invokeVoid(destroyFramebuffer, dev, old, MemorySegment.NULL);
        }
        SharedImage image = swapchain.image();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment views = temp.allocate(JAVA_LONG);
            views.set(JAVA_LONG, 0, image.view());
            MemorySegment info = temp.allocate(VkStructs.FRAMEBUFFER_CREATE_INFO);
            si(info, VkStructs.FRAMEBUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
            sl(info, VkStructs.FRAMEBUFFER_CREATE_INFO, "renderPass", renderPass);
            si(info, VkStructs.FRAMEBUFFER_CREATE_INFO, "attachmentCount", 1);
            sa(info, VkStructs.FRAMEBUFFER_CREATE_INFO, "pAttachments", views);
            si(info, VkStructs.FRAMEBUFFER_CREATE_INFO, "width", image.width());
            si(info, VkStructs.FRAMEBUFFER_CREATE_INFO, "height", image.height());
            si(info, VkStructs.FRAMEBUFFER_CREATE_INFO, "layers", 1);
            MemorySegment pFramebuffer = temp.allocate(JAVA_LONG);
            check(invoke(createFramebuffer, dev, info, MemorySegment.NULL, pFramebuffer), "vkCreateFramebuffer");
            framebuffer = pFramebuffer.get(JAVA_LONG, 0);
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Probe.closed(Lane.GPU, "DxgiPresenter", this);
        device.waitIdle();
        try {
            swapchain.close();
        } finally {
            if (framebuffer != 0L) {
                invokeVoid(destroyFramebuffer, dev, framebuffer, MemorySegment.NULL);
                framebuffer = 0L;
            }
            invokeVoid(destroyFence, dev, inFlight, MemorySegment.NULL);
            invokeVoid(destroyPool, dev, pool, MemorySegment.NULL);
            arena.close();
        }
    }
}
