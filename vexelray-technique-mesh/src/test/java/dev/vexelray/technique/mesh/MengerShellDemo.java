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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Manual demo (not a unit test): an endless Menger lattice seen only through polygon shells
 * ({@link MengerShellShader}) under a procedural {@link Sky}: a cube on the lattice's axes, the same cube stood on
 * its corner (its field left square to the world, so the diagonal cut), and a random lumpy blob with concave pits,
 * turning with its field.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED}. No arguments opens a window; a look ({@code distance},
 * {@code reflect}, {@code reflect-exit}: see {@link #scene(String)}) opens it in that look.
 * {@code --capture <dir> [look]} writes one offscreen frame per view in {@link #VIEWS} to
 * {@code <dir>/menger-[look-]<n>.png}; {@code --compare <dir>}
 * writes {@link #comparison()}, the blob marching on through its back face beside the blob transparent there, to
 * {@code <dir>/compare-<n>.png}; {@code --motion <dir>} writes {@link #motion()} at each of {@link #MOTION_TIMES}
 * to {@code <dir>/motion-<n>.png}.
 */
public final class MengerShellDemo {

    private static final double LOOK_Y = 1.2;

    /** Orbit angle, eye height, radius, and the point looked at (x): four ways of seeing the same three shells. */
    private static final double[][] VIEWS = {
            {0.0, 2.2, 7.0, 0.0},
            {0.75, 3.6, 6.5, 0.0},
            {-0.9, 1.5, 6.0, 0.0},
            {0.45, 1.9, 2.8, 2.4},
            // Low and close on the cube on its corner, looking up at it.
            {0.25, 0.75, 2.4, 0.0},
    };

    /** The pair from straight on, from above, and close up on the seam between them. */
    private static final double[][] COMPARE_VIEWS = {
            {0.0, 1.8, 5.0, 0.0},
            {0.5, 3.4, 4.6, 0.0},
            {-0.35, 1.4, 3.2, 0.0},
    };

    /**
     * What a frame draws, in order: the {@link Sky}, the ground, then the shells, one technique per kind (a
     * technique with exits baked in draws one mesh). {@code blob} is the turning blob, as an index into
     * {@code blobShells}.
     */
    private record Scene(SkyTechnique sky, SdfRaymarchTechnique march, List<MengerShellTechnique> shells,
                         MengerShellTechnique blobShells, int blob) {

        MengerShellTechnique first() {
            return shells.getFirst();
        }

        void eachShells(java.util.function.Consumer<MengerShellTechnique> action) {
            shells.forEach(action);
        }
    }

    /** The ground, marched. Its own sky never shows (the {@link SkyTechnique} is the backdrop). */
    private static SdfRaymarchTechnique ground() {
        return new SdfRaymarchTechnique(SdfScene.of(Surface.Plane.ground()).withAlbedo(GROUND));
    }

    /** Seconds into {@link #motion()} at which {@code --motion} captures. */
    private static final double[] MOTION_TIMES = {0.0, 0.7, 1.4};

    /**
     * The three ways a field can move, side by side. Left, the blob turning with its field in it: one solid, its
     * pattern carried round with it. Middle, the same blob turning through a field that stays in the world: the
     * slice it shows changes as it turns. Right, a still cube whose field slides and turns inside it.
     */
    private static Scene motion() {
        SdfRaymarchTechnique march = ground();
        SdfScene sdf = march.scene();
        MengerShellTechnique shells = new MengerShellTechnique(sdf.lens(), sdf.shading());
        Mesh blob = blob(new Random(7), 0.95);
        double apart = 4 * MengerShellShader.CELL;
        shells.add(blob, Placement.at(-apart, LOOK_Y, 0), FieldSpace.OBJECT);
        shells.add(blob, Placement.at(0, LOOK_Y, 0), FieldSpace.inWorld(Placement.ORIGIN));
        double side = 2 * MengerShellShader.CELL;
        shells.add(Mesh.box(side, side, side), Placement.at(apart, LOOK_Y, 0), FieldSpace.OBJECT);
        return new Scene(new SkyTechnique(sdf.lens()), march, List.of(shells), shells, 0);
    }

    private static void animateMotion(Scene scene, double t) {
        MengerShellTechnique shells = scene.first();
        for (int shell = 0; shell < 2; shell++) {
            Placement at = shells.placement(shell);
            shells.place(shell, new Placement(at.x(), at.y(), at.z(), t * 0.9, t * 0.35, 0, 1));
        }
        shells.field(2, shells.field(2).placed(new Placement(t * 0.25, t * 0.15, 0, t * 0.3, 0, 0, 1)));
    }

    /**
     * The blob twice, side by side: on the left as the main scene draws it (a ray marches on through the back
     * face), on the right transparent where a ray leaves it. Each carries its field, at the same turn, so both cut
     * the same slice.
     */
    private static Scene comparison() {
        SdfRaymarchTechnique march = ground();
        SdfScene sdf = march.scene();
        Mesh blob = blob(new Random(7), 0.95);
        double apart = 2 * MengerShellShader.CELL;
        Placement left = new Placement(-apart, LOOK_Y, 0, 0.3, 0.2, 0, 1);
        Placement right = new Placement(apart, LOOK_Y, 0, 0.3, 0.2, 0, 1);
        MengerShellTechnique shells = new MengerShellTechnique(sdf.lens(), sdf.shading());
        int index = shells.add(blob, left);
        MengerShellTechnique transparent = new MengerShellTechnique(sdf.lens(), sdf.shading(), blob);
        transparent.add(blob, right);
        return new Scene(new SkyTechnique(sdf.lens()), march, List.of(shells, transparent), shells, index);
    }

    public static void main(String[] args) throws IOException {
        if (args.length >= 1 && args[0].equals("--capture")) {
            File dir = new File(args.length >= 2 ? args[1] : ".");
            dir.mkdirs();
            String look = args.length >= 3 ? args[2] : "steps";
            String prefix = look.equals("steps") ? "menger-" : "menger-" + look + "-";
            for (int v = 0; v < VIEWS.length; v++) {
                capture(new File(dir, prefix + (v + 1) + ".png"), scene(look), VIEWS[v]);
            }
        } else if (args.length >= 1 && args[0].equals("--motion")) {
            File dir = new File(args.length >= 2 ? args[1] : ".");
            dir.mkdirs();
            for (int n = 0; n < MOTION_TIMES.length; n++) {
                Scene scene = motion();
                animateMotion(scene, MOTION_TIMES[n]);
                capture(new File(dir, "motion-" + (n + 1) + ".png"), scene, VIEWS[0]);
            }
        } else if (args.length >= 1 && args[0].equals("--compare")) {
            File dir = new File(args.length >= 2 ? args[1] : ".");
            dir.mkdirs();
            for (int v = 0; v < COMPARE_VIEWS.length; v++) {
                capture(new File(dir, "compare-" + (v + 1) + ".png"), comparison(), COMPARE_VIEWS[v]);
            }
        } else {
            run(args.length >= 1 ? args[0] : "steps");
        }
    }

    private static final Surface.Rgb GROUND = new Surface.Rgb(0.55, 0.57, 0.62);

    /**
     * The three shells: a cube on the lattice's axes, the same cube on its corner, the blob. With {@code exit},
     * every ray is clipped where it leaves its shell, which takes a technique per mesh; without, one draws all.
     */
    private static Scene scene(boolean exit) {
        SdfRaymarchTechnique march = ground();
        SdfScene sdf = march.scene();
        // The sponge's own cube: 2*CELL on a side, centred on a lattice centre.
        double side = 2 * MengerShellShader.CELL;
        Mesh cube = Mesh.box(side, side, side);
        Mesh blobMesh = blob(new Random(7), 0.95);
        MengerShellTechnique cubes = new MengerShellTechnique(sdf.lens(), sdf.shading(), exit ? cube : null);
        MengerShellTechnique blobs = exit
                ? new MengerShellTechnique(sdf.lens(), sdf.shading(), blobMesh)
                : cubes;

        cubes.add(cube, Placement.at(-4 * MengerShellShader.CELL, LOOK_Y, 0));
        // The same cube on its corner, its field left square to the world: its faces cut the lattice on the
        // diagonal.
        cubes.add(cube, new Placement(0, LOOK_Y, 0, Math.PI / 4, Math.atan(1 / Math.sqrt(2)), 0, 1),
                FieldSpace.inWorld(Placement.ORIGIN));
        int blob = blobs.add(blobMesh, new Placement(4 * MengerShellShader.CELL, LOOK_Y, 0, 0.3, 0.2, 0, 1));
        List<MengerShellTechnique> shells = blobs == cubes ? List.of(cubes) : List.of(cubes, blobs);
        return new Scene(new SkyTechnique(sdf.lens()), march, shells, blobs, blob);
    }

    /**
     * A closed, lumpy, concave mesh: an icosahedron split twice, every vertex pushed to a random radius. Shared
     * vertices are pushed once, so it stays watertight.
     */
    static Mesh blob(Random random, double radius) {
        double g = (1 + Math.sqrt(5)) / 2;
        List<double[]> points = new ArrayList<>(List.of(
                new double[]{-1, g, 0}, new double[]{1, g, 0}, new double[]{-1, -g, 0}, new double[]{1, -g, 0},
                new double[]{0, -1, g}, new double[]{0, 1, g}, new double[]{0, -1, -g}, new double[]{0, 1, -g},
                new double[]{g, 0, -1}, new double[]{g, 0, 1}, new double[]{-g, 0, -1}, new double[]{-g, 0, 1}));
        int[][] faces = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11}, {1, 5, 9}, {5, 11, 4}, {11, 10, 2},
                {10, 7, 6}, {7, 1, 8}, {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9}, {4, 9, 5},
                {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}};
        for (int split = 0; split < 2; split++) {
            Map<Long, Integer> midpoints = new HashMap<>();
            int[][] next = new int[faces.length * 4][];
            int n = 0;
            for (int[] f : faces) {
                int ab = midpoint(points, midpoints, f[0], f[1]);
                int bc = midpoint(points, midpoints, f[1], f[2]);
                int ca = midpoint(points, midpoints, f[2], f[0]);
                next[n++] = new int[]{f[0], ab, ca};
                next[n++] = new int[]{f[1], bc, ab};
                next[n++] = new int[]{f[2], ca, bc};
                next[n++] = new int[]{ab, bc, ca};
            }
            faces = next;
        }
        // Outward winding, so a back face is one whose normal points along the ray. Every face is wound the same
        // way and a radial push keeps it so; which way is read off one face of the sphere, before the push.
        double[] a0 = points.get(faces[0][0]);
        double[] b0 = points.get(faces[0][1]);
        double[] c0 = points.get(faces[0][2]);
        double[] ab = {b0[0] - a0[0], b0[1] - a0[1], b0[2] - a0[2]};
        double[] ac = {c0[0] - a0[0], c0[1] - a0[1], c0[2] - a0[2]};
        double outward = (ab[1] * ac[2] - ab[2] * ac[1]) * a0[0] + (ab[2] * ac[0] - ab[0] * ac[2]) * a0[1]
                + (ab[0] * ac[1] - ab[1] * ac[0]) * a0[2];
        if (outward < 0) {
            for (int[] f : faces) {
                int swap = f[1];
                f[1] = f[2];
                f[2] = swap;
            }
        }
        for (double[] p : points) {
            double length = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
            double r = radius * (0.55 + 0.45 * random.nextDouble()) / length;
            p[0] *= r;
            p[1] *= r;
            p[2] *= r;
        }
        Mesh.Builder builder = Mesh.builder();
        for (int[] f : faces) {
            double[] a = points.get(f[0]);
            double[] b = points.get(f[1]);
            double[] c = points.get(f[2]);
            builder.triangle(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]);
        }
        return builder.build();
    }

    private static int midpoint(List<double[]> points, Map<Long, Integer> cache, int i, int j) {
        long key = ((long) Math.min(i, j) << 32) | Math.max(i, j);
        return cache.computeIfAbsent(key, k -> {
            double[] a = points.get(i);
            double[] b = points.get(j);
            points.add(new double[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2, (a[2] + b[2]) / 2});
            return points.size() - 1;
        });
    }

    private static RenderPipeline pipeline(Scene scene, Target target) {
        // The sky after the march, over the march's own flat sky and nothing else (see SkyTechnique).
        RenderPipeline.Builder builder = RenderPipeline.builder()
                .target(target)
                .technique(scene.march())
                .technique(scene.sky());
        scene.eachShells(builder::technique);
        return builder.build();
    }

    /**
     * The main scene in a look: {@code steps} or {@code distance} (a {@link MengerShellShader.Fade}),
     * {@code reflect} (polished, no fade), or {@code reflect-exit} (polished, and every ray, the eye's and the
     * reflected, transparent where it leaves its shell).
     */
    private static Scene scene(String look) {
        boolean exit = look.endsWith("-exit");
        String base = exit ? look.substring(0, look.length() - "-exit".length()) : look;
        Scene scene = scene(exit);
        if (base.equals("reflect")) {
            MengerShellShader.Mirror mirror = new MengerShellShader.Mirror(
                    new double[]{GROUND.r(), GROUND.g(), GROUND.b()});
            scene.eachShells(s -> s.fade(MengerShellShader.Fade.NONE).mirror(mirror));
        } else {
            MengerShellShader.Fade fade = MengerShellShader.Fade.valueOf(base.toUpperCase(java.util.Locale.ROOT));
            scene.eachShells(s -> s.fade(fade));
        }
        return scene;
    }

    private static void run(String look) {
        Scene scene = scene(look);
        RenderPipeline pipeline = pipeline(scene, Target.windowed("VexelRay — a Menger lattice through shells",
                        1100, 700)
                .color(AttachmentFormat.SWAPCHAIN)
                .depth(AttachmentFormat.DEPTH32F)
                .build());
        Placement start = scene.blobShells().placement(scene.blob());
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("MengerShellDemo"))) {
            engine.run(pipeline, frame -> {
                double t = frame.timeSeconds();
                // A slow orbit that swings in close and back out, rising and falling, so reflections slide.
                double radius = 5.6 - 1.4 * Math.sin(t * 0.21);
                aim(scene, new double[]{-0.6 + t * 0.15, 1.6 + 0.8 * Math.sin(t * 0.13), radius, 0.0});
                scene.blobShells().place(scene.blob(), start.turned(start.yaw() + t * 0.3, start.pitch()));
                return true;
            });
        }
    }

    private static void capture(File file, Scene scene, double[] view) throws IOException {
        int w = 1100;
        int h = 700;
        RenderPipeline pipeline = pipeline(scene, Target.offscreen(w, h)
                .color(AttachmentFormat.RGBA8_UNORM)
                .depth(AttachmentFormat.DEPTH32F)
                .build());
        byte[] rgba;
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("MengerShellDemo"))) {
            engine.run(pipeline, frame -> {
                aim(scene, view);
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

    /** One camera, told to every technique: on an orbit around {@code (lookX, LOOK_Y, 0)}, looking at it. */
    private static void aim(Scene scene, double[] view) {
        double angle = view[0];
        double eyeY = view[1];
        double radius = view[2];
        double lookX = view[3];
        double flat = Math.sqrt(radius * radius - (eyeY - LOOK_Y) * (eyeY - LOOK_Y));
        double eyeX = lookX - Math.sin(angle) * flat;
        double eyeZ = -Math.cos(angle) * flat;
        double pitch = Math.asin((eyeY - LOOK_Y) / radius);
        scene.sky().camera(angle, pitch);
        scene.march().camera(eyeX, eyeY, eyeZ, angle, pitch);
        scene.eachShells(s -> s.camera(eyeX, eyeY, eyeZ, angle, pitch));
    }
}
