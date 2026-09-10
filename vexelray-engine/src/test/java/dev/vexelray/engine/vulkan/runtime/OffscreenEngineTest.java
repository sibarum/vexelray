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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The engine driving a {@link Target.Kind#OFFSCREEN} target, and — for the first time in this repository —
 * <b>assertions about the pixels a frame contains</b>.
 *
 * <h2>What was missing</h2>
 *
 * <p>Every other engine-level check here ({@link TwoTechniqueTest}, {@code SdfEngineTest},
 * {@code HybridFrameTest}, {@link EngineEventsTest}) needs a window, and the windowed path does no readback.
 * So they can assert that frames happened and that the technique lifecycle was obeyed, and they cannot assert
 * that anything was <em>drawn</em>: a pipeline whose fragment shader wrote nothing at all would pass all of
 * them. "Two techniques composite in list order" has been a claim about the code rather than a measurement of
 * its output.
 *
 * <p>These run headless, and the last one measures. That is the whole reason
 * {@link dev.vexelray.vulkan.present.OffscreenPresenter} exists.
 *
 * <h2>Why a solid colour</h2>
 *
 * <p>{@link SolidTechnique} fills every pixel with one pushed RGBA, so every assertion below is an equality
 * on an exact byte rather than a tolerance on a gradient. A test that has to say "roughly this colour" cannot
 * tell a wrong colour from a slightly-wrong one, and the failures worth catching here — a swapped channel
 * order in the readback, a frame that never drew, a technique recorded in the wrong order — are all exact.
 */
class OffscreenEngineTest {

    /** Small: nothing here scales with area, and a 64×64 capture is 16KB rather than a megabyte. */
    private static final int SIZE = 64;

    /** Enough frames that the loop is plainly iterating and the presenter is plainly reusing its objects. */
    private static final int FRAMES = 3;

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    /**
     * An offscreen run with no frame callback is refused, and refused <em>before</em> a device is touched —
     * which is why this one asserts without a GPU. It is the failure mode that matters most about the
     * headless path: a windowed run ends when its window closes, an offscreen run has nothing of the sort, so
     * accepting a null callback would not be a bug that fails, it would be a bug that hangs. A hung build is
     * far more expensive to diagnose than a thrown exception, and CI reports it as a timeout with no stack.
     */
    @Test
    void anOffscreenRunWithoutACallbackIsRefusedRatherThanHung() {
        assumeTrue(hasRuntime(), "no available EngineProvider");

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.offscreen(SIZE, SIZE).color(AttachmentFormat.RGBA8_UNORM))
                .technique(new SolidTechnique(1, 0, 0, 1))
                .build();

        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("OffscreenEngineTest"))) {
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> engine.run(pipeline, null, null));
            assertTrue(thrown.getMessage().contains("frame callback"),
                    "the message must name what is missing, not merely that something is: " + thrown.getMessage());
        }
    }

    /** Reading a capture that was never taken is a fault, not an empty array — the two mean different things. */
    @Test
    void readingACaptureBeforeAnyRunIsRefused() {
        assumeTrue(hasRuntime(), "no available EngineProvider");

        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("OffscreenEngineTest"))) {
            assertThrows(IllegalStateException.class, engine::lastFrameRgba);
        }
    }

    /**
     * The headline: a technique that fills the frame with a known colour, run headlessly, produces exactly
     * that colour in every pixel of the capture.
     *
     * <p>Four separate things fail here and each fails distinctly. If the run never happened there is no
     * capture. If the capture is the wrong length the extent or the stride is wrong. If the channels are
     * permuted the first pixel says so. And if the fragment shader drew nothing, every pixel is the clear
     * value — which is precisely the failure no windowed test in this repository can detect.
     */
    @Test
    void aTechniqueFillsTheCaptureWithTheColourItPushed() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        SolidTechnique red = new SolidTechnique(1.0f, 0.0f, 0.0f, 1.0f);
        byte[] rgba = capture(red, null, false);

        assertEquals(SIZE * SIZE * 4, rgba.length,
                "a capture is tightly-packed RGBA at the target's extent, with no padding between rows");

        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                assertPixel(rgba, x, y, 255, 0, 0, 255);
            }
        }

        assertEquals(1, red.realizeCount(), "realise must happen exactly once per run");
        assertEquals(FRAMES, red.recordCount(),
                "an offscreen frame cannot come back out of date and be skipped the way a windowed one can, "
                        + "so unlike the windowed tests this is an equality: every frame the loop counted is a "
                        + "frame the technique recorded");
        assertEquals(1, red.closeCount(), "close must happen exactly once: zero is a GPU-object leak");
    }

    /**
     * Order is the composition — measured rather than asserted about.
     *
     * <p>Two techniques that each cover every pixel opaquely, so the picture is entirely decided by which one
     * recorded last. Run twice with the list reversed: if list order did not decide the frame, one of the two
     * captures is the wrong colour, and if the second technique were silently never recorded, both captures
     * would be the first one's colour.
     *
     * <p>No depth on this target, deliberately. With a depth attachment both techniques would test and write
     * it at the same z, and what the second draw does then is a question about the compare op rather than
     * about ordering — a different claim, worth its own test, and not one to smuggle into this one.
     */
    @Test
    void listOrderDecidesTheFrame() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        byte[] blueLast = capture(new SolidTechnique(1, 0, 0, 1), new SolidTechnique(0, 0, 1, 1), false);
        assertPixel(blueLast, SIZE / 2, SIZE / 2, 0, 0, 255, 255);

        byte[] redLast = capture(new SolidTechnique(0, 0, 1, 1), new SolidTechnique(1, 0, 0, 1), false);
        assertPixel(redLast, SIZE / 2, SIZE / 2, 255, 0, 0, 255);
    }

    /**
     * The same capture with the target declaring depth — a pass with two attachments, a framebuffer with two
     * views, a depth image sized to the offscreen extent, and pipelines carrying depth-stencil state.
     *
     * <p>One technique rather than two: this is asking whether the depth <em>plumbing</em> stands up offscreen,
     * which is the part {@link dev.vexelray.vulkan.present.OffscreenPresenter} newly owns. What two techniques
     * at differing depths do to each other is the question {@code gl_FragDepth} has to arrive before anyone
     * can honestly ask.
     */
    @Test
    void aDepthDeclaringTargetCapturesTheSamePicture() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        byte[] rgba = capture(new SolidTechnique(0.0f, 1.0f, 0.0f, 1.0f), null, true);
        assertPixel(rgba, 0, 0, 0, 255, 0, 255);
        assertPixel(rgba, SIZE - 1, SIZE - 1, 0, 255, 0, 255);
    }

    /** Run {@code first} (and {@code second}, when given) for {@link #FRAMES} frames and return the last one. */
    private static byte[] capture(SolidTechnique first, SolidTechnique second, boolean depth) {
        Target.Builder target = Target.offscreen(SIZE, SIZE).color(AttachmentFormat.RGBA8_UNORM);
        if (depth) {
            target.depth(AttachmentFormat.DEPTH32F);
        }
        RenderPipeline.Builder pipeline = RenderPipeline.builder().target(target.build()).technique(first);
        if (second != null) {
            pipeline.technique(second);
        }

        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("OffscreenEngineTest"))) {
            engine.run(pipeline.build(), frame -> frame.frameIndex() < FRAMES - 1);
            return engine.lastFrameRgba();
        }
    }

    private static void assertPixel(byte[] rgba, int x, int y, int r, int g, int b, int a) {
        int i = (y * SIZE + x) * 4;
        assertEquals(r, rgba[i] & 0xFF, () -> "red at " + x + "," + y);
        assertEquals(g, rgba[i + 1] & 0xFF, () -> "green at " + x + "," + y);
        assertEquals(b, rgba[i + 2] & 0xFF, () -> "blue at " + x + "," + y);
        assertEquals(a, rgba[i + 3] & 0xFF, () -> "alpha at " + x + "," + y);
    }
}
