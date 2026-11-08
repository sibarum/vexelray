package dev.vexelray.shadelets;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.DistanceField;
import dev.vexelray.canvas.SdgDrawing;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Look;
import dev.vexelray.canvas.shadelet.LookTable;
import dev.vexelray.fields.sdg.Sdg;
import dev.vexelray.target.ImageHandle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * An SDG layer with a look draws what a field with the same look draws: one SDF, the icon set's layout (64 texels and
 * 16 of margin), drawn by {@link Canvas#field} and by {@link Canvas#sdg} as a one-layer drawing over the same texels,
 * plain and with each effect an icon is given (glow, shadow, weight, outline, and all four), and the two compared. What
 * slice 3 of vexelray-framework's {@code docs/plans/sdg-native.md} needs before the icons move onto SDG.
 */
class SdgEffectsRenderTest {

    static final int W = 240, H = 160;
    static final int INNER = 64, SPREAD = 16, SIDE = INNER + 2 * SPREAD;
    /** The box the shape's square is drawn across: 96 px for 64 texels, as a large toolbar icon at 150%. */
    static final float X = 72, Y = 32, BOX = 96;
    /** Measured 2026-11-07: worst 1 for every look, so no pixel past 2. */
    static final int TOLERANCE = 2;

    static OffscreenCanvas gpu;

    @BeforeAll
    static void open() {
        assumeTrue(OffscreenCanvas.available(), "no Vulkan graphics device: the GPU tests need one");
        gpu = new OffscreenCanvas(EffectsRenderTest.WITH_EFFECTS, W, H);
    }

    @AfterAll
    static void close() {
        if (gpu != null) {
            gpu.close();
        }
    }

    @Test
    void plain() {
        compare("plain", List.of(), List.of(), 0f, 0f);
    }

    @Test
    void glow() {
        compare("glow", List.of(), List.of(), 10f, 0f);
    }

    @Test
    void shadow() {
        compare("shadow", List.of(), List.of(), 0f, 6f);
    }

    @Test
    void weight() {
        compare("weight", List.of(WeightStyleLayout.use("weight", new WeightStyle(3f))), List.of(), 0f, 0f);
    }

    @Test
    void outline() {
        compare("outline", List.of(),
                List.of(OutlineStyleLayout.use("outline", new OutlineStyle(Color.rgb(1f, 0.2f, 0.1f), 4f))), 0f, 0f);
    }

    @Test
    void everything() {
        compare("all four", List.of(WeightStyleLayout.use("weight", new WeightStyle(2f))),
                List.of(OutlineStyleLayout.use("outline", new OutlineStyle(Color.rgb(1f, 0.2f, 0.1f), 3f))), 8f, 5f);
    }

    private static void compare(String what, List<Look.Use> modifiers, List<Look.Use> front, float glow,
                                float shadow) {
        LookTable table = CoreLooks.table(EffectsRenderTest.WITH_EFFECTS);
        DistanceField field = DistanceField.of(mask(), INNER, SPREAD);
        OffscreenCanvas.Handle handle = new OffscreenCanvas.Handle();
        OffscreenCanvas.Texture texture = new OffscreenCanvas.Texture(handle, SIDE, SIDE, field.rgba());
        Color tint = Color.rgb(0.35f, 0.8f, 1f);

        Canvas asField = new Canvas(W, H).begin().looks(table);
        asField.field(X, Y, BOX, BOX, handle, 0f, 0f, 1f, 1f, SIDE, SPREAD, field.margin(), tint,
                look(CoreShadelets.FIELD.name(), CoreShadelets.FIELD_FILL.name(), modifiers, front, glow, shadow));
        byte[] a = gpu.draw(table, asField, texture);

        Sdg.Stencil stencil = new Sdg.Stencil(Sdg.Stencil.SDF, 0, 0, 0, 0, SIDE, SIDE, -SPREAD, -SPREAD, 1, 2 * SPREAD);
        int rgba = Math.round(tint.r() * 255) << 24 | Math.round(tint.g() * 255) << 16
                | Math.round(tint.b() * 255) << 8 | 0xFF;
        SdgDrawing drawing = new SdgDrawing(INNER, INNER,
                List.of(new Sdg.Layer("shape", stencil, new Sdg.Paint.Solid(rgba), 1f)),
                new int[]{SIDE}, new int[]{SIDE});
        Canvas asSdg = new Canvas(W, H).begin().looks(table);
        asSdg.sdg(drawing, new ImageHandle[]{handle}, X, Y, BOX, BOX, Canvas.Roles.NONE, 1f,
                look(CoreShadelets.SDG_STENCIL.name(), CoreShadelets.SDG_PAINT.name(), modifiers, front, glow, shadow));
        byte[] b = gpu.draw(table, asSdg, texture);

        int worst = OffscreenCanvas.maxDifference(a, b, W, 0, 0, W, H);
        int different = OffscreenCanvas.countDifferent(a, b, W, H, TOLERANCE);
        int lit = OffscreenCanvas.litPixels(a, W, H);
        System.out.printf("[sdg effects] %-9s %d lit; %d differ by more than %d; worst %d%n",
                what, lit, different, TOLERANCE, worst);
        assertTrue(lit > 3000, what + ": the field drew something");
        assertTrue(different == 0, what + ": " + different + " pixels differ (worst " + worst + ")");
    }

    /** The look an icon's effects make ({@code ImageField.effects}), over {@code source} and filled by {@code fill}. */
    private static Look look(String source, String fill, List<Look.Use> modifiers, List<Look.Use> front, float glow,
                             float shadow) {
        List<Look.Use> painters = new ArrayList<>(front);
        painters.add(Look.Use.of(fill));
        if (glow > 0f) {
            painters.add(Look.Use.of(CoreShadelets.GLOW.name(), glow));
        }
        if (shadow > 0f) {
            painters.add(Look.Use.of(CoreShadelets.DROP_SHADOW.name(), shadow));
        }
        return new Look(Look.Use.of(source), modifiers, painters);
    }

    /** A ring with a bar through it, corners and curves both, oversampled as {@link DistanceField#of} takes it. */
    private static byte[] mask() {
        int n = DistanceField.maskSide(INNER);
        byte[] m = new byte[n * n * 4];
        double c = n / 2.0;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                double r = Math.hypot(x + 0.5 - c, y + 0.5 - c) / n;
                boolean ring = r > 0.28 && r < 0.46;
                boolean bar = Math.abs(y + 0.5 - c) < n * 0.07 && x > n * 0.1 && x < n * 0.9;
                m[(y * n + x) * 4 + 3] = (byte) (ring || bar ? 255 : 0);
            }
        }
        return m;
    }
}
