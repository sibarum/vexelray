package dev.vexelray.technique.mesh;

import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.technique.sdf.SdfRaymarchTechnique;
import dev.vexelray.technique.sdf.SdfScene;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Manual demo (not a unit test): polygon meshes standing in a marched scene, lit by the scene's own light and
 * sharing its depth buffer.
 *
 * <p>Three meshes, each showing one thing. A crate pushed halfway into a marched sphere, so the two cut each
 * other along a curve that neither technique drew — the depth buffer did. A pyramid, flat-shaded, turning, so
 * its faces brighten and darken under the same key light as the spheres beside it. And a star — a concave
 * polygon, one face — spinning upright, lit from whichever side faces the camera.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED}. No arguments opens a window; {@code --capture <png>
 * [seconds]} renders one offscreen frame at that point and writes it to a file.
 */
public final class MeshDemo {

    private static final double LOOK_Y = 1.0;

    public static void main(String[] args) throws IOException {
        if (args.length >= 1 && args[0].equals("--capture")) {
            String path = args.length >= 2 ? args[1] : "mesh.png";
            double at = args.length >= 3 ? Double.parseDouble(args[2]) : 1.5;
            capture(path, at);
        } else {
            run(args.length > 0 ? Integer.parseInt(args[0]) : 1200);
        }
    }

    private static SdfRaymarchTechnique march() {
        Surface scene = Surface.union(
                Surface.Plane.ground(),
                new Surface.Sphere(1.9, 0.9, 1.1, 0.9),
                new Surface.Sphere(-2.4, 0.7, -0.6, 0.7));
        return new SdfRaymarchTechnique(SdfScene.of(scene).withAlbedo(new Surface.Rgb(0.72, 0.75, 0.83)));
    }

    /** The meshes and the instances that move. */
    private record Scene(MeshTechnique meshes, MeshTechnique.Instance pyramid, MeshTechnique.Instance star) {
    }

    private static Scene meshes(SdfScene scene) {
        MeshTechnique meshes = new MeshTechnique(scene.focalLength(), scene.clipDepth(), scene.shading());

        // A white box, coloured per instance: one mesh, uploaded once, tinted by a push constant.
        meshes.add(Mesh.box(1.2, 1.2, 1.2), new Placement(1.15, 0.6, 0.55, 0.4, 0, 0, 1)).tint(0.80, 0.55, 0.30);

        double h = 1.3;
        Mesh pyramid = Mesh.builder()
                .colour(0.35, 0.75, 0.55)
                .polygon(-0.7, 0, -0.7, 0.7, 0, -0.7, 0.7, 0, 0.7, -0.7, 0, 0.7)
                .triangle(-0.7, 0, -0.7, 0.7, 0, -0.7, 0, h, 0)
                .triangle(0.7, 0, -0.7, 0.7, 0, 0.7, 0, h, 0)
                .triangle(0.7, 0, 0.7, -0.7, 0, 0.7, 0, h, 0)
                .triangle(-0.7, 0, 0.7, -0.7, 0, -0.7, 0, h, 0)
                .build();
        MeshTechnique.Instance p = meshes.add(pyramid, Placement.at(-1.3, 0, 1.6));

        int points = 5;
        double[] xyz = new double[points * 2 * 3];
        for (int i = 0; i < points * 2; i++) {
            double angle = Math.PI / 2 + Math.PI * i / points;
            double radius = i % 2 == 0 ? 0.75 : 0.3;
            xyz[i * 3] = radius * Math.cos(angle);
            xyz[i * 3 + 1] = radius * Math.sin(angle);
        }
        Mesh star = Mesh.builder().colour(0.95, 0.78, 0.25).polygon(xyz).build();
        MeshTechnique.Instance s = meshes.add(star, Placement.at(0.1, 2.3, 1.8));
        return new Scene(meshes, p, s);
    }

    private static void animate(Scene scene, double t) {
        scene.pyramid().place(scene.pyramid().placement().turned(t * 0.6, 0));
        scene.star().place(scene.star().placement().turned(t * 1.1, 0));
    }

    private static void run(int frames) {
        SdfRaymarchTechnique march = march();
        Scene scene = meshes(march.scene());
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("VexelRay — polygons in a marched scene", 1100, 700)
                        .color(AttachmentFormat.SWAPCHAIN)
                        .depth(AttachmentFormat.DEPTH32F))
                .technique(march)
                .technique(scene.meshes())
                .build();
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("MeshDemo"))) {
            engine.run(pipeline, frame -> {
                double t = frame.timeSeconds();
                aim(march, scene.meshes(), t);
                animate(scene, t);
                return frame.frameIndex() + 1 < frames;
            });
        }
    }

    private static void capture(String path, double at) throws IOException {
        int w = 1100;
        int h = 700;
        SdfRaymarchTechnique march = march();
        Scene scene = meshes(march.scene());
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.offscreen(w, h)
                        .color(AttachmentFormat.RGBA8_UNORM)
                        .depth(AttachmentFormat.DEPTH32F)
                        .build())
                .technique(march)
                .technique(scene.meshes())
                .build();
        byte[] rgba;
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("MeshDemo"))) {
            engine.run(pipeline, frame -> {
                aim(march, scene.meshes(), at);
                animate(scene, at);
                return false;
            });
            rgba = engine.lastFrameRgba();
        }
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < w * h; i++) {
            image.setRGB(i % w, i / w,
                    (rgba[i * 4] & 0xFF) << 16 | (rgba[i * 4 + 1] & 0xFF) << 8 | (rgba[i * 4 + 2] & 0xFF));
        }
        ImageIO.write(image, "png", new File(path));
        System.out.println("wrote " + path);
    }

    /** One camera, told to both techniques — the engine never learns what a camera is (D5). */
    private static void aim(SdfRaymarchTechnique march, MeshTechnique meshes, double t) {
        double angle = -1.6 + t * 0.25;
        double radius = 6.0;
        double eyeX = -Math.sin(angle) * radius;
        double eyeZ = -Math.cos(angle) * radius + 1.0;
        double eyeY = 2.2;
        double pitch = Math.asin((eyeY - LOOK_Y) / radius);
        march.camera(eyeX, eyeY, eyeZ, angle, pitch);
        meshes.camera(eyeX, eyeY, eyeZ, angle, pitch);
    }
}
