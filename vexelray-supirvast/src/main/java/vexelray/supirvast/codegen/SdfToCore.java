package vexelray.supirvast.codegen;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Expr.MathCall;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.lower.CoreToSpirv;
import dev.supirvast.vastir.type.Type;

import vexelray.supirvast.sdf.Primitive;
import vexelray.supirvast.sdf.SdfScene;
import vexelray.supirvast.sdf.Vec3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Lowers an {@link SdfScene} (box CSG) to Supir-Vast {@code core} IR — the integration
 * seam between the editor's scene model and the shader pipeline.
 *
 * <p>{@link #distanceFunction} emits a {@code float sdScene(float px, float py, float pz)}
 * that evaluates the scene's signed-distance field via the same sequential
 * union/subtract/intersect (+ smooth) fold over per-box {@code sdBox} terms that
 * dasum-vis's {@code CsgField} / {@code vexelray.frag} use, so CPU and GPU agree.
 * {@link #toSpirv} wraps it in a compute entry point and lowers the whole module to
 * validated SPIR-V — proving the scene round-trips through the real Supir-Vast backend.
 *
 * <p>Only {@link Primitive.Kind#BOX} is emitted today; other kinds throw until their
 * {@code sdXxx} term is added here (and in the renderer).
 */
public final class SdfToCore {

    private SdfToCore() {}

    private static final Type.Float F32 = Type.float32();

    // ---- tiny Expr builders ----
    private static Expr cf(double v) { return new Expr.ConstFloat(F32, v); }
    private static Expr add(Expr a, Expr b) { return new Expr.Binary(BinaryOp.ADD, a, b); }
    private static Expr sub(Expr a, Expr b) { return new Expr.Binary(BinaryOp.SUB, a, b); }
    private static Expr mul(Expr a, Expr b) { return new Expr.Binary(BinaryOp.MUL, a, b); }
    private static Expr div(Expr a, Expr b) { return new Expr.Binary(BinaryOp.DIV, a, b); }
    private static Expr neg(Expr a) { return sub(cf(0), a); }
    private static Expr read(LocalVar v) { return new Expr.Read(v); }

    /**
     * Build {@code float sdScene(float px, float py, float pz)} for the scene.
     */
    public static Function distanceFunction(SdfScene scene) {
        Expr px = new Expr.Param(0, F32);
        Expr py = new Expr.Param(1, F32);
        Expr pz = new Expr.Param(2, F32);

        List<Statement> body = new ArrayList<>();
        LocalVar d = new LocalVar("d", F32);
        body.add(new Statement.DeclareVar(d, cf(1e9))); // empty field = "far away"

        int i = 0;
        for (Primitive p : scene.primitives()) {
            if (p.kind() != Primitive.Kind.BOX) {
                throw new IllegalArgumentException("SdfToCore supports BOX only (got " + p.kind() + ")");
            }
            LocalVar di = new LocalVar("di" + i, F32);
            body.add(new Statement.DeclareVar(di, sdBox(px, py, pz, p.center(), p.halfExtents())));
            appendFold(body, d, read(di), p, i);
            i++;
        }

        body.add(new Statement.Return(sub(read(d), cf(scene.rounding()))));

        Type.FunctionType sig = new Type.FunctionType(F32, List.of(F32, F32, F32));
        return new Function("sdScene", sig, new Region(body));
    }

    /** {@code d = op(d, di)} for one primitive, appended as statements (smooth ops need a temp). */
    private static void appendFold(List<Statement> body, LocalVar d, Expr di, Primitive p, int i) {
        Expr dR = read(d);
        switch (p.op()) {
            case UNION            -> body.add(new Statement.Assign(d, MathCall.min(dR, di)));
            case SUBTRACT         -> body.add(new Statement.Assign(d, MathCall.max(dR, neg(di))));
            case INTERSECT        -> body.add(new Statement.Assign(d, MathCall.max(dR, di)));
            case SMOOTH_UNION     -> sminInto(body, d, dR, di, p.smoothK(), i);
            // smax(a, b, k) = -smin(-a, -b, k); subtract uses b = -di, intersect uses b = di.
            case SMOOTH_SUBTRACT  -> smaxInto(body, d, dR, neg(di), p.smoothK(), i);
            case SMOOTH_INTERSECT -> smaxInto(body, d, dR, di, p.smoothK(), i);
        }
    }

    /** d = smin(a, b, k) = mix(b, a, h) - k*h*(1-h), h = clamp(0.5 + 0.5*(b-a)/k, 0, 1). */
    private static void sminInto(List<Statement> body, LocalVar d, Expr a, Expr b, float k, int i) {
        LocalVar h = new LocalVar("h" + i, F32);
        body.add(new Statement.DeclareVar(h,
            MathCall.clamp(add(cf(0.5), mul(cf(0.5), div(sub(b, a), cf(k)))), cf(0), cf(1))));
        Expr hR = read(h);
        body.add(new Statement.Assign(d,
            sub(MathCall.mix(b, a, hR), mul(cf(k), mul(hR, sub(cf(1), hR))))));
    }

    /** d = smax(a, b, k) = -smin(-a, -b, k). */
    private static void smaxInto(List<Statement> body, LocalVar d, Expr a, Expr b, float k, int i) {
        Expr na = neg(a), nb = neg(b);
        LocalVar h = new LocalVar("h" + i, F32);
        body.add(new Statement.DeclareVar(h,
            MathCall.clamp(add(cf(0.5), mul(cf(0.5), div(sub(nb, na), cf(k)))), cf(0), cf(1))));
        Expr hR = read(h);
        body.add(new Statement.Assign(d,
            neg(sub(MathCall.mix(nb, na, hR), mul(cf(k), mul(hR, sub(cf(1), hR)))))));
    }

    /** Signed distance to an axis-aligned box (exact, mirrors the GLSL sdBox). */
    private static Expr sdBox(Expr px, Expr py, Expr pz, Vec3f c, Vec3f h) {
        Expr qx = sub(MathCall.abs(sub(px, cf(c.x()))), cf(h.x()));
        Expr qy = sub(MathCall.abs(sub(py, cf(c.y()))), cf(h.y()));
        Expr qz = sub(MathCall.abs(sub(pz, cf(c.z()))), cf(h.z()));
        Expr ox = MathCall.max(qx, cf(0));
        Expr oy = MathCall.max(qy, cf(0));
        Expr oz = MathCall.max(qz, cf(0));
        Expr outside = MathCall.sqrt(add(add(mul(ox, ox), mul(oy, oy)), mul(oz, oz)));
        Expr inside = MathCall.min(MathCall.max(qx, MathCall.max(qy, qz)), cf(0));
        return add(outside, inside);
    }

    /**
     * A self-contained {@link CoreModule}: the {@code sdScene} helper plus a compute
     * {@code main} that samples it at the origin and stores the bit-pattern to an int
     * storage buffer. Enough to lower the whole thing to SPIR-V end-to-end.
     */
    public static CoreModule toModule(SdfScene scene) {
        Function sdScene = distanceFunction(scene);

        Buffer out = new Buffer("out", 0, Type.int32());
        Expr gid = new Expr.InvocationId();
        Expr dist = new Expr.Call(sdScene, List.of(cf(0), cf(0), cf(0)));
        Expr bits = new Expr.Bitcast(dist, Type.int32());

        Function main = new Function("main",
            new Type.FunctionType(Type.VOID, List.of()),
            Region.of(new Statement.BufferStore(out, gid, bits), new Statement.ReturnVoid()));

        return new CoreModule()
            .addFunction(sdScene)
            .addEntryPoint(EntryPoint.compute(main, 1, 1, 1));
    }

    /** Lower the scene to validated SPIR-V binary via the Supir-Vast backend. */
    public static byte[] toSpirv(SdfScene scene) {
        return new CoreToSpirv().lower(toModule(scene)).toByteArray();
    }

    /** Manual verification: build a sample scene, lower it, and report the SPIR-V size. */
    public static void main(String[] args) {
        SdfScene scene = new SdfScene(0.03f);
        scene.add(Primitive.box(vexelray.supirvast.sdf.BoolOp.UNION,
            Vec3f.ZERO, new Vec3f(0.5f, 0.5f, 0.5f), 0f));
        scene.add(Primitive.box(vexelray.supirvast.sdf.BoolOp.SMOOTH_SUBTRACT,
            new Vec3f(0.3f, 0.3f, 0.3f), new Vec3f(0.35f, 0.35f, 0.35f), 0.1f));

        byte[] spirv = toSpirv(scene);
        System.out.printf("SdfToCore: %d primitive(s) -> SPIR-V %d bytes (%d words)%n",
            scene.size(), spirv.length, spirv.length / 4);
    }
}
