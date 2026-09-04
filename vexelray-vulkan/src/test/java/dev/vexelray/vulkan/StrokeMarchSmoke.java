package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.surface.Bounds;
import dev.vexelray.surface.Surface;
import dev.vexelray.technique.sdf.SdfComposer;
import dev.vexelray.technique.sdf.SdfScene;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual smoke check (not a unit test): march a {@link Surface.Stroke} headlessly and say, in numbers, whether
 * anything was drawn.
 *
 * <p>It exists because "nothing renders" is not one question but three, and a window cannot tell them apart. A
 * host that shows nothing may have composed a shader that draws nothing, or composed a good one and pointed the
 * camera somewhere else, or never reached the draw at all. This runs the first of those in isolation: no window,
 * no GUI, no frame loop, no viewport plumbing — compose a stroke, march it, count the pixels that are not sky.
 *
 * <p>The camera deliberately matches what a host built around a fixed world box uses: an eye orbiting the
 * <b>origin</b> at a fixed distance, looking back at it. That is the other half of the diagnosis. A surface
 * derived from an expression is framed into that box on the way in; a surface handed in already built is not,
 * so it sits wherever its own coordinates put it — and a stroke authored a hundred units away is not a bug in
 * anything, it is simply out of shot.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED} and an output path.
 */
public final class StrokeMarchSmoke {

    /** The orbit a fixed-box host looks from, and the angles it starts at. */
    private static final double DISTANCE = 4.6;
    private static final double YAW = Math.toRadians(38);
    private static final double PITCH = Math.toRadians(26);

    /** The world box a host frames into: flatter than it is wide. */
    private static final double BOX_HALF = 2.0;
    private static final double BOX_HALF_HEIGHT = 1.25;

    public static void main(String[] args) throws IOException {
        int width = 512;
        int height = 512;
        String out = args.length > 0 ? args[0] : "stroke-march.png";

        // Deliberately hostile placement: sixty units from the origin and a twentieth of a unit across. Marched
        // as authored this is a perfect picture of empty space — which is exactly what a host saw, and exactly
        // what is indistinguishable from a broken renderer until something measures it.
        Surface.Stroke stroke = zigzag(4, true);
        // ...and a ground plane over the top of it, which is the shape that actually failed: the plane is
        // infinite, so a containment box refuses, and a host framing on that framed on nothing at all.
        Surface awkward = Surface.union(
                new Surface.Scale(0.02, new Surface.Translate(60, 20, 10, stroke)),
                Surface.Plane.ground());

        System.out.println("containment    " + Bounds.of(awkward));
        Bounds box = Bounds.subject(awkward).orElseThrow(() -> new IllegalStateException("nothing to frame"));
        System.out.println("authored at    centre " + fmt(box.centre())
                + ", half-extent " + fmt(box.halfExtent()));
        Surface framed = frame(awkward, box);

        SdfScene scene = SdfScene.of(framed).withAlbedo(new SdfScene.Rgb(0.78, 0.80, 0.86));

        List<ComposedShader> composed = new SdfComposer().compose(scene);
        byte[] vertex = composed.get(0).spirv();
        byte[] fragment = composed.get(1).spirv();
        System.out.println("cones          " + stroke.coneBound() + " (upper bound)");
        System.out.println("fragment       " + fragment.length / 1024 + " kB");
        System.out.println("colour carried " + SurfaceColour.carried(stroke));

        NativePlatform platform = NativePlatform.current();
        try (VulkanInstance instance = new VulkanInstance("VexelRay stroke march",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics-capable device"));
            System.out.println("device         " + selection.deviceName());

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection)) {
                SdfScene.Rgb sky = scene.sky();
                byte[] rgba = OffscreenRenderer.render(device, width, height,
                        vertex, "main", fragment, "main", 3,
                        (float) sky.r(), (float) sky.g(), (float) sky.b(), 1f,
                        camera((double) width / height));

                int drawn = countNonSky(rgba, sky);
                System.out.println("pixels drawn   " + drawn + " of " + (width * height)
                        + " (" + Math.round(100.0 * drawn / (width * height)) + "%)");
                System.out.println(drawn == 0
                        ? "NOTHING DRAWN -- the shader itself renders no geometry from this camera"
                        : "geometry rendered; if a host shows nothing, the difference is the host's camera,"
                                + " its mount state, or where the surface sits");
                write(out, rgba, width, height);
            }
        }
    }

    /**
     * The surface centred on the origin and scaled into the world box -- the pass a host runs so that a camera
     * aimed at a fixed box finds geometry it did not place.
     */
    private static Surface frame(Surface surface, Bounds box) {
        double[] c = box.centre();
        double[] h = box.halfExtent();
        double factor = Double.POSITIVE_INFINITY;
        if (h[0] > 0) {
            factor = Math.min(factor, BOX_HALF / h[0]);
        }
        if (h[1] > 0) {
            factor = Math.min(factor, BOX_HALF_HEIGHT / h[1]);
        }
        if (h[2] > 0) {
            factor = Math.min(factor, BOX_HALF / h[2]);
        }
        factor = Double.isFinite(factor) && factor > 0 ? factor : 1;
        System.out.println("framed         centred and scaled " + Math.round(factor * 100) / 100.0 + "x");
        return new Surface.Scale(factor, new Surface.Translate(-c[0], -c[1], -c[2], surface));
    }

    private static String fmt(double[] v) {
        return String.format("(%.3f, %.3f, %.3f)", v[0], v[1], v[2]);
    }

    /** The push-constant block for an eye orbiting the origin, in the same axis order a plot host uses. */
    private static byte[] camera(double aspect) {
        double cp = Math.cos(PITCH);
        // Forward, in plot space: the direction the eye looks along. The eye stands opposite it.
        double[] forward = {cp * Math.sin(YAW), cp * Math.cos(YAW), -Math.sin(PITCH)};
        double[] at = {-DISTANCE * forward[0], -DISTANCE * forward[1], -DISTANCE * forward[2]};
        // The plot's z is the world's y, and the plot's y is the world's z — the swap every renderer here makes.
        return SdfComposer.cameraBytes(at[0], at[2], at[1], YAW, PITCH, aspect);
    }

    private static Surface.Stroke zigzag(int vertices, boolean coloured) {
        List<Surface.Stroke.Vertex> vs = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            double t = (double) i / (vertices - 1);
            Surface.Stroke.Vertex v = new Surface.Stroke.Vertex(
                    -1.4 + 2.8 * t, (i % 2 == 0 ? -0.5 : 0.5), 0, 0.18, 1);
            vs.add(coloured
                    ? v.painted(new Surface.Rgb(1 - t, 0.25, t))
                    : v);
        }
        return new Surface.Stroke(vs, 4);
    }

    private static int countNonSky(byte[] rgba, SdfScene.Rgb sky) {
        int r = (int) Math.round(sky.r() * 255);
        int g = (int) Math.round(sky.g() * 255);
        int b = (int) Math.round(sky.b() * 255);
        int drawn = 0;
        for (int i = 0; i < rgba.length; i += 4) {
            if (Math.abs((rgba[i] & 0xFF) - r) > 3
                    || Math.abs((rgba[i + 1] & 0xFF) - g) > 3
                    || Math.abs((rgba[i + 2] & 0xFF) - b) > 3) {
                drawn++;
            }
        }
        return drawn;
    }

    private static void write(String out, byte[] rgba, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                image.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                        | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
        }
        File file = new File(out);
        ImageIO.write(image, "PNG", file);
        System.out.println("wrote          " + file.getAbsolutePath());
    }

    /** Whether the compiled field carries colour of its own — reported so a blank frame is not blamed on it. */
    private static final class SurfaceColour {
        static String carried(Surface surface) {
            return dev.vexelray.surface.SurfaceCompiler.compile(surface).hasAlbedo() ? "yes" : "no";
        }
    }

    private StrokeMarchSmoke() {
    }
}
