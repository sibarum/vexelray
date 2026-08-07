package dev.vexelray.demo;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.present.GraphicsPipeline;
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
import java.util.List;

/**
 * Fathom — VexelRay's reference demo, v0: a first-person raymarched signed-distance world in a live window.
 * The scene is a fragment shader authored as SupirVast {@code core} IR at runtime and lowered to SPIR-V; the
 * fullscreen triangle comes from {@link Fullscreen}. This is also the canonical client-app wiring: it selects a
 * platform module at build time and ships a {@code -Pnative} single-binary profile.
 *
 * <p>Run: {@code Fathom} (windowed, until closed), {@code Fathom <frames>} (windowed, capped), or
 * {@code Fathom --capture <out.png>} (headless one-frame grab). Needs {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p>v0 scene: a ground plane + a sphere, sphere-traced with a fixed step budget and shaded by a
 * finite-difference normal under one directional light. Camera + movement + CPU collision against this same SDF
 * are the next steps.
 */
public final class Fathom {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector V3 = new Type.Vector(F32, 3);
    private static final Type.Vector V4 = new Type.Vector(F32, 4);
    private static final Type.Vector V2 = new Type.Vector(F32, 2);

    public static void main(String[] args) throws IOException {
        byte[] vertexSpirv = Fullscreen.triangleVertexWithUvSpirv();
        byte[] fragmentSpirv = raymarchFragment();
        System.out.println("Fathom — raymarch fragment composed: " + fragmentSpirv.length + " bytes of SPIR-V");

        NativePlatform platform = NativePlatform.current();

        String capture = null;
        int maxFrames = 0;
        if (args.length >= 2 && args[0].equals("--capture")) {
            capture = args[1];
        } else if (args.length == 1) {
            maxFrames = Integer.parseInt(args[0]);
        }

        if (capture != null) {
            captureFrame(platform, vertexSpirv, fragmentSpirv, capture);
            return;
        }

        try (NativeWindow window = platform.createWindow(new WindowConfig("Fathom", 800, 600, true));
             VulkanInstance instance = new VulkanInstance("Fathom", platform.requiredVulkanInstanceExtensions())) {
            long surface = window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                    .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
            System.out.println("device: " + selection.deviceName());

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 VulkanSwapchain swapchain = new VulkanSwapchain(instance.handle(), device, surface,
                         window.width(), window.height());
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, swapchain.format(),
                         Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR, swapchain.width(), swapchain.height(),
                         vertexSpirv, "main", fragmentSpirv, "main");
                 WindowedPresenter presenter = new WindowedPresenter(device, swapchain, pipeline, window)) {
                presenter.run(maxFrames);
            }
            instance.destroySurface(surface);
        }
        System.out.println("clean shutdown");
    }

    private static void captureFrame(NativePlatform platform, byte[] vert, byte[] frag, String path)
            throws IOException {
        int w = 512;
        int h = 512;
        try (VulkanInstance instance = new VulkanInstance("Fathom", platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            System.out.println("device: " + selection.deviceName());
            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection)) {
                byte[] rgba = OffscreenRenderer.render(device, w, h, vert, "main", frag, "main", 3,
                        0.10f, 0.12f, 0.16f, 1.0f);
                BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int i = (y * w + x) * 4;
                        image.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                                | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
                    }
                }
                ImageIO.write(image, "PNG", new File(path));
                System.out.println("captured " + new File(path).getAbsolutePath());
            }
        }
    }

    /**
     * The v0 scene, authored as {@code core} IR: a first-person sphere-traced ground-plane + sphere, normal-shaded
     * under one light. The SDF is inlined via {@link #sceneSdf} (a Java method emitting fresh IR per call), so no
     * entry-point function call is needed and there is no hand-duplicated math.
     */
    private static byte[] raymarchFragment() {
        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);

        Expr uvx = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 0);
        Expr uvy = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 1);
        // screen ray: x spans [-1,1], y flipped so up is +y; focal length 1.4
        Expr sx = sub(mul(uvx, f(2.0)), f(1.0));
        Expr sy = sub(f(1.0), mul(uvy, f(2.0)));
        Expr rdInit = Expr.MathCall.normalize(new Expr.VectorConstruct(V3, List.of(sx, sy, f(1.4))));

        LocalVar ro = new LocalVar("ro", V3);
        LocalVar rd = new LocalVar("rd", V3);
        LocalVar t = new LocalVar("t", F32);
        LocalVar i = new LocalVar("i", Type.int32());
        LocalVar p = new LocalVar("p", V3);
        LocalVar d = new LocalVar("d", F32);

        Region march = Region.of(
                new Statement.Assign(p, add(read(ro), mulS(read(rd), read(t)))),
                new Statement.Assign(d, sceneSdf(read(p))),
                new Statement.Assign(t, add(read(t), read(d))),
                new Statement.Assign(i, new Expr.Binary(BinaryOp.ADD, read(i), new Expr.ConstInt(Type.int32(), 1))));

        // finite-difference normal at the hit point
        double eps = 0.002;
        Expr n = Expr.MathCall.normalize(new Expr.VectorConstruct(V3, List.of(
                sub(sceneSdf(add(read(p), v3(eps, 0, 0))), sceneSdf(sub(read(p), v3(eps, 0, 0)))),
                sub(sceneSdf(add(read(p), v3(0, eps, 0))), sceneSdf(sub(read(p), v3(0, eps, 0)))),
                sub(sceneSdf(add(read(p), v3(0, 0, eps))), sceneSdf(sub(read(p), v3(0, 0, eps)))))));
        Expr light = v3(0.575, 0.766, -0.287);   // pre-normalized direction to the light
        Expr diff = Expr.MathCall.max(Expr.MathCall.dot(n, light), f(0.0));
        Expr shade = Expr.MathCall.clamp(add(mul(diff, f(0.85)), f(0.15)), f(0.0), f(1.0));
        Region hit = Region.of(new Statement.InterfaceWrite(fragColor,
                new Expr.VectorConstruct(V4, List.of(shade, shade, shade, f(1.0)))));
        Region miss = Region.of(new Statement.InterfaceWrite(fragColor,
                new Expr.VectorConstruct(V4, List.of(f(0.10), f(0.12), f(0.16), f(1.0)))));

        Region body = Region.of(
                new Statement.DeclareVar(ro, v3(0.0, 1.2, -3.0)),
                new Statement.DeclareVar(rd, rdInit),
                new Statement.DeclareVar(t, f(0.0)),
                new Statement.DeclareVar(i, new Expr.ConstInt(Type.int32(), 0)),
                new Statement.DeclareVar(p, v3(0, 0, 0)),
                new Statement.DeclareVar(d, f(0.0)),
                new Statement.While(new Expr.Binary(BinaryOp.LESS_THAN, read(i), new Expr.ConstInt(Type.int32(), 80)), march),
                new Statement.Assign(p, add(read(ro), mulS(read(rd), read(t)))),
                new Statement.Assign(d, sceneSdf(read(p))),
                new Statement.If(new Expr.Binary(BinaryOp.LESS_THAN, read(d), f(0.01)), hit, miss),
                new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    /** Signed distance to the v0 scene: {@code min(ground plane y=0, sphere at (0,1,3) r=1)}. Fresh IR per call. */
    private static Expr sceneSdf(Expr point) {
        Expr ground = new Expr.VectorExtract(point, 1);                       // p.y
        Expr sphere = sub(Expr.MathCall.length(sub(point, v3(0, 1, 3))), f(1.0));
        return Expr.MathCall.min(ground, sphere);
    }

    // --- tiny IR-authoring helpers ---
    private static Expr f(double v) {
        return new Expr.ConstFloat(F32, v);
    }

    private static Expr v3(double x, double y, double z) {
        return new Expr.VectorConstruct(V3, List.of(f(x), f(y), f(z)));
    }

    private static Expr read(LocalVar v) {
        return new Expr.Read(v);
    }

    private static Expr add(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.ADD, a, b);
    }

    private static Expr sub(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.SUB, a, b);
    }

    private static Expr mul(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.MUL, a, b);
    }

    /** vec3 * scalar via broadcast (vector·scalar isn't a core primitive yet). */
    private static Expr mulS(Expr vec, Expr scalar) {
        return mul(vec, new Expr.VectorConstruct(V3, List.of(scalar, scalar, scalar)));
    }

    private Fathom() {
    }
}
