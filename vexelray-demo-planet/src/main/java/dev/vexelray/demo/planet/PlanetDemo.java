package dev.vexelray.demo.planet;

import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.Lens;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Milestone one: the planet from orbit. A world of 3,000 km radius, land to 9 km high and sea as deep, under 80 km
 * of air, turning slowly in front of the camera.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED}, or {@code mvn -pl vexelray-demo-planet -am compile
 * exec:exec}. No arguments opens a window; {@code capture <dir>} writes the views in {@link #VIEWS} to
 * {@code <dir>/planet-<n>.png}.
 */
public final class PlanetDemo {

    /** Kilometres. */
    static final double RADIUS = 3000;
    static final double MAX_HEIGHT = 9;

    /** A pinhole of focal length 1.4 seeing from a kilometre out to past the far side of the system's orbits. */
    static final Lens LENS = new Lens(1.4, new ClipDepth(1.0, 40_000.0));

    /** Eye position (x, y, z km), the point looked at (x, y, z km), and the planet's spin: four ways to see it. */
    private static final double[][] VIEWS = {
            // The whole disc, from a little off the sun's side: day, the terminator, and the night.
            {-1500, 3200, -9500, 0, 0, 0, 0.0},
            // 250 km up, looking along the horizon toward the sun: the air edge-on.
            over(-0.2, 0.35, -0.9, 250, 0.12, 0.4),
            // From the night side with the sun behind the world: the atmosphere's ring.
            {-4200, -1500, 7600, 0, 0, 0, 1.1},
            // 120 km up over the day side, looking well down at the ground ahead.
            over(0.55, 0.25, -0.8, 120, 0.7, 2.3),
    };

    /**
     * A view from {@code altitude} km above the ground along direction {@code (x, y, z)}, looking toward the sun's
     * side of the horizon and tipped down by {@code down} (kilometres of drop per kilometre ahead).
     */
    private static double[] over(double x, double y, double z, double altitude, double down, double spin) {
        double length = Math.sqrt(x * x + y * y + z * z);
        double[] n = {x / length, y / length, z / length};
        double[] sun = PlanetShader.SUN;
        double along = sun[0] * n[0] + sun[1] * n[1] + sun[2] * n[2];
        double[] t = {sun[0] - along * n[0], sun[1] - along * n[1], sun[2] - along * n[2]};
        double tl = Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]);
        double r = RADIUS + altitude;
        double[] eye = {n[0] * r, n[1] * r, n[2] * r};
        return new double[]{eye[0], eye[1], eye[2],
                eye[0] + t[0] / tl - down * n[0], eye[1] + t[1] / tl - down * n[1],
                eye[2] + t[2] / tl - down * n[2], spin};
    }

    public static void main(String[] args) throws IOException {
        // exec:exec hands planet.args over as one token, "capture DIR" included.
        if (args.length == 1) {
            args = args[0].trim().split("\\s+");
        }
        if (args.length >= 1 && args[0].equals("capture")) {
            File dir = new File(args.length >= 2 ? args[1] : ".");
            dir.mkdirs();
            for (int v = 0; v < VIEWS.length; v++) {
                capture(new File(dir, "planet-" + (v + 1) + ".png"), VIEWS[v]);
            }
        } else {
            run();
        }
    }

    private static PlanetTechnique planet() {
        return new PlanetTechnique(LENS, new PlanetShader(RADIUS, new Terrain(MAX_HEIGHT)));
    }

    private static void run() {
        PlanetTechnique planet = planet();
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("VexelRay — a planet", 1280, 760)
                        .color(AttachmentFormat.SWAPCHAIN)
                        .depth(AttachmentFormat.DEPTH32F)
                        .build())
                .technique(planet)
                .build();
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("PlanetDemo"))) {
            engine.run(pipeline, frame -> {
                double t = frame.timeSeconds();
                // A slow orbit that swings from the day side round to the terminator, the planet turning beneath.
                double angle = -0.6 + 0.05 * t;
                double distance = 8200 - 2200 * Math.sin(0.03 * t);
                double eyeX = distance * Math.sin(angle);
                double eyeZ = -distance * Math.cos(angle);
                double eyeY = 1600 * Math.sin(0.021 * t);
                aim(planet, eyeX, eyeY, eyeZ, 0, 0, 0);
                planet.spin(0.02 * t);
                return true;
            });
        }
    }

    private static void capture(File file, double[] view) throws IOException {
        int w = 1280;
        int h = 760;
        PlanetTechnique planet = planet();
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.offscreen(w, h).color(AttachmentFormat.RGBA8_UNORM)
                        .depth(AttachmentFormat.DEPTH32F).build())
                .technique(planet)
                .build();
        byte[] rgba;
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("PlanetDemo"))) {
            engine.run(pipeline, frame -> {
                aim(planet, view[0], view[1], view[2], view[3], view[4], view[5]);
                planet.spin(view[6]);
                return false;
            });
            rgba = engine.lastFrameRgba();
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++) {
            image.setRGB(i % w, i / w,
                    (rgba[i * 4] & 0xFF) << 16 | (rgba[i * 4 + 1] & 0xFF) << 8 | (rgba[i * 4 + 2] & 0xFF));
        }
        ImageIO.write(image, "png", file);
        System.out.println("wrote " + file);
    }

    /**
     * Point the camera from the eye at the target. The march's forward axis for a yaw and a pitch is
     * {@code (sin yaw cos pitch, -sin pitch, cos yaw cos pitch)}, so both angles come straight off the direction.
     */
    private static void aim(PlanetTechnique planet, double ex, double ey, double ez, double tx, double ty,
                            double tz) {
        double fx = tx - ex;
        double fy = ty - ey;
        double fz = tz - ez;
        double length = Math.sqrt(fx * fx + fy * fy + fz * fz);
        planet.camera(ex, ey, ez, Math.atan2(fx, fz), -Math.asin(fy / length));
    }
}
