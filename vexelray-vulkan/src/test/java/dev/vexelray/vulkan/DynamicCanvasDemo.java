package dev.vexelray.vulkan;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.Color;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.TextLayout;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual smoke check (not a unit test): a standalone, <em>live</em> 2D UI drawn with the {@link Canvas}. The scene
 * is rebuilt from scratch every frame — a clock, a frame counter / FPS read-out, a pulsing progress bar, an
 * animated equaliser, and an orbiting dot — and the immediate-mode geometry is re-uploaded to a dynamic
 * {@link VertexBuffer} each frame via {@link WindowedPresenter}'s per-frame callback. This is the immediate-mode
 * UI loop end to end: clear, draw, upload, present.
 *
 * <p>Run windowed (until closed) or {@code DynamicCanvasDemo <frames>} (capped). Needs
 * {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class DynamicCanvasDemo {

    private static final int W = 760;
    private static final int H = 460;
    private static final int CAPACITY_FLOATS = 400_000;   // headroom for the per-frame geometry
    private static final String ATLAS_JSON = "/dev/vexelray/text/atlas/primary.json";
    private static final String ATLAS_PNG = "/dev/vexelray/text/atlas/primary.png";

    public static void main(String[] args) throws IOException {
        ComposedShader vs = CanvasShader.vertex();
        ComposedShader fs = CanvasShader.fragment();
        AtlasData atlas = AtlasData.loadFromResource(ATLAS_JSON);
        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);
        NativePlatform platform = NativePlatform.current();
        if (args.length >= 1 && args[0].equals("--capture")) {
            String path = args.length >= 2 ? args[1] : "live-ui.png";
            double t = args.length >= 3 ? Double.parseDouble(args[2]) : 2.35;
            captureOne(platform, vs, fs, atlas, atlasSize, atlasRgba, t, path);
            return;
        }
        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;

        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay Live UI", W, H, true));
             VulkanInstance instance = new VulkanInstance("VexelRay live ui",
                     platform.requiredVulkanInstanceExtensions())) {
            long surface = window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                    .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
            System.out.println("device: " + selection.deviceName());

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 VulkanSwapchain swapchain = new VulkanSwapchain(instance.handle(), device, surface,
                         window.width(), window.height());
                 VulkanRenderPass renderPass = new VulkanRenderPass(device, swapchain.format(),
                         Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR);
                 AtlasTexture atlasTex = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
                 VertexBuffer vb = new VertexBuffer(device, CAPACITY_FLOATS);
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, renderPass.handle(),
                         swapchain.width(), swapchain.height(), vs.spirv(), "main", fs.spirv(), "main",
                         canvasConfig(atlasTex));
                 WindowedPresenter presenter = new WindowedPresenter(device, swapchain, renderPass.handle(),
                         pipeline, window)) {

                Canvas canvas = new Canvas(W, H);
                TextLayout tl = new TextLayout(atlas);
                double[] state = {0.0, 60.0, 0.0};   // [elapsed seconds, smoothed fps, frame count]

                presenter.configureDraw(vb.handle(), atlasTex.descriptorSet(), 0);
                presenter.run(maxFrames, 0, (dt, pc) -> {
                    state[0] += dt;
                    if (dt > 0) {
                        state[1] = state[1] * 0.9 + (1.0 / dt) * 0.1;
                    }
                    state[2] += 1;
                    buildFrame(canvas, tl, state[0], state[1], (long) state[2]);
                    vb.update(canvas.toVertexArray());
                    presenter.setVertexCount(canvas.vertexCount());
                });
            }
            instance.destroySurface(surface);
        }
        System.out.println("clean shutdown");
    }

    /** Rebuild the whole UI for time {@code t} (seconds). Everything here is recomputed every frame. */
    private static void buildFrame(Canvas c, TextLayout tl, double t, double fps, long frame) {
        Color panel = Color.rgb(0x151a24);
        Color card = Color.rgb(0x1d2431);
        Color accent = Color.rgb(0x46b0ff);
        Color ink = Color.rgb(0xeef2f8);
        Color dim = Color.rgb(0x8b97a8);

        c.begin();
        c.fillRoundRect(16, 16, W - 32, H - 32, 22, panel);
        c.text(tl, "VexelRay Live UI", 40, 34, TextLayout.TextStyle.of(30f), ink);
        c.text(tl, String.format("frame %d   %.0f fps", frame, fps), 40, 76, TextLayout.TextStyle.of(18f), dim);

        // Clock (mm:ss.t), large, right-aligned in the header.
        int totalTenths = (int) (t * 10);
        int mins = totalTenths / 600;
        int secs = (totalTenths / 10) % 60;
        int tenths = totalTenths % 10;
        c.text(tl, String.format("%02d:%02d.%d", mins, secs, tenths), W - 240, 30, 200, 48,
                TextLayout.TextStyle.of(40f).withAlign(TextLayout.HAlign.RIGHT, TextLayout.VAlign.MIDDLE), accent);

        // Pulsing progress bar.
        float px = 40;
        float py = 118;
        float pw = W - 80;
        float frac = (float) (0.5 + 0.5 * Math.sin(t * 0.9));
        c.fillRoundRect(px, py, pw, 22, 11, card);
        c.fillRoundRect(px, py, Math.max(22f, pw * frac), 22, 11, accent);
        c.text(tl, String.format("loading  %d%%", (int) (frac * 100)), px, py - 2, pw, 26,
                TextLayout.TextStyle.of(15f).withAlign(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE), ink);

        // Equaliser: a row of bars bouncing at different phases.
        int bars = 24;
        float bx0 = 40;
        float top = 170;
        float bottom = 300;
        float gap = 6;
        float bw = (pw - (bars - 1) * gap) / bars;
        c.fillRoundRect(bx0 - 12, top - 14, pw + 24, (bottom - top) + 40, 14, card);
        for (int i = 0; i < bars; i++) {
            float phase = i * 0.5f;
            float amp = (float) (0.15 + 0.85 * (0.5 + 0.5 * Math.sin(t * 3.0 + phase)));
            float bh = amp * (bottom - top);
            float bx = bx0 + i * (bw + gap);
            Color bar = new Color(0.27f + 0.5f * amp, 0.7f - 0.2f * amp, 1.0f - 0.3f * amp, 1f);
            c.fillRoundRect(bx, bottom - bh, bw, bh, Math.min(bw * 0.5f, 5f), bar);
        }

        // Orbiting dot with a trailing ring, over a labelled footer.
        float ocx = W / 2f;
        float ocy = 370;
        float rr = 34;
        c.strokeLine(ocx - 120, ocy, ocx + 120, ocy, 2, Color.rgba(1, 1, 1, 0.08f));
        for (int k = 0; k < 6; k++) {
            double a = t * 2.2 - k * 0.35;
            float dx = (float) Math.cos(a) * (90 + k * 4);
            float dy = (float) Math.sin(a) * rr;
            float s = 10f - k * 1.2f;
            c.fillCircle(ocx + dx, ocy + dy, Math.max(2f, s), Color.rgba(0.27f, 0.69f, 1f, 1f - k * 0.14f));
        }
        c.text(tl, "immediate-mode - rebuilt every frame - one draw", 40, H - 52, pw, 28,
                TextLayout.TextStyle.of(16f).withAlign(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE), dim);
    }

    /** Snapshot a single animated frame at time {@code t} to a PNG (offscreen), for visual verification. */
    private static void captureOne(NativePlatform platform, ComposedShader vs, ComposedShader fs,
                                   AtlasData atlas, int[] atlasSize, byte[] atlasRgba, double t, String path)
            throws IOException {
        Canvas canvas = new Canvas(W, H);
        buildFrame(canvas, new TextLayout(atlas), t, 60.0, (long) (t * 60));
        float[] verts = canvas.toVertexArray();
        int count = canvas.vertexCount();
        try (VulkanInstance instance = new VulkanInstance("VexelRay live ui",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            System.out.println("device: " + sel.deviceName());
            try (VulkanDevice device = new VulkanDevice(instance.handle(), sel);
                 VulkanRenderPass rp = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                         Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                 AtlasTexture atlasTex = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
                 VertexBuffer vb = new VertexBuffer(device, verts);
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, rp.handle(), W, H,
                         vs.spirv(), "main", fs.spirv(), "main", canvasConfig(atlasTex))) {
                byte[] rgba = dev.vexelray.vulkan.present.OffscreenDraw.toRgba(device, rp.handle(), pipeline, W, H,
                        vb.handle(), atlasTex.descriptorSet(), count, 0.05f, 0.06f, 0.08f, 1f);
                BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < H; y++) {
                    for (int x = 0; x < W; x++) {
                        int i = (y * W + x) * 4;
                        img.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                                | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
                    }
                }
                ImageIO.write(img, "PNG", new java.io.File(path));
                System.out.println("captured " + new java.io.File(path).getAbsolutePath());
            }
        }
    }

    private static GraphicsPipeline.Config canvasConfig(AtlasTexture atlasTex) {
        List<GraphicsPipeline.VertexAttribute> attrs = new ArrayList<>();
        for (CanvasVertex.Attr a : CanvasVertex.ATTRIBUTES) {
            attrs.add(new GraphicsPipeline.VertexAttribute(a.location(), vkFormat(a.components()), a.offset()));
        }
        return new GraphicsPipeline.Config(CanvasVertex.STRIDE_BYTES, attrs,
                new long[]{atlasTex.descriptorSetLayout()}, true, Vk.SHADER_STAGE_FRAGMENT_BIT, 0);
    }

    private static int vkFormat(int components) {
        return switch (components) {
            case 1 -> Vk.FORMAT_R32_SFLOAT;
            case 2 -> Vk.FORMAT_R32G32_SFLOAT;
            case 4 -> Vk.FORMAT_R32G32B32A32_SFLOAT;
            default -> throw new IllegalArgumentException("bad components " + components);
        };
    }

    private static byte[] loadAtlasRgba(int[] sizeOut) throws IOException {
        try (InputStream in = DynamicCanvasDemo.class.getResourceAsStream(ATLAS_PNG)) {
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

    private DynamicCanvasDemo() {
    }
}
