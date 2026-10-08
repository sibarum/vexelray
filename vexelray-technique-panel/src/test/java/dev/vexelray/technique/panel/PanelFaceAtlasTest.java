package dev.vexelray.technique.panel;

import dev.vexelray.canvas.Color;
import dev.vexelray.canvas.Canvas;
import dev.vexelray.engine.EngineProvider;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.text.AtlasPixels;
import dev.vexelray.text.FontSet;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.AtlasTexture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Text in two faces in one frame, measured in pixels: set 0 is rebound per run to the atlas of the face its glyphs
 * came from.
 *
 * <p>The measurement is an identity. An arrow drawn through sans — which has none, and borrows mono's — must be the
 * same pixels as the arrow drawn in mono directly. The control shows the identity is not vacuous: give face 1 the
 * wrong atlas and the borrowed arrow samples sans's texels at mono's UVs, and the picture changes.
 */
class PanelFaceAtlasTest {

    private static final int SIZE = 256;
    private static final String ARROW = "→";

    private static final FontSet SET = FontSet.standard().withFallback("mono");

    private static boolean hasRuntime() {
        for (EngineProvider provider : ServiceLoader.load(EngineProvider.class)) {
            if (provider.isAvailable()) {
                return true;
            }
        }
        return false;
    }

    @Test
    void aBorrowedGlyphDrawsFromTheAtlasItCameFrom() {
        assumeTrue(hasRuntime(), "no available EngineProvider — this needs a Vulkan device");

        List<AtlasPixels> pixels = new ArrayList<>();
        for (FontSet.Face f : SET.faces()) {
            pixels.add(SET.pixels(f));
        }
        Function<Integer, AtlasPixels> right = pixels::get;

        byte[] borrowed = capture(new TextLayout(SET.layout(SET.face("sans"))), right);
        byte[] direct = capture(new TextLayout(SET.layout(SET.face("mono"))), right);
        byte[] wrong = capture(new TextLayout(SET.layout(SET.face("sans"))), face -> pixels.get(0));

        assertTrue(inked(direct), "the arrow drew nothing at all, so the identity below would prove nothing");
        assertArrayEquals(direct, borrowed, "the borrowed arrow is not mono's arrow");
        assertFalse(java.util.Arrays.equals(direct, wrong),
                "with sans's atlas bound for face 1 the picture did not change, so this test cannot see a bind");
    }

    /** One frame of {@code ARROW} in {@code layout}, with face {@code i}'s atlas built from {@code atlas(i)}. */
    private static byte[] capture(TextLayout layout, Function<Integer, AtlasPixels> atlas) {
        Canvas canvas = new Canvas(SIZE, SIZE);
        PanelTechnique panel = new PanelTechnique(canvas, Panel.at(0, 0, 4, 0.01), 1.4, ClipDepth.DEFAULT);
        panel.camera(0, 0, 0, 0, 0);
        panel.atlases(device -> {
            List<AtlasTexture> out = new ArrayList<>();
            for (int i = 0; i < SET.faces().size(); i++) {
                AtlasPixels p = atlas.apply(i);
                out.add(new AtlasTexture(device, p.width(), p.height(), p.rgba()));
            }
            return out;
        });
        panel.draw(c -> c.text(layout, ARROW, 40, 40, TextLayout.TextStyle.of(120f), Color.WHITE));

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.offscreen(SIZE, SIZE).color(AttachmentFormat.RGBA8_UNORM).build())
                .technique(panel)
                .build();
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("PanelFaceAtlasTest"))) {
            engine.run(pipeline, frame -> false);
            return engine.lastFrameRgba();
        }
    }

    private static boolean inked(byte[] rgba) {
        for (int i = 0; i < rgba.length; i += 4) {
            if ((rgba[i] & 0xFF) > 128) {
                return true;
            }
        }
        return false;
    }
}
