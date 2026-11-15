package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.gi;
import static dev.supirvast.vulkan.Ffm.gl;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.sa;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A device-written feed's slots (vexelray-gui's docs/plans/device-feed.md A, 4d): storage buffers of one size on the
 * application's device, written by a producer on a queue of its own, and the timeline semaphore its submissions
 * signal when a slot's writes are done. The frame that reads a slot binds its buffer and waits for its value inside
 * the GPU ({@link FrameWaits}); nothing is copied.
 *
 * <p><b>The feed owns the timeline, as it owns the buffers.</b> A frame in flight waits on it, and only the side that
 * draws knows when no frame does any more, so a timeline the producer made and closed when it stopped could be
 * destroyed under a frame still waiting on it. Freed together, after the frames ({@link #close}).
 *
 * <p><b>Shared, not transferred</b> (device-feed.md, open question 3): made with concurrent sharing between the queue
 * family that draws and the writer's, where they differ, so no ownership transfer is recorded on either side. A buffer
 * has no compression for exclusive ownership to keep.
 *
 * <p>Made on any thread (allocating on a device is not a queue's business). {@link #counter} on one thread, the one
 * that records frames; {@link #close} there once no frame reads the buffers and no submission writes them.
 */
public final class FeedBuffers implements AutoCloseable {

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    private static final GroupLayout BUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("size"),
            JAVA_INT.withName("usage"), JAVA_INT.withName("sharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices")).withName("VkBufferCreateInfo");
    private static final GroupLayout MEMORY_REQUIREMENTS = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"), JAVA_LONG.withName("alignment"),
            JAVA_INT.withName("memoryTypeBits"), MemoryLayout.paddingLayout(4)).withName("VkMemoryRequirements");
    private static final GroupLayout MEMORY_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("allocationSize"), JAVA_INT.withName("memoryTypeIndex"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryAllocateInfo");
    private static final GroupLayout SEMAPHORE_TYPE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("semaphoreType"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("initialValue"))
            .withName("VkSemaphoreTypeCreateInfo");
    private static final GroupLayout SEMAPHORE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("VkSemaphoreCreateInfo");

    private static final int SHARING_MODE_CONCURRENT = 1;
    private static final int STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO = 1000207002;
    private static final int SEMAPHORE_TYPE_TIMELINE = 1;

    private final VulkanDevice device;
    private final long bytes;
    private final boolean shared;
    private final long[] buffers;
    private final long[] memories;
    private final long timeline;

    private final MethodHandle vkDestroyBuffer;
    private final MethodHandle vkFreeMemory;
    private final MethodHandle vkDestroySemaphore;
    private final MethodHandle vkGetSemaphoreCounterValue;
    /** Where the counter is read into, made once: reading it allocates nothing. */
    private final Arena arena = Arena.ofShared();
    private final MemorySegment counterOut = arena.allocate(JAVA_LONG);
    private boolean closed;

    /**
     * {@code slots} buffers of {@code bytes} each on {@code device}, written by a queue of family {@code writerFamily}
     * and read by the one that draws ({@link VulkanDevice#queueFamilyIndex()}). Refused on a device made without
     * timeline semaphores, which a device that lends a queue always has.
     */
    public FeedBuffers(VulkanDevice device, long bytes, int slots, int writerFamily) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("a slot has bytes: " + bytes);
        }
        if (slots < 1) {
            throw new IllegalArgumentException("a feed has a slot at least: " + slots);
        }
        if (!device.timelineSemaphore()) {
            throw new IllegalStateException("a device-written feed hands over with a timeline value, and this device "
                    + "was made without timeline semaphores; a device that lends a queue is made with them");
        }
        Probe.opened(Lane.GPU, "FeedBuffers", this);
        this.device = device;
        this.bytes = bytes;
        int drawing = device.queueFamilyIndex();
        this.shared = writerFamily != drawing;
        this.buffers = new long[slots];
        this.memories = new long[slots];
        MemorySegment dev = device.handle();
        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkCreateSemaphore = device.command("vkCreateSemaphore", C4);
        this.vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        this.vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        this.vkDestroySemaphore = device.command("vkDestroySemaphore", D_LONG);
        this.vkGetSemaphoreCounterValue = device.command("vkGetSemaphoreCounterValue",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
        try (Arena a = Arena.ofConfined()) {
            MemorySegment families = a.allocate(JAVA_INT, 2);
            families.setAtIndex(JAVA_INT, 0, drawing);
            families.setAtIndex(JAVA_INT, 1, writerFamily);
            for (int i = 0; i < slots; i++) {
                MemorySegment info = a.allocate(BUFFER_CREATE_INFO);
                si(info, BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
                sl(info, BUFFER_CREATE_INFO, "size", bytes);
                si(info, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_STORAGE_BUFFER_BIT
                        | Vk.BUFFER_USAGE_TRANSFER_SRC_BIT | Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
                if (shared) {
                    si(info, BUFFER_CREATE_INFO, "sharingMode", SHARING_MODE_CONCURRENT);
                    si(info, BUFFER_CREATE_INFO, "queueFamilyIndexCount", 2);
                    sa(info, BUFFER_CREATE_INFO, "pQueueFamilyIndices", families);
                } else {
                    si(info, BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
                }
                MemorySegment pBuffer = a.allocate(JAVA_LONG);
                check(invoke(vkCreateBuffer, dev, info, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
                buffers[i] = pBuffer.get(JAVA_LONG, 0);
                MemorySegment req = a.allocate(MEMORY_REQUIREMENTS);
                invokeVoid(vkGetBufferMemoryRequirements, dev, buffers[i], req);
                MemorySegment alloc = a.allocate(MEMORY_ALLOCATE_INFO);
                si(alloc, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
                sl(alloc, MEMORY_ALLOCATE_INFO, "allocationSize", gl(req, MEMORY_REQUIREMENTS, "size"));
                si(alloc, MEMORY_ALLOCATE_INFO, "memoryTypeIndex", device.findMemoryType(
                        gi(req, MEMORY_REQUIREMENTS, "memoryTypeBits"), Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
                MemorySegment pMem = a.allocate(JAVA_LONG);
                check(invoke(vkAllocateMemory, dev, alloc, MemorySegment.NULL, pMem), "vkAllocateMemory");
                memories[i] = pMem.get(JAVA_LONG, 0);
                check(invoke(vkBindBufferMemory, dev, buffers[i], memories[i], 0L), "vkBindBufferMemory");
            }
            MemorySegment type = a.allocate(SEMAPHORE_TYPE_CREATE_INFO);
            si(type, SEMAPHORE_TYPE_CREATE_INFO, "sType", STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO);
            si(type, SEMAPHORE_TYPE_CREATE_INFO, "semaphoreType", SEMAPHORE_TYPE_TIMELINE);
            MemorySegment info = a.allocate(SEMAPHORE_CREATE_INFO);
            si(info, SEMAPHORE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
            sa(info, SEMAPHORE_CREATE_INFO, "pNext", type);
            MemorySegment pSem = a.allocate(JAVA_LONG);
            check(invoke(vkCreateSemaphore, dev, info, MemorySegment.NULL, pSem), "vkCreateSemaphore");
            this.timeline = pSem.get(JAVA_LONG, 0);
        }
    }

    /** The device the buffers are on. */
    public VulkanDevice device() {
        return device;
    }

    /** How many slots. */
    public int slots() {
        return buffers.length;
    }

    /** The bytes of each slot's buffer. */
    public long bytes() {
        return bytes;
    }

    /** Slot {@code slot}'s buffer: storage, and a copy's source or destination. */
    public long buffer(int slot) {
        if (slot < 0 || slot >= buffers.length) {
            throw new IndexOutOfBoundsException("no slot " + slot + " in " + this);
        }
        return buffers[slot];
    }

    /** The timeline a writer's submission signals once a slot's writes are done, starting at zero. */
    public long timeline() {
        return timeline;
    }

    /** Whether the buffers are shared between two queue families, rather than one's alone. */
    public boolean shared() {
        return shared;
    }

    /** The timeline's value now: what every submission that signalled it has reached. Never waits, allocates nothing. */
    public long counter() {
        check(invoke(vkGetSemaphoreCounterValue, device.handle(), timeline, counterOut), "vkGetSemaphoreCounterValue");
        return counterOut.get(JAVA_LONG, 0);
    }

    /** Free the buffers and the timeline: once no frame reads them and no submission writes or signals them. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Probe.closed(Lane.GPU, "FeedBuffers", this);
        MemorySegment dev = device.handle();
        for (int i = 0; i < buffers.length; i++) {
            invokeVoid(vkDestroyBuffer, dev, buffers[i], MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, memories[i], MemorySegment.NULL);
        }
        invokeVoid(vkDestroySemaphore, dev, timeline, MemorySegment.NULL);
        arena.close();
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public String toString() {
        return "FeedBuffers[" + buffers.length + (buffers.length == 1 ? " slot" : " slots") + " of " + bytes + " bytes"
                + (shared ? ", shared" : "") + (closed ? ", closed" : "") + "]";
    }
}
