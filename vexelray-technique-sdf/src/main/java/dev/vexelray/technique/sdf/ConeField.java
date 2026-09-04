package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Buffer;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.MathFn;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.vexelray.shader.ComposedShader;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;
import dev.vexelray.surface.Cones;

/**
 * A distance field whose geometry is <b>data</b>: a {@code float sdf(vec3)} that loops over round cones read from
 * a storage buffer, instead of one with the cones compiled into it.
 *
 * <h2>The point of it</h2>
 *
 * <p>{@code SurfaceCompiler} unrolls a scene into the shader — every cone becomes its own tree of arithmetic,
 * folded against its own constants, and the whole lot is min-ed together. That is the fastest thing to
 * <em>march</em>, because every coefficient is a literal. It is also why a curve of a few hundred segments
 * lowers to some hundreds of kilobytes of SPIR-V, and why building a pipeline from it was measured at
 * <b>five seconds</b> — on the frame loop, which is one thread for every window, so the whole application
 * stopped for it.
 *
 * <p>This trades that back. The arithmetic is the same formula, but its coefficients are loaded per cone per
 * step rather than folded, so a march costs more; in exchange the module is a fixed size and, far more
 * importantly, <b>the same bytes for every scene</b>. One pipeline, built once when the window opens. A new
 * expression becomes a buffer copy.
 *
 * <h2>The layout</h2>
 *
 * <p>One buffer of floats at set 0, binding {@link #BINDING}. The first {@link Cones#FLOATS} floats are a header
 * whose first element is the cone count; cone {@code i} then occupies
 * {@code [FLOATS*(i+1), FLOATS*(i+2))} as {@code ax, ay, az, ar, bx, by, bz, br}. See {@link #buffer}.
 *
 * <p>The count lives in the buffer rather than in a push constant so that this composes with the ordinary
 * {@link SdfComposer#cameraBytes} block unchanged — the camera is still six floats and nothing else had to learn
 * about cones. It is read once per {@code sdf} call, from an address every invocation in the group shares, which
 * is the cheapest kind of load there is.
 *
 * <h2>One primitive, no cases</h2>
 *
 * <p>{@link Cones} has already turned a degenerate piece — one end sphere swallowing the other — into a cone
 * whose ends coincide, so the loop below has no sphere case. It gets away with that because the axis length is
 * clamped to {@link #AXIS_EPSILON} rather than used raw: at a coincident axis the formula's three regions
 * collapse and the surviving branch evaluates to {@code |p - a| - r}, which is the sphere. A branch per cone
 * saved is worth a clamp per cone spent.
 */
public final class ConeField {

    /** The descriptor binding, at set 0, that {@link #buffer} is read from. */
    public static final int BINDING = 0;

    /** Floats of header before the first cone; its first element is the count. */
    public static final int HEADER_FLOATS = Cones.FLOATS;

    /**
     * The clamp on a cone's squared axis length.
     *
     * <p>Small enough to leave a real cone's geometry untouched — the plot's own tubes are hundredths of a unit
     * across and their axes far longer than that — and large enough that {@code 1/l2} stays finite for a cone
     * whose ends coincide, which is how a sphere is spelled here. Without it that reciprocal is an infinity and
     * the whole march downstream of it is a NaN, which reads as a hole in the picture rather than as an error.
     */
    private static final double AXIS_EPSILON = 1e-12;

    private ConeField() {
    }

    /** The buffer this field reads — the same object the module's declaration is discovered from. */
    public static Buffer buffer() {
        return new Buffer("cones", BINDING, Ir.F32);
    }

    /**
     * The vertex and fragment pair, ready to build a pipeline from — and the same bytes every time, whatever is
     * in the buffer.
     *
     * <p>Here rather than at the call site because the pairing is not a caller's to get right. The fragment reads
     * {@code vUv}, so it must go with the fullscreen vertex stage that <em>writes</em> one; paired with the stage
     * that writes only {@code gl_Position} the input is simply never written, every pixel marches the same ray,
     * the frame comes out one flat colour, and the module is still perfectly valid SPIR-V that passes
     * {@code spirv-val}. See the note on {@link SdfComposer#compose}.
     *
     * <p>{@code scene.surface()} is not compiled — the field comes from {@link #sdfFunction} and the buffer.
     * Everything else about the picture is read from the scene as usual.
     */
    public static java.util.List<ComposedShader> compose(SdfScene scene) {
        return java.util.List.of(
                new ComposedShader(ShaderStage.VERTEX, Fullscreen.triangleVertexWithUvSpirv(),
                        Fullscreen.ENTRY_POINT),
                new ComposedShader(ShaderStage.FRAGMENT,
                        SdfComposer.fragmentSpirv(scene, sdfFunction(SdfComposer.SDF_FUNCTION), null),
                        Fullscreen.ENTRY_POINT));
    }

    /**
     * How many floats a buffer must hold to carry {@code cones} of them.
     *
     * <p>Here rather than at the call site because the header is this class's business: a caller that sized its
     * allocation by multiplying and forgot the header would write every cone one slot low and see nothing at all.
     */
    public static int floatsFor(int cones) {
        return HEADER_FLOATS + cones * Cones.FLOATS;
    }

    /**
     * Pack {@code cones} — already flattened by {@link Cones#flatten} — behind the header this field expects.
     *
     * @return an array of exactly {@link #floatsFor} length, ready for a storage buffer
     */
    public static float[] pack(float[] flattened, int cones) {
        float[] out = new float[floatsFor(cones)];
        out[0] = cones;
        System.arraycopy(flattened, 0, out, HEADER_FLOATS, cones * Cones.FLOATS);
        return out;
    }

    /**
     * {@code float sdf(vec3 p)}: the smallest distance to any cone in the buffer.
     *
     * <p>The formula per cone is the exact round cone — the convex hull of two spheres — in the same three
     * regions {@code SurfaceCompiler} emits: past the far cap, before the near cap, or against the tapered side.
     * What differs is only that {@code l2}, {@code rr}, {@code a2} and {@code 1/l2} are computed here instead of
     * being literals, because the cone they describe is not known until the buffer is read.
     */
    public static Function sdfFunction(String name) {
        Buffer cones = buffer();
        Type.Int i32 = Type.int32();

        LocalVar n = new LocalVar("n", i32);
        LocalVar i = new LocalVar("i", i32);
        LocalVar o = new LocalVar("o", i32);
        LocalVar best = new LocalVar("best", Ir.F32);
        LocalVar av = new LocalVar("av", Ir.V3);
        LocalVar bv = new LocalVar("bv", Ir.V3);
        LocalVar ar = new LocalVar("ar", Ir.F32);
        LocalVar br = new LocalVar("br", Ir.F32);
        LocalVar ba = new LocalVar("ba", Ir.V3);
        LocalVar pa = new LocalVar("pa", Ir.V3);
        LocalVar perp = new LocalVar("perp", Ir.V3);
        LocalVar l2 = new LocalVar("l2", Ir.F32);
        LocalVar il2 = new LocalVar("il2", Ir.F32);
        LocalVar rr = new LocalVar("rr", Ir.F32);
        LocalVar a2 = new LocalVar("a2", Ir.F32);
        LocalVar yy = new LocalVar("yy", Ir.F32);
        LocalVar zz = new LocalVar("zz", Ir.F32);
        LocalVar x2 = new LocalVar("x2", Ir.F32);
        LocalVar y2 = new LocalVar("y2", Ir.F32);
        LocalVar z2 = new LocalVar("z2", Ir.F32);
        LocalVar k = new LocalVar("k", Ir.F32);
        LocalVar d = new LocalVar("d", Ir.F32);

        Expr p = Ir.POINT;

        // The tapered side: the region where the ray is nearer the cone's slope than either cap.
        Expr side = Ir.sub(
                Ir.mul(Ir.add(Ir.sqrt(nonNegative(Ir.mul(read(x2), Ir.mul(read(a2), read(il2))))),
                                Ir.mul(read(yy), read(rr))),
                        read(il2)),
                read(ar));
        Expr capNear = Ir.sub(Ir.mul(Ir.sqrt(nonNegative(Ir.add(read(x2), read(y2)))), read(il2)), read(ar));
        Expr capFar = Ir.sub(Ir.mul(Ir.sqrt(nonNegative(Ir.add(read(x2), read(z2)))), read(il2)), read(br));

        // Which region holds p, as the two sign tests the formula is built around.
        Expr pastFar = new Expr.Binary(BinaryOp.GREATER_THAN,
                Ir.mul(sign(read(zz)), Ir.mul(read(a2), read(z2))), read(k));
        Expr beforeNear = new Expr.Binary(BinaryOp.LESS_THAN,
                Ir.mul(sign(read(yy)), Ir.mul(read(a2), read(y2))), read(k));

        Region body = Region.of(
                new Statement.Assign(o, plus(HEADER_FLOATS, times(read(i), Cones.FLOATS))),
                new Statement.Assign(av, Ir.v3(load(cones, o, 0), load(cones, o, 1), load(cones, o, 2))),
                new Statement.Assign(ar, load(cones, o, 3)),
                new Statement.Assign(bv, Ir.v3(load(cones, o, 4), load(cones, o, 5), load(cones, o, 6))),
                new Statement.Assign(br, load(cones, o, 7)),

                new Statement.Assign(ba, Ir.sub(read(bv), read(av))),
                new Statement.Assign(l2, Ir.max(Ir.dot(read(ba), read(ba)), Ir.f(AXIS_EPSILON))),
                new Statement.Assign(il2, Ir.div(Ir.f(1.0), read(l2))),
                new Statement.Assign(rr, Ir.sub(read(ar), read(br))),
                new Statement.Assign(a2, nonNegative(Ir.sub(read(l2), Ir.mul(read(rr), read(rr))))),

                new Statement.Assign(pa, Ir.sub(p, read(av))),
                new Statement.Assign(yy, Ir.dot(read(pa), read(ba))),
                new Statement.Assign(zz, Ir.sub(read(yy), read(l2))),
                new Statement.Assign(perp, Ir.sub(Ir.scale(read(pa), read(l2)), Ir.scale(read(ba), read(yy)))),
                new Statement.Assign(x2, Ir.dot(read(perp), read(perp))),
                new Statement.Assign(y2, Ir.mul(Ir.mul(read(yy), read(yy)), read(l2))),
                new Statement.Assign(z2, Ir.mul(Ir.mul(read(zz), read(zz)), read(l2))),
                // sign(rr)*rr*rr is rr*|rr|, which is one call rather than two multiplies and a sign.
                new Statement.Assign(k, Ir.mul(Ir.mul(read(rr), Ir.abs(read(rr))), read(x2))),

                new Statement.If(pastFar,
                        Region.of(new Statement.Assign(d, capFar)),
                        Region.of(new Statement.If(beforeNear,
                                Region.of(new Statement.Assign(d, capNear)),
                                Region.of(new Statement.Assign(d, side))))),

                new Statement.Assign(best, Ir.min(read(best), read(d))),
                new Statement.Assign(i, plus(read(i), 1)));

        Region whole = Region.of(
                new Statement.DeclareVar(n, new Expr.Convert(new Expr.BufferLoad(cones, intConst(0)), i32)),
                new Statement.DeclareVar(i, intConst(0)),
                new Statement.DeclareVar(o, intConst(0)),
                // Not Float.MAX_VALUE: the march adds this to its travelled distance and compares against the far
                // plane, and an enormous finite float still overflows to an infinity there. Far beyond any scene
                // that fits in the normalised box, and still an ordinary number.
                new Statement.DeclareVar(best, Ir.f(1.0e9)),
                new Statement.DeclareVar(av, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(bv, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(ba, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(pa, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(perp, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(ar, Ir.f(0)),
                new Statement.DeclareVar(br, Ir.f(0)),
                new Statement.DeclareVar(l2, Ir.f(0)),
                new Statement.DeclareVar(il2, Ir.f(0)),
                new Statement.DeclareVar(rr, Ir.f(0)),
                new Statement.DeclareVar(a2, Ir.f(0)),
                new Statement.DeclareVar(yy, Ir.f(0)),
                new Statement.DeclareVar(zz, Ir.f(0)),
                new Statement.DeclareVar(x2, Ir.f(0)),
                new Statement.DeclareVar(y2, Ir.f(0)),
                new Statement.DeclareVar(z2, Ir.f(0)),
                new Statement.DeclareVar(k, Ir.f(0)),
                new Statement.DeclareVar(d, Ir.f(0)),
                new Statement.While(new Expr.Binary(BinaryOp.LESS_THAN, read(i), read(n)), body),
                new Statement.Return(read(best)));

        return new Function(name, new Type.FunctionType(Ir.F32, java.util.List.of(Ir.V3)), whole);
    }

    // --- small spellings, so the field above reads as the formula rather than as constructor calls ---

    private static Expr read(LocalVar v) {
        return new Expr.Read(v);
    }

    private static Expr intConst(int v) {
        return new Expr.ConstInt(Type.int32(), v);
    }

    private static Expr plus(Expr a, int b) {
        return new Expr.Binary(BinaryOp.ADD, a, intConst(b));
    }

    private static Expr plus(int a, Expr b) {
        return new Expr.Binary(BinaryOp.ADD, intConst(a), b);
    }

    private static Expr times(Expr a, int b) {
        return new Expr.Binary(BinaryOp.MUL, a, intConst(b));
    }

    /** Element {@code slot} of the cone whose block starts at {@code base}. */
    private static Expr load(Buffer buffer, LocalVar base, int slot) {
        return new Expr.BufferLoad(buffer, plus(read(base), slot));
    }

    private static Expr sign(Expr v) {
        return Ir.call(MathFn.SIGN, Ir.F32, v);
    }

    /**
     * Clamped at zero before a square root.
     *
     * <p>Every one of these quantities is a sum of squares and cannot be negative in exact arithmetic. In
     * {@code float} they can be, by a rounding error, precisely when the point is on the surface — which is
     * where the march spends its last steps and where a NaN therefore does the most damage.
     */
    private static Expr nonNegative(Expr v) {
        return Ir.max(v, Ir.f(0.0));
    }
}
