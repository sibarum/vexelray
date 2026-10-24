package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link StreamTexture} on a real device: the life of a slot, that the slot a node names is never handed out to be
 * written, and that a long run of frames goes through a ring of three without a wait the test can see. Skipped where
 * there is no Vulkan. Run with {@code -Dvexelray.vulkan.validation} to have the layer check every copy and barrier.
 */
class StreamTextureTest {

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
            instance = new VulkanInstance("stream texture test", List.of());
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
    void aSlotIsTakenWrittenSubmittedAndShownAndTheOneBeforeIsFreed() {
        try (Rig rig = rigOrSkip(); StreamTexture stream = new StreamTexture(rig.device, 8, 4, 3, true)) {
            assertEquals(4, stream.levels(), "8x4 has 8, 4, 2 and 1 on its long side");
            assertEquals((8 * 4 + 4 * 2 + 2 * 1 + 1 * 1) * 4L, stream.frameBytes());

            StreamTexture.Slot a = stream.take();
            a.write(MipChain.of(solid(8, 4, 255, 0, 0), 8, 4));
            stream.submit(a);
            stream.show(a);
            assertSame(a, stream.shown());

            StreamTexture.Slot b = stream.take();
            StreamTexture.Slot c = stream.take();
            assertNotNull(b);
            assertNotNull(c);
            assertNull(stream.take(), "one shown and two taken: nothing is free, and the shown one is never offered");

            b.write(MipChain.of(solid(8, 4, 0, 255, 0), 8, 4));
            stream.submit(b);
            stream.show(b);
            assertSame(a, stream.take(), "showing b freed a");
            stream.drop(c);
            assertNotEquals(0L, b.descriptorSet());
            assertNotEquals(a.descriptorSet(), b.descriptorSet(), "each slot is its own descriptor set");
        }
    }

    @Test
    void theLiveImageIsOneObjectThatDrawsTheSlotShownAndEveryShowIsTold() {
        try (Rig rig = rigOrSkip(); StreamTexture stream = new StreamTexture(rig.device, 8, 4, 3, true)) {
            SampledImage live = stream.live();
            int[] told = {0};
            stream.onShown(() -> told[0]++);
            assertThrows(IllegalStateException.class, live::descriptorSet, "nothing shown yet: nothing to draw");

            StreamTexture.Slot a = stream.take();
            a.write(MipChain.of(solid(8, 4, 255, 0, 0), 8, 4));
            stream.submit(a);
            stream.show(a);
            assertEquals(a.descriptorSet(), live.descriptorSet());

            StreamTexture.Slot b = stream.take();
            b.write(MipChain.of(solid(8, 4, 0, 255, 0), 8, 4));
            stream.submit(b);
            stream.show(b);
            assertEquals(b.descriptorSet(), live.descriptorSet(), "a new frame moves what it draws, not the handle");
            assertSame(live, stream.live(), "one handle for the stream's life");
            assertEquals(2, told[0], "each show asks for a frame");
            assertEquals(stream.descriptorSetLayout(), live.descriptorSetLayout());
            assertEquals(stream.picture(), live.linear());
        }
    }

    @Test
    void aLongRunGoesRoundTheRing() {
        try (Rig rig = rigOrSkip(); StreamTexture stream = new StreamTexture(rig.device, 64, 64, 3, true)) {
            for (int i = 0; i < 300; i++) {
                StreamTexture.Slot s = stream.take();
                assertNotNull(s, "frame " + i + " found a free slot");
                s.write(MipChain.of(solid(64, 64, i, 255 - i, 0), 64, 64));
                stream.submit(s);
                stream.show(s);
            }
        }
    }

    @Test
    void aPlainStreamHasOneLevelAndRefusesMisuse() {
        try (Rig rig = rigOrSkip(); StreamTexture stream = new StreamTexture(rig.device, 5, 3, 2, false)) {
            assertEquals(1, stream.levels());
            StreamTexture.Slot s = stream.take();
            assertThrows(IllegalArgumentException.class, () -> s.write(new byte[][] {new byte[4]}),
                    "a frame of the wrong size");
            assertThrows(IllegalStateException.class, () -> stream.show(s), "shown before it was submitted");
            s.write(new byte[][] {solid(5, 3, 1, 2, 3)});
            stream.submit(s);
            assertThrows(IllegalStateException.class, () -> stream.submit(s), "submitted twice");
            stream.show(s);
        }
    }

    @Test
    void closingWaitsForItsOwnCopiesAndClosingTwiceDoesNothing() {
        try (Rig rig = rigOrSkip()) {
            StreamTexture stream = new StreamTexture(rig.device, 256, 256, 3, true);
            for (int i = 0; i < 3; i++) {
                StreamTexture.Slot s = stream.take();
                s.write(MipChain.of(solid(256, 256, 9, 9, 9), 256, 256));
                stream.submit(s);
            }
            assertDoesNotThrow(stream::close);
            assertDoesNotThrow(stream::close);
            assertNull(stream.take(), "a closed stream has no slots to give");
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
}
