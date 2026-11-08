package dev.vexelray.shadelets;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.SdgPages;
import dev.vexelray.canvas.shadelet.CoreLooks;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.LookTable;
import dev.vexelray.fields.art.Raster;
import dev.vexelray.fields.art.Svg;
import dev.vexelray.fields.sdg.Sdg;
import dev.vexelray.fields.sdg.SdgRender;
import dev.vexelray.fields.sdg.SvgToSdg;
import dev.vexelray.fields.sdg.Trace;
import dev.vexelray.target.ImageHandle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * SDG drawn natively, against its reference: drawings made by vexelray-fields' encoders, drawn by {@link Canvas#sdg}
 * with the core shader and read back, and compared pixel by pixel with {@link SdgRender}, the CPU drawing the format
 * says a reader must match. Both over opaque black, fitted and magnified. Skipped without a Vulkan graphics device.
 */
class SdgRenderTest {

    /** A small landscape badge, vexelray-fields' own fixture: a gradient, flat fills, a star, a stroke. */
    static final String BADGE = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 100">
              <defs>
                <linearGradient id="sky" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0" stop-color="#2b5fd9"/><stop offset="1" stop-color="#9fd3ff"/>
                </linearGradient>
              </defs>
              <rect x="4" y="4" width="92" height="92" rx="14" fill="url(#sky)"/>
              <circle cx="68" cy="34" r="16" fill="#ffcc33"/>
              <path d="M4 74 L30 48 L50 66 L66 54 L96 80 L96 82 Q96 96 82 96 L18 96 Q4 96 4 82 Z" fill="#2f8f4e"/>
              <path d="M10 88 C30 70 60 98 90 80" fill="none" stroke="#173d22" stroke-width="3"
                    stroke-linecap="round"/>
              <polygon points="22,30 26,40 37,40 28,46 31,57 22,50 13,57 16,46 7,40 18,40" fill="#ffffff"/>
            </svg>
            """;

    static final int W = 192, H = 192;
    /**
     * Per channel, of 255. The GPU's linear filter weighs texels in fixed point (Vulkan's sub-texel precision, 8 bits
     * on the devices this has run on), the reference in doubles; magnified eight times, an MSDF's median of those
     * weighed channels moves the edge by a few levels. Measured 2026-11-07: fitted, worst 2; magnified, worst 5, with
     * 47 of 36,864 pixels past 3. So at most 1% of the lit pixels past {@link #TOLERANCE}, and none past {@link #WORST}.
     */
    static final int TOLERANCE = 3, WORST = 8;

    static OffscreenCanvas gpu;
    static LookTable table;

    @BeforeAll
    static void open() {
        assumeTrue(OffscreenCanvas.available(), "no Vulkan graphics device: SDG's GPU witness needs one");
        gpu = new OffscreenCanvas(CoreShadelets.composition(), W, H);
        table = CoreLooks.table(CoreShadelets.composition());
    }

    @AfterAll
    static void close() {
        if (gpu != null) {
            gpu.close();
        }
    }

    static Sdg badge() {
        Svg svg = Svg.parse(BADGE);
        return SvgToSdg.convert(svg, SvgToSdg.Options.across(svg, 64));
    }

    static Sdg traced() {
        return Trace.trace(Svg.parse(BADGE).render(64, 64, 4), Trace.Options.DEFAULT);
    }

    @Test
    void anSvgDrawsAsItsReference() {
        compare("svg, fitted", badge(), 1.0 / Math.min(W / 100.0, H / 100.0), 0, 0);
    }

    @Test
    void anSvgMagnifiedDrawsAsItsReference() {
        // a quarter of the badge across the whole target: the star, eight times as large as at 64 texels
        compare("svg, magnified", badge(), 25.0 / W, 8, 26);
    }

    @Test
    void aTracedRasterDrawsAsItsReference() {
        compare("traced, fitted", traced(), 1.0 / Math.min(W / 100.0, H / 100.0), 0, 0);
    }

    @Test
    void aTracedRasterMagnifiedDrawsAsItsReference() {
        compare("traced, magnified", traced(), 25.0 / W, 8, 26);
    }

    /** A paint naming a role takes the theme's colour; with no theme, the colour it was authored in. */
    @Test
    void aRoleTakesTheThemesColour() {
        Sdg badge = badge();
        List<Sdg.Layer> layers = new ArrayList<>();
        for (Sdg.Layer l : badge.layers()) {
            layers.add(l.paint() instanceof Sdg.Paint.Solid s && s.rgba() == 0xFFFFFFFF
                    ? new Sdg.Layer(l.name(), l.stencil(), s.as("foreground"), l.opacity()) : l);
        }
        Sdg themed = new Sdg(badge.width(), badge.height(), badge.pages(), layers);
        double upp = 1.0 / Math.min(W / 100.0, H / 100.0);
        int sx = (int) (22 / upp), sy = (int) (46 / upp);   // inside the star
        byte[] none = draw(themed, upp, 0, 0, Canvas.Roles.NONE);
        byte[] red = draw(themed, upp, 0, 0, (role, stored) -> role.equals("foreground") ? 0xFF0000FF : stored);
        assertEquals(255, OffscreenCanvas.channel(none, W, sx, sy, 1), "no theme: the star as authored, white");
        assertEquals(255, OffscreenCanvas.channel(red, W, sx, sy, 0), "the theme's foreground: red");
        assertEquals(0, OffscreenCanvas.channel(red, W, sx, sy, 1));
    }

    // --- drawing both ways ---

    private static void compare(String what, Sdg sdg, double upp, double originX, double originY) {
        byte[] gpuPixels = draw(sdg, upp, originX, originY, Canvas.Roles.NONE);
        byte[] cpuPixels = overBlack(SdgRender.render(sdg, W, H, originX, originY, upp));
        int worst = 0, different = 0, lit = OffscreenCanvas.litPixels(cpuPixels, W, H);
        for (int p = 0; p < W * H; p++) {
            int d = 0;
            for (int c = 0; c < 3; c++) {
                d = Math.max(d, Math.abs((gpuPixels[p * 4 + c] & 0xFF) - (cpuPixels[p * 4 + c] & 0xFF)));
            }
            worst = Math.max(worst, d);
            if (d > TOLERANCE) {
                different++;
            }
        }
        System.out.printf("[sdg] %-18s %d layers on %d pages: %d of %d lit pixels differ by more than %d; worst %d%n",
                what, sdg.layers().size(), SdgPages.colocated(sdg).pages().size(), different, lit, TOLERANCE, worst);
        assertTrue(lit > W * H / 4, what + ": the reference drew something");
        assertTrue(different <= lit / 100, what + ": " + different + " pixels differ from the reference by more than "
                + TOLERANCE + " (worst " + worst + ")");
        assertTrue(worst <= WORST, what + ": a pixel differs from the reference by " + worst);
    }

    /** {@code sdg} drawn by the canvas with pixel {@code (i, j)} at drawing point {@code origin + (i + .5, j + .5) · upp}. */
    private static byte[] draw(Sdg sdg, double upp, double originX, double originY, Canvas.Roles roles) {
        Sdg drawing = SdgPages.colocated(sdg);
        ImageHandle[] handles = new ImageHandle[drawing.pages().size()];
        List<OffscreenCanvas.Texture> textures = new ArrayList<>();
        for (int i = 0; i < handles.length; i++) {
            handles[i] = new OffscreenCanvas.Handle();
            Sdg.Page page = drawing.pages().get(i);
            textures.add(new OffscreenCanvas.Texture(handles[i], page.width(), page.height(), page.rgba()));
        }
        float scale = (float) (1.0 / upp);
        Canvas canvas = new Canvas(W, H).begin();
        canvas.sdg(drawing, handles, (float) (-originX * scale), (float) (-originY * scale), scale, roles, 1f);
        return gpu.draw(table, canvas, textures);
    }

    /** Straight RGBA laid over opaque black, as the GPU target is cleared to. */
    private static byte[] overBlack(Raster r) {
        byte[] out = new byte[r.rgba().length];
        for (int p = 0; p < W * H; p++) {
            float a = (r.rgba()[p * 4 + 3] & 0xFF) / 255f;
            for (int c = 0; c < 3; c++) {
                out[p * 4 + c] = (byte) Math.round((r.rgba()[p * 4 + c] & 0xFF) * a);
            }
            out[p * 4 + 3] = (byte) 255;
        }
        return out;
    }
}
