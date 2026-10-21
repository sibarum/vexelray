package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VkStructs;
import dev.supirvast.vulkan.VulkanDevice;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayDeque;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.gi;
import static dev.supirvast.vulkan.Ffm.gl;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A presenter's {@link FrameTap}: a few host-visible buffers that each presented frame is copied into, inside the
 * frame's own command buffer, and handed to the tap once the GPU has finished with it. What both presenters share, so
 * the copy, the order and the hand-over are written once.
 *
 * <p><b>The frame loop never waits on it.</b> {@link #record} adds the copy to a command buffer the presenter is about
 * to submit, if a buffer is free. {@link #collect}, called after the presenter's own fence wait at the top of a frame,
 * asks each pending copy's fence whether it is done ({@code vkGetFenceStatus}, which does not block), and hands the
 * finished ones to the tap in order. A buffer comes back when the tap {@linkplain TappedFrame#release releases} it.
 * With {@value #SLOTS} buffers, a tap that encodes within two frames' time misses nothing.
 *
 * <p><b>Cached memory where there is some.</b> The tap reads every byte of every frame, and a readback buffer in
 * write-combined memory reads at a small fraction of the speed of cached memory, which would make an encoder that keeps
 * up from cached memory fall behind. So the buffers are host-cached and coherent where the device offers that, and
 * plain host-visible and coherent where it does not.
 *
 * <p>Everything here except {@link TappedFrame#release} runs on the thread that renders.
 */
public final class FrameTapRing implements AutoCloseable {

    /** Buffers in the ring: one being copied into, one being handed over, one held by the tap. */
    public static final int SLOTS = 3;

    /** How long {@link #close} waits for the tap to give its buffers back before leaving them unfreed. */
    private static final long CLOSE_WAIT_MILLIS = 5_000;

    private static final int FREE = 0, COPYING = 1, HELD = 2;

    // Not in supir's Vk.
    private static final int SUCCESS = 0;
    private static final int STRUCTURE_TYPE_MEMORY_BARRIER = 46;
    private static final int ACCESS_HOST_READ_BIT = 0x2000;
    private static final int PIPELINE_STAGE_HOST_BIT = 0x4000;
    private static final int MEMORY_PROPERTY_HOST_CACHED_BIT = 0x8;

    private static final GroupLayout MEMORY_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask")
    ).withName("VkMemoryBarrier");

    private static final GroupLayout IMAGE_MEMORY_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("oldLayout"), JAVA_INT.withName("newLayout"),
            JAVA_INT.withName("srcQueueFamilyIndex"), JAVA_INT.withName("dstQueueFamilyIndex"),
            JAVA_LONG.withName("image"),
            JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"), JAVA_INT.withName("sr_levelCount"),
            JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageMemoryBarrier");

    private final VulkanDevice device;
    private final MemorySegment dev;
    private final boolean bgra;
    private final Arena arena = Arena.ofShared();
    private final Slot[] slots = new Slot[SLOTS];
    /** Copies recorded and not yet handed over, oldest first. */
    private final ArrayDeque<Slot> pending = new ArrayDeque<>(SLOTS);

    private final MethodHandle createBuffer, destroyBuffer, bufferRequirements, allocateMemory, freeMemory;
    private final MethodHandle bindBufferMemory, mapMemory, unmapMemory, pipelineBarrier, copyImageToBuffer;
    private final MethodHandle fenceStatus, waitFences;

    /** The buffer-to-image copy and its barriers, filled per copy; made once. */
    private final MemorySegment region, toCopy, toAfter, toHost, pFence;

    private FrameTap tap;
    private long skipped;
    private boolean closed;

    /** A ring on {@code device} for images whose bytes are B, G, R, A ({@code bgra}) or R, G, B, A. */
    public FrameTapRing(VulkanDevice device, boolean bgra) {
        this.device = device;
        this.dev = device.handle();
        this.bgra = bgra;
        FunctionDescriptor c4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
        FunctionDescriptor destroy = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
        this.createBuffer = device.command("vkCreateBuffer", c4);
        this.destroyBuffer = device.command("vkDestroyBuffer", destroy);
        this.bufferRequirements = device.command("vkGetBufferMemoryRequirements",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        this.allocateMemory = device.command("vkAllocateMemory", c4);
        this.freeMemory = device.command("vkFreeMemory", destroy);
        this.bindBufferMemory = device.command("vkBindBufferMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG));
        this.mapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        this.unmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        this.pipelineBarrier = device.command("vkCmdPipelineBarrier",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                        JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.copyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        this.fenceStatus = device.command("vkGetFenceStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        this.waitFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        this.region = arena.allocate(VkStructs.BUFFER_IMAGE_COPY);
        this.toCopy = arena.allocate(IMAGE_MEMORY_BARRIER);
        this.toAfter = arena.allocate(IMAGE_MEMORY_BARRIER);
        this.toHost = arena.allocate(MEMORY_BARRIER);
        this.pFence = arena.allocate(JAVA_LONG);
        for (int i = 0; i < SLOTS; i++) {
            slots[i] = new Slot();
        }
    }

    /**
     * Hand frames to {@code tap} from the next one recorded, or stop with {@code null}. A tap being replaced or removed
     * first gets every copy already recorded for it, waiting for the GPU to finish them, so the last frames shown
     * before it stops are not lost.
     */
    public void tap(FrameTap tap) {
        if (this.tap != null && tap != this.tap) {
            flush();
        }
        this.tap = closed ? null : tap;
    }

    /** Whether frames are being copied for a tap. */
    public boolean tapped() {
        return tap != null;
    }

    /** Frames presented while a tap was set but not copied, because every buffer was busy. */
    public long skipped() {
        return skipped;
    }

    /**
     * Copy the top-left {@code width x height} of {@code image}, just drawn, into a free buffer, in {@code cmd} after
     * the image's last write (made at {@code srcStage} with {@code srcAccess}) and before anything that presents it.
     * The image is in {@code layout} and is left in {@code layout}; the copy reads it in {@code copyLayout} (the same
     * layout where that allows transfer reads, {@code GENERAL}; {@code TRANSFER_SRC_OPTIMAL} for a swapchain image in
     * {@code PRESENT_SRC_KHR}). {@code fence} is the fence the submit carrying {@code cmd} signals. Nothing is recorded
     * without a tap, or when no buffer is free.
     */
    public void record(MemorySegment cmd, long image, int layout, int copyLayout, int srcStage, int srcAccess,
                       int width, int height, long fence) {
        if (tap == null || closed || width <= 0 || height <= 0) {
            return;
        }
        Slot slot = null;
        for (Slot s : slots) {
            if (s.state.get() == FREE) {
                slot = s;
                break;
            }
        }
        if (slot == null) {
            skipped++;
            Probe.count(Lane.GPU, "frame tap skipped", 1);
            return;
        }
        long bytes = (long) width * height * 4;
        if (!slot.fits(bytes)) {
            return;
        }
        // Make the frame's writes visible to the copy, in the layout the copy reads it in.
        imageBarrier(toCopy, image, srcAccess, Vk.ACCESS_TRANSFER_READ_BIT, layout, copyLayout);
        invokeVoid(pipelineBarrier, cmd, srcStage, Vk.PIPELINE_STAGE_TRANSFER_BIT, 0,
                0, MemorySegment.NULL, 0, MemorySegment.NULL, 1, toCopy);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "is_layerCount", 1);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_width", width);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_height", height);
        si(region, VkStructs.BUFFER_IMAGE_COPY, "ext_depth", 1);
        invokeVoid(copyImageToBuffer, cmd, image, copyLayout, slot.buffer, 1, region);
        // Back to the layout it was in for whatever follows (the present), and the copy's writes made visible to the
        // host, which reads them once the fence says the submit is done.
        imageBarrier(toAfter, image, Vk.ACCESS_TRANSFER_READ_BIT, 0, copyLayout, layout);
        si(toHost, MEMORY_BARRIER, "sType", STRUCTURE_TYPE_MEMORY_BARRIER);
        si(toHost, MEMORY_BARRIER, "srcAccessMask", Vk.ACCESS_TRANSFER_WRITE_BIT);
        si(toHost, MEMORY_BARRIER, "dstAccessMask", ACCESS_HOST_READ_BIT);
        invokeVoid(pipelineBarrier, cmd, Vk.PIPELINE_STAGE_TRANSFER_BIT,
                Vk.PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT | PIPELINE_STAGE_HOST_BIT, 0,
                1, toHost, 0, MemorySegment.NULL, 1, toAfter);
        slot.state.set(COPYING);
        slot.width = width;
        slot.height = height;
        slot.fence = fence;
        slot.presentedNanos = System.nanoTime();
        pending.addLast(slot);
    }

    /**
     * Hand the tap every copy the GPU has finished, oldest first, stopping at the first that is not. Does not block:
     * called after the presenter's fence wait at the top of a frame, when the copy recorded {@code framesInFlight}
     * frames ago is certainly done.
     */
    public void collect() {
        while (!pending.isEmpty()) {
            Slot s = pending.peekFirst();
            if (invoke(fenceStatus, dev, s.fence) != SUCCESS) {
                return;
            }
            pending.pollFirst();
            deliver(s);
        }
    }

    /** Wait for every pending copy and hand it over: before the tap changes, and at close. */
    private void flush() {
        while (!pending.isEmpty()) {
            Slot s = pending.pollFirst();
            pFence.set(JAVA_LONG, 0, s.fence);
            check(invoke(waitFences, dev, 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
            deliver(s);
        }
    }

    private void deliver(Slot s) {
        FrameTap to = tap;
        if (to == null) {
            s.state.set(FREE);
            return;
        }
        s.state.set(HELD);
        try {
            to.frame(s.new Handed());
        } catch (RuntimeException e) {
            s.state.set(FREE);
            throw e;
        }
    }

    /**
     * Hand over what is pending, stop tapping, and free the buffers once the tap has given them back, or after
     * {@value #CLOSE_WAIT_MILLIS} ms, after which a buffer still held is left unfreed rather than freed under its
     * reader. The device must be idle on this ring's copies (the presenter waits for it).
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        flush();
        tap = null;
        closed = true;
        long deadline = System.nanoTime() + CLOSE_WAIT_MILLIS * 1_000_000L;
        for (Slot s : slots) {
            while (s.state.get() == HELD && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (s.state.get() != HELD) {
                s.free();
            }
        }
        // The arena holds only the structs above; the buffers' memory is the device's, freed slot by slot.
        arena.close();
    }

    private void imageBarrier(MemorySegment b, long image, int srcAccess, int dstAccess, int oldLayout, int newLayout) {
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

    /** One readback buffer, persistently mapped, grown when a frame outgrows it. */
    private final class Slot {

        final AtomicInteger state = new AtomicInteger(FREE);
        long buffer;
        long memory;
        long capacity;
        MemorySegment mapped = MemorySegment.NULL;
        int width;
        int height;
        long fence;
        long presentedNanos;

        /** Make the buffer at least {@code bytes}, while it is free. False, and nothing copied, if that fails. */
        boolean fits(long bytes) {
            if (bytes <= capacity) {
                return true;
            }
            free();
            try (Arena temp = Arena.ofConfined()) {
                MemorySegment info = temp.allocate(VkStructs.BUFFER_CREATE_INFO);
                si(info, VkStructs.BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
                sl(info, VkStructs.BUFFER_CREATE_INFO, "size", bytes);
                si(info, VkStructs.BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
                si(info, VkStructs.BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
                MemorySegment pBuffer = temp.allocate(JAVA_LONG);
                check(invoke(createBuffer, dev, info, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
                buffer = pBuffer.get(JAVA_LONG, 0);
                MemorySegment req = temp.allocate(VkStructs.MEMORY_REQUIREMENTS);
                invokeVoid(bufferRequirements, dev, buffer, req);
                int bits = gi(req, VkStructs.MEMORY_REQUIREMENTS, "memoryTypeBits");
                int coherent = Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT;
                int type = device.tryFindMemoryType(bits, coherent | MEMORY_PROPERTY_HOST_CACHED_BIT);
                if (type < 0) {
                    type = device.findMemoryType(bits, coherent);
                }
                MemorySegment alloc = temp.allocate(VkStructs.MEMORY_ALLOCATE_INFO);
                si(alloc, VkStructs.MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
                sl(alloc, VkStructs.MEMORY_ALLOCATE_INFO, "allocationSize", gl(req, VkStructs.MEMORY_REQUIREMENTS, "size"));
                si(alloc, VkStructs.MEMORY_ALLOCATE_INFO, "memoryTypeIndex", type);
                MemorySegment pMemory = temp.allocate(JAVA_LONG);
                check(invoke(allocateMemory, dev, alloc, MemorySegment.NULL, pMemory), "vkAllocateMemory");
                memory = pMemory.get(JAVA_LONG, 0);
                check(invoke(bindBufferMemory, dev, buffer, memory, 0L), "vkBindBufferMemory");
                MemorySegment ppData = temp.allocate(ADDRESS);
                check(invoke(mapMemory, dev, memory, 0L, bytes, 0, ppData), "vkMapMemory");
                mapped = ppData.get(ADDRESS, 0).reinterpret(bytes);
                capacity = bytes;
                return true;
            } catch (RuntimeException e) {
                free();
                Probe.mark(Lane.GPU, "frame tap", "no readback buffer of " + bytes + " bytes: " + e.getMessage());
                return false;
            }
        }

        void free() {
            if (memory != 0L) {
                invokeVoid(unmapMemory, dev, memory);
            }
            if (buffer != 0L) {
                invokeVoid(destroyBuffer, dev, buffer, MemorySegment.NULL);
            }
            if (memory != 0L) {
                invokeVoid(freeMemory, dev, memory, MemorySegment.NULL);
            }
            buffer = 0L;
            memory = 0L;
            capacity = 0L;
            mapped = MemorySegment.NULL;
        }

        /** This slot's frame, as the tap sees it: fixed at hand-over, so a later copy cannot change what it reads. */
        final class Handed implements TappedFrame {

            private final MemorySegment pixels = mapped.asSlice(0, (long) width * height * 4);
            private final int w = width;
            private final int h = height;
            private final long at = presentedNanos;
            private final AtomicInteger released = new AtomicInteger();

            @Override
            public MemorySegment pixels() {
                return pixels;
            }

            @Override
            public int width() {
                return w;
            }

            @Override
            public int height() {
                return h;
            }

            @Override
            public boolean bgra() {
                return bgra;
            }

            @Override
            public long presentedNanos() {
                return at;
            }

            @Override
            public void release() {
                if (released.compareAndSet(0, 1)) {
                    state.set(FREE);
                }
            }
        }
    }
}
