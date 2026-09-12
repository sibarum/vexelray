package dev.vexelray.technique.panel;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.Color;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.technique.sdf.SdfRaymarchTechnique;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.AtlasTexture;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;

/**
 * Manual demo (not a unit test): a canvas hanging in a marched scene, drawn as vector art rather than as a
 * texture, sharing the frame's depth buffer.
 *
 * <p>The camera orbits and closes in, which is the whole demonstration in one motion: the panel's lines and text
 * grow as they are approached because they are measured on a surface, and stay sharp while they grow because
 * their edges are analytic and the shader computes its anti-aliasing from the projection. A sphere crosses in
 * front of the panel each lap, and the panel is occluded at the sphere's silhouette rather than at a plane.
 *
 * <p>The panel's background is translucent, so the scene shows through it — the alpha channel composing against
 * a marched frame rather than against a cleared one.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED}. No arguments opens a window; {@code --capture <png>
 * [seconds]} renders one offscreen frame at that point in the orbit and writes it to a file.
 */
public final class PanelDemo {

    private static final String ATLAS_JSON = "/dev/vexelray/text/atlas/primary.json";
    private static final String ATLAS_PNG = "/dev/vexelray/text/atlas/primary.png";

    private static final int CANVAS_W = 900;
    private static final int CANVAS_H = 520;

    /** 3.15 by 1.82 world units — a sign you could stand in front of. */
    private static final double UNITS_PER_PIXEL = 0.0035;

    /** Height of the panel's centre, and what the camera looks at. */
    private static final double PANEL_Y = 1.7;

    private static final Color INK = Color.rgb(0xF2F5FF);
    private static final Color DIM = Color.rgb(0x93A0C0);
    private static final Color PANEL_BG = new Color(0.06f, 0.08f, 0.13f, 0.82f);
    private static final Color ACCENT = Color.rgb(0xFFB347);
    private static final Color GOOD = Color.rgb(0x6FE39B);

    public static void main(String[] args) throws IOException {
        if (args.length >= 1 && args[0].equals("--capture")) {
            String path = args.length >= 2 ? args[1] : "panel.png";
            double at = args.length >= 3 ? Double.parseDouble(args[2]) : 2.4;
            capture(path, at);
        } else {
            run(args.length > 0 ? Integer.parseInt(args[0]) : 600);
        }
    }

    /** The scene the panel stands in: a ground plane, and spheres one of which crosses in front of it. */
    private static SdfRaymarchTechnique march() {
        Surface scene = Surface.union(
                Surface.Plane.ground(),
                // Behind the panel, so the translucent background has something to show through it.
                new Surface.Sphere(1.9, 0.9, 1.1, 0.9),
                new Surface.Sphere(-2.4, 0.7, -0.6, 0.7),
                // In front of it, at panel height, on the side the orbit passes: this is the one that proves
                // the depth buffer is shared, by cutting a silhouette out of the drawing rather than a plane.
                new Surface.Sphere(-0.9, 1.9, -0.9, 0.45));
        return new SdfRaymarchTechnique(SdfScene.of(scene).withAlbedo(new Surface.Rgb(0.72, 0.75, 0.83)));
    }

    private static PanelTechnique panel(SdfScene scene) {
        Canvas canvas = new Canvas(CANVAS_W, CANVAS_H);
        PanelTechnique panel = new PanelTechnique(canvas,
                Panel.at(0, PANEL_Y, 0, UNITS_PER_PIXEL), scene.focalLength(), scene.clipDepth());
        // The atlas cannot exist before the device does, so it is built at realise from the device the runtime
        // hands over -- see PanelTechnique.atlas(AtlasSource).
        panel.atlas(device -> {
            try {
                int[] size = new int[2];
                byte[] rgba = atlasRgba(size);
                return new AtlasTexture(device, size[0], size[1], rgba);
            } catch (IOException e) {
                throw new IllegalStateException("could not load the glyph atlas", e);
            }
        });
        return panel;
    }

    /** One frame of content, rebuilt every frame because the canvas is immediate-mode. */
    private static void draw(Canvas c, TextLayout tl, double seconds) {
        c.fillRoundRect(0, 0, CANVAS_W, CANVAS_H, 28, PANEL_BG);
        c.strokeRoundRect(0, 0, CANVAS_W, CANVAS_H, 28, 3, new Color(1, 1, 1, 0.18f));

        c.text(tl, "VEXELRAY", 48, 40, TextLayout.TextStyle.of(46f), INK);
        c.text(tl, "a canvas standing in the scene", 48, 100, TextLayout.TextStyle.of(24f), DIM);
        c.strokeLine(48, 150, CANVAS_W - 48, 150, 3, new Color(1, 1, 1, 0.25f));

        // Every measurement below is in canvas units, and therefore in world units. Nothing is a pixel.
        c.text(tl, "line widths are 2 mm, 4 mm, 8 mm on this surface", 48, 180,
                TextLayout.TextStyle.of(20f), DIM);
        float[] widths = {2f / 3.5f, 4f / 3.5f, 8f / 3.5f};   // mm converted through unitsPerPixel
        float y = 232;
        for (float w : widths) {
            c.strokeLine(48, y, 420, y, w, ACCENT);
            y += 34;
        }

        c.fillCircle(560, 250, 70, new Color(1, 1, 1, 0.06f));
        double sweep = 0.5 + 0.5 * Math.sin(seconds * 1.1);
        c.fillRoundRect(48, 360, (float) (sweep * (CANVAS_W - 96)), 26, 13, GOOD);
        c.strokeRoundRect(48, 360, CANVAS_W - 96, 26, 13, 2, new Color(1, 1, 1, 0.22f));

        c.text(tl, String.format("t = %5.1f s", seconds), 48, 420, TextLayout.TextStyle.of(22f), DIM);
        c.text(tl, "no texture, no resolution, no fixed pixel width", 48, 456,
                TextLayout.TextStyle.of(22f), ACCENT);
    }

    private static void run(int frames) throws IOException {
        SdfRaymarchTechnique march = march();
        PanelTechnique panel = panel(march.scene());
        TextLayout layout = new TextLayout(AtlasData.loadFromResource(ATLAS_JSON));

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("VexelRay — a canvas in the world", 1100, 700)
                        .color(AttachmentFormat.SWAPCHAIN)
                        .depth(AttachmentFormat.DEPTH32F))
                .technique(march)
                .technique(panel)
                .build();

        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("PanelDemo"))) {
            engine.run(pipeline, frame -> {
                double t = frame.timeSeconds();
                aim(march, panel, t);
                panel.draw(c -> draw(c, layout, t));
                return frame.frameIndex() + 1 < frames;
            });
        }
    }

    private static void capture(String path, double at) throws IOException {
        int w = 1100;
        int h = 700;
        SdfRaymarchTechnique march = march();
        PanelTechnique panel = panel(march.scene());
        TextLayout layout = new TextLayout(AtlasData.loadFromResource(ATLAS_JSON));

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.offscreen(w, h)
                        .color(AttachmentFormat.RGBA8_UNORM)
                        .depth(AttachmentFormat.DEPTH32F)
                        .build())
                .technique(march)
                .technique(panel)
                .build();

        byte[] rgba;
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("PanelDemo"))) {
            engine.run(pipeline, frame -> {
                aim(march, panel, at);
                panel.draw(c -> draw(c, layout, at));
                return false;
            });
            rgba = engine.lastFrameRgba();
        }
        ImageIO.write(toImage(rgba, w, h), "png", new File(path));
        System.out.println("wrote " + path);
    }

    /**
     * One camera, told to both techniques — the engine never learns what a camera is (D5).
     *
     * <p>An orbit that also closes in and backs off, because the property worth seeing is what happens to a
     * stroke's width and sharpness as the distance changes.
     */
    private static void aim(SdfRaymarchTechnique march, PanelTechnique panel, double t) {
        double angle = t * 0.35;
        double radius = 4.6 - 2.4 * Math.sin(t * 0.27);
        // Placed so that yaw = angle looks back at the panel: the camera's forward is (sin yaw, ., cos yaw)
        // once rotated, so the eye has to sit at the negative of that, scaled by the radius.
        double eyeX = -Math.sin(angle) * radius;
        double eyeZ = -Math.cos(angle) * radius;
        double eyeY = 1.7 + 0.35 * Math.sin(t * 0.5);
        // And positive pitch tilts the view down, because the rotation puts -sin(pitch) in the ray's y.
        double pitch = Math.asin((eyeY - PANEL_Y) / Math.max(0.001, radius));
        march.camera(eyeX, eyeY, eyeZ, angle, pitch);
        panel.camera(eyeX, eyeY, eyeZ, angle, pitch);
    }

    private static byte[] atlasRgba(int[] sizeOut) throws IOException {
        try (InputStream in = PanelDemo.class.getResourceAsStream(ATLAS_PNG)) {
            if (in == null) {
                throw new IllegalStateException("atlas PNG not found: " + ATLAS_PNG);
            }
            BufferedImage img = ImageIO.read(in);
            int w = img.getWidth();
            int h = img.getHeight();
            sizeOut[0] = w;
            sizeOut[1] = h;
            byte[] rgba = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int i = (y * w + x) * 4;
                    rgba[i] = (byte) ((argb >> 16) & 0xFF);
                    rgba[i + 1] = (byte) ((argb >> 8) & 0xFF);
                    rgba[i + 2] = (byte) (argb & 0xFF);
                    rgba[i + 3] = (byte) ((argb >> 24) & 0xFF);
                }
            }
            return rgba;
        }
    }

    private static BufferedImage toImage(byte[] rgba, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                int argb = ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                        | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF);
                image.setRGB(x, y, argb);
            }
        }
        return image;
    }

    private PanelDemo() {
    }
}
