package dev.vexelray.shadelets;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Fragment;
import dev.vexelray.canvas.shadelet.PaintContext;
import dev.vexelray.canvas.shadelet.Shadelet;
import dev.vexelray.canvas.shadelet.StyleLayout;
import dev.vexelray.canvas.shadelet.StyleView;

import java.util.ArrayList;
import java.util.List;

import static dev.vexelray.ir.Ir.F32;
import static dev.vexelray.ir.Ir.V2;
import static dev.vexelray.ir.Ir.V3;
import static dev.vexelray.ir.Ir.V4;
import static dev.vexelray.ir.Ir.abs;
import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.broadcast;
import static dev.vexelray.ir.Ir.clamp;
import static dev.vexelray.ir.Ir.div;
import static dev.vexelray.ir.Ir.dot;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.length;
import static dev.vexelray.ir.Ir.max;
import static dev.vexelray.ir.Ir.min;
import static dev.vexelray.ir.Ir.mix;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.neg;
import static dev.vexelray.ir.Ir.scale;
import static dev.vexelray.ir.Ir.sqrt;
import static dev.vexelray.ir.Ir.step;
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.v2;
import static dev.vexelray.ir.Ir.v3;
import static dev.vexelray.ir.Ir.v4;
import static dev.vexelray.ir.Ir.w;
import static dev.vexelray.ir.Ir.x;
import static dev.vexelray.ir.Ir.xyz;
import static dev.vexelray.ir.Ir.y;
import static dev.vexelray.ir.Ir.z;

/**
 * A mark that precipitates out of its own distance field: the {@link #MTSDF} source a baked art pair is read through,
 * the {@link #CONDENSE} painter that grows the shape behind a front, and the {@link #ORB} that lands to start it.
 *
 * <p>Each is a function of a distance and of values the application writes per frame; none reads a clock. The design
 * lengths below (a 22-unit growth, contours 6 apart, a 110-unit ramp behind the front) are for a mark 104 units
 * across, and {@link CondenseStyle#unit()} scales them to the mark as drawn.
 */
public final class Condense {

    private Condense() {
    }

    /**
     * {@link MtsdfStyle}: a field in the MTSDF layout, in the image slot. The median of red, green and blue is the
     * distance over {@code near} of the range; alpha is the distance over all of it, which the field's
     * {@code filter.x} gives in canvas units as the core field's does. Near the edge the median answers, precise to an
     * eighth of what alpha could say; past where it saturates, alpha does, so an effect sixty pixels out reads a
     * smooth distance rather than a staircase of eight-bit steps. Trusted to half the far range, the spread.
     */
    public static final Shadelet.Source MTSDF = new Shadelet.Source() {
        @Override
        public String name() {
            return "mtsdf";
        }

        @Override
        public StyleLayout style() {
            return MtsdfStyleLayout.LAYOUT;
        }

        @Override
        public Expr distance(Fragment fragment, Expr offset, StyleView style) {
            Expr fx = fragment.filter();
            Expr uv = fragment.uv();
            Expr moved = v2(add(x(uv), mul(x(offset), y(fx))), add(y(uv), mul(y(offset), y(fx))));
            Expr t = new Expr.SampleTexture(CoreShadelets.IMAGE, moved);
            Expr median = max(min(x(t), y(t)), min(max(x(t), y(t)), z(t)));
            Expr range = x(fx);
            Expr far = mul(sub(w(t), f(0.5)), range);
            Expr near = mul(sub(median, f(0.5)), mul(range, style.scalar("near")));
            // The near channels saturate at 0 and 1: hand over to alpha before they do.
            Expr toFar = Expr.MathCall.smoothstep(f(0.36), f(0.46), abs(sub(median, f(0.5))));
            return v2(mix(near, far, toFar), mul(f(0.5), range));
        }
    };

    /** {@link CondenseStyle}: see there. Over {@link #MTSDF} for a baked pair; over any field source in principle. */
    public static final Shadelet.Painter CONDENSE = new Shadelet.Painter() {
        @Override
        public String name() {
            return "condense";
        }

        @Override
        public StyleLayout style() {
            return CondenseStyleLayout.LAYOUT;
        }

        @Override
        public Region paint(PaintContext c, StyleView s, LocalVar layer) {
            Fragment fr = c.fragment();
            Locals b = new Locals("condense");
            Expr heat = xyz(s.vector("heat"));
            Expr u = b.let("u", F32, max(s.scalar("unit"), f(1e-3)));
            Expr p = fr.local();
            Expr dist = b.let("dist", V2, c.distance());
            // In design units, negative inside: the way the effect was drawn up.
            Expr dm = b.let("dm", F32, div(neg(x(dist)), u));
            Expr orb = v2(s.scalar("orbX"), s.scalar("orbY"));

            // The front, and how far behind it this fragment is.
            Expr e = b.let("e", F32, div(sub(length(sub(p, v2(s.scalar("frontX"), s.scalar("frontY")))),
                    s.scalar("front")), u));
            Expr a = b.let("a", F32, smooth(0, 110, neg(e)));
            Expr behind = b.let("behind", F32, sub(f(1.0), step(f(0.0), e)));
            // The light behind the front comes up over a few units rather than at its line, so the front reads as a
            // moving edge of heat and not as a cut across the rings.
            Expr warm = b.let("warm", F32, smooth(0, 8, neg(e)));
            // The cut: nowhere before the front; just behind it, deeper than any shape is thick, so nothing shows along
            // the front's own line; then up to the medial axis over 40 units and out to the edge as the front moves on.
            // Continuous behind the front, so a shape always grows from its middle and is never wiped on by a line.
            Expr deep = mul(f(60.0), sub(f(1.0), smooth(0, 40, neg(e))));
            Expr th = b.let("th", F32, mix(f(-1e4), neg(add(mul(f(22.0), pow(sub(f(1.0), a), 1.5)), deep)), behind));
            Expr covField = clamp(add(mul(sub(th, dm), u), f(0.5)), f(0.0), f(1.0));

            // The colour, from the pair's other cell, straight from its premultiplied texels.
            Expr t = b.let("t", V2, v2(add(mul(x(fr.uv()), s.scalar("mapX")), s.scalar("mapU")),
                    add(mul(y(fr.uv()), s.scalar("mapY")), s.scalar("mapV"))));
            Expr in = mul(mul(step(f(0.0), x(t)), step(x(t), f(1.0))), mul(step(f(0.0), y(t)), step(y(t), f(1.0))));
            Expr cuv = v2(mix(s.scalar("colourU0"), s.scalar("colourU1"), clamp(x(t), f(0.0), f(1.0))),
                    mix(s.scalar("colourV0"), s.scalar("colourV1"), clamp(y(t), f(0.0), f(1.0))));
            Expr tex = b.let("tex", V4, scale(new Expr.SampleTexture(CoreShadelets.IMAGE, cuv), in));
            Expr ca = w(tex);
            Expr base = b.let("base", V3, mixV(heat, scale(xyz(tex), div(f(1.0), max(ca, f(1e-3)))),
                    step(f(0.004), ca)));
            Expr settle = s.scalar("settle");
            Expr cov = b.let("cov", F32, mix(covField, ca, settle));

            // The bevel: the field's slope near the edge, lit by the orb as a point light just above the page.
            Expr gx = sub(x(c.distanceAt(v2(1.0, 0.0))), x(c.distanceAt(v2(-1.0, 0.0))));
            Expr gy = sub(x(c.distanceAt(v2(0.0, 1.0))), x(c.distanceAt(v2(0.0, -1.0))));
            Expr slope = b.let("slope", F32, mul(sub(f(1.0), smooth(0, 4.5, neg(dm))), f(-0.9)));
            Expr n = b.let("n", V3, Expr.MathCall.normalize(v3(mul(gx, slope), mul(gy, slope), f(1.0))));
            Expr toLight = Expr.MathCall.normalize(v3(div(sub(x(orb), x(p)), u), div(sub(y(orb), y(p)), u), f(36.0)));
            Expr half = Expr.MathCall.normalize(add(toLight, v3(0.0, 0.0, 1.0)));
            Expr spec = pow(max(dot(n, half), f(0.0)), 36.0);
            Expr glinted = add(base, scale(v3(1.3, 1.118, 0.806), mul(spec, s.scalar("glint"))));
            Expr hot = b.let("hot", F32, mul(warm, sub(f(1.0), a)));
            Expr rim = mul(Expr.MathCall.exp(div(neg(abs(sub(dm, th))), f(1.2))), hot);
            Expr shape = b.let("shape", V3, mixV(glinted, scale(heat, f(1.35)), clamp(mul(rim, f(0.85)), f(0.0),
                    f(1.0))));

            // Light on the page round the shape, all of it the heat colour.
            Expr toOrb = div(length(sub(p, orb)), u);
            Expr lit = mul(Expr.MathCall.exp(neg(div(toOrb, add(mul(div(s.scalar("orbRadius"), u), f(1.4)),
                    f(30.0))))), s.scalar("preview"));
            Expr nearEdge = mul(step(f(-1.0), dm), step(dm, f(54.0)));
            Expr contours = mul(mul(lit, nearEdge), add(
                    mul(mul(iso(dm, 6, 0.8, u), sub(f(1.0), div(dm, f(54.0)))), f(0.8)),
                    mul(Expr.MathCall.exp(div(neg(abs(dm)), f(0.9))), f(0.9))));
            Expr band = b.let("band", F32, max(mul(f(70.0), sub(f(1.0), a)), f(1e-3)));
            Expr rings = mul(mul(mul(warm, step(f(0.0), dm)), step(dm, band)),
                    mul(mul(iso(add(dm, mul(s.scalar("time"), f(42.0))), 7, 0.7, u), sub(f(1.0), div(dm, band))),
                            mul(mul(smooth(0, 0.12, a), f(0.5)), sub(f(1.0), settle))));
            Expr afterglow = mul(mul(Expr.MathCall.exp(div(neg(max(sub(dm, th), f(0.0))), f(3.0))), hot),
                    mul(f(0.35), step(f(-30.0), dm)));
            Expr frontRing = mul(mul(Expr.MathCall.exp(div(neg(abs(e)), f(5.0))), s.scalar("ring")),
                    add(f(0.05), mul(f(0.55), Expr.MathCall.exp(div(neg(max(dm, f(0.0))), f(12.0))))));
            // Gone before the quad's edge, where the field stops being true.
            Expr reach = y(dist);
            Expr fade = smoothE(mul(f(-0.95), reach), mul(f(-0.6), reach), x(dist));
            Expr light = b.let("light", F32, clamp(mul(add(add(contours, rings), add(afterglow, frontRing)), fade),
                    f(0.0), f(1.0)));

            // The shape over its light, premultiplied, at the element's own alpha.
            Expr under = mul(light, sub(f(1.0), cov));
            Expr rgb = add(scale(shape, cov), scale(heat, under));
            Expr alpha = add(cov, under);
            Expr tint = w(fr.color());
            b.add(new Statement.Assign(layer, v4(mul(x(rgb), tint), mul(y(rgb), tint), mul(z(rgb), tint),
                    mul(alpha, tint))));
            return b.region();
        }
    };

    /**
     * {@link OrbStyle}: see there. The sphere is centred on its element and reads its own position, not the source's
     * distance, so any source that gives the element room serves: a circle's field, whose margin is the halo's room.
     */
    public static final Shadelet.Painter ORB = new Shadelet.Painter() {
        @Override
        public String name() {
            return "orb";
        }

        @Override
        public StyleLayout style() {
            return OrbStyleLayout.LAYOUT;
        }

        @Override
        public Region paint(PaintContext c, StyleView s, LocalVar layer) {
            Fragment fr = c.fragment();
            Locals b = new Locals("orb");
            Expr r = b.let("r", F32, max(s.scalar("radius"), f(1e-3)));
            Expr q = b.let("q", V2, scale(fr.local(), div(f(1.0), r)));
            Expr rq = b.let("rq", F32, length(q));
            Expr alpha = s.scalar("alpha");
            Expr soft = b.let("soft", F32, max(div(fr.aa(), r), s.scalar("blur")));
            Expr cover = b.let("cover", F32, mul(sub(f(1.0), smoothE(sub(f(1.0), soft),
                    add(f(1.0), mul(soft, f(0.4))), rq)), alpha));

            Expr zz = sqrt(max(sub(f(1.0), mul(rq, rq)), f(0.0)));
            Expr nz = b.let("nz", F32, zz);
            Expr n = b.let("n", V3, Expr.MathCall.normalize(v3(x(q), y(q), add(nz, f(1e-4)))));
            // The canvas's one light, from the top left and a little in front.
            Expr light = Expr.MathCall.normalize(v3(-0.45, -0.62, 0.65));
            Expr diffuse = max(dot(n, light), f(0.0));
            Expr body = scale(v3(0.88, 0.50, 0.16), add(f(0.22), mul(f(0.9), diffuse)));
            Expr bounced = Expr.MathCall.reflect(v3(0.0, 0.0, -1.0), n);
            Expr env = mixV(v3(1.0, 0.88, 0.62), v3(0.30, 0.15, 0.05), smooth(-0.35, 0.45, y(bounced)));
            Expr fresnel = pow(sub(f(1.0), nz), 3.0);
            Expr metal = b.let("metal", V3, mixV(body, mul(env, v3(1.0, 0.76, 0.42)), add(f(0.3),
                    mul(f(0.5), fresnel))));
            Expr highlight = pow(max(dot(n, Expr.MathCall.normalize(add(light, v3(0.0, 0.0, 1.0)))), f(0.0)), 70.0);
            Expr lifted = add(add(metal, scale(v3(1.4, 1.33, 1.176), highlight)),
                    scale(v3(0.25, 0.55, 0.62), mul(mul(max(y(n), f(0.0)), f(0.22)), s.scalar("bounce"))));

            Expr reach = y(c.distance());
            Expr fade = smoothE(mul(f(-0.95), reach), mul(f(-0.6), reach), x(c.distance()));
            Expr halo = mul(mul(mul(s.scalar("glow"), f(0.4)), Expr.MathCall.exp(mul(neg(max(sub(rq, f(1.0)),
                    f(0.0))), f(2.4)))), mul(alpha, fade));
            Expr under = mul(clamp(halo, f(0.0), f(1.0)), sub(f(1.0), cover));
            Expr rgb = add(scale(lifted, cover), scale(xyz(s.vector("halo")), under));
            Expr a = add(cover, under);
            Expr tint = w(fr.color());
            b.add(new Statement.Assign(layer, v4(mul(x(rgb), tint), mul(y(rgb), tint), mul(z(rgb), tint),
                    mul(a, tint))));
            return b.region();
        }
    };

    /** All three, to compose with. */
    public static final List<Shadelet> ALL = List.of(MTSDF, CONDENSE, ORB);

    /** {@code 1 - smoothstep(w/2, w/2 + one canvas unit, k)}: a line every {@code spacing} along {@code v}. */
    private static Expr iso(Expr v, double spacing, double width, Expr unit) {
        Expr k = mul(abs(sub(Expr.MathCall.fract(div(v, f(spacing))), f(0.5))), f(spacing));
        return sub(f(1.0), smoothE(f(width * 0.5), add(f(width * 0.5), div(f(1.0), unit)), k));
    }

    private static Expr smooth(double from, double to, Expr v) {
        return Expr.MathCall.smoothstep(f(from), f(to), v);
    }

    private static Expr smoothE(Expr from, Expr to, Expr v) {
        return Expr.MathCall.smoothstep(from, to, v);
    }

    private static Expr pow(Expr base, double exponent) {
        return Expr.MathCall.pow(base, f(exponent));
    }

    /** {@code mix} of two vectors by a scalar, which SPIR-V's FMix wants as a vector too. */
    private static Expr mixV(Expr a, Expr b, Expr t) {
        return mix(a, b, broadcast(t, a.type()));
    }

    /** Statements declaring locals, named apart so two painters in one look cannot collide. */
    private static final class Locals {
        private final String prefix;
        private final List<Statement> statements = new ArrayList<>();

        Locals(String prefix) {
            this.prefix = prefix;
        }

        Expr let(String name, Type type, Expr value) {
            LocalVar v = new LocalVar(prefix + "_" + name, type);
            statements.add(new Statement.DeclareVar(v, value));
            return new Expr.Read(v);
        }

        void add(Statement s) {
            statements.add(s);
        }

        Region region() {
            return new Region(statements);
        }
    }
}
