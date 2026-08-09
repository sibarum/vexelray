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
import dev.vexelray.vulkan.present.OffscreenDraw;
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
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual smoke check (not a unit test): the {@link Canvas} API — VexelRay's immediate-mode 2D drawing surface —
 * rendered through the unified {@link CanvasShader} uber-shader. One {@link Canvas} accumulates rounded panels,
 * buttons, a circle, a line, and several text runs into a single vertex batch; one draw renders all of it, shapes
 * (analytic rounded-box SDF) and glyphs (MSDF) branching in the fragment shader.
 *
 * <p>Demonstrates both Canvas targets: {@code --capture <png>} draws to an offscreen texture and reads it back
 * ({@link OffscreenDraw}); no args (or a frame count) draws to the swapchain framebuffer via
 * {@link WindowedPresenter}. Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class CanvasDemo {

    private static final int W = 760;
    private static final int H = 460;
    private static final String ATLAS_JSON = "/dev/vexelray/text/atlas/primary.json";
    private static final String ATLAS_PNG = "/dev/vexelray/text/atlas/primary.png";

    public static void main(String[] args) throws IOException {
        ComposedShader vs = CanvasShader.vertex();
        ComposedShader fs = CanvasShader.fragment();
        AtlasData atlas = AtlasData.loadFromResource(ATLAS_JSON);
        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);

        // Build the scene once (static) into a Canvas; a real app rebuilds each frame.
        Canvas canvas = new Canvas(W, H);
        buildScene(canvas, new TextLayout(atlas));
        float[] vertices = canvas.toVertexArray();
        int vertexCount = canvas.vertexCount();
        System.out.println("CanvasDemo — uber-shader v=" + vs.spirv().length + " f=" + fs.spirv().length
                + " bytes; " + vertexCount + " vertices");

        NativePlatform platform = NativePlatform.current();
        if (args.length >= 1 && args[0].equals("--capture")) {
            String path = args.length >= 2 ? args[1] : "canvas.png";
            try (VulkanInstance instance = new VulkanInstance("VexelRay canvas",
                    platform.requiredVulkanInstanceExtensions())) {
                VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                        .orElseThrow(() -> new IllegalStateException("no graphics device"));
                System.out.println("device: " + sel.deviceName());
                try (VulkanDevice device = new VulkanDevice(instance.handle(), sel);
                     VulkanRenderPass renderPass = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                             Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                     AtlasTexture atlasTex = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
                     VertexBuffer vb = new VertexBuffer(device, vertices);
                     GraphicsPipeline pipeline = new GraphicsPipeline(device, renderPass.handle(), W, H,
                             vs.spirv(), "main", fs.spirv(), "main", canvasConfig(atlasTex))) {
                    byte[] rgba = OffscreenDraw.toRgba(device, renderPass.handle(), pipeline, W, H, vb.handle(),
                            atlasTex.descriptorSet(), vertexCount, 0.05f, 0.06f, 0.08f, 1f);
                    ImageIO.write(toImage(rgba, W, H), "PNG", new File(path));
                    System.out.println("captured " + new File(path).getAbsolutePath());
                }
            }
            return;
        }

        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;
        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay Canvas", W, H, true));
             VulkanInstance instance = new VulkanInstance("VexelRay canvas",
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
                 VertexBuffer vb = new VertexBuffer(device, vertices);
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, renderPass.handle(),
                         swapchain.width(), swapchain.height(), vs.spirv(), "main", fs.spirv(), "main",
                         canvasConfig(atlasTex));
                 WindowedPresenter presenter = new WindowedPresenter(device, swapchain, renderPass.handle(),
                         pipeline, window)) {
                presenter.configureDraw(vb.handle(), atlasTex.descriptorSet(), vertexCount);
                presenter.run(maxFrames);
            }
            instance.destroySurface(surface);
        }
        System.out.println("clean shutdown");
    }

    /** A little dashboard: rounded panel, accent header + title, two buttons with labels, an avatar circle, a divider, and body text. */
    private static void buildScene(Canvas c, TextLayout tl) {
        Color panel = Color.rgb(0x171a22);
        Color accent = Color.rgb(0x3aa0f5);
        Color green = Color.rgb(0x37b26a);
        Color chip = Color.rgb(0x272c38);
        Color ink = Color.rgb(0xeef1f6);
        Color dim = Color.rgb(0x9aa3b2);

        c.begin();
        c.fillRoundRect(24, 24, W - 48, H - 48, 22, panel);
        c.fillRoundRect(24, 24, W - 48, 76, 22, Color.rgb(0x1e2330));   // header band (over-rounded bottom is hidden by body)
        c.fillCircle(70, 62, 22, accent);
        c.text(tl, "VexelRay Canvas", 108, 44, TextLayout.TextStyle.of(34f), ink);

        // Buttons
        TextLayout.TextStyle btn = TextLayout.TextStyle.of(22f)
                .withAlign(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE);
        c.fillRoundRect(W - 250, 40, 100, 40, 10, accent);
        c.text(tl, "Save", W - 250, 40, 100, 40, btn, Color.WHITE);
        c.fillRoundRect(W - 140, 40, 100, 40, 10, chip);
        c.text(tl, "Cancel", W - 140, 40, 100, 40, btn, dim);

        // Divider line
        c.strokeLine(48, 120, W - 48, 120, 2, Color.rgba(1, 1, 1, 0.10f));

        // Body copy, wrapped + justified inside a box.
        String body = "The Canvas batches rounded rectangles, circles, lines, and text into one vertex buffer, "
                + "drawn by a single uber-shader that branches per primitive: an analytic rounded-box distance "
                + "field for shapes, and MSDF sampling for glyphs. Colour and edge sharpness are per-vertex, so "
                + "sizes and hues mix freely in one draw.";
        c.text(tl, body, new TextLayout.TextBox(48, 140, W - 300, 250),
                TextLayout.TextStyle.of(21f).withWrap(TextLayout.WrapMode.WORD_CHAR)
                        .withAlign(TextLayout.HAlign.JUSTIFY, TextLayout.VAlign.TOP), ink);

        // A little swatch column on the right.
        float sx = W - 220;
        c.fillRoundRect(sx, 150, 172, 240, 14, Color.rgb(0x1e2330));
        c.fillCircle(sx + 40, 195, 20, accent);
        c.fillCircle(sx + 90, 195, 20, green);
        c.fillCircle(sx + 140, 195, 20, Color.rgb(0xf0703a));
        c.fillRoundRect(sx + 20, 235, 132, 26, 13, chip);
        c.fillRoundRect(sx + 20, 235, 84, 26, 13, accent);   // a "progress" pill
        c.text(tl, "62%", sx + 20, 235, 132, 26,
                TextLayout.TextStyle.of(16f).withAlign(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE),
                Color.WHITE);
        c.text(tl, "shapes + text\none pipeline\none draw call", sx + 20, 285,
                TextLayout.TextStyle.of(18f), dim);
    }

    /** The unified-canvas pipeline config: the fat vertex format, the atlas descriptor, alpha blend, no push constant. */
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
            default -> throw new IllegalArgumentException("unsupported component count " + components);
        };
    }

    private static byte[] loadAtlasRgba(int[] sizeOut) throws IOException {
        try (InputStream in = CanvasDemo.class.getResourceAsStream(ATLAS_PNG)) {
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
                image.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                        | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
        }
        return image;
    }

    private CanvasDemo() {
    }
}
