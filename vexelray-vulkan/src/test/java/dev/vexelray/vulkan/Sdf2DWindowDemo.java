package dev.vexelray.vulkan;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * Manual smoke check (not a unit test): the first SDF-based 2D pipeline in a live window — a small GUI mock drawn
 * entirely as signed-distance fields in a fullscreen fragment. This is VexelRay's 2D path in its most minimal,
 * on-brand form: the same fullscreen-triangle + {@link GraphicsPipeline} + {@link WindowedPresenter} machinery the
 * SDF-3D demo uses, differing <em>only</em> in the fragment IR. No vertex buffers, no descriptor sets, no hardware
 * blend — 2D shapes are evaluated analytically in pixel space and composited back-to-front with anti-aliased
 * {@code smoothstep} coverage and a manual alpha-over via {@code mix}.
 *
 * <p>The scene: a background gradient, a rounded panel, an accent bar, two pill toggles, and a circular button that
 * pulses with {@code time} — proving the per-frame push constant ({@code resX, resY, time}, 12 bytes, reusing the
 * existing fragment-stage layout) drives the 2D scene. Run with {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p>This is deliberately hand-wired (see docs/refactor-decisions.md §1, strangler): it folds into an
 * {@code Overlay2DTechnique} against the {@code RenderTechnique} SPI once the Phase 2 runtime lands. The vertex
 * buffer + combined-image-sampler + hardware-blend generalisations of {@code GraphicsPipeline} arrive with instanced
 * quads and MSDF text; a single fullscreen SDF fragment needs none of them.
 */
public final class Sdf2DWindowDemo {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector V2 = new Type.Vector(F32, 2);
    private static final Type.Vector V3 = new Type.Vector(F32, 3);
    private static final Type.Vector V4 = new Type.Vector(F32, 4);

    /** Edge softness, in pixels, for the analytic anti-aliasing of every shape. */
    private static final double AA = 1.3;

    public static void main(String[] args) throws java.io.IOException {
        byte[] vertexSpirv = Fullscreen.triangleVertexWithUvSpirv();
        byte[] fragmentSpirv = sdf2dFragment();
        System.out.println("Sdf2DWindowDemo — 2D SDF fragment composed: " + fragmentSpirv.length + " bytes of SPIR-V");

        NativePlatform platform = NativePlatform.current();
        if (args.length >= 1 && args[0].equals("--capture")) {
            captureFrame(platform, vertexSpirv, fragmentSpirv, args.length >= 2 ? args[1] : "sdf2d.png");
            return;
        }
        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;   // 0 = run until the window closes
        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay 2D (SDF)", 800, 600, true));
             VulkanInstance instance = new VulkanInstance("VexelRay 2D",
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
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, renderPass.handle(),
                         swapchain.width(), swapchain.height(),
                         vertexSpirv, "main", fragmentSpirv, "main", 12);
                 WindowedPresenter presenter = new WindowedPresenter(device, swapchain, renderPass.handle(),
                         pipeline, window)) {

                float resX = swapchain.width();
                float resY = swapchain.height();
                double[] elapsed = {0.0};
                presenter.run(maxFrames, 12, (dt, pc) -> {
                    elapsed[0] += dt;
                    pc.set(JAVA_FLOAT, 0, resX);
                    pc.set(JAVA_FLOAT, 4, resY);
                    pc.set(JAVA_FLOAT, 8, (float) elapsed[0]);
                });
            }
            instance.destroySurface(surface);
        }
        System.out.println("clean shutdown");
    }

    /**
     * The 2D scene as {@code core} IR. UV (0..1) is mapped to pixel coordinates so shapes and the anti-aliasing
     * softness are authored in device pixels; every primitive is composited over the running colour with
     * {@link #over}. Colours are linear-ish literals — a proper colour pipeline is later work.
     */
    private static byte[] sdf2dFragment() {
        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);
        PushConstants pc = new PushConstants(List.of(
                new PushConstants.Member("resX", F32),
                new PushConstants.Member("resY", F32),
                new PushConstants.Member("time", F32)));
        Expr res = new Expr.VectorConstruct(V2, List.of(pc.read(0), pc.read(1)));
        Expr time = pc.read(2);

        Expr uv = new Expr.InterfaceRead(vUv);
        Expr p = mul(uv, res);                                   // pixel-space fragment coordinate (y down)
        Expr centre = mulS2(res, f(0.5));

        // Background: a subtle vertical gradient between two near-blacks.
        Expr col = Expr.MathCall.mix(v3(0.05, 0.06, 0.08), v3(0.10, 0.12, 0.16), broadcast3(y(uv)));

        // Rounded panel, centred.
        Expr dPanel = sdRoundBox(p, centre, v2(260, 180), 30);
        col = over(col, v3(0.13, 0.15, 0.19), dPanel, 1.0);

        // Accent bar near the panel's top edge.
        Expr dAccent = sdRoundBox(p, add(centre, v2(0, -140)), v2(232, 6), 3);
        col = over(col, v3(0.30, 0.72, 0.98), dAccent, 1.0);

        // Two pill toggles.
        Expr dPillL = sdRoundBox(p, add(centre, v2(-120, -40)), v2(66, 22), 22);
        Expr dPillR = sdRoundBox(p, add(centre, v2(120, -40)), v2(66, 22), 22);
        col = over(col, v3(0.22, 0.24, 0.30), dPillL, 1.0);
        col = over(col, v3(0.35, 0.62, 0.45), dPillR, 1.0);

        // Circular button that pulses with time — the visible proof the per-frame push constant reaches the scene.
        Expr r = add(f(46.0), mul(f(6.0), Expr.MathCall.sin(mul(time, f(2.2)))));
        Expr dBtn = sub(Expr.MathCall.length(sub(p, add(centre, v2(0, 66)))), r);
        col = over(col, v3(0.95, 0.45, 0.30), dBtn, 1.0);

        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor,
                        new Expr.VectorConstruct(V4, List.of(x(col), y(col), z(col), f(1.0)))),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    /** Headless one-frame grab through {@link dev.vexelray.vulkan.offscreen.OffscreenRenderer} — CI-reproducible. */
    private static void captureFrame(NativePlatform platform, byte[] vert, byte[] frag, String path)
            throws java.io.IOException {
        int w = 640;
        int h = 400;
        try (VulkanInstance instance = new VulkanInstance("VexelRay 2D",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            System.out.println("device: " + sel.deviceName());
            try (VulkanDevice device = new VulkanDevice(instance.handle(), sel)) {
                byte[] push = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                        .putFloat(w).putFloat(h).putFloat(0.6f).array();
                byte[] rgba = dev.vexelray.vulkan.offscreen.OffscreenRenderer.render(
                        device, w, h, vert, "main", frag, "main", 3, 0f, 0f, 0f, 1f, push);
                java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                        w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                for (int yy = 0; yy < h; yy++) {
                    for (int xx = 0; xx < w; xx++) {
                        int i = (yy * w + xx) * 4;
                        img.setRGB(xx, yy, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                                | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
                    }
                }
                javax.imageio.ImageIO.write(img, "PNG", new java.io.File(path));
                System.out.println("captured " + new java.io.File(path).getAbsolutePath());
            }
        }
    }

    // --- 2D SDF primitives (pixel space) ---

    /** Rounded box: distance from {@code p} to a box centred at {@code c} with half-extents {@code half}, corner {@code r}. */
    private static Expr sdRoundBox(Expr p, Expr c, Expr half, double r) {
        Expr q = add(sub(Expr.MathCall.abs(sub(p, c)), half), v2(r, r));   // abs(p-c) - half + r
        Expr outside = Expr.MathCall.length(Expr.MathCall.max(q, v2(0, 0)));
        Expr inside = Expr.MathCall.min(Expr.MathCall.max(x(q), y(q)), f(0.0));
        return sub(add(outside, inside), f(r));
    }

    /** Alpha-over compositing with analytic AA: coverage 1 inside the shape, ramping to 0 across ~2·AA pixels. */
    private static Expr over(Expr col, Expr shape, Expr d, double alpha) {
        Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(f(-AA), f(AA), d));
        Expr a = mul(cov, f(alpha));
        return Expr.MathCall.mix(col, shape, broadcast3(a));
    }

    // --- tiny IR-authoring helpers (mirrors Fathom's style) ---

    private static Expr f(double v) {
        return new Expr.ConstFloat(F32, v);
    }

    private static Expr v2(double a, double b) {
        return new Expr.VectorConstruct(V2, List.of(f(a), f(b)));
    }

    private static Expr v3(double a, double b, double c) {
        return new Expr.VectorConstruct(V3, List.of(f(a), f(b), f(c)));
    }

    private static Expr broadcast3(Expr s) {
        return new Expr.VectorConstruct(V3, List.of(s, s, s));
    }

    private static Expr x(Expr v) {
        return new Expr.VectorExtract(v, 0);
    }

    private static Expr y(Expr v) {
        return new Expr.VectorExtract(v, 1);
    }

    private static Expr z(Expr v) {
        return new Expr.VectorExtract(v, 2);
    }

    private static Expr add(Expr a, Expr b) {
        return new Expr.Binary(dev.supirvast.vastir.core.BinaryOp.ADD, a, b);
    }

    private static Expr sub(Expr a, Expr b) {
        return new Expr.Binary(dev.supirvast.vastir.core.BinaryOp.SUB, a, b);
    }

    private static Expr mul(Expr a, Expr b) {
        return new Expr.Binary(dev.supirvast.vastir.core.BinaryOp.MUL, a, b);
    }

    /** vec2 * scalar via broadcast (vector·scalar isn't a core primitive yet). */
    private static Expr mulS2(Expr vec, Expr scalar) {
        return mul(vec, new Expr.VectorConstruct(V2, List.of(scalar, scalar)));
    }

    private Sdf2DWindowDemo() {
    }
}
