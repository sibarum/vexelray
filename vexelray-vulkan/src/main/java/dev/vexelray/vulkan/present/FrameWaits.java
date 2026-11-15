package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.VkStructs;

import java.lang.foreign.Arena;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;

import static dev.supirvast.vulkan.Ffm.sa;
import static dev.supirvast.vulkan.Ffm.si;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The timeline values a frame's submission waits for before anything in it runs: what another queue wrote that the
 * frame reads (vexelray-gui's docs/plans/device-feed.md A, a device-written feed's slot). A pre-pass names them
 * ({@link Recorder#waits}) and whoever submits the frame (every presenter, and {@link OffscreenDraw}) adds them to its
 * own waits, at every stage, through a {@code VkTimelineSemaphoreSubmitInfo}.
 *
 * <p><b>Why a wait, when the value is already reached.</b> A frame reads such a slot only once the host has seen its
 * value reached, so the wait never holds the GPU. It is there for what reading the counter does not give: a semaphore
 * wait is what makes another queue's writes visible to this one (a signal makes them available, and only a wait on a
 * queue makes them visible to it). So it is owed by every frame that reads the slot, and it costs nothing.
 *
 * <p>One semaphore named twice is waited for once, at the higher value. Reused frame after frame: {@link #clear},
 * then add; it allocates only when it grows. Not thread-safe: the thread that records frames.
 */
public final class FrameWaits {

    /** The stage a frame waits at: every one, since what it reads may be read by any of them. */
    public static final int STAGE = 0x00010000;   // VK_PIPELINE_STAGE_ALL_COMMANDS_BIT

    /** Chained on a {@code VkSubmitInfo} to give each timeline semaphore's value. */
    public static final GroupLayout TIMELINE_SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreValueCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pWaitSemaphoreValues"), JAVA_INT.withName("signalSemaphoreValueCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pSignalSemaphoreValues"))
            .withName("VkTimelineSemaphoreSubmitInfo");

    public static final int STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO = 1000207003;

    private long[] semaphores = new long[4];
    private long[] values = new long[4];
    private int count;

    /** Wait for {@code semaphore}, a timeline, to reach {@code value}; named again, the higher value is kept. */
    public void add(long semaphore, long value) {
        if (semaphore == 0L) {
            throw new IllegalArgumentException("a frame waits on a semaphore, not on none");
        }
        for (int i = 0; i < count; i++) {
            if (semaphores[i] == semaphore) {
                values[i] = Math.max(values[i], value);
                return;
            }
        }
        if (count == semaphores.length) {
            semaphores = Arrays.copyOf(semaphores, count * 2);
            values = Arrays.copyOf(values, count * 2);
        }
        semaphores[count] = semaphore;
        values[count] = value;
        count++;
    }

    /** Every wait in {@code other} added here. */
    public void addAll(FrameWaits other) {
        for (int i = 0; i < other.count; i++) {
            add(other.semaphores[i], other.values[i]);
        }
    }

    public void clear() {
        count = 0;
    }

    public int count() {
        return count;
    }

    public long semaphore(int i) {
        return semaphores[i];
    }

    public long value(int i) {
        return values[i];
    }

    /**
     * Write every wait into a submission's arrays from index {@code at}: the semaphore, {@link #STAGE}, and the value.
     * The caller sized the arrays for its own waits plus {@link #count()}, and gives a binary semaphore of its own a
     * value of zero, which Vulkan ignores.
     */
    public void fill(MemorySegment pSemaphores, MemorySegment pStages, MemorySegment pValues, int at) {
        for (int i = 0; i < count; i++) {
            pSemaphores.setAtIndex(JAVA_LONG, at + i, semaphores[i]);
            pStages.setAtIndex(JAVA_INT, at + i, STAGE);
            pValues.setAtIndex(JAVA_LONG, at + i, values[i]);
        }
    }

    /**
     * Make {@code submit}, a {@code VkSubmitInfo} that waits for nothing of its own, wait for these: its wait arrays and
     * the chained timeline values allocated from {@code arena}. Nothing changes when there are none.
     */
    public void applyTo(Arena arena, MemorySegment submit) {
        if (count == 0) {
            return;
        }
        MemorySegment pSemaphores = arena.allocate(JAVA_LONG, count);
        MemorySegment pStages = arena.allocate(JAVA_INT, count);
        MemorySegment pValues = arena.allocate(JAVA_LONG, count);
        fill(pSemaphores, pStages, pValues, 0);
        MemorySegment chained = arena.allocate(TIMELINE_SUBMIT_INFO);
        si(chained, TIMELINE_SUBMIT_INFO, "sType", STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO);
        si(chained, TIMELINE_SUBMIT_INFO, "waitSemaphoreValueCount", count);
        sa(chained, TIMELINE_SUBMIT_INFO, "pWaitSemaphoreValues", pValues);
        sa(submit, VkStructs.SUBMIT_INFO, "pNext", chained);
        si(submit, VkStructs.SUBMIT_INFO, "waitSemaphoreCount", count);
        sa(submit, VkStructs.SUBMIT_INFO, "pWaitSemaphores", pSemaphores);
        sa(submit, VkStructs.SUBMIT_INFO, "pWaitDstStageMask", pStages);
    }

    @Override
    public String toString() {
        StringBuilder b = new StringBuilder("FrameWaits[");
        for (int i = 0; i < count; i++) {
            b.append(i == 0 ? "" : ", ").append(Long.toHexString(semaphores[i])).append('@').append(values[i]);
        }
        return b.append(']').toString();
    }
}
