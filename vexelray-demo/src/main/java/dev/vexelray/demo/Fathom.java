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
import sibarum.atchung.Atchung;
import sibarum.tactroller.api.BackendException;
import sibarum.tactroller.api.InputEvent;
import sibarum.tactroller.api.Key;
import sibarum.tactroller.api.PointerLockMode;
import sibarum.tactroller.api.Tactroller;
import sibarum.tactroller.atchung.TactrollerInputBridge;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.present.GraphicsPipeline;
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

    /** The terrain SDF as a callable core Function — emitted once, called (not inlined) at every SDF tap. */
    private static final Function TERRAIN_FN = buildTerrainFunction();

    /** The whole scene SDF as a callable core Function {@code float sdf(vec3)} — the one field the march calls. */
    private static final Function SDF_FN = sdfFunction();

    /**
     * THE ray-march, authored once as {@code core} IR: {@code float march(vec3 ro, vec3 rd)} returns the distance
     * travelled to the surface. This is lowered to SPIR-V for the GPU fragment AND to Truffle for CPU raycasts, so
     * the stepping loop is a single source of truth — the GPU and CPU cannot use different marchers. (Previously
     * the GPU marcher was inline IR and the CPU marcher was a hand-written Java loop; they silently diverged.)
     */
    private static final Function MARCH_FN = buildMarchFunction();

    public static void main(String[] args) throws IOException, BackendException {
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
             VulkanInstance instance = new VulkanInstance("Fathom", platform.requiredVulkanInstanceExtensions());
             Tactroller input = Tactroller.open()) {
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
                         vertexSpirv, "main", fragmentSpirv, "main", 20);
                 WindowedPresenter presenter = new WindowedPresenter(device, swapchain, renderPass.handle(),
                         pipeline, window)) {
                // v2c: WASD steers the camera; the CPU collides it against the SAME SDF the GPU renders —
                // render/sim unity, driven by you. (Facing is fixed +z for now; turning/mouse-look is next.)
                //
                // Input now flows through the suite's fabric rather than window.isKeyDown: Tactroller reads the
                // device, the tactroller-atchung bridge publishes discrete edges onto an Atchung! bus, and this
                // demo is just a consumer — it folds KeyPressed/KeyReleased into a held-set. Focus is arbitrated
                // by Tactroller (attached to the window's HWND), so movement only happens when Fathom is focused.
                input.attach(sibarum.tactroller.api.NativeWindow.ofHwnd(window.osHandle()));
                Atchung bus = Atchung.create();
                TactrollerInputBridge inputBridge = new TactrollerInputBridge(input, bus);
                java.util.EnumSet<Key> held = java.util.EnumSet.noneOf(Key.class);
                int[] lookDelta = {0, 0};                       // this frame's summed pointer motion, from the bus
                bus.subscribe(inputBridge.events(), e -> {
                    if (e instanceof InputEvent.KeyPressed k) {
                        held.add(k.key());
                    } else if (e instanceof InputEvent.KeyReleased k) {
                        held.remove(k.key());
                    } else if (e instanceof InputEvent.PointerMoved m) {
                        lookDelta[0] += m.dx();                 // sum: pump() may emit several between reads
                        lookDelta[1] += m.dy();
                    }
                });

                CallTarget cpu = lowerSdf();
                float[] cam = {0.0f, 1.2f, -3.0f};
                float[] look = {0.0f, 0.0f};                    // yaw, pitch (radians)
                boolean[] paused = {false};                     // Escape pauses look until the window is refocused
                boolean[] locked = {false};                     // tracks the RAW pointer-lock state we own
                final float lookSens = 0.0025f;                 // radians per pixel of mouse motion
                System.out.println("Click the window, then look with the mouse and move with WASD (Escape pauses "
                        + "look). Walk into the sphere — you cannot enter it.");
                presenter.run(maxFrames, 20, (dt, pc) -> {
                    // Input flows Tactroller -> tactroller-atchung bridge -> Atchung!, consumed here. Mouselook uses
                    // a RAW pointer lock: RawInput device deltas, so motion never clamps at the screen edge and the
                    // camera keeps every degree of freedom. We hold the lock only while actively looking and release
                    // it when paused/unfocused so the OS cursor reappears for interacting with the window.
                    //
                    // pump() is the SOLE drain of Tactroller's motion accumulator: it publishes this frame's delta as
                    // an InputEvent.PointerMoved, which our subscriber sums into lookDelta. We must not also call
                    // pollPointerDelta() — a second drain would race pump() for the same accumulator and win/lose at
                    // random, freezing the camera. So: reset the accumulator, set the lock mode, then pump.
                    lookDelta[0] = 0;
                    lookDelta[1] = 0;
                    boolean focused = input.isFocused();

                    if (held.contains(Key.ESCAPE)) {
                        paused[0] = true;
                    }
                    if (!focused) {
                        paused[0] = false;                      // regaining focus resumes look
                    }

                    // Reconcile the lock BEFORE pumping so this frame's snapshot drains in the right mode.
                    // lockPointer(RAW) zeroes the backend accumulator, so toggling never yields a stray jump.
                    boolean wantLock = focused && !paused[0];
                    try {
                        if (wantLock && !locked[0]) {
                            input.lockPointer(PointerLockMode.RAW);
                            locked[0] = true;
                        } else if (!wantLock && locked[0]) {
                            input.unlockPointer();
                            locked[0] = false;
                        }
                        inputBridge.pump();                     // snapshot -> publish edges + PointerMoved (fills lookDelta)
                    } catch (BackendException ex) {
                        throw new RuntimeException("input failed", ex);
                    }

                    if (wantLock) {
                        look[0] += lookDelta[0] * lookSens;     // yaw
                        look[1] = Math.max(-1.5f, Math.min(1.5f, look[1] + lookDelta[1] * lookSens)); // pitch (clamped)
                        float step = 2.5f * (float) dt;
                        float fx = (float) Math.sin(look[0]);   // forward = yaw direction (horizontal)
                        float fz = (float) Math.cos(look[0]);
                        float rrx = (float) Math.cos(look[0]);  // right = forward rotated -90°
                        float rrz = (float) -Math.sin(look[0]);
                        if (held.contains(Key.W)) { cam[0] += fx * step; cam[2] += fz * step; }
                        if (held.contains(Key.S)) { cam[0] -= fx * step; cam[2] -= fz * step; }
                        if (held.contains(Key.D)) { cam[0] += rrx * step; cam[2] += rrz * step; }
                        if (held.contains(Key.A)) { cam[0] -= rrx * step; cam[2] -= rrz * step; }
                    }

                    resolveCollision(cpu, cam, 0.35f);          // stopped/slid by the field it's looking at
                    pc.set(JAVA_FLOAT, 0, cam[0]);
                    pc.set(JAVA_FLOAT, 4, cam[1]);
                    pc.set(JAVA_FLOAT, 8, cam[2]);
                    pc.set(JAVA_FLOAT, 12, look[0]);            // yaw
                    pc.set(JAVA_FLOAT, 16, look[1]);            // pitch
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
                new PushConstants.Member("camZ", F32),
                new PushConstants.Member("yaw", F32),
                new PushConstants.Member("pitch", F32)));
        Expr camPos = new Expr.VectorConstruct(V3, List.of(cam.read(0), cam.read(1), cam.read(2)));
        Expr yaw = cam.read(3);
        Expr pitch = cam.read(4);

        Expr uvx = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 0);
        Expr uvy = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 1);
        // screen ray: x spans [-1,1], y flipped so up is +y; focal length 1.4
        Expr sx = sub(mul(uvx, f(2.0)), f(1.0));
        Expr sy = sub(f(1.0), mul(uvy, f(2.0)));
        // Base forward ray, then rotate by pitch (about X) then yaw (about Y) — mouselook. At yaw=pitch=0 this
        // reduces exactly to the old fixed +z forward, so an idle mouse renders identically to before.
        Expr cp = Expr.MathCall.cos(pitch);
        Expr sp = Expr.MathCall.sin(pitch);
        Expr py = sub(mul(sy, cp), mul(f(1.4), sp));      // pitched y
        Expr pz = add(mul(sy, sp), mul(f(1.4), cp));      // pitched z
        Expr cy = Expr.MathCall.cos(yaw);
        Expr syw = Expr.MathCall.sin(yaw);
        Expr rx = add(mul(sx, cy), mul(pz, syw));         // yawed x
        Expr rz = sub(mul(pz, cy), mul(sx, syw));         // yawed z
        Expr rdInit = Expr.MathCall.normalize(new Expr.VectorConstruct(V3, List.of(rx, py, rz)));

        LocalVar ro = new LocalVar("ro", V3);
        LocalVar rd = new LocalVar("rd", V3);
        LocalVar t = new LocalVar("t", F32);
        LocalVar p = new LocalVar("p", V3);
        LocalVar d = new LocalVar("d", F32);

        // Finite-difference normal at the hit point, with a DISTANCE-SCALED sampling radius. A far pixel's hit
        // lands up to the hit-epsilon (~0.008+0.0008·t) off the true surface, and its footprint covers many world
        // units; sampling the field at a fixed ~3cm eps there straddles sub-cell wiggles and off-surface error,
        // so normalize() amplifies noise and the normal flips sign — the black scribbles on distant grazing
        // slopes. Growing eps with t makes the normal reflect the surface curvature at the pixel's actual scale:
        // crisp up close, broad far away. (The sphere/glyph are near and large, so their normals stay sharp.)
        Expr eps = add(f(0.03), mul(f(0.006), read(t)));
        Expr ex = new Expr.VectorConstruct(V3, List.of(eps, f(0.0), f(0.0)));
        Expr ey = new Expr.VectorConstruct(V3, List.of(f(0.0), eps, f(0.0)));
        Expr ez = new Expr.VectorConstruct(V3, List.of(f(0.0), f(0.0), eps));
        Expr n = Expr.MathCall.normalize(new Expr.VectorConstruct(V3, List.of(
                sub(sdf(add(read(p), ex)), sdf(sub(read(p), ex))),
                sub(sdf(add(read(p), ey)), sdf(sub(read(p), ey))),
                sub(sdf(add(read(p), ez)), sdf(sub(read(p), ez))))));
        Expr light = v3(0.575, 0.766, -0.287);   // pre-normalized direction to the light
        Expr diff = Expr.MathCall.max(Expr.MathCall.dot(n, light), f(0.0));
        Expr shade = Expr.MathCall.clamp(add(mul(diff, f(0.92)), f(0.08)), f(0.0), f(1.0));
        Region hit = Region.of(new Statement.InterfaceWrite(fragColor,
                new Expr.VectorConstruct(V4, List.of(shade, shade, shade, f(1.0)))));
        Region miss = Region.of(new Statement.InterfaceWrite(fragColor,
                new Expr.VectorConstruct(V4, List.of(f(0.10), f(0.12), f(0.16), f(1.0)))));

        Region body = Region.of(
                new Statement.DeclareVar(ro, camPos),
                new Statement.DeclareVar(rd, rdInit),
                // The march is the shared MARCH_FN — the SAME core function the CPU raycast calls. The stepping
                // loop lives only in that function now, so the GPU and CPU marchers cannot drift apart.
                new Statement.DeclareVar(t, new Expr.Call(MARCH_FN, List.of(read(ro), read(rd)))),
                new Statement.DeclareVar(p, add(read(ro), mulS(read(rd), read(t)))),
                new Statement.DeclareVar(d, sdf(read(p))),
                // distance-relative hit epsilon: threshold grows with march distance t so grazing rays that run out
                // of step budget short of the surface still register as a hit instead of leaking sky-coloured streaks.
                new Statement.If(new Expr.Binary(BinaryOp.LESS_THAN, read(d), add(f(0.008), mul(f(0.0008), read(t)))), hit, miss),
                new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule()
                .addFunction(TERRAIN_FN).addFunction(SDF_FN).addFunction(MARCH_FN)
                .addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    /**
     * Demonstrates render/sim unity: lower the SAME {@link #sceneSdf} to the CPU (SupirVast Truffle backend) and
     * both probe it and sphere-trace it. The GPU raymarches this field to draw the world; here the CPU evaluates
     * the identical IR — which is how collision, line-of-sight, and physics will query exactly what you see.
     */
    private static void verify() {
        CallTarget cpu = lowerSdf();
        System.out.println("CPU-evaluating the SAME sceneSdf IR the GPU renders (SupirVast Truffle backend):");
        probe(cpu, 0.0f, 1.0f, 3.0f);    // sphere centre  -> ~ -1 (deep inside)
        probe(cpu, 0.0f, 1.5f, 3.0f);    // upper sphere   -> ~ -0.5 (inside)
        probe(cpu, 0.0f, 2.2f, 3.0f);    // just above it  -> ~ +0.2 (air)
        probe(cpu, 5.0f, 0.0f, 3.0f);    // on the floor   -> ~ 0
        probe(cpu, 0.0f, 4.0f, 3.0f);    // high air       -> ~ +2

        // CPU raycast of the centre ray — a physics-style raycast against the render field. Crucially this is NOT
        // a hand-written Java march: it calls the SAME MARCH_FN core function the GPU fragment calls, lowered to
        // Truffle. The stepping loop is single-source, so "the CPU marched differently than the GPU" is now
        // unrepresentable. The GPU draws the sphere front near z=2; the CPU finds it at the identical t.
        CallTarget march = lowerMarch();
        float ox = 0.0f, oy = 1.2f, oz = -3.0f;
        float t = (Float) march.call(new float[] {ox, oy, oz}, new float[] {0.0f, 0.0f, 1.0f});
        float residual = cpuSdf(cpu, ox, oy, oz + t);
        System.out.printf("  centre-ray march (shared MARCH_FN): t=%.3f  hit=(%.2f, %.2f, %.2f)  residual=%.4f  %s%n",
                t, ox, oy, oz + t, residual, residual < 0.02f ? "HIT" : "miss");
        System.out.println("render == sim: one field AND one marcher, run on the GPU and on the CPU.");
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
        CallTarget cpu = lowerSdf();
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

    /** Camera position + orientation as 20 little-endian bytes (5 floats: x,y,z,yaw,pitch). */
    private static byte[] camBytes(float x, float y, float z) {
        return java.nio.ByteBuffer.allocate(20).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putFloat(x).putFloat(y).putFloat(z).putFloat(0.0f).putFloat(0.0f).array();
    }

    /** Lower the shared scene SDF for the CPU (Truffle), including the called terrain function in the module. */
    private static CallTarget lowerSdf() {
        return new CoreToTruffle().lowerModule(List.of(TERRAIN_FN, SDF_FN), SDF_FN);
    }

    /**
     * Lower the shared {@link #MARCH_FN} for the CPU (Truffle) — the SAME marcher the GPU fragment calls. Its
     * module carries the whole call graph it reaches ({@code march -> sdf -> terrain}). Calling the returned
     * target is {@code march(float[] ro, float[] rd) -> Float t}.
     */
    private static CallTarget lowerMarch() {
        return new CoreToTruffle().lowerModule(List.of(TERRAIN_FN, SDF_FN, MARCH_FN), MARCH_FN);
    }

    /** The scene SDF as a pure, CPU-lowerable {@code core} function {@code float sdf(vec3 p)} — same body as the shader. */
    private static Function sdfFunction() {
        return new Function("sdf", new Type.FunctionType(F32, List.of(V3)),
                Region.of(new Statement.Return(sceneSdf(new Expr.Param(0, V3)))));
    }

    /** Call the shared scene {@link #SDF_FN} at a point — the one field both the march and the shading normal use. */
    private static Expr sdf(Expr point) {
        return new Expr.Call(SDF_FN, List.of(point));
    }

    /**
     * The scene SDF: ground plane + sphere + a round-extruded 2D "V" glyph — the SDF-native "sprite". Because
     * the glyph is just another term in this one field, it renders (GPU), collides (CPU), and casts shadows from
     * the same source — a flat shape given real thickness, that you can walk around. Fresh IR per call.
     */
    private static Expr sceneSdf(Expr point) {
        Expr ground = new Expr.Call(TERRAIN_FN, List.of(point));
        Expr sphere = sub(Expr.MathCall.length(sub(point, v3(0.0, 1.0, 3.0))), f(1.0));
        Expr q = sub(point, v3(-1.2, 1.1, 1.5));                 // glyph local space (left of the sphere, facing us)
        Expr glyph = extrudeRounded(q, glyphV(q), 0.06, 0.04);   // thin paper with rounded edges
        return Expr.MathCall.min(Expr.MathCall.min(ground, sphere), glyph);
    }

    /**
     * The floor, built the SDF-native way: a base plane smooth-unioned with a field of <em>exact</em> sphere
     * primitives placed on a jittered grid. This is NOT a heightfield {@code y - h(x,z)} — a heightfield is a
     * function graph (a polygon-terrain concept) that isn't a real distance field, which is the whole source of
     * the overshoot/Lipschitz/gradient-correction pain. Here every term is a true SDF and {@code smin} keeps the
     * combined field well-behaved (it under-estimates distance near a blend, which is <em>safe</em> for sphere
     * tracing — it can only understep, never overshoot). So: no Lipschitz factor, no gradient correction, no
     * noise, no seam cracks — and a small shader. Only the 3x3 grid neighbourhood around the point is evaluated.
     * The same field renders on the GPU and collides on the CPU — render == sim.
     */
    private static Function buildTerrainFunction() {
        Expr point = new Expr.Param(0, V3);
        Expr px = x(point);
        Expr pz = z(point);
        double cell = 3.5;
        double k = 0.4;                                   // smooth-union blend radius (thin halo)
        double radius = 1.5;
        Expr gx = Expr.MathCall.floor(div(px, f(cell)));
        Expr gz = Expr.MathCall.floor(div(pz, f(cell)));
        LocalVar d = new LocalVar("d", F32);
        java.util.List<Statement> body = new java.util.ArrayList<>();
        body.add(new Statement.DeclareVar(d, add(y(point), f(0.5))));   // base plane at y=-0.5
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                Expr cx = add(gx, f(dx));
                Expr cz = add(gz, f(dz));
                Expr wx = mul(add(cx, hash(cx, cz, 0.0)), f(cell));      // jittered blob centre, world x
                Expr wz = mul(add(cz, hash(cx, cz, 19.1)), f(cell));     //                        world z
                Expr wy = sub(mul(hash(cx, cz, 41.7), f(0.7)), f(1.7)); // centre [-1.7,-1.0] -> tops [-0.2,0.5]
                Expr centre = new Expr.VectorConstruct(V3, List.of(wx, wy, wz));
                Expr sphere = sub(Expr.MathCall.length(sub(point, centre)), f(radius));
                // Accumulate into a LOCAL variable — read(d) is a leaf, so no expression-tree duplication
                // across the 9 blobs (a chained pure-expression smin would blow up exponentially).
                body.add(new Statement.Assign(d, smin(read(d), sphere, k)));
            }
        }
        body.add(new Statement.Return(read(d)));
        return new Function("terrain", new Type.FunctionType(F32, List.of(V3)),
                Region.of(body.toArray(new Statement[0])));
    }

    /**
     * THE sphere-tracer as a callable {@code core} function {@code float march(vec3 ro, vec3 rd)}: step along the
     * ray by the FULL scene distance each iteration (the field is a conservative SDF, so a step of length d never
     * overshoots), for a fixed budget, and return the marched distance t. Both the GPU fragment and the CPU
     * raycast call this exact function — the stepping loop exists once, in IR, and lowers to both backends. There
     * is deliberately no per-backend copy of this loop; that is what let the GPU and CPU marchers drift before.
     */
    private static Function buildMarchFunction() {
        Expr ro = new Expr.Param(0, V3);
        Expr rd = new Expr.Param(1, V3);
        LocalVar t = new LocalVar("t", F32);
        LocalVar i = new LocalVar("i", Type.int32());
        LocalVar p = new LocalVar("p", V3);
        LocalVar d = new LocalVar("d", F32);

        Region step = Region.of(
                new Statement.Assign(p, add(ro, mulS(rd, read(t)))),
                new Statement.Assign(d, new Expr.Call(SDF_FN, List.of(read(p)))),
                // Sphere tracing, advancing by the reported distance, clamped to the terrain's conservative
                // bound. The sphere/glyph are exact global SDFs, but the terrain only evaluates its 3x3 cell
                // neighbourhood, so it OVER-estimates distance once the true nearest blob sits outside that
                // window — non-conservative, and an over-estimate lets a full-distance step leap a hilltop
                // (overshoot). The window is provably safe while d < cell-radius = 3.5-1.5 = 2.0, so clamping the
                // step to 1.8 (a margin under that) keeps every step overshoot-free. This is NOT the old 0.06
                // heightmap relic: its value is the field's real conservative radius, ~33x larger, so open-space
                // striding stays fast. max(.,0) freezes the ray on a numerical d<0 for the caller's hit test.
                new Statement.Assign(t, add(read(t),
                        Expr.MathCall.max(Expr.MathCall.min(read(d), f(1.8)), f(0.0)))),
                new Statement.Assign(i, new Expr.Binary(BinaryOp.ADD, read(i), new Expr.ConstInt(Type.int32(), 1))));

        Region body = Region.of(
                new Statement.DeclareVar(t, f(0.0)),
                new Statement.DeclareVar(i, new Expr.ConstInt(Type.int32(), 0)),
                new Statement.DeclareVar(p, v3(0, 0, 0)),
                new Statement.DeclareVar(d, f(0.0)),
                new Statement.While(new Expr.Binary(BinaryOp.LESS_THAN, read(i),
                        new Expr.ConstInt(Type.int32(), 256)), step),
                new Statement.Return(read(t)));
        return new Function("march", new Type.FunctionType(F32, List.of(V3, V3)), body);
    }

    /**
     * A 2D value hash in [0,1) for a grid cell, seeded so several independent jitters can be drawn per cell.
     *
     * <p>Deliberately NOT the classic {@code fract(sin(dot(...)) * 43758.5)}: that hash is not portable across
     * backends. {@code sin} of a moderately large argument evaluates differently in GPU float32 than in CPU
     * float64, and the {@code ×43758} amplifies that tiny difference past the {@code fract}, so a cell can get a
     * completely different blob position on the GPU than on the CPU. That broke render==sim and scattered
     * degenerate blobs on the GPU only — the dark diagonal slashes. This is a Dave Hoskins-style hash using only
     * multiply/add/fract on small-magnitude values, so it is bit-stable enough to agree on both backends.
     */
    private static Expr hash(Expr cx, Expr cz, double seed) {
        Expr h = Expr.MathCall.fract(mul(
                add(add(mul(cx, f(0.1031)), mul(cz, f(0.11369))), f(0.13787 + seed * 0.0173)),
                f(0.1031)));
        h = mul(h, add(h, f(33.33)));
        h = mul(h, add(h, h));
        return Expr.MathCall.fract(h);
    }

    /** Polynomial smooth-minimum: blends two SDFs over radius k, under-estimating near the blend (overshoot-safe). */
    private static Expr smin(Expr a, Expr b, double k) {
        Expr hh = Expr.MathCall.clamp(add(f(0.5), mul(f(0.5 / k), sub(b, a))), f(0.0), f(1.0));
        return sub(Expr.MathCall.mix(b, a, hh), mul(f(k), mul(hh, sub(f(1.0), hh))));
    }

    /** The 2D "V" field in the local xy-plane: two strokes (capsules) meeting at the bottom, given a half-width. */
    private static Expr glyphV(Expr q) {
        Expr q2 = new Expr.VectorConstruct(V2, List.of(x(q), y(q)));
        Expr left = sdSegment2(q2, -0.35, 0.5, 0.0, -0.5);
        Expr right = sdSegment2(q2, 0.35, 0.5, 0.0, -0.5);
        return sub(Expr.MathCall.min(left, right), f(0.09));     // stroke half-width
    }

    /** Distance from a 2D point to the segment a→b (a capsule spine). Uses dot/clamp/length — CPU + GPU. */
    private static Expr sdSegment2(Expr p, double ax, double ay, double bx, double by) {
        Expr a = v2(ax, ay);
        Expr b = v2(bx, by);
        Expr pa = sub(p, a);
        Expr ba = sub(b, a);
        Expr h = Expr.MathCall.clamp(div(Expr.MathCall.dot(pa, ba), Expr.MathCall.dot(ba, ba)), f(0.0), f(1.0));
        return Expr.MathCall.length(sub(pa, mulS2(ba, h)));
    }

    /** Round-extrude a 2D field {@code g} along local z with half-depth {@code h}, rounding edges by {@code r}. */
    private static Expr extrudeRounded(Expr q, Expr g, double h, double r) {
        Expr wy = sub(Expr.MathCall.abs(z(q)), f(h));            // |q.z| - h
        Expr w = new Expr.VectorConstruct(V2, List.of(g, wy));
        Expr outside = Expr.MathCall.length(Expr.MathCall.max(w, v2(0.0, 0.0)));
        Expr inside = Expr.MathCall.min(Expr.MathCall.max(g, wy), f(0.0));
        return sub(add(inside, outside), f(r));
    }

    // --- tiny IR-authoring helpers ---
    private static Expr f(double v) {
        return new Expr.ConstFloat(F32, v);
    }

    private static Expr v3(double x, double y, double z) {
        return new Expr.VectorConstruct(V3, List.of(f(x), f(y), f(z)));
    }

    private static Expr v2(double a, double b) {
        return new Expr.VectorConstruct(V2, List.of(f(a), f(b)));
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

    private static Expr div(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.DIV, a, b);
    }

    /** vec2 * scalar via broadcast. */
    private static Expr mulS2(Expr vec, Expr scalar) {
        return mul(vec, new Expr.VectorConstruct(V2, List.of(scalar, scalar)));
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
