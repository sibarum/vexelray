package dev.vexelray.vulkan;

import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.Texture;
import dev.supirvast.vastir.type.Type;
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
import dev.vexelray.vulkan.present.SampledColorTarget;
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
 * Manual smoke check (not a unit test): render a {@link Canvas} into a {@link SampledColorTarget} (an offscreen
 * image that ends in {@code SHADER_READ_ONLY}), then <em>sample that image</em> onto a perspective-tilted quad —
 * a 2D panel placed as a surface in a scene. This is the "2D drawn to a texture, then used inside 3D" path: pass 1
 * draws the UI into the target; pass 2 textures geometry with it.
 *
 * <p>{@code --capture <png>} composites offscreen and writes a PNG; a frame count (or no args) shows it live.
 * Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class SampledSurfaceDemo {

    private static final int PANEL_W = 520;   // the canvas (texture) resolution
    private static final int PANEL_H = 300;
    private static final int OUT_W = 800;     // the composite output resolution
    private static final int OUT_H = 500;
    private static final String ATLAS_JSON = "/dev/vexelray/text/atlas/primary.json";
    private static final String ATLAS_PNG = "/dev/vexelray/text/atlas/primary.png";

    public static void main(String[] args) throws IOException {
        AtlasData atlas = AtlasData.loadFromResource(ATLAS_JSON);
        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);

        // The 2D content to bake into a texture.
        Canvas canvas = new Canvas(PANEL_W, PANEL_H);
        buildPanel(canvas, new TextLayout(atlas));
        float[] canvasVerts = canvas.toVertexArray();
        int canvasCount = canvas.vertexCount();

        ComposedShader canvasVs = CanvasShader.vertex();
        ComposedShader canvasFs = CanvasShader.fragment();
        ComposedShader quadVs = quadVertex();
        ComposedShader quadFs = quadFragment();

        // The tilted quad that displays the texture, as a trapezoid (narrower at top) to read as perspective.
        float[] quad = tiltedQuad();

        NativePlatform platform = NativePlatform.current();
        boolean capture = args.length >= 1 && args[0].equals("--capture");
        String path = capture && args.length >= 2 ? args[1] : "sampled-surface.png";
        int maxFrames = !capture && args.length > 0 ? Integer.parseInt(args[0]) : 0;

        if (capture) {
            try (VulkanInstance instance = new VulkanInstance("VexelRay sampled",
                    platform.requiredVulkanInstanceExtensions())) {
                VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                        .orElseThrow(() -> new IllegalStateException("no graphics device"));
                System.out.println("device: " + sel.deviceName());
                try (VulkanDevice device = new VulkanDevice(instance.handle(), sel)) {
                    byte[] rgba = composite(device, atlasSize, atlasRgba, canvasVerts, canvasCount,
                            canvasVs, canvasFs, quad, quadVs, quadFs);
                    ImageIO.write(toImage(rgba, OUT_W, OUT_H), "PNG", new File(path));
                    System.out.println("captured " + new File(path).getAbsolutePath());
                }
            }
            return;
        }

        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay sampled surface", OUT_W, OUT_H, true));
             VulkanInstance instance = new VulkanInstance("VexelRay sampled",
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
                 SampledColorTarget target = new SampledColorTarget(device, PANEL_W, PANEL_H);
                 VertexBuffer canvasVb = new VertexBuffer(device, canvasVerts);
                 GraphicsPipeline canvasPipe = new GraphicsPipeline(device, target.renderPass(), PANEL_W, PANEL_H,
                         canvasVs.spirv(), "main", canvasFs.spirv(), "main", canvasConfig(atlasTex));
                 VertexBuffer quadVb = new VertexBuffer(device, quad);
                 GraphicsPipeline quadPipe = new GraphicsPipeline(device, renderPass.handle(),
                         swapchain.width(), swapchain.height(), quadVs.spirv(), "main", quadFs.spirv(), "main",
                         quadConfig(target));
                 WindowedPresenter presenter = new WindowedPresenter(device, swapchain, renderPass.handle(),
                         quadPipe, window)) {
                // Pass 1 (once): draw the canvas into the sampled texture.
                target.renderInto(canvasPipe, canvasVb.handle(), atlasTex.descriptorSet(), canvasCount,
                        0.10f, 0.11f, 0.14f, 1f);
                // Pass 2 (per frame): draw the tilted quad, sampling the texture.
                presenter.configureDraw(quadVb.handle(), target.descriptorSet(), 6);
                presenter.run(maxFrames);
            }
            instance.destroySurface(surface);
        }
        System.out.println("clean shutdown");
    }

    /** Two-pass offscreen composite (canvas -> sampled texture -> tilted quad) returning the RGBA of the OUT image. */
    private static byte[] composite(VulkanDevice device, int[] atlasSize, byte[] atlasRgba,
                                    float[] canvasVerts, int canvasCount, ComposedShader canvasVs, ComposedShader canvasFs,
                                    float[] quad, ComposedShader quadVs, ComposedShader quadFs) {
        try (AtlasTexture atlasTex = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
             SampledColorTarget target = new SampledColorTarget(device, PANEL_W, PANEL_H);
             VertexBuffer canvasVb = new VertexBuffer(device, canvasVerts);
             GraphicsPipeline canvasPipe = new GraphicsPipeline(device, target.renderPass(), PANEL_W, PANEL_H,
                     canvasVs.spirv(), "main", canvasFs.spirv(), "main", canvasConfig(atlasTex));
             VulkanRenderPass outPass = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                     Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
             VertexBuffer quadVb = new VertexBuffer(device, quad);
             GraphicsPipeline quadPipe = new GraphicsPipeline(device, outPass.handle(), OUT_W, OUT_H,
                     quadVs.spirv(), "main", quadFs.spirv(), "main", quadConfig(target))) {
            target.renderInto(canvasPipe, canvasVb.handle(), atlasTex.descriptorSet(), canvasCount,
                    0.10f, 0.11f, 0.14f, 1f);
            return OffscreenDraw.toRgba(device, outPass.handle(), quadPipe, OUT_W, OUT_H, quadVb.handle(),
                    target.descriptorSet(), 6, 0.04f, 0.05f, 0.07f, 1f);
        }
    }

    /** The 2D panel content baked into the texture. */
    private static void buildPanel(Canvas c, TextLayout tl) {
        Color panel = Color.rgb(0x1b2130);
        Color accent = Color.rgb(0x46b0ff);
        Color ink = Color.rgb(0xf2f5fa);
        Color dim = Color.rgb(0x93a0b4);
        c.begin();
        c.fillRoundRect(16, 16, PANEL_W - 32, PANEL_H - 32, 20, panel);
        c.fillCircle(56, 60, 20, accent);
        c.text(tl, "Live Panel", 92, 44, TextLayout.TextStyle.of(30f), ink);
        c.strokeLine(32, 96, PANEL_W - 32, 96, 2, Color.rgba(1, 1, 1, 0.12f));
        c.text(tl, "This UI was drawn by the Canvas into an offscreen image, then sampled onto a surface.",
                new TextLayout.TextBox(32, 112, PANEL_W - 64, 120),
                TextLayout.TextStyle.of(19f).withWrap(TextLayout.WrapMode.WORD_CHAR)
                        .withAlign(TextLayout.HAlign.LEFT, TextLayout.VAlign.TOP), dim);
        c.fillRoundRect(32, PANEL_H - 74, 120, 40, 10, accent);
        c.text(tl, "OK", 32, PANEL_H - 74, 120, 40,
                TextLayout.TextStyle.of(20f).withAlign(TextLayout.HAlign.CENTER, TextLayout.VAlign.MIDDLE),
                Color.WHITE);
    }

    /** A trapezoid quad in clip space (narrower at top → leaning-back perspective), with 0..1 UVs. 6 verts. */
    private static float[] tiltedQuad() {
        float topY = -0.72f;
        float botY = 0.78f;
        float topX = 0.42f;
        float botX = 0.82f;
        float[] tl = {-topX, topY, 0f, 0f};
        float[] tr = {topX, topY, 1f, 0f};
        float[] br = {botX, botY, 1f, 1f};
        float[] bl = {-botX, botY, 0f, 1f};
        return new float[]{
                tl[0], tl[1], tl[2], tl[3], tr[0], tr[1], tr[2], tr[3], br[0], br[1], br[2], br[3],
                tl[0], tl[1], tl[2], tl[3], br[0], br[1], br[2], br[3], bl[0], bl[1], bl[2], bl[3]};
    }

    // --- pipeline configs ---

    private static GraphicsPipeline.Config canvasConfig(AtlasTexture atlasTex) {
        List<GraphicsPipeline.VertexAttribute> attrs = new ArrayList<>();
        for (CanvasVertex.Attr a : CanvasVertex.ATTRIBUTES) {
            attrs.add(new GraphicsPipeline.VertexAttribute(a.location(), vkFormat(a.components()), a.offset()));
        }
        return new GraphicsPipeline.Config(CanvasVertex.STRIDE_BYTES, attrs,
                new long[]{atlasTex.descriptorSetLayout()}, true, Vk.SHADER_STAGE_FRAGMENT_BIT, 0);
    }

    private static GraphicsPipeline.Config quadConfig(SampledColorTarget target) {
        List<GraphicsPipeline.VertexAttribute> attrs = List.of(
                new GraphicsPipeline.VertexAttribute(0, Vk.FORMAT_R32G32_SFLOAT, 0),
                new GraphicsPipeline.VertexAttribute(1, Vk.FORMAT_R32G32_SFLOAT, 8));
        return new GraphicsPipeline.Config(16, attrs, new long[]{target.descriptorSetLayout()},
                true, Vk.SHADER_STAGE_FRAGMENT_BIT, 0);
    }

    private static int vkFormat(int components) {
        return switch (components) {
            case 1 -> Vk.FORMAT_R32_SFLOAT;
            case 2 -> Vk.FORMAT_R32G32_SFLOAT;
            case 4 -> Vk.FORMAT_R32G32B32A32_SFLOAT;
            default -> throw new IllegalArgumentException("bad components " + components);
        };
    }

    // --- the textured-quad compositor shader (pos+uv -> sample uTex) ---

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector V2 = new Type.Vector(F32, 2);
    private static final Type.Vector V4 = new Type.Vector(F32, 4);

    private static ComposedShader quadVertex() {
        InterfaceVar inPos = InterfaceVar.input("inPos", 0, V2);
        InterfaceVar inUv = InterfaceVar.input("inUv", 1, V2);
        InterfaceVar vUv = InterfaceVar.output("vUv", 0, V2);
        Expr pos = new Expr.InterfaceRead(inPos);
        Expr clip = new Expr.VectorConstruct(V4, List.of(
                new Expr.VectorExtract(pos, 0), new Expr.VectorExtract(pos, 1),
                new Expr.ConstFloat(F32, 0.0), new Expr.ConstFloat(F32, 1.0)));
        Region body = Region.of(
                new Statement.BuiltinWrite(Builtin.POSITION, clip),
                new Statement.InterfaceWrite(vUv, new Expr.InterfaceRead(inUv)),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        return ComposedShader.lower(ShaderStage.VERTEX,
                new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX)), "main");
    }

    private static ComposedShader quadFragment() {
        InterfaceVar vUv = InterfaceVar.input("vUv", 0, V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);
        Texture tex = new Texture("uTex", 0, 0);
        Expr sampled = new Expr.SampleTexture(tex, new Expr.InterfaceRead(vUv));
        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor, sampled),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        return ComposedShader.lower(ShaderStage.FRAGMENT,
                new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT)), "main");
    }

    // --- helpers ---

    private static byte[] loadAtlasRgba(int[] sizeOut) throws IOException {
        try (InputStream in = SampledSurfaceDemo.class.getResourceAsStream(ATLAS_PNG)) {
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

    private SampledSurfaceDemo() {
    }
}
