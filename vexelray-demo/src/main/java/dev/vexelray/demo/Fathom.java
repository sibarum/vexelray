package dev.vexelray.demo;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.supirvast.vast.CoreToTruffle;
import com.oracle.truffle.api.CallTarget;

import java.lang.foreign.MemorySegment;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
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

        // Drop blank args so `exec:exec` can pass a possibly-empty ${fathom.args} property harmlessly.
        args = java.util.Arrays.stream(args).filter(s -> !s.isBlank()).toArray(String[]::new);

        String capture = null;
        int maxFrames = 0;
        if (args.length >= 1 && args[0].equals("--verify")) {
            verify();
            return;
        } else if (args.length >= 1 && args[0].equals("--demo")) {
            demoFilmstrip(platform, vertexSpirv, fragmentSpirv, args.length >= 2 ? args[1] : "fathom-walk.png");
            return;
        } else if (args.length >= 1 && args[0].equals("--capture")) {
            capture = args.length >= 2 ? args[1] : "fathom.png";
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
                         vertexSpirv, "main", fragmentSpirv, "main", 12);
                 WindowedPresenter presenter = new WindowedPresenter(device, swapchain, pipeline, window)) {
                // v2b: the camera auto-walks toward the sphere and collides against the SAME SDF on the CPU —
                // render/sim unity, live. (Interactive WASD lands next; keyboard input is the follow-up.)
                CallTarget cpu = new CoreToTruffle().lower(sdfFunction());
                float[] cam = {-0.6f, 1.2f, -3.0f};
                presenter.run(maxFrames, 12, (dt, pc) -> {
                    cam[2] += 1.2f * (float) dt;                 // stroll forward
                    resolveCollision(cpu, cam, 0.35f);          // stopped/slid by the field it's looking at
                    pc.set(JAVA_FLOAT, 0, cam[0]);
                    pc.set(JAVA_FLOAT, 4, cam[1]);
                    pc.set(JAVA_FLOAT, 8, cam[2]);
                });
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
                        0.10f, 0.12f, 0.16f, 1.0f, camBytes(0.0f, 1.2f, -3.0f));
                ImageIO.write(toImage(rgba, w, h), "PNG", new File(path));
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
        // Camera position comes from a push constant the host sets each frame (3 floats, 12 bytes) — see CAM_BYTES.
        PushConstants cam = new PushConstants(List.of(
                new PushConstants.Member("camX", F32),
                new PushConstants.Member("camY", F32),
                new PushConstants.Member("camZ", F32)));
        Expr camPos = new Expr.VectorConstruct(V3, List.of(cam.read(0), cam.read(1), cam.read(2)));

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
                new Statement.DeclareVar(ro, camPos),
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

    /**
     * Demonstrates render/sim unity: lower the SAME {@link #sceneSdf} to the CPU (SupirVast Truffle backend) and
     * both probe it and sphere-trace it. The GPU raymarches this field to draw the world; here the CPU evaluates
     * the identical IR — which is how collision, line-of-sight, and physics will query exactly what you see.
     */
    private static void verify() {
        CallTarget cpu = new CoreToTruffle().lower(sdfFunction());
        System.out.println("CPU-evaluating the SAME sceneSdf IR the GPU renders (SupirVast Truffle backend):");
        probe(cpu, 0.0f, 1.0f, 3.0f);    // sphere centre  -> ~ -1 (deep inside)
        probe(cpu, 0.0f, 1.5f, 3.0f);    // upper sphere   -> ~ -0.5 (inside)
        probe(cpu, 0.0f, 2.2f, 3.0f);    // just above it  -> ~ +0.2 (air)
        probe(cpu, 5.0f, 0.0f, 3.0f);    // on the floor   -> ~ 0
        probe(cpu, 0.0f, 4.0f, 3.0f);    // high air       -> ~ +2

        // CPU sphere-trace of the centre ray — a physics-style raycast against the render field, in plain Java
        // calling the shared IR each step. The GPU draws the sphere front near z=2; the CPU should find it there.
        float ox = 0.0f, oy = 1.2f, oz = -3.0f;
        float t = 0.0f;
        for (int i = 0; i < 80; i++) {
            t += cpuSdf(cpu, ox, oy, oz + t);
        }
        float residual = cpuSdf(cpu, ox, oy, oz + t);
        System.out.printf("  centre-ray march: t=%.3f  hit=(%.2f, %.2f, %.2f)  residual=%.4f  %s%n",
                t, ox, oy, oz + t, residual, residual < 0.01f ? "HIT" : "miss");
        System.out.println("render == sim: one field, drawn on the GPU and queried on the CPU.");
    }

    private static void probe(CallTarget cpu, float x, float y, float z) {
        float d = cpuSdf(cpu, x, y, z);
        System.out.printf("  sdf(%5.1f,%4.1f,%4.1f) = %+.3f  (%s)%n",
                x, y, z, d, d < 0 ? "inside geometry" : "empty space");
    }

    private static float cpuSdf(CallTarget cpu, float x, float y, float z) {
        return (Float) cpu.call((Object) new float[] {x, y, z});
    }

    /**
     * Sphere-trace character-controller resolve: if the player sphere (radius {@code r}) overlaps the field,
     * push it out along the SDF gradient by the penetration depth. A handful of CPU {@code sdf} calls against the
     * exact field the GPU renders. Mutates {@code cam} (x,y,z); y is kept fixed (eye height) by only nudging x/z.
     */
    private static void resolveCollision(CallTarget cpu, float[] cam, float r) {
        for (int iter = 0; iter < 2; iter++) {
            float d = cpuSdf(cpu, cam[0], cam[1], cam[2]);
            if (d < r) {
                float e = 0.01f;
                float gx = cpuSdf(cpu, cam[0] + e, cam[1], cam[2]) - cpuSdf(cpu, cam[0] - e, cam[1], cam[2]);
                float gz = cpuSdf(cpu, cam[0], cam[1], cam[2] + e) - cpuSdf(cpu, cam[0], cam[1], cam[2] - e);
                float g = (float) Math.sqrt(gx * gx + gz * gz) + 1e-6f;
                float push = r - d;
                cam[0] += (gx / g) * push;
                cam[2] += (gz / g) * push;
            }
        }
    }

    /**
     * The "live demo" surrogate: simulate walking the camera straight at the sphere, resolving collision on the
     * CPU against the SAME field the GPU renders, and render a horizontal filmstrip of the approach. The camera
     * stops at the sphere surface — you can see it stop growing — proving movement + render/sim-unity collision.
     */
    private static void demoFilmstrip(NativePlatform platform, byte[] vert, byte[] frag, String out)
            throws IOException {
        CallTarget cpu = new CoreToTruffle().lower(sdfFunction());
        float playerRadius = 0.35f;
        float[] cam = {0.0f, 1.2f, -3.0f};
        java.util.List<float[]> path = new java.util.ArrayList<>();
        path.add(cam.clone());
        for (int step = 0; step < 40; step++) {
            cam[2] += 0.15f;                              // try to walk forward (+z)
            resolveCollision(cpu, cam, playerRadius);    // pushed out of the sphere it can see
            path.add(cam.clone());
        }
        float[] end = path.get(path.size() - 1);
        System.out.printf("walk blocked at z=%.3f (sphere at z=3, player r=%.2f) — collision from the render SDF%n",
                end[2], playerRadius);

        int fw = 320;
        int fh = 320;
        int frames = 6;
        BufferedImage strip = new BufferedImage(fw * frames, fh, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = strip.createGraphics();
        try (VulkanInstance instance = new VulkanInstance("Fathom", platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            System.out.println("device: " + sel.deviceName());
            try (VulkanDevice device = new VulkanDevice(instance.handle(), sel)) {
                for (int k = 0; k < frames; k++) {
                    float[] p = path.get(k * (path.size() - 1) / (frames - 1));
                    byte[] rgba = OffscreenRenderer.render(device, fw, fh, vert, "main", frag, "main", 3,
                            0.10f, 0.12f, 0.16f, 1.0f, camBytes(p[0], p[1], p[2]));
                    g2.drawImage(toImage(rgba, fw, fh), k * fw, 0, null);
                }
            }
        }
        g2.dispose();
        ImageIO.write(strip, "PNG", new File(out));
        System.out.println("wrote filmstrip " + new File(out).getAbsolutePath());
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

    /** Camera position as 12 little-endian bytes (3 floats) for the push constant. */
    private static byte[] camBytes(float x, float y, float z) {
        return java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(x).putFloat(y).putFloat(z).array();
    }

    /** The scene SDF as a pure, CPU-lowerable {@code core} function {@code float sdf(vec3 p)} — same body as the shader. */
    private static Function sdfFunction() {
        return new Function("sdf", new Type.FunctionType(F32, List.of(V3)),
                Region.of(new Statement.Return(sceneSdf(new Expr.Param(0, V3)))));
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
