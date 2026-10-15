package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.si;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A point in the device queue's submission order: a fence submitted with no work, which signals once everything
 * submitted to {@link VulkanDevice#queue()} before it has finished. A fence signal covers every command earlier in
 * submission order, not only its own batch, so this is {@code vkDeviceWaitIdle} taken as a promise instead of a
 * wait: whoever must not free something until the frames that used it are done takes a mark after the last of them
 * is submitted, and frees it once {@link #passed}, while frames submitted after the mark carry on.
 *
 * <p>Every VexelRay submission goes to that one queue, so the mark covers frames, uploads and readbacks alike. The
 * caller holds the queue as it would for any submission: the thread that records frames.
 */
public final class QueueMark implements AutoCloseable {

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final int VK_NOT_READY = 1;

    private static final GroupLayout FENCE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("VkFenceCreateInfo");

    private final VulkanDevice device;
    private final long fence;
    private final MethodHandle vkGetFenceStatus;
    private final MethodHandle vkWaitForFences;
    private final MethodHandle vkDestroyFence;
    private boolean passed;
    private boolean closed;

    private QueueMark(VulkanDevice device) {
        this.device = device;
        MemorySegment dev = device.handle();
        MethodHandle vkCreateFence = device.command("vkCreateFence", C4);
        MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
        this.vkGetFenceStatus = device.command("vkGetFenceStatus", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
        this.vkWaitForFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        this.vkDestroyFence = device.command("vkDestroyFence", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment info = arena.allocate(FENCE_CREATE_INFO);
            si(info, FENCE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
            MemorySegment pFence = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateFence, dev, info, MemorySegment.NULL, pFence), "vkCreateFence");
            this.fence = pFence.get(JAVA_LONG, 0);
        }
        // No batches, only the fence: it signals when all work previously submitted to the queue has completed.
        check(invoke(vkQueueSubmit, device.queue(), 0, MemorySegment.NULL, fence), "vkQueueSubmit");
    }

    /** A mark after everything submitted to {@code device}'s queue so far. */
    public static QueueMark now(VulkanDevice device) {
        return new QueueMark(device);
    }

    /** Whether everything submitted before this mark has finished. Never blocks. */
    public boolean passed() {
        if (!passed) {
            int r = invoke(vkGetFenceStatus, device.handle(), fence);
            if (r != VK_NOT_READY) {
                check(r, "vkGetFenceStatus");
                passed = true;
            }
        }
        return passed;
    }

    /** Block until everything submitted before this mark has finished, and no longer. */
    public void await() {
        if (passed) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pFence = arena.allocate(JAVA_LONG);
            pFence.set(JAVA_LONG, 0, fence);
            check(invoke(vkWaitForFences, device.handle(), 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        }
        passed = true;
    }

    /** Destroy the fence. A fence still pending on the queue must not be destroyed, so this waits for it first. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        await();
        invokeVoid(vkDestroyFence, device.handle(), fence, MemorySegment.NULL);
    }
}
