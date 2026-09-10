package dev.vexelray.technique.sdf;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.surface.Cones;
import dev.vexelray.surface.Surface;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.present.StorageBuffer;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual smoke check (not a unit test): march a {@link ConeField} — geometry from a storage <b>buffer</b> rather
 * than from the shader — headlessly, and say in numbers whether anything was drawn.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link StrokeMarchSmoke} is the same question for a field compiled into its own SPIR-V. This is the other
 * lane, and until now nothing in this repository ran it. {@code ConeField}, {@code GuiApp.storage} and the
 * push-constant form of {@code SampledColorTarget.renderInto} were built one link at a time, each naming a
 * consumer in its javadoc, and the first program to execute any of the chain was an application in another
 * repository. {@code ConeFieldTest}'s five cases prove the module is valid SPIR-V, geometry-independent and
 * correctly packed — <b>none of them draws</b>, so none of them could tell a correct buffer layout from one the
 * march reads at the wrong offset and renders as empty space.
 *
 * <p>It could not have been written before {@code OffscreenRenderer.render} took a descriptor set: a
 * buffer-driven field has one by definition, and the one render-and-count path in the stack had no way to bind
 * it. That is the shape of the gap — the instrument that existed for exactly this question could not be pointed
 * at it.
 *
 * <h2>What the controls establish</h2>
 *
 * <p>Per {@link Smoke}, the count is only evidence if it moves. Two knobs, and they fail differently:
 *
 * <ul>
 *   <li><b>An empty buffer.</b> The same SPIR-V, the same camera, a header saying zero cones. If this draws what
 *       the full buffer draws, the picture is not coming from the buffer at all — which is the failure a
 *       geometry-independent module makes possible and a validity test cannot see.</li>
 *   <li><b>The camera turned away.</b> Nothing else is in the scene, so this must find empty space. If it does
 *       not, the march is not reading the camera.</li>
 * </ul>
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED} and, optionally, an output path.
 */
public final class ConeMarchSmoke {

    /** The orbit, matching {@link StrokeMarchSmoke} so the two pictures are comparable by eye. */
    private static final double DISTANCE = 4.6;
    private static final double YAW = Math.toRadians(38);
    private static final double PITCH = Math.toRadians(26);

    public static void main(String[] args) throws IOException {
        if (!measure(args.length > 0 ? args[0] : "cone-march.png").verdict()) {
            System.exit(1);
        }
    }

    /**
     * The measurement itself, separated from {@code main} so {@link ConeMarchTest} runs exactly this and not a
     * second copy of it.
     *
     * <p>Worth stating why that separation matters here specifically. This smoke reported NOTHING DRAWN for two
     * commits about a field that was fine, and it went unseen because a {@code main} is not run by surefire. A
     * test that re-implemented the measurement would have re-implemented that blind spot; a test that calls this
     * shares its fate, which is the point.
     *
     * @param out where to write the subject's render, for a human to look at afterwards
     * @return the smoke, unjudged — the caller decides what a failed verdict means
     */
    static Smoke measure(String out) throws IOException {
        int width = 512;
        int height = 512;

        // Authored where the fixed camera already looks, deliberately: a buffer-driven field is not framed on
        // the way in — it sits wherever its own coordinates put it, which is the whole point of the lane and
        // the first thing to get wrong. See StrokeMarchSmoke's note on a stroke that is simply out of shot.
        Surface.Stroke stroke = zigzag(6);
        List<Cones.Cone> cones = Cones.of(stroke);
        float[] packed = ConeField.pack(Cones.flatten(cones), cones.size());
        float[] empty = ConeField.pack(new float[0], 0);

        // The surface is in the scene for everything *except* its geometry: ConeField.compose does not compile
        // it, and the march reads the buffer instead. Passing the same stroke keeps the two lanes describing
        // the same subject, which is what makes the two smokes' pictures comparable.
        SdfScene scene = SdfScene.of(stroke).withAlbedo(new Surface.Rgb(0.78, 0.80, 0.86));
        List<ComposedShader> composed = ConeField.compose(scene);
        byte[] vertex = composed.get(0).spirv();
        byte[] fragment = composed.get(1).spirv();

        System.out.println("cones          " + cones.size() + " (" + packed.length + " floats packed)");
        System.out.println("fragment       " + fragment.length / 1024 + " kB");
        System.out.println();

        NativePlatform platform = NativePlatform.current();
        try (VulkanInstance instance = new VulkanInstance("VexelRay cone march",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics-capable device"));
            System.out.println("device         " + selection.deviceName());
            System.out.println();

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 StorageBuffer buffer = new StorageBuffer(device, packed.length, ConeField.BINDING)) {

                Surface.Rgb sky = scene.sky();
                double aspect = (double) width / height;
                Smoke smoke = new Smoke("ConeMarchSmoke", width * height);

                buffer.update(packed, packed.length);
                byte[] rgba = march(device, buffer, width, height, vertex, fragment, sky,
                        camera(scene, YAW, YAW, aspect));
                smoke.measured("the cone chain", countNonSky(rgba, sky));
                write(out, rgba, width, height);

                // Buffer emptied, everything else untouched -- see the class note.
                buffer.update(empty, empty.length);
                smoke.control("empty buffer",
                        countNonSky(march(device, buffer, width, height, vertex, fragment, sky,
                                camera(scene, YAW, YAW, aspect)), sky));

                buffer.update(packed, packed.length);
                smoke.control("camera turned away",
                        countNonSky(march(device, buffer, width, height, vertex, fragment, sky,
                                camera(scene, YAW, YAW + Math.PI, aspect)), sky));

                return smoke;
            }
        }
    }

    private static byte[] march(VulkanDevice device, StorageBuffer buffer, int width, int height,
                                byte[] vertex, byte[] fragment, Surface.Rgb sky, byte[] camera) {
        return OffscreenRenderer.render(device, width, height, vertex, "main", fragment, "main", 3,
                (float) sky.r(), (float) sky.g(), (float) sky.b(), 1f, camera,
                new long[]{buffer.descriptorSetLayout()}, new long[]{buffer.descriptorSet()});
    }

    /**
     * The push-constant block for an eye placed by {@code orbitYaw} and looking along {@code lookYaw}, in the
     * same axis order a plot host uses.
     *
     * <p>The two are separate parameters only so that "turned away" can mean it. Adding π to a single yaw moves
     * the eye to the far side <em>and</em> turns it back towards the origin, which is a valid second view of the
     * same subject and drew 97% of the subject's pixel count — a control that changes the number without
     * establishing anything, and one {@link Smoke} passed. The knob has to be absurd, not merely different.
     */
    private static byte[] camera(SdfScene scene, double orbitYaw, double lookYaw, double aspect) {
        double cp = Math.cos(PITCH);
        double[] forward = {cp * Math.sin(orbitYaw), cp * Math.cos(orbitYaw), -Math.sin(PITCH)};
        double[] at = {-DISTANCE * forward[0], -DISTANCE * forward[1], -DISTANCE * forward[2]};
        // The plot's z is the world's y, and the plot's y is the world's z -- the swap every renderer here makes.
        //
        // pushConstantBytes, not the deprecated cameraBytes. This smoke used the six-float form, and when the
        // block grew focalLength as its seventh member (79ed04c) the lens stopped being written: it read as
        // whatever was last in the command buffer, which for a fresh offscreen render is zero. A focal length of
        // zero collapses every primary ray onto the screen plane, so the march found nothing and the smoke drew
        // pure sky -- an instrument reporting NOTHING DRAWN about a field that was fine. Exactly what the
        // deprecation note predicted, and it went unnoticed because a main() is not run by surefire.
        return SdfComposer.pushConstantBytes(scene, at[0], at[2], at[1], lookYaw, PITCH, aspect,
                SdfComposer.paramBlock(scene));
    }

    private static Surface.Stroke zigzag(int vertices) {
        List<Surface.Stroke.Vertex> vs = new ArrayList<>();
        for (int i = 0; i < vertices; i++) {
            double t = (double) i / (vertices - 1);
            vs.add(new Surface.Stroke.Vertex(-1.4 + 2.8 * t, i % 2 == 0 ? -0.5 : 0.5, 0, 0.18, 1));
        }
        return new Surface.Stroke(vs, 4);
    }

    private static int countNonSky(byte[] rgba, Surface.Rgb sky) {
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

    private ConeMarchSmoke() {
    }
}
