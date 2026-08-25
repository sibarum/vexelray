package dev.vexelray.technique.sdf;

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
import dev.vexelray.shader.Bindings;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.ShaderComposer;
import dev.vexelray.shader.ShadingPoint;
import dev.vexelray.surface.Field;
import dev.vexelray.surface.Ir;
import dev.vexelray.surface.SurfaceCompiler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an {@link SdfScene} into the vertex+fragment pair that renders it — the composer
 * {@link ShaderComposer}'s javadoc has been describing all along: <em>"an SDF composer turns a signed-distance
 * scene into a fullscreen fragment for the ray-march path."</em>
 *
 * <p>The vertex stage is SupirVast's {@code Fullscreen} triangle, unchanged and independent of the scene. All
 * the scene-specific work is one fragment: sphere-trace the surface from a push-constant camera, shade the hit
 * through the scene's {@link dev.vexelray.shader.Shading} model, and miss to the sky.
 *
 * <p><b>The field is emitted once, as a called function.</b> A march touches the field at nine points per
 * pixel — every step, six normal taps, and the final hit test — and inlining it at each site multiplied a
 * field's shader size by roughly eight; D12 records one that reached 22 MB of SPIR-V that way. Emitting
 * {@code float sdf(vec3)} once and calling it moves the cost from code size to runtime, and it is what keeps a
 * normalised implicit affordable at all, given the derivative already multiplies the field (surface-compiler.md
 * §2.1). It also means the CPU lowers the identical function — render == sim, for a surface that may have been
 * typed in at runtime.
 */
public final class SdfComposer implements ShaderComposer<SdfScene> {

    /** Push-constant block size in bytes: {@code camX, camY, camZ, yaw, pitch, aspect} as six floats. */
    public static final int CAMERA_BYTES = 24;

    /** The name of the generated field function, for hosts that lower the same module on the CPU. */
    public static final String SDF_FUNCTION = "sdf";

    private static final Type.Float F32 = Ir.F32;

    @Override
    public List<ShaderStage> stages() {
        return List.of(ShaderStage.VERTEX, ShaderStage.FRAGMENT);
    }

    @Override
    public List<ComposedShader> compose(SdfScene scene) {
        return List.of(
                new ComposedShader(ShaderStage.VERTEX, Fullscreen.triangleVertexSpirv(), Fullscreen.ENTRY_POINT),
                new ComposedShader(ShaderStage.FRAGMENT, fragmentSpirv(scene), Fullscreen.ENTRY_POINT));
    }

    /**
     * The scene's distance field as a standalone {@code float sdf(vec3)} — the same function the fragment calls,
     * exposed so a host can lower it to the CPU and collide against exactly what it draws.
     */
    public static Function sdfFunction(SdfScene scene) {
        return SurfaceCompiler.compile(scene.surface()).asFunction(SDF_FUNCTION);
    }

    /** The compiled field, if a caller wants the Lipschitz bound along with the expression. */
    public static Field field(SdfScene scene) {
        return SurfaceCompiler.compile(scene.surface());
    }

    /**
     * The camera push-constant block, little-endian, matching the layout the generated fragment reads.
     *
     * @param aspect viewport width divided by height; kept a push constant so a window resize does not
     *               recompile the shader
     */
    public static byte[] cameraBytes(double x, double y, double z, double yaw, double pitch, double aspect) {
        ByteBuffer buffer = ByteBuffer.allocate(CAMERA_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat((float) x).putFloat((float) y).putFloat((float) z);
        buffer.putFloat((float) yaw).putFloat((float) pitch).putFloat((float) aspect);
        return buffer.array();
    }

    /** Compose and lower the ray-march fragment for {@code scene}. */
    public static byte[] fragmentSpirv(SdfScene scene) {
        MarchSettings march = scene.march();
        Function sdf = sdfFunction(scene);

        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, Ir.V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        PushConstants camera = new PushConstants(List.of(
                new PushConstants.Member("camX", F32),
                new PushConstants.Member("camY", F32),
                new PushConstants.Member("camZ", F32),
                new PushConstants.Member("yaw", F32),
                new PushConstants.Member("pitch", F32),
                new PushConstants.Member("aspect", F32)));

        Expr eye = Ir.v3(camera.read(0), camera.read(1), camera.read(2));
        Expr rayDirection = primaryRay(vUv, camera.read(3), camera.read(4), camera.read(5), scene.focalLength());

        LocalVar ro = new LocalVar("ro", Ir.V3);
        LocalVar rd = new LocalVar("rd", Ir.V3);
        LocalVar t = new LocalVar("t", F32);
        LocalVar i = new LocalVar("i", Type.int32());
        LocalVar p = new LocalVar("p", Ir.V3);
        LocalVar d = new LocalVar("d", F32);

        // One step: sample the field, advance by it (clamped), count the iteration.
        Region step = Region.of(
                new Statement.Assign(p, Ir.add(read(ro), Ir.scale(read(rd), read(t)))),
                new Statement.Assign(d, call(sdf, read(p))),
                new Statement.Assign(t, Ir.add(read(t), Ir.min(read(d), Ir.f(march.maxStep())))),
                new Statement.Assign(i, new Expr.Binary(BinaryOp.ADD, read(i), new Expr.ConstInt(Type.int32(), 1))));

        // Stop on the step budget OR the far plane. The far-plane half is what bounds an unbounded or periodic
        // implicit: without it such a field never stops being "somewhere ahead" and every sky pixel costs the
        // full budget.
        Expr keepMarching = new Expr.Binary(BinaryOp.LOGICAL_AND,
                new Expr.Binary(BinaryOp.LESS_THAN, read(i), new Expr.ConstInt(Type.int32(), march.steps())),
                new Expr.Binary(BinaryOp.LESS_THAN, read(t), Ir.f(march.farPlane())));

        Region body = Region.of(
                new Statement.DeclareVar(ro, eye),
                new Statement.DeclareVar(rd, rayDirection),
                new Statement.DeclareVar(t, Ir.f(0.0)),
                new Statement.DeclareVar(i, new Expr.ConstInt(Type.int32(), 0)),
                new Statement.DeclareVar(p, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(d, Ir.f(0.0)),
                new Statement.While(keepMarching, step),
                new Statement.Assign(p, Ir.add(read(ro), Ir.scale(read(rd), read(t)))),
                new Statement.Assign(d, call(sdf, read(p))),
                new Statement.If(hitTest(march, read(d), read(t)),
                        hit(scene, sdf, fragColor, p, rd, t),
                        miss(scene, fragColor)),
                new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule()
                .addFunction(sdf)                       // emitted once; called nine times per pixel
                .addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, Fullscreen.ENTRY_POINT).spirv();
    }

    /**
     * The primary ray for a pixel: screen coordinates through a focal length, pitched then yawed.
     *
     * <p>Horizontal screen coordinates are scaled by the aspect ratio, so a wide window shows more of the world
     * sideways rather than stretching what it already had.
     */
    private static Expr primaryRay(InterfaceVar vUv, Expr yaw, Expr pitch, Expr aspect, double focalLength) {
        Expr u = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 0);
        Expr v = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 1);
        Expr sx = Ir.mul(Ir.sub(Ir.mul(u, Ir.f(2.0)), Ir.f(1.0)), aspect);
        Expr sy = Ir.sub(Ir.f(1.0), Ir.mul(v, Ir.f(2.0)));
        Expr focal = Ir.f(focalLength);

        Expr cosPitch = Expr.MathCall.cos(pitch);
        Expr sinPitch = Expr.MathCall.sin(pitch);
        Expr py = Ir.sub(Ir.mul(sy, cosPitch), Ir.mul(focal, sinPitch));
        Expr pz = Ir.add(Ir.mul(sy, sinPitch), Ir.mul(focal, cosPitch));

        Expr cosYaw = Expr.MathCall.cos(yaw);
        Expr sinYaw = Expr.MathCall.sin(yaw);
        Expr rx = Ir.add(Ir.mul(sx, cosYaw), Ir.mul(pz, sinYaw));
        Expr rz = Ir.sub(Ir.mul(pz, cosYaw), Ir.mul(sx, sinYaw));

        return Expr.MathCall.normalize(Ir.v3(rx, py, rz));
    }

    /** Distance-relative hit threshold: a far pixel covers more world, so it may not demand the same precision. */
    private static Expr hitTest(MarchSettings march, Expr distance, Expr travelled) {
        return new Expr.Binary(BinaryOp.LESS_THAN, distance,
                Ir.add(Ir.f(march.hitEpsilon()), Ir.mul(Ir.f(march.hitEpsilonSlope()), travelled)));
    }

    private static Region hit(SdfScene scene, Function sdf, InterfaceVar fragColor,
                              LocalVar p, LocalVar rd, LocalVar t) {
        // Finite-difference normal, sampled at a width that grows with distance. At a fixed near-field width a
        // far hit point's neighbours differ only by float noise, so normalize() amplifies it and the normal
        // flips sign — black scribbles across distant grazing slopes. Widening makes the normal describe the
        // surface at the pixel's actual scale: crisp near, broad far.
        MarchSettings march = scene.march();
        RegionBindings bindings = new RegionBindings();
        Expr width = Ir.add(Ir.f(march.normalEpsilon()), Ir.mul(Ir.f(march.normalEpsilonSlope()), read(t)));

        // Bound before it reaches the shading model. This expression is six calls into the distance field, and a
        // model is free to reference the normal more than once — unbound, each reference would drag another six
        // calls in with it.
        Expr normal = bindings.bind("normal", Expr.MathCall.normalize(Ir.v3(
                centralDifference(sdf, p, Ir.v3(width, Ir.f(0.0), Ir.f(0.0))),
                centralDifference(sdf, p, Ir.v3(Ir.f(0.0), width, Ir.f(0.0))),
                centralDifference(sdf, p, Ir.v3(Ir.f(0.0), Ir.f(0.0), width)))));

        SdfScene.Rgb albedo = scene.albedo();
        Expr shaded = scene.shading().shade(ShadingPoint.diffuse(
                read(p), normal, Ir.neg(read(rd)), Ir.v3(albedo.r(), albedo.g(), albedo.b())), bindings);

        LocalVar colour = new LocalVar("colour", Ir.V3);
        List<Statement> statements = new ArrayList<>(bindings.statements());
        statements.add(new Statement.DeclareVar(colour, shaded));
        statements.add(new Statement.InterfaceWrite(fragColor, opaque(read(colour))));
        return new Region(statements);
    }

    /**
     * {@link Bindings} backed by a list of declarations the composer splices in ahead of their uses.
     *
     * <p>Names are made unique by a counter rather than trusted from the caller, so two models — or one model
     * binding twice — cannot collide. The counter advances in composition order, which is deterministic, so
     * composing the same scene twice still yields byte-identical SPIR-V.
     */
    private static final class RegionBindings implements Bindings {

        private final List<Statement> statements = new ArrayList<>();
        private int next;

        @Override
        public Expr bind(String name, Expr value) {
            LocalVar variable = new LocalVar(name + "_" + next++, value.type());
            statements.add(new Statement.DeclareVar(variable, value));
            return new Expr.Read(variable);
        }

        List<Statement> statements() {
            return statements;
        }
    }

    private static Region miss(SdfScene scene, InterfaceVar fragColor) {
        SdfScene.Rgb sky = scene.sky();
        return Region.of(new Statement.InterfaceWrite(fragColor,
                opaque(Ir.v3(sky.r(), sky.g(), sky.b()))));
    }

    /** {@code sdf(p + offset) - sdf(p - offset)} — one axis of the gradient, by central difference. */
    private static Expr centralDifference(Function sdf, LocalVar p, Expr offset) {
        return Ir.sub(call(sdf, Ir.add(read(p), offset)), call(sdf, Ir.sub(read(p), offset)));
    }

    private static Expr opaque(Expr colour) {
        return new Expr.VectorConstruct(Ir.V4, List.of(
                new Expr.VectorExtract(colour, 0),
                new Expr.VectorExtract(colour, 1),
                new Expr.VectorExtract(colour, 2),
                Ir.f(1.0)));
    }

    private static Expr read(LocalVar v) {
        return new Expr.Read(v);
    }

    private static Expr call(Function sdf, Expr point) {
        return new Expr.Call(sdf, List.of(point));
    }
}
