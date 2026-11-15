package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.lang.foreign.MemorySegment;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link FeedSlots} on a real device: what a producer writes into a slot's staging is what the slot's picture holds once
 * a command buffer has landed it, its mips are made from it on the GPU, and slots are apart. Skipped where there is no
 * Vulkan. Run with {@code -Dvexelray.vulkan.validation} to have the layer check every copy, blit and barrier.
 */
class FeedSlotsTest {

    private record Rig(VulkanInstance instance, VulkanDevice device) implements AutoCloseable {
        @Override
        public void close() {
            device.close();
            instance.close();
        }
    }

    private static Rig rigOrSkip() {
        VulkanInstance instance;
        try {
            instance = new VulkanInstance("feed slots test", List.of());
        } catch (RuntimeException | Error e) {
            assumeTrue(false, "no Vulkan loader: " + e.getMessage());
            throw e;
        }
        Optional<VulkanInstance.DeviceSelection> selection = instance.selectGraphicsDevice();
        if (selection.isEmpty()) {
            instance.close();
            assumeTrue(false, "no device with a graphics queue");
        }
        return new Rig(instance, new VulkanDevice(instance.handle(), selection.orElseThrow(),
                new VulkanDevice.Request(false, null, 1)));
    }

    @Test
    void whatIsStagedIsWhatTheLandedSlotHolds() {
        try (Rig rig = rigOrSkip(); FeedSlots slots = new FeedSlots(rig.device, 5, 3, 2, false)) {
            assertEquals(1, slots.levels());
            assertEquals(5 * 3 * 4L, slots.stagingBytes());
            byte[] px = gradient(5, 3);
            MemorySegment.copy(MemorySegment.ofArray(px), 0, slots.staging(1), 0, px.length);
            FeedSlots.oneShot(rig.device, cmd -> slots.land(cmd, 1));
            assertArrayEquals(px, slots.read(1, 0), "an unfiltered picture lands texel for texel");
        }
    }

    @Test
    void aPictureLandsWithItsMipsMadeOnTheGpu() {
        try (Rig rig = rigOrSkip(); FeedSlots slots = new FeedSlots(rig.device, 8, 4, 3, true)) {
            assertEquals(4, slots.levels(), "8x4 has 8, 4, 2 and 1 on its long side");
            byte[] px = solid(8, 4, 200, 40, 90);
            MemorySegment.copy(MemorySegment.ofArray(px), 0, slots.staging(0), 0, px.length);
            FeedSlots.oneShot(rig.device, cmd -> slots.land(cmd, 0));
            assertArrayEquals(px, slots.read(0, 0), "level 0 is what was staged");
            for (int l = 1; l < slots.levels(); l++) {
                byte[] level = slots.read(0, l);
                assertEquals(MipChain.size(8, l) * MipChain.size(4, l) * 4, level.length);
                for (int i = 0; i < level.length; i += 4) {
                    // a flat colour averages to itself, give or take the sRGB round trip
                    assertNear(200, level[i], "red of level " + l);
                    assertNear(40, level[i + 1], "green of level " + l);
                    assertNear(90, level[i + 2], "blue of level " + l);
                    assertNear(255, level[i + 3], "alpha of level " + l);
                }
            }
        }
    }

    @Test
    void slotsAreApartAndLandingOneLeavesTheOthers() {
        try (Rig rig = rigOrSkip(); FeedSlots slots = new FeedSlots(rig.device, 4, 4, 3, false)) {
            byte[] red = solid(4, 4, 255, 0, 0);
            byte[] green = solid(4, 4, 0, 255, 0);
            MemorySegment.copy(MemorySegment.ofArray(red), 0, slots.staging(0), 0, red.length);
            MemorySegment.copy(MemorySegment.ofArray(green), 0, slots.staging(2), 0, green.length);
            FeedSlots.oneShot(rig.device, cmd -> {
                slots.land(cmd, 0);
                slots.land(cmd, 2);
            });
            assertArrayEquals(red, slots.read(0, 0));
            assertArrayEquals(green, slots.read(2, 0));
            assertNotEquals(slots.image(0).descriptorSet(), slots.image(2).descriptorSet(),
                    "each slot is its own descriptor set");

            // written again and landed again: a slot is a frame of a video, not a still
            MemorySegment.copy(MemorySegment.ofArray(green), 0, slots.staging(0), 0, green.length);
            FeedSlots.oneShot(rig.device, cmd -> slots.land(cmd, 0));
            assertArrayEquals(green, slots.read(0, 0));
        }
    }

    @Test
    void refusesWhatIsNotThereAndClosesOnce() {
        try (Rig rig = rigOrSkip()) {
            FeedSlots slots = new FeedSlots(rig.device, 2, 2, 1, true);
            assertThrows(IndexOutOfBoundsException.class, () -> slots.staging(1));
            assertThrows(IllegalArgumentException.class, () -> new FeedSlots(rig.device, 0, 2, 1, false));
            assertThrows(IllegalArgumentException.class, () -> new FeedSlots(rig.device, 2, 2, 0, false));
            assertDoesNotThrow(slots::close);
            assertDoesNotThrow(slots::close);
        }
    }

    private static void assertNear(int want, byte got, String what) {
        int g = got & 0xFF;
        if (Math.abs(g - want) > 2) {
            throw new AssertionError(what + ": wanted about " + want + ", was " + g);
        }
    }

    private static byte[] solid(int w, int h, int r, int g, int b) {
        byte[] px = new byte[w * h * 4];
        for (int i = 0; i < px.length; i += 4) {
            px[i] = (byte) r;
            px[i + 1] = (byte) g;
            px[i + 2] = (byte) b;
            px[i + 3] = (byte) 255;
        }
        return px;
    }

    private static byte[] gradient(int w, int h) {
        byte[] px = new byte[w * h * 4];
        for (int i = 0; i < px.length; i++) {
            px[i] = (byte) (i * 7 + 3);
        }
        return px;
    }
}
