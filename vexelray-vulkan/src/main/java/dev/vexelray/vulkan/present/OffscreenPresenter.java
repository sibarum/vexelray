package dev.vexelray.vulkan.present;

import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkStructs;
import dev.vexelray.vulkan.vk.VulkanDevice;
import sibarum.probe.Lane;
import sibarum.probe.Probe;
import sibarum.probe.Zone;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.vexelray.vulkan.vk.Ffm.check;
import static dev.vexelray.vulkan.vk.Ffm.gi;
import static dev.vexelray.vulkan.vk.Ffm.gl;
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.invokeVoid;
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.sf;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * {@link WindowedPresenter}'s headless twin: drives frames into an offscreen colour image (with optional
 * depth) through the same {@link Recorder} seam, and hands the result back as pixels via {@link #readRgba()}.
 *
 * <h2>Why this exists, and why it is not {@link OffscreenDraw}</h2>
 *
 * <p>Two headless paths already existed and neither could carry a pipeline of techniques.
 * {@link OffscreenDraw} and {@code OffscreenRenderer} are single-pipeline and per-call: they create an image,
 * a framebuffer, a command buffer and a readback buffer, record one draw with the pipeline they were handed,
 * tear all of it down, and return bytes. There is no seam for a caller to record into, so N techniques cannot
 * share their frame, and no state survives between calls, so a run of frames pays full setup for each one.
 * They stay: they are the right shape for "render this one thing to a texture", which is what the Canvas
 * texture target wants.
 *
 * <p>What was missing is the other shape — a frame <em>loop</em> with no window. Without it every engine-level
 * test needed a window and a GPU, so a headless environment could only skip them; and because the windowed
 * path does no readback, none of them could count a pixel. A test that cannot count pixels can only assert
 * that a frame happened, never that it contained the right picture, which is why "two techniques occlude each
 * other correctly" has been a claim rather than a check.
 *
 * <h2>What it owns, and what it does not</h2>
 *
 * <p>Everything is created once and reused for every frame: the colour image and its view, the
 * {@link DepthAttachment} when the pass has one, the framebuffer, the command pool and two command buffers,
 * the fence, and a host-visible readback buffer that stays mapped for this presenter's whole life. There is no
 * swapchain, no acquire, no semaphores and no present — an offscreen frame is submit-and-fence, and the two
 * semaphores a windowed frame needs exist only to order work against a presentation engine that is not here.
 *
 * <p>There is also no rebuild. A window changes size and a swapchain follows it; an offscreen target is the
 * extent it was asked for, for as long as it lives. That is the one simplification this class gets over
 * {@link WindowedPresenter}, and it removes the resize path entirely rather than leaving a dormant one.
 *
 * <h2>What the caller must have got right</h2>
 *
 * <p>Three things, none of which this class can check, because it is handed a render-pass <em>handle</em> and
 * a handle cannot be asked:
 *
 * <ul>
 *   <li>the pass's colour attachment is {@link #FORMAT};</li>
 *   <li>the pass's colour {@code finalLayout} is {@code TRANSFER_SRC_OPTIMAL}, so the image is copy-ready the
 *       moment the pass ends and {@link #readRgba()} needs no barrier of its own;</li>
 *   <li>{@code depthFormat} is what the pass was built with — {@link DepthAttachment#FORMAT} or
 *       {@link VulkanRenderPass#NO_DEPTH}. Wrong here means a framebuffer whose attachment count disagrees
 *       with the pass.</li>
 * </ul>
 *
 * <p>{@code new VulkanRenderPass(device, OffscreenPresenter.FORMAT, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
 * depthFormat)} satisfies all three, and is what the engine builds.
 *
 * <h2>Threading</h2>
 *
 * <p>Same contract as {@link WindowedPresenter}: one thread, the one that calls {@link #frame}. Nothing here
 * starts a thread and nothing is synchronised.
 */
public final class OffscreenPresenter implements AutoCloseable {

    /**
     * The colour format an offscreen frame is drawn and read back in.
     *
     * <p>Fixed rather than a constructor parameter, for the same reason {@link DepthAttachment#FORMAT} is:
     * {@link #readRgba()} promises tightly-packed R8G8B8A8, and a format parameter would let a caller ask for
     * {@code RGBA16F} and get back bytes that are neither the layout the method's name claims nor an error.
     * When a floating-point capture is actually wanted, the honest change is a second read method that says
     * what it returns — and this constant is where a reader will look to find that only one format is
     * supported yet.
     */
    public static final int FORMAT = Vk.FORMAT_R8G8B8A8_UNORM;

    /** Bytes per pixel of {@link #FORMAT}, and therefore the stride arithmetic {@link #readRgba} relies on. */
    private static final int BYTES_PER_PIXEL = 4;

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor MEMREQ = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);
    private static final FunctionDescriptor SET_VS = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    private final VulkanDevice device;
    private final MemorySegment dev;
    private final int width;
    private final int height;
    private final long pixelBytes;
    private final Arena a = Arena.ofShared();

    private final MethodHandle waitFences, resetFences, submitQueue;
    private final MethodHandle beginCmd, endCmd, beginRp, endRp, setViewport, setScissor;
    private final MethodHandle destroyImage, freeMemory, destroyImageView, destroyFramebuffer;
    private final MethodHandle destroyBuffer, unmapMemory, destroyPool, destroyFence;

    private final long image, imageMemory, imageView, framebuffer;
    private final long buffer, bufferMemory;
    private final long pool, fence;
    private final DepthAttachment depth;

    /** The readback buffer's host mapping, held for this presenter's life rather than mapped per read. */
    private final MemorySegment mapped;

    /** Records the frame; re-recorded every {@link #frame}, which the pool's reset flag permits. */
    private final MemorySegment cmd;

    /**
     * Records image → buffer, once, at construction.
     *
     * <p>Pre-recorded because it never varies: the same image, the same buffer, the same extent, every read.
     * It is also the reason a read costs nothing when nobody reads — a copy recorded into the frame's own
     * command buffer would run on every frame whether or not anyone ever asked for the pixels, and a
     * full-image copy per frame is exactly the cost a headless run of a thousand frames must not pay.
     */
    private final MemorySegment copyCmd;

    private final MemorySegment pFence, pCmd, submit, beginInfo, rpBegin, clear, pViewport, pScissor;

    private MemorySegment pushSeg = MemorySegment.NULL;
    private int pushCapacity;
    private long previousNanos = System.nanoTime();
    private long frames;
    private boolean closed;

    /**
     * @param device      the device everything here is created on
     * @param renderPass  the shared pass every technique's pipeline was built against — see the class note on
     *                    what this class cannot check about it
     * @param width       the target extent, fixed for this presenter's life
     * @param height      the target extent
     * @param depthFormat {@link DepthAttachment#FORMAT}, or {@link VulkanRenderPass#NO_DEPTH}
     */
    public OffscreenPresenter(VulkanDevice device, long renderPass, int width, int height, int depthFormat) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("offscreen extent must be positive, got " + width + "x" + height);
        }
        Probe.opened(Lane.GPU, "OffscreenPresenter", this);
        this.device = device;
        this.dev = device.handle();
        this.width = width;
        this.height = height;
        this.pixelBytes = (long) width * height * BYTES_PER_PIXEL;

        MethodHandle createImage = device.command("vkCreateImage", C4);
        MethodHandle imageMemReq = device.command("vkGetImageMemoryRequirements", MEMREQ);
        MethodHandle allocMemory = device.command("vkAllocateMemory", C4);
        MethodHandle bindImageMemory = device.command("vkBindImageMemory", BIND);
        MethodHandle createImageView = device.command("vkCreateImageView", C4);
        MethodHandle createFramebuffer = device.command("vkCreateFramebuffer", C4);
        MethodHandle createBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle bufferMemReq = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle bindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle mapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle createPool = device.command("vkCreateCommandPool", C4);
        MethodHandle allocCmd = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle createFence = device.command("vkCreateFence", C4);
        MethodHandle copyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));

        this.waitFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        this.resetFences = device.command("vkResetFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.submitQueue = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        this.beginCmd = device.command("vkBeginCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.endCmd = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.beginRp = device.command("vkCmdBeginRenderPass", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        this.endRp = device.command("vkCmdEndRenderPass", FunctionDescriptor.ofVoid(ADDRESS));
        this.setViewport = device.command("vkCmdSetViewport", SET_VS);
        this.setScissor = device.command("vkCmdSetScissor", SET_VS);
        this.destroyImage = device.command("vkDestroyImage", D_LONG);
        this.freeMemory = device.command("vkFreeMemory", D_LONG);
        this.destroyImageView = device.command("vkDestroyImageView", D_LONG);
        this.destroyFramebuffer = device.command("vkDestroyFramebuffer", D_LONG);
        this.destroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        this.unmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        this.destroyPool = device.command("vkDestroyCommandPool", D_LONG);
        this.destroyFence = device.command("vkDestroyFence", D_LONG);

        // --- colour image: drawn into as an attachment, then copied out as a transfer source ---
        MemorySegment imgInfo = a.allocate(VkStructs.IMAGE_CREATE_INFO);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "imageType", Vk.IMAGE_TYPE_2D);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "format", FORMAT);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "extent_width", width);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "extent_height", height);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "extent_depth", 1);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "mipLevels", 1);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "arrayLayers", 1);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "samples", Vk.SAMPLE_COUNT_1_BIT);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "tiling", Vk.IMAGE_TILING_OPTIMAL);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "usage",
                Vk.IMAGE_USAGE_COLOR_ATTACHMENT_BIT | Vk.IMAGE_USAGE_TRANSFER_SRC_BIT);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
        si(imgInfo, VkStructs.IMAGE_CREATE_INFO, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
        MemorySegment p = a.allocate(JAVA_LONG);
        check(invoke(createImage, dev, imgInfo, MemorySegment.NULL, p), "vkCreateImage");
        this.image = p.get(JAVA_LONG, 0);

        MemorySegment req = a.allocate(VkStructs.MEMORY_REQUIREMENTS);
        invokeVoid(imageMemReq, dev, image, req);
        this.imageMemory = allocateMemory(allocMemory, gl(req, VkStructs.MEMORY_REQUIREMENTS, "size"),
                device.findMemoryType(gi(req, VkStructs.MEMORY_REQUIREMENTS, "memoryTypeBits"),
                        Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
        check(invoke(bindImageMemory, dev, image, imageMemory, 0L), "vkBindImageMemory");

        MemorySegment viewInfo = a.allocate(VkStructs.IMAGE_VIEW_CREATE_INFO);
        si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
        sl(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "image", image);
        si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
        si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "format", FORMAT);
        si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
        si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_levelCount", 1);
        si(viewInfo, VkStructs.IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
        check(invoke(createImageView, dev, viewInfo, MemorySegment.NULL, p), "vkCreateImageView");
        this.imageView = p.get(JAVA_LONG, 0);

        // --- depth, when the pass has it. Colour first: a framebuffer's views must match the pass's
        // attachments in count and in order, and the pass puts depth at index 1.
        this.depth = depthFormat == VulkanRenderPass.NO_DEPTH ? null : new DepthAttachment(device, width, height);

        int attachmentCount = depth == null ? 1 : 2;
        MemorySegment attachments = a.allocate(JAVA_LONG, attachmentCount);
        attachments.setAtIndex(JAVA_LONG, 0, imageView);
        if (depth != null) {
            attachments.setAtIndex(JAVA_LONG, 1, depth.view());
        }
        MemorySegment fbInfo = a.allocate(VkStructs.FRAMEBUFFER_CREATE_INFO);
        si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
        sl(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "renderPass", renderPass);
        si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "attachmentCount", attachmentCount);
        sa(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "pAttachments", attachments);
        si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "width", width);
        si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "height", height);
        si(fbInfo, VkStructs.FRAMEBUFFER_CREATE_INFO, "layers", 1);
        check(invoke(createFramebuffer, dev, fbInfo, MemorySegment.NULL, p), "vkCreateFramebuffer");
        this.framebuffer = p.get(JAVA_LONG, 0);

        // --- host-visible readback buffer, mapped once and left mapped ---
        MemorySegment bufInfo = a.allocate(VkStructs.BUFFER_CREATE_INFO);
        si(bufInfo, VkStructs.BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
        sl(bufInfo, VkStructs.BUFFER_CREATE_INFO, "size", pixelBytes);
        si(bufInfo, VkStructs.BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
        si(bufInfo, VkStructs.BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
        check(invoke(createBuffer, dev, bufInfo, MemorySegment.NULL, p), "vkCreateBuffer");
        this.buffer = p.get(JAVA_LONG, 0);

        invokeVoid(bufferMemReq, dev, buffer, req);
        // HOST_COHERENT as well as HOST_VISIBLE, which is what lets readRgba() read the mapping straight after
        // the fence with no vkInvalidateMappedMemoryRanges: signalling a fence makes the submitted writes
        // available to the host, and coherent memory makes them visible without an explicit flush.
        this.bufferMemory = allocateMemory(allocMemory, gl(req, VkStructs.MEMORY_REQUIREMENTS, "size"),
                device.findMemoryType(gi(req, VkStructs.MEMORY_REQUIREMENTS, "memoryTypeBits"),
                        Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT));
        check(invoke(bindBufferMemory, dev, buffer, bufferMemory, 0L), "vkBindBufferMemory");
        MemorySegment ppData = a.allocate(ADDRESS);
        check(invoke(mapMemory, dev, bufferMemory, 0L, pixelBytes, 0, ppData), "vkMapMemory");
        this.mapped = ppData.get(ADDRESS, 0).reinterpret(pixelBytes);

        // --- command pool, the frame buffer and the copy buffer, and the fence ---
        MemorySegment poolInfo = a.allocate(VkStructs.COMMAND_POOL_CREATE_INFO);
        si(poolInfo, VkStructs.COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
        si(poolInfo, VkStructs.COMMAND_POOL_CREATE_INFO, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
        si(poolInfo, VkStructs.COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
        check(invoke(createPool, dev, poolInfo, MemorySegment.NULL, p), "vkCreateCommandPool");
        this.pool = p.get(JAVA_LONG, 0);

        MemorySegment cbAlloc = a.allocate(VkStructs.COMMAND_BUFFER_ALLOCATE_INFO);
        si(cbAlloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        sl(cbAlloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
        si(cbAlloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
        si(cbAlloc, VkStructs.COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 2);
        MemorySegment pCmds = a.allocate(ADDRESS, 2);
        check(invoke(allocCmd, dev, cbAlloc, pCmds), "vkAllocateCommandBuffers");
        this.cmd = pCmds.getAtIndex(ADDRESS, 0);
        this.copyCmd = pCmds.getAtIndex(ADDRESS, 1);

        MemorySegment fenceInfo = a.allocate(VkStructs.CREATE_INFO);
        si(fenceInfo, VkStructs.CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
        // Created signalled, so the first frame's wait returns immediately rather than deadlocking on a fence
        // nothing has submitted against — the same trick WindowedPresenter's in-flight fence uses.
        si(fenceInfo, VkStructs.CREATE_INFO, "flags", 0x1);
        check(invoke(createFence, dev, fenceInfo, MemorySegment.NULL, p), "vkCreateFence");
        this.fence = p.get(JAVA_LONG, 0);

        // --- the loop-invariant structs, filled once ---
        this.pFence = a.allocate(JAVA_LONG);
        pFence.set(JAVA_LONG, 0, fence);
        this.pCmd = a.allocate(ADDRESS);
        this.submit = a.allocate(VkStructs.SUBMIT_INFO);
        si(submit, VkStructs.SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
        si(submit, VkStructs.SUBMIT_INFO, "commandBufferCount", 1);
        sa(submit, VkStructs.SUBMIT_INFO, "pCommandBuffers", pCmd);
        // No wait or signal semaphores, and none are needed: the only consumer of this frame is either the next
        // frame (ordered by the fence) or a host read (ordered by the same fence). Semaphores order work against
        // a presentation engine, and there is not one here.

        this.beginInfo = a.allocate(VkStructs.COMMAND_BUFFER_BEGIN_INFO);
        si(beginInfo, VkStructs.COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

        // One VkClearValue (a 16-byte union) per attachment the pass clears — two with depth, and the depth
        // float goes in the first slot of the second union. Under-counting is not reliably caught: the loader
        // reads whatever follows the array.
        this.clear = a.allocate(JAVA_FLOAT, depth == null ? 4 : 8);
        this.rpBegin = a.allocate(VkStructs.RENDER_PASS_BEGIN_INFO);
        si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
        sl(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "renderPass", renderPass);
        sl(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "framebuffer", framebuffer);
        si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "area_extent_width", width);
        si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "area_extent_height", height);
        if (depth != null) {
            clear.setAtIndex(JAVA_FLOAT, 4, DepthAttachment.CLEAR_DEPTH);
            si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "clearValueCount", 2);
        } else {
            si(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "clearValueCount", 1);
        }
        sa(rpBegin, VkStructs.RENDER_PASS_BEGIN_INFO, "pClearValues", clear);

        // The extent never changes, so unlike the windowed presenter these are filled once and never touched
        // again — but they are still *set* every frame, because dynamic state is per command buffer.
        this.pViewport = a.allocate(VkStructs.VIEWPORT);
        sf(pViewport, VkStructs.VIEWPORT, "width", width);
        sf(pViewport, VkStructs.VIEWPORT, "height", height);
        sf(pViewport, VkStructs.VIEWPORT, "maxDepth", 1.0f);
        this.pScissor = a.allocate(VkStructs.RECT_2D);
        si(pScissor, VkStructs.RECT_2D, "extent_width", width);
        si(pScissor, VkStructs.RECT_2D, "extent_height", height);

        recordCopy(copyImageToBuffer);
    }

    /** The fixed extent every frame is drawn at. */
    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** How many frames this presenter has submitted. */
    public long frameCount() {
        return frames;
    }

    /**
     * Render one frame: run {@code perFrame}, then let {@code recorder} record into a begun render pass, then
     * submit. Returns {@code true} always.
     *
     * <p>The boolean is not decoration and it is not a placeholder for a future condition. It is what makes
     * this method substitutable for {@link WindowedPresenter#frame(int, FrameUpdate, Recorder)}, so the
     * engine's loop — {@code while (running && presenter.frame(0, perFrame, recorder))} — is one piece of code
     * driving both. An offscreen run simply has nothing that can end it from the presenter's side: there is no
     * window to close and no swapchain to go out of date, which is why the caller's frame callback is the only
     * thing that can stop the loop, and why the engine refuses an offscreen run without one.
     *
     * @param pushConstantBytes bytes of presenter-owned push-constant scratch to hand {@code perFrame}, or 0 —
     *                          techniques push their own layouts inside {@code record}, so the engine passes 0
     * @param perFrame          run before recording, or null
     * @param recorder          records this frame's draws; required, because a presenter with no pipeline of
     *                          its own has nothing to draw without one
     */
    public boolean frame(int pushConstantBytes, FrameUpdate perFrame, Recorder recorder) {
        if (closed) {
            throw new IllegalStateException("this presenter is closed");
        }
        if (recorder == null) {
            throw new IllegalArgumentException("an offscreen frame needs a Recorder — this presenter has no "
                    + "pipeline of its own, so without one the frame would be a clear and nothing else");
        }
        try (Zone z = Probe.zone(Lane.GPU, "offscreen frame")) {
            renderFrame(pushConstantBytes, perFrame, recorder);
        }
        return true;
    }

    private void renderFrame(int pushConstantBytes, FrameUpdate perFrame, Recorder recorder) {
        if (pushConstantBytes > pushCapacity) {
            pushSeg = a.allocate(pushConstantBytes);
            pushCapacity = pushConstantBytes;
        }
        // The previous frame must have finished before its command buffer is re-recorded and its image redrawn.
        // One frame in flight, exactly as the windowed path — see DepthAttachment on why the shared depth image
        // is safe only at that number.
        try (Zone w = Probe.zone(Lane.GPU, "wait fence")) {
            check(invoke(waitFences, dev, 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        }
        check(invoke(resetFences, dev, 1, pFence), "vkResetFences");

        long now = System.nanoTime();
        double dt = (now - previousNanos) / 1_000_000_000.0;
        previousNanos = now;
        if (perFrame != null) {
            perFrame.update(dt, pushSeg);
        }

        check(invoke(beginCmd, cmd, beginInfo), "vkBeginCommandBuffer");
        invokeVoid(beginRp, cmd, rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
        // Unconditionally, and before the recorder: the contract Recorder states is that viewport and scissor
        // are already the frame's, and a technique written against the windowed presenter must not have to
        // learn that the headless one is different.
        invokeVoid(setViewport, cmd, 0, 1, pViewport);
        invokeVoid(setScissor, cmd, 0, 1, pScissor);
        recorder.record(cmd, width, height);
        invokeVoid(endRp, cmd);
        check(invoke(endCmd, cmd), "vkEndCommandBuffer");

        pCmd.set(ADDRESS, 0, cmd);
        try (Zone w = Probe.zone(Lane.GPU, "queue submit")) {
            check(invoke(submitQueue, device.queue(), 1, submit, fence), "vkQueueSubmit");
        }
        frames++;
    }

    /**
     * The last frame rendered, as tightly-packed R8G8B8A8 — row-major, top-to-bottom,
     * {@code width * height * 4} bytes.
     *
     * <p>Waits for that frame, copies the colour image into the readback buffer, waits for the copy, and
     * returns a fresh array. Safe to call repeatedly; each call re-copies, so reading twice with a frame
     * between them gives two different pictures, and reading twice with nothing between them gives the same
     * one.
     *
     * <p>The image is already in {@code TRANSFER_SRC_OPTIMAL} when this runs — the render pass's final layout
     * puts it there and the pass's outgoing subpass dependency makes the colour writes visible to the transfer
     * stage — which is why there is no barrier here and why the class note insists on that {@code finalLayout}.
     *
     * @throws IllegalStateException if no frame has been rendered yet, because the image's contents are then
     *                               undefined and its layout is not one a copy may read
     */
    public byte[] readRgba() {
        if (closed) {
            throw new IllegalStateException("this presenter is closed");
        }
        if (frames == 0) {
            throw new IllegalStateException("nothing has been rendered yet — call frame(...) before readRgba()");
        }
        try (Zone z = Probe.zone(Lane.GPU, "offscreen readback")) {
            check(invoke(waitFences, dev, 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
            check(invoke(resetFences, dev, 1, pFence), "vkResetFences");
            pCmd.set(ADDRESS, 0, copyCmd);
            check(invoke(submitQueue, device.queue(), 1, submit, fence), "vkQueueSubmit");
            check(invoke(waitFences, dev, 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
            // Left signalled deliberately: the next frame's wait is then a no-op rather than a hang, which is
            // the same state the constructor starts in.
            return mapped.toArray(JAVA_BYTE);
        }
    }

    /**
     * Record the image → buffer copy once. Begun without {@code ONE_TIME_SUBMIT}, because this buffer is
     * submitted again on every read; that flag would make the second read undefined behaviour.
     */
    private void recordCopy(MethodHandle copyImageToBuffer) {
        MemorySegment copyBegin = a.allocate(VkStructs.COMMAND_BUFFER_BEGIN_INFO);
        si(copyBegin, VkStructs.COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
        check(invoke(beginCmd, copyCmd, copyBegin), "vkBeginCommandBuffer");
        MemorySegment region = a.allocate(VkStructs.BUFFER_IMAGE_COPY);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "is_layerCount", 1);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_width", width);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_height", height);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_depth", 1);
        invokeVoid(copyImageToBuffer, copyCmd, image, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, region);
        check(invoke(endCmd, copyCmd), "vkEndCommandBuffer");
    }

    private long allocateMemory(MethodHandle allocMemory, long size, int typeIndex) {
        MemorySegment info = a.allocate(VkStructs.MEMORY_ALLOCATE_INFO);
        si(info, VkStructs.MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
        sl(info, VkStructs.MEMORY_ALLOCATE_INFO, "allocationSize", size);
        si(info, VkStructs.MEMORY_ALLOCATE_INFO, "memoryTypeIndex", typeIndex);
        MemorySegment pMemory = a.allocate(JAVA_LONG);
        check(invoke(allocMemory, dev, info, MemorySegment.NULL, pMemory), "vkAllocateMemory");
        return pMemory.get(JAVA_LONG, 0);
    }

    /**
     * Idempotent, for the reason {@link WindowedPresenter#close()} spells out at length: destroying an
     * already-destroyed handle corrupts the loader's own heap, and the symptom surfaces later as a call
     * through an entry point that is no longer code.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Probe.closed(Lane.GPU, "OffscreenPresenter", this);
        device.waitIdle();
        invokeVoid(destroyPool, dev, pool, MemorySegment.NULL);
        invokeVoid(destroyFence, dev, fence, MemorySegment.NULL);
        invokeVoid(unmapMemory, dev, bufferMemory);
        invokeVoid(destroyBuffer, dev, buffer, MemorySegment.NULL);
        invokeVoid(freeMemory, dev, bufferMemory, MemorySegment.NULL);
        invokeVoid(destroyFramebuffer, dev, framebuffer, MemorySegment.NULL);
        if (depth != null) {
            depth.close();
        }
        invokeVoid(destroyImageView, dev, imageView, MemorySegment.NULL);
        invokeVoid(destroyImage, dev, image, MemorySegment.NULL);
        invokeVoid(freeMemory, dev, imageMemory, MemorySegment.NULL);
        a.close();
    }
}
