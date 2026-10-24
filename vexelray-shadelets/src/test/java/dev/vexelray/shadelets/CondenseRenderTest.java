package dev.vexelray.shadelets;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.LookTable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.vexelray.shadelets.OffscreenCanvas.channel;
import static dev.vexelray.shadelets.OffscreenCanvas.litPixels;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link Condense} on the GPU, against an art pair made here: a disc's field in the MTSDF layout and the disc in red
 * beside it. Before the front, nothing; settled, the colour layer exactly; the orb, lit in its middle and gone before
 * its quad's edge. Skipped without a Vulkan graphics device.
 */
class CondenseRenderTest {

    static final int W = 160;
    static final int H = 96;
    static final int INNER = 32;
    static final int SPREAD = 16;
    static final int FIELD = INNER + 2 * SPREAD;          // 64 texels
    static final int COLOUR_X = FIELD + 3;                // after a gutter and a texel of nothing
    static final int SIDE = 100;                          // square, so the field's V per unit holds on both axes
    static final float DISC = 12f;                        // the disc's radius, texels of the shape's square

    static OffscreenCanvas gpu;

    @BeforeAll
    static void open() {
        assumeTrue(OffscreenCanvas.available(), "no Vulkan graphics device: the effects' GPU tests need one");
        gpu = new OffscreenCanvas(EffectsRenderTest.WITH_EFFECTS, W, H);
    }

    @AfterAll
    static void close() {
        if (gpu != null) {
            gpu.close();
        }
    }

    static final Look CONDENSING = new Look(MtsdfStyleLayout.use("mtsdf", new MtsdfStyle(MtsdfStyle.BAKED)), List.of(),
            List.of(CondenseStyleLayout.use("condense", style(0f, 0f, 0f))));

    @Test
    void beforeTheFrontNothingIsDrawnAndSettledItIsTheColourLayer() {
        OffscreenCanvas.Handle handle = new OffscreenCanvas.Handle();
        OffscreenCanvas.Texture pair = new OffscreenCanvas.Texture(handle, SIDE, SIDE, pair());
        LookTable table = CoreLooks.table(EffectsRenderTest.WITH_EFFECTS);

        byte[] before = gpu.draw(table, mark(table, handle, style(-1e4f, 0f, 0f)), pair);
        byte[] settled = gpu.draw(table, mark(table, handle, style(1e4f, 1f, 2.8f)), pair);
        System.out.printf("[condense] lit before the front %d; settled centre %d,%d,%d edge-out %d%n",
                litPixels(before, W, H), channel(settled, W, 80, 48, 0), channel(settled, W, 80, 48, 1),
                channel(settled, W, 80, 48, 2), channel(settled, W, 80 + 30, 48, 0));

        assertEquals(0, litPixels(before, W, H), "before the front reaches it, the mark draws nothing");
        assertEquals(255, channel(settled, W, 80, 48, 0), "settled, the middle is the colour layer's red");
        assertEquals(0, channel(settled, W, 80, 48, 1));
        // The disc is 12 of 32 texels, drawn 64 px across: 24 px of radius. 30 px out is past it.
        assertEquals(0, channel(settled, W, 80 + 30, 48, 0), "and outside the disc, nothing");
    }

    @Test
    void theOrbIsLitInItsMiddleAndFadesBeforeItsQuadEnds() {
        OffscreenCanvas.Handle handle = new OffscreenCanvas.Handle();
        OffscreenCanvas.Texture pair = new OffscreenCanvas.Texture(handle, SIDE, SIDE, pair());
        LookTable table = CoreLooks.table(EffectsRenderTest.WITH_EFFECTS);
        Look orb = new Look(Look.Use.of("field"), List.of(),
                List.of(OrbStyleLayout.use("orb", new OrbStyle(Color.rgb(1f, 0.7f, 0.3f), 0f, 0f, 0f, 0f, 0f))));
        float[] instance = new float[OrbStyleLayout.LAYOUT.instanceFloats()];
        OrbStyleLayout.instance(new OrbStyle(Color.rgb(1f, 0.7f, 0.3f), 16f, 0f, 1f, 1f, 0f), instance, 0);
        float u = (float) FIELD / SIDE;
        Canvas c = new Canvas(W, H).looks(table).begin()
                .field(64, 32, 32, 32, handle, 0f, 0f, u, u, FIELD, SPREAD, (float) SPREAD / INNER, Color.WHITE, orb,
                        instance);
        byte[] rgba = gpu.draw(table, c, pair);
        int middle = channel(rgba, W, 80, 48, 0);
        int far = channel(rgba, W, 80 + 31, 48, 0);
        System.out.printf("[orb] red at the middle %d, at 31 px (the quad's edge) %d%n", middle, far);
        assertTrue(middle > 120, "the sphere is lit: " + middle);
        assertTrue(far < 8, "the halo is gone before the quad's edge, which never shows as a square: " + far);
    }

    /** The disc mark drawn 64 px across at the canvas's middle, by the condense look with {@code style}. */
    private static Canvas mark(LookTable table, OffscreenCanvas.Handle handle, CondenseStyle style) {
        float[] instance = new float[CondenseStyleLayout.LAYOUT.instanceFloats()];
        CondenseStyleLayout.instance(style, instance, 0);
        float u = (float) FIELD / SIDE;
        return new Canvas(W, H).looks(table).begin().field(48, 16, 64, 64, handle, 0f, 0f, u, u, FIELD, SPREAD,
                (float) SPREAD / INNER, Color.WHITE, CONDENSING, instance);
    }

    /** The front at {@code front}, settled by {@code settle}, at {@code time}; the light far off and dark. */
    private static CondenseStyle style(float front, float settle, float time) {
        float margin = (float) SPREAD / INNER;
        float fieldU = (float) FIELD / SIDE;
        float map = (1f + 2f * margin) / fieldU;
        return new CondenseStyle(Color.rgb(1f, 0.7f, 0.3f), 64f / 104f, 0f, 0f, front, 500f, 500f, 4f,
                0f, 0f, 0f, settle, time, map, map, -margin, -margin,
                (float) COLOUR_X / SIDE, 1f / SIDE, (float) (COLOUR_X + INNER) / SIDE, (1f + INNER) / SIDE);
    }

    /** A disc's field in the MTSDF layout at the top left, and the disc in red at {@link #COLOUR_X}. */
    static byte[] pair() {
        byte[] rgba = new byte[SIDE * SIDE * 4];
        float far = 2f * SPREAD;
        float near = far * MtsdfStyle.BAKED;
        for (int y = 0; y < FIELD; y++) {
            for (int x = 0; x < FIELD; x++) {
                float d = DISC - (float) Math.hypot(x + 0.5 - FIELD / 2.0, y + 0.5 - FIELD / 2.0);
                int o = (y * SIDE + x) * 4;
                byte n = unit(0.5f + d / near);
                rgba[o] = n;
                rgba[o + 1] = n;
                rgba[o + 2] = n;
                rgba[o + 3] = unit(0.5f + d / far);
            }
        }
        for (int y = 0; y < INNER; y++) {
            for (int x = 0; x < INNER; x++) {
                float d = DISC - (float) Math.hypot(x + 0.5 - INNER / 2.0, y + 0.5 - INNER / 2.0);
                float a = Math.max(0f, Math.min(1f, d + 0.5f));
                int o = ((1 + y) * SIDE + COLOUR_X + x) * 4;
                rgba[o] = unit(a);           // premultiplied red
                rgba[o + 3] = unit(a);
            }
        }
        return rgba;
    }

    private static byte unit(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v)) * 255f);
    }
}
