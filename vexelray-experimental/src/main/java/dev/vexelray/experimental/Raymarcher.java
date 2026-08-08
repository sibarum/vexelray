package dev.vexelray.experimental;

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
import dev.vexelray.shader.ComposedShader;

import java.util.List;

import static dev.vexelray.experimental.Ir.F32;
import static dev.vexelray.experimental.Ir.V2;
import static dev.vexelray.experimental.Ir.V3;
import static dev.vexelray.experimental.Ir.V4;
import static dev.vexelray.experimental.Ir.add;
import static dev.vexelray.experimental.Ir.f;
import static dev.vexelray.experimental.Ir.mul;
import static dev.vexelray.experimental.Ir.mulS3;
import static dev.vexelray.experimental.Ir.sub;
import static dev.vexelray.experimental.Ir.v3;

/**
 * The one ray-march every {@link ShapeField} is rendered through, so a comparison isolates the field. It composes
 * a fullscreen fragment that sphere-traces the field from a push-constant camera, shades it by a finite-difference
 * normal under one directional light, and misses to a flat sky. The only knob is the step budget, so the harness
 * can render a cheap candidate and an expensive reference of the <em>same</em> field to measure fidelity.
 *
 * <p>March hardening is fixed and identical for all fields: a clamped step (no leaping thin features) and a
 * distance-relative hit epsilon. Fields must supply conservative distance estimates (see {@link ShapeField#sdf}).
 * Camera push constants are 3 floats (camX, camY, camZ), 12 bytes — see {@link #CAM_BYTES}.
 */
public final class Raymarcher {

    /** Push-constant size in bytes: {@code vec3} camera position. */
    public static final int CAM_BYTES = 12;

    private Raymarcher() {
    }

    /** The scene SDF as a standalone {@code float sdf(vec3)} function — for CPU (Truffle) evaluation of the field. */
    public static Function sdfFunction(ShapeField field) {
        return new Function("sdf", new Type.FunctionType(F32, List.of(V3)),
                Region.of(new Statement.Return(field.sdf(new Expr.Param(0, V3)))));
    }

    /** Compose and lower the raymarch fragment for {@code field} with the given sphere-trace step budget. */
    public static byte[] fragmentSpirv(ShapeField field, int steps) {
        // Emit the field as ONE callable `float sdf(vec3)` function and call it, instead of inlining the field
        // expression at every use (march + 6 normal taps + hit). Inlining multiplied a field's shader size by ~8×;
        // a call keeps one copy, so cost moves from code-size to runtime. (The CPU benchmark lowers the same
        // function standalone, so render==sim is unaffected.)
        Function sdfFn = sdfFunction(field);

        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);
        PushConstants cam = new PushConstants(List.of(
                new PushConstants.Member("camX", F32),
                new PushConstants.Member("camY", F32),
                new PushConstants.Member("camZ", F32)));
        Expr camPos = new Expr.VectorConstruct(V3, List.of(cam.read(0), cam.read(1), cam.read(2)));

        Expr uvx = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 0);
        Expr uvy = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 1);
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
                new Statement.Assign(p, add(read(ro), mulS3(read(rd), read(t)))),
                new Statement.Assign(d, call(sdfFn, read(p))),
                // Clamp the step so a ray can't leap over a thin feature (overshoot = seam artifacts).
                new Statement.Assign(t, add(read(t), Expr.MathCall.min(read(d), f(0.4)))),
                new Statement.Assign(i, new Expr.Binary(BinaryOp.ADD, read(i), new Expr.ConstInt(Type.int32(), 1))));

        double eps = 0.02;   // finite-difference normal width: broad enough for smooth shading, tight enough for detail
        Expr n = Expr.MathCall.normalize(new Expr.VectorConstruct(V3, List.of(
                sub(call(sdfFn, add(read(p), v3(eps, 0, 0))), call(sdfFn, sub(read(p), v3(eps, 0, 0)))),
                sub(call(sdfFn, add(read(p), v3(0, eps, 0))), call(sdfFn, sub(read(p), v3(0, eps, 0)))),
                sub(call(sdfFn, add(read(p), v3(0, 0, eps))), call(sdfFn, sub(read(p), v3(0, 0, eps)))))));
        Expr light = v3(0.575, 0.766, -0.287);
        Expr diff = Expr.MathCall.max(Expr.MathCall.dot(n, light), f(0.0));
        Expr shade = Expr.MathCall.clamp(add(mul(diff, f(0.9)), f(0.1)), f(0.0), f(1.0));
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
                new Statement.While(new Expr.Binary(BinaryOp.LESS_THAN, read(i),
                        new Expr.ConstInt(Type.int32(), steps)), march),
                new Statement.Assign(p, add(read(ro), mulS3(read(rd), read(t)))),
                new Statement.Assign(d, call(sdfFn, read(p))),
                new Statement.If(new Expr.Binary(BinaryOp.LESS_THAN, read(d),
                        add(f(0.008), mul(f(0.001), read(t)))), hit, miss),
                new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule()
                .addFunction(sdfFn)   // the shared, called sdf — emitted once
                .addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    private static Expr read(LocalVar v) {
        return new Expr.Read(v);
    }

    /** Call the single {@code sdf} function with one {@code vec3} argument. */
    private static Expr call(Function sdfFn, Expr point) {
        return new Expr.Call(sdfFn, List.of(point));
    }
}
