package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link FeedBuffers} on a real device, and {@link FrameWaits}: a device-written feed's slots are buffers apart, shared
 * between two families only when the writer's is not the drawing one, and its timeline is read without waiting.
 * Skipped where there is no Vulkan. Writing on another queue and reading in a frame is vexelray-gui's
 * {@code DeviceWrittenFeedTest}.
 */
class FeedBuffersTest {

    private static final GroupLayout SEMAPHORE_SIGNAL_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("semaphore"), JAVA_LONG.withName("value")).withName("VkSemaphoreSignalInfo");

    private record Rig(VulkanInstance instance, VulkanDevice device, int computeFamily) implements AutoCloseable {
        @Override
        public void close() {
            device.close();
            instance.close();
        }
    }

    private static Rig rigOrSkip(boolean timelines) {
        VulkanInstance instance;
        try {
            instance = new VulkanInstance("feed buffers test", List.of());
        } catch (RuntimeException | Error e) {
            assumeTrue(false, "no Vulkan loader: " + e.getMessage());
            throw e;
        }
        Optional<VulkanInstance.DeviceSelection> selection = instance.selectGraphicsDevice();
        if (selection.isEmpty()) {
            instance.close();
            assumeTrue(false, "no device with a graphics queue");
        }
        int compute = instance.computeOnlyQueueFamily(selection.orElseThrow().physicalDevice());
        VulkanDevice.Request request = new VulkanDevice.Request(false, null, 1);
        if (timelines) {
            request = request.withTimelineSemaphore();
            if (compute >= 0) {
                request = request.withQueues(compute, 1, 0.5f);
            }
        }
        return new Rig(instance, new VulkanDevice(instance.handle(), selection.orElseThrow(), request), compute);
    }

    @Test
    void slotsAreBuffersApartAndTheTimelineStartsAtZero() {
        try (Rig rig = rigOrSkip(true);
             FeedBuffers slots = new FeedBuffers(rig.device, 256, 3, rig.device.queueFamilyIndex())) {
            assertEquals(3, slots.slots());
            assertEquals(256, slots.bytes());
            assertNotEquals(slots.buffer(0), slots.buffer(1));
            assertNotEquals(slots.buffer(1), slots.buffer(2));
            assertEquals(false, slots.shared(), "written by the family that draws: one family's alone");
            assertEquals(0L, slots.counter(), "nothing has signalled it yet");
            assertThrows(IndexOutOfBoundsException.class, () -> slots.buffer(3));
        }
    }

    @Test
    void aWriterOfAnotherFamilyShares() {
        try (Rig rig = rigOrSkip(true)) {
            assumeTrue(rig.computeFamily >= 0, "no compute-only queue family on this device");
            try (FeedBuffers slots = new FeedBuffers(rig.device, 64, 2, rig.computeFamily)) {
                assertEquals(true, slots.shared(), "concurrent between the family that draws and the writer's");
            }
        }
    }

    @Test
    void theCounterIsWhatWasSignalled() {
        try (Rig rig = rigOrSkip(true);
             FeedBuffers slots = new FeedBuffers(rig.device, 16, 1, rig.device.queueFamilyIndex());
             Arena a = Arena.ofConfined()) {
            MemorySegment info = a.allocate(SEMAPHORE_SIGNAL_INFO);
            si(info, SEMAPHORE_SIGNAL_INFO, "sType", 1000207005);
            sl(info, SEMAPHORE_SIGNAL_INFO, "semaphore", slots.timeline());
            sl(info, SEMAPHORE_SIGNAL_INFO, "value", 5L);
            check(invoke(rig.device.command("vkSignalSemaphore", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                    rig.device.handle(), info), "vkSignalSemaphore");
            assertEquals(5L, slots.counter());
        }
    }

    @Test
    void refusedOnADeviceWithoutTimelines() {
        try (Rig rig = rigOrSkip(false)) {
            assumeTrue(!rig.device.timelineSemaphore(), "the device has timelines whether asked or not");
            assertThrows(IllegalStateException.class,
                    () -> new FeedBuffers(rig.device, 16, 1, rig.device.queueFamilyIndex()));
        }
    }

    @Test
    void frameWaitsKeepTheHigherValueOfOneSemaphoreAndGrow() {
        FrameWaits w = new FrameWaits();
        w.add(7L, 3L);
        w.add(7L, 9L);
        w.add(7L, 4L);
        assertEquals(1, w.count(), "one semaphore is waited for once");
        assertEquals(9L, w.value(0), "at the higher value");
        for (long s = 100; s < 110; s++) {
            w.add(s, s);
        }
        assertEquals(11, w.count());
        try (Arena a = Arena.ofConfined()) {
            MemorySegment sems = a.allocate(JAVA_LONG, 12);
            MemorySegment stages = a.allocate(JAVA_INT, 12);
            MemorySegment values = a.allocate(JAVA_LONG, 12);
            w.fill(sems, stages, values, 1);
            assertEquals(7L, sems.getAtIndex(JAVA_LONG, 1));
            assertEquals(109L, values.getAtIndex(JAVA_LONG, 11));
            assertEquals(FrameWaits.STAGE, stages.getAtIndex(JAVA_INT, 5));
        }
        w.clear();
        assertEquals(0, w.count());
        assertThrows(IllegalArgumentException.class, () -> w.add(0L, 1L));
    }
}
