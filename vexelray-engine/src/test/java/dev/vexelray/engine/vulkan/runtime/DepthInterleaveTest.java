package dev.vexelray.engine.vulkan.runtime;

import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * <b>The claim the whole technique model rests on, measured for the first time: two techniques interleaving
 * per pixel in one render pass.</b>
 *
 * <h2>What was unproven, and why it stayed that way</h2>
 *
 * <p>"N techniques composited into one frame" is only worth more than rendering each to a texture and
 * compositing if they can occlude <em>each other</em>, pixel by pixel, inside one pass. Everything needed for
 * that has existed for a while — a shared depth attachment, a pass with two attachments, pipelines carrying
 * depth-stencil state — and none of it was ever exercised by anything that wrote a meaningful depth, because
 * {@code core} had no {@code FRAG_DEPTH} to write and no headless path could count the result. Both halves
 * landed; this is the check they were for.
 *
 * <h2>Why the ramp is the experiment</h2>
 *
 * <p>Two flat depths would not settle it. Whichever is nearer wins the whole frame, and a frame where one
 * technique wins everywhere is exactly what plain submission order produces — so the check would pass on an
 * engine with no depth buffer at all, as long as the techniques happened to be listed in the flattering
 * order.
 *
 * <p>So: a flat technique at 0.5, and a ramp from 0 to 1 across the screen, with <b>the ramp drawn second</b>.
 * Ordering alone predicts the ramp everywhere. Depth predicts the ramp on the left half, where it is nearer,
 * and the flat colour on the right half, where the technique that was drawn <em>first</em> survives. The
 * second outcome cannot be produced by ordering, which is what makes it evidence.
 *
 * <p>The boundary is arithmetic, not judgement. Pixel {@code x} samples {@code u = (x + 0.5) / width}, so the
 * ramp's depth crosses 0.5 between {@code x = 63} and {@code x = 64} of 128 — the two halves are exactly
 * 64 pixels each, and the nearest pixel to the boundary is still 0.004 of depth clear of it, which is four
 * orders of magnitude above anything float32 could blur.
 */
class DepthInterleaveTest {

    /** Even, so the ramp's crossing lands exactly between two pixels rather than on one. */
    private static final int SIZE = 128;

    private static final float[] RED = {1, 0, 0};
    private static final float[] BLUE = {0, 0, 1};

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aRampAndAFlatDepthEachWinHalfTheFrame() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        DepthRampTechnique flat = DepthRampTechnique.flat(RED[0], RED[1], RED[2], 0.5f);
        DepthRampTechnique ramp = new DepthRampTechnique(BLUE[0], BLUE[1], BLUE[2], 0.0f, 1.0f);

        byte[] rgba = capture(flat, ramp, true);

        int blue = 0;
        int red = 0;
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                float[] expected = x < SIZE / 2 ? BLUE : RED;
                assertPixel(rgba, x, y, expected);
                if (x < SIZE / 2) {
                    blue++;
                } else {
                    red++;
                }
            }
        }
        assertEquals(SIZE * SIZE / 2, blue);
        assertEquals(SIZE * SIZE / 2, red);
    }

    /**
     * The control, and the reason the test above means something.
     *
     * <p>The identical pipeline against a target that declares <b>no</b> depth. Nothing can be occluded, so
     * the technique drawn last wins every pixel — the ramp, everywhere, including the right half it loses
     * when depth is present. If this and the test above ever agreed, the depth attachment would be doing
     * nothing and the first test would be measuring submission order under a more elaborate name.
     */
    @Test
    void withoutDepthTheLastTechniqueWinsEverything() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        DepthRampTechnique flat = DepthRampTechnique.flat(RED[0], RED[1], RED[2], 0.5f);
        DepthRampTechnique ramp = new DepthRampTechnique(BLUE[0], BLUE[1], BLUE[2], 0.0f, 1.0f);

        byte[] rgba = capture(flat, ramp, false);

        assertPixel(rgba, 1, SIZE / 2, BLUE);
        assertPixel(rgba, SIZE - 2, SIZE / 2, BLUE);
    }

    /**
     * And the other direction: with the ramp drawn <em>first</em>, depth must produce the same picture.
     *
     * <p>The frame that settles what is actually being measured. If the split in the first test came from
     * anything order-shaped, reversing the list would move it; depth does not care which technique recorded
     * first, only which is nearer, so the two frames must be identical pixel for pixel.
     */
    @Test
    void reversingTheOrderChangesNothingWhenDepthDecides() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        byte[] flatFirst = capture(
                DepthRampTechnique.flat(RED[0], RED[1], RED[2], 0.5f),
                new DepthRampTechnique(BLUE[0], BLUE[1], BLUE[2], 0.0f, 1.0f), true);
        byte[] rampFirst = capture(
                new DepthRampTechnique(BLUE[0], BLUE[1], BLUE[2], 0.0f, 1.0f),
                DepthRampTechnique.flat(RED[0], RED[1], RED[2], 0.5f), true);

        for (int i = 0; i < flatFirst.length; i++) {
            int at = i;
            assertEquals(flatFirst[i], rampFirst[i],
                    () -> "byte " + at + " differs: depth decided one frame and order the other");
        }
    }

    private static byte[] capture(DepthRampTechnique first, DepthRampTechnique second, boolean depth) {
        Target.Builder target = Target.offscreen(SIZE, SIZE).color(AttachmentFormat.RGBA8_UNORM);
        if (depth) {
            target.depth(AttachmentFormat.DEPTH32F);
        }
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(target.build())
                .technique(first)
                .technique(second)
                .build();

        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("DepthInterleaveTest"))) {
            engine.run(pipeline, frame -> false);
            return engine.lastFrameRgba();
        }
    }

    private static void assertPixel(byte[] rgba, int x, int y, float[] expected) {
        int i = (y * SIZE + x) * 4;
        assertEquals(Math.round(expected[0] * 255), rgba[i] & 0xFF, () -> "red at " + x + "," + y);
        assertEquals(Math.round(expected[1] * 255), rgba[i + 1] & 0xFF, () -> "green at " + x + "," + y);
        assertEquals(Math.round(expected[2] * 255), rgba[i + 2] & 0xFF, () -> "blue at " + x + "," + y);
    }
}
