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
 * carrying the cone count, the group count and where the group bounds begin; cone {@code i} then occupies
 * {@code [FLOATS*(i+1), FLOATS*(i+2))} as {@code ax, ay, az, ar, bx, by, bz, br}, and the bounds follow the last
 * cone. See {@link #buffer} and {@link #pack}.
 *
 * <p>The counts live in the buffer rather than in a push constant so that this composes with the ordinary
 * {@link SdfComposer#cameraBytes} block unchanged — the camera is still six floats and nothing else had to learn
 * about cones. They are read once per {@code sdf} call, from an address every invocation in the group shares,
 * which is the cheapest kind of load there is.
 *
 * <h2>Culling, which is what makes the march affordable</h2>
 *
 * <p>The loop below is over <b>groups</b> of {@link #GROUP} consecutive cones, not over cones. Each group
 * carries a bounding sphere, computed in Java by {@link #pack}, and a group whose sphere is already further
 * away than the best distance found so far is skipped whole — {@code GROUP} cones' worth of loads and
 * arithmetic for four loads and a square root.
 *
 * <p><b>The skip is exact, not an approximation.</b> Every cone in a group lies inside that group's sphere, so
 * for any point {@code p} the cone's distance is at least {@code |p - centre| - radius}. When that lower bound
 * is already {@code >= best}, no cone in the group can lower {@code best}, and not looking at them returns the
 * same number the full loop would have. There is no tolerance to tune and no geometry to lose.
 *
 * <p>Why it pays: cones come off a curve, so consecutive ones are neighbours in space and a group's sphere is
 * tight. A ray step is near one part of the curve and far from all the rest, so most groups fail the test.
 * Where nothing can be culled — a ray inside a dense tangle — the cost is the group tests themselves, about a
 * quarter of a cone evaluation per {@code GROUP} of them, which is a few percent.
 *
 * <p>It is order-dependent, and deliberately left that way: {@code best} starts at infinity, so the first group
 * examined is never skipped and the ones after it are skipped against whatever that found. Sorting groups by
 * distance would cull more and costs per-pixel work to do it; the chain order is free and already spatially
 * coherent.
 *
 * <p><b>The module is still the same bytes for every scene.</b> {@code GROUP} is a Java constant compiled into
 * the shader; the group <em>count</em> and the offset the bounds start at are data, exactly as the cone count
 * already was. {@code ConeFieldTest.isIndependentOfItsGeometry} is what holds this.
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

    /**
     * Floats of header before the first cone.
     *
     * <p>Three of them are used — the cone count, the group count, and the float offset the group bounds start
     * at. The width is {@link Cones#FLOATS} so that a cone's block starts at a multiple of its own size, which
     * is what lets the shader index cone {@code i} by multiplying rather than by adding an offset it read.
     */
    public static final int HEADER_FLOATS = Cones.FLOATS;

    /**
     * Cones per culling group — the granularity of the bounding spheres {@link #pack} computes.
     *
     * <p>A compile-time constant rather than a header field, because the shader derives a group's first cone
     * from its index by multiplying by this. Making it data would cost nothing in the march and would buy
     * nothing either: there is no caller who wants a different value, and the module has to stay
     * scene-independent whatever it is.
     *
     * <p>Eight is chosen against the two costs it sits between. Smaller groups cull more finely and spend more
     * on tests; larger ones spend less and cull worse, because a group's sphere grows with how much curve it
     * spans and a loose sphere fails to exclude anything. At eight, a group test is roughly a quarter of a cone
     * evaluation, so the tests cost about 3% when nothing culls at all.
     */
    public static final int GROUP = 8;

    /** Floats per group bound: {@code cx, cy, cz, radius}. */
    public static final int BOUND_FLOATS = 4;

    /**
     * How much a group's bounding radius is grown before it is written, relatively and absolutely.
     *
     * <p>Slack against float rounding, in a direction chosen on purpose — see {@link #bound}. Large enough to
     * swallow the error in narrowing the ends, averaging them and taking a square root, plus whatever the
     * shader adds when it measures against the same bound; small enough to be five orders of magnitude below a
     * pixel at the scale this draws.
     */
    private static final float MARGIN = 1e-5f;

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
     * <p>Here rather than at the call site because the layout is this class's business: a caller that sized its
     * allocation by multiplying and forgot the header would write every cone one slot low and see nothing at all,
     * and one that forgot the bounds would have {@link #pack} overrun the buffer it was sizing for.
     *
     * <p>Monotonic in {@code cones}, which is the property a caller allocating once for a worst case relies on:
     * a buffer sized for {@code MAX} holds anything up to {@code MAX} with its bounds.
     */
    public static int floatsFor(int cones) {
        return HEADER_FLOATS + cones * Cones.FLOATS + groupsFor(cones) * BOUND_FLOATS;
    }

    /** How many culling groups {@code cones} cones fall into — the last one short unless it divides. */
    public static int groupsFor(int cones) {
        return (cones + GROUP - 1) / GROUP;
    }

    /**
     * Pack {@code cones} — already flattened by {@link Cones#flatten} — behind the header this field expects,
     * with a bounding sphere per {@link #GROUP} of them appended for the march to cull against.
     *
     * <p>The bounds are computed here rather than asked of the caller for the reason the header is written here:
     * they are part of the layout, and a buffer whose bounds do not contain their cones renders geometry
     * missing rather than wrong — the march would skip a group it should have looked at, and the curve would
     * have a gap in it that moved as the camera turned. Nobody would read that as a bad bounding sphere.
     *
     * @return an array of exactly {@link #floatsFor} length, ready for a storage buffer
     */
    public static float[] pack(float[] flattened, int cones) {
        float[] out = new float[floatsFor(cones)];
        int groups = groupsFor(cones);
        int bounds = HEADER_FLOATS + cones * Cones.FLOATS;
        out[0] = cones;
        out[1] = groups;
        out[2] = bounds;
        System.arraycopy(flattened, 0, out, HEADER_FLOATS, cones * Cones.FLOATS);
        for (int g = 0; g < groups; g++) {
            bound(out, HEADER_FLOATS + g * GROUP * Cones.FLOATS,
                    Math.min(GROUP, cones - g * GROUP), bounds + g * BOUND_FLOATS);
        }
        return out;
    }

    /**
     * Write the bounding sphere of {@code count} cones starting at {@code from} into {@code buffer} at
     * {@code at}.
     *
     * <p>Two passes, because a centre has to exist before a radius can be measured from it. The first takes the
     * axis-aligned box of the cones' <em>end spheres</em> — centre ± radius, not the centre — and the second
     * takes the furthest any end sphere reaches from the box's middle.
     *
     * <p>The box's own half-diagonal would have been one pass and is a legitimate answer, but a loose sphere is
     * exactly what defeats the culling this exists for: a bound is only useful in proportion to how often it
     * excludes something. The second pass is a few dozen operations per group, once per plot, against a test
     * evaluated per group per ray step per pixel.
     *
     * <p>A cone is the convex hull of its two end spheres, so a sphere containing both ends contains the cone —
     * which is why bounding the ends is enough and no point along the axis has to be considered.
     */
    private static void bound(float[] buffer, int from, int count, int at) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        float maxZ = Float.NEGATIVE_INFINITY;
        for (int c = 0; c < count; c++) {
            int o = from + c * Cones.FLOATS;
            for (int end = 0; end < 2; end++) {
                int e = o + end * 4;
                float r = buffer[e + 3];
                minX = Math.min(minX, buffer[e] - r);
                maxX = Math.max(maxX, buffer[e] + r);
                minY = Math.min(minY, buffer[e + 1] - r);
                maxY = Math.max(maxY, buffer[e + 1] + r);
                minZ = Math.min(minZ, buffer[e + 2] - r);
                maxZ = Math.max(maxZ, buffer[e + 2] + r);
            }
        }
        float cx = (minX + maxX) * 0.5f;
        float cy = (minY + maxY) * 0.5f;
        float cz = (minZ + maxZ) * 0.5f;
        float radius = 0;
        for (int c = 0; c < count; c++) {
            int o = from + c * Cones.FLOATS;
            for (int end = 0; end < 2; end++) {
                int e = o + end * 4;
                double dx = buffer[e] - cx;
                double dy = buffer[e + 1] - cy;
                double dz = buffer[e + 2] - cz;
                radius = Math.max(radius,
                        (float) (Math.sqrt(dx * dx + dy * dy + dz * dz) + buffer[e + 3]));
            }
        }
        buffer[at] = cx;
        buffer[at + 1] = cy;
        buffer[at + 2] = cz;
        // Grown by MARGIN before it is written, and the asymmetry is the whole reason for it: a bound that is
        // slightly too big costs a group test that excludes nothing, and a bound that is slightly too small
        // costs a cone the march never looks at — a hole in the curve that moves as the camera turns.
        //
        // Rounding alone gets there. Every number above has been through float: the ends were narrowed by
        // Cones.flatten, the centre is their mean, and the reach is a float square root away from that. Two
        // ulps of slack was tried and was not enough — ConeFieldTest.everyConeIsInsideItsGroupBound caught a
        // group short by about two and a half, measured against the double-precision cone it is supposed to
        // contain. The shader then adds its own float error on top, and none of that is reproducible here.
        //
        // So the margin is set far above the error rather than tuned to it. At the scale this draws — a
        // normalised box two units across, tubes five hundredths of a unit thick — it is five orders of
        // magnitude below anything a pixel could show.
        buffer[at + 3] = radius * (1f + MARGIN) + MARGIN;
    }

    /**
     * {@code float sdf(vec3 p)}: the smallest distance to any cone in the buffer.
     *
     * <p>The formula per cone is the exact round cone — the convex hull of two spheres — in the same three
     * regions {@code SurfaceCompiler} emits: past the far cap, before the near cap, or against the tapered side.
     * What differs is only that {@code l2}, {@code rr}, {@code a2} and {@code 1/l2} are computed here instead of
     * being literals, because the cone they describe is not known until the buffer is read.
     *
     * <p>Two loops, not one: over groups, and over the cones of a group whose bounding sphere is close enough
     * to matter. See the class note on culling for why the skip returns the same number the flat loop did.
     *
     * <p>Every variable is declared at the top of the function and only assigned inside the loops. That is not
     * style: SPIR-V wants its {@code OpVariable}s in a function's first block, and a declaration nested inside
     * a loop inside an {@code if} is the shape most likely to find out whether the lowering agrees.
     */
    public static Function sdfFunction(String name) {
        Buffer cones = buffer();
        Type.Int i32 = Type.int32();

        LocalVar n = new LocalVar("n", i32);
        LocalVar i = new LocalVar("i", i32);
        LocalVar o = new LocalVar("o", i32);
        LocalVar g = new LocalVar("g", i32);
        LocalVar gn = new LocalVar("gn", i32);
        LocalVar bo = new LocalVar("bo", i32);
        LocalVar gb = new LocalVar("gb", i32);
        LocalVar end = new LocalVar("end", i32);
        LocalVar cc = new LocalVar("cc", Ir.V3);
        LocalVar cr = new LocalVar("cr", Ir.F32);
        LocalVar pc = new LocalVar("pc", Ir.V3);
        LocalVar lo = new LocalVar("lo", Ir.F32);
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

        // The group's bounding sphere, and the lower bound it puts under every cone inside it.
        //
        // sqrt rather than comparing squares: `best` is signed — negative inside the geometry — so squaring the
        // comparison needs a case for a negative threshold, and that case is a branch per group per ray step.
        // One square root per group is cheaper than being clever, and reads as the geometry it is.
        Region group = Region.of(
                new Statement.Assign(gb, plus(read(bo), times(read(g), BOUND_FLOATS))),
                new Statement.Assign(cc, Ir.v3(load(cones, gb, 0), load(cones, gb, 1), load(cones, gb, 2))),
                new Statement.Assign(cr, load(cones, gb, 3)),
                new Statement.Assign(pc, Ir.sub(p, read(cc))),
                new Statement.Assign(lo,
                        Ir.sub(Ir.sqrt(nonNegative(Ir.dot(read(pc), read(pc)))), read(cr))),
                new Statement.If(new Expr.Binary(BinaryOp.LESS_THAN, read(lo), read(best)),
                        Region.of(
                                new Statement.Assign(i, times(read(g), GROUP)),
                                new Statement.Assign(end, plus(read(i), GROUP)),
                                // The last group is short whenever GROUP does not divide the count. Clamping
                                // here rather than storing a per-group end keeps a group's bound four floats,
                                // which is the whole reason the test is cheap.
                                new Statement.If(new Expr.Binary(BinaryOp.GREATER_THAN, read(end), read(n)),
                                        Region.of(new Statement.Assign(end, read(n))),
                                        Region.of()),
                                new Statement.While(
                                        new Expr.Binary(BinaryOp.LESS_THAN, read(i), read(end)), body)),
                        Region.of()),
                new Statement.Assign(g, plus(read(g), 1)));

        Region whole = Region.of(
                new Statement.DeclareVar(n, new Expr.Convert(new Expr.BufferLoad(cones, intConst(0)), i32)),
                new Statement.DeclareVar(gn, new Expr.Convert(new Expr.BufferLoad(cones, intConst(1)), i32)),
                new Statement.DeclareVar(bo, new Expr.Convert(new Expr.BufferLoad(cones, intConst(2)), i32)),
                new Statement.DeclareVar(i, intConst(0)),
                new Statement.DeclareVar(o, intConst(0)),
                new Statement.DeclareVar(g, intConst(0)),
                new Statement.DeclareVar(gb, intConst(0)),
                new Statement.DeclareVar(end, intConst(0)),
                new Statement.DeclareVar(cc, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(pc, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(cr, Ir.f(0)),
                new Statement.DeclareVar(lo, Ir.f(0)),
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
                new Statement.While(new Expr.Binary(BinaryOp.LESS_THAN, read(g), read(gn)), group),
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

    private static Expr plus(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.ADD, a, b);
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
