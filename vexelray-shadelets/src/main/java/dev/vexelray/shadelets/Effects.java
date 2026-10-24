package dev.vexelray.shadelets;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.canvas.shadelet.Fragment;
import dev.vexelray.canvas.shadelet.Layers;
import dev.vexelray.canvas.shadelet.PaintContext;
import dev.vexelray.canvas.shadelet.Shadelet;
import dev.vexelray.canvas.shadelet.StyleLayout;
import dev.vexelray.canvas.shadelet.StyleView;

import java.util.List;

import static dev.vexelray.ir.Ir.abs;
import static dev.vexelray.ir.Ir.add;
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
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.v2;
import static dev.vexelray.ir.Ir.w;
import static dev.vexelray.ir.Ir.x;
import static dev.vexelray.ir.Ir.xyz;
import static dev.vexelray.ir.Ir.y;

/**
 * The canvas's first effects beyond its core set. An application composes the ones it draws with:
 * {@code CoreShadelets.composition().with(Effects.ALL)}, or any of them.
 *
 * <p>Each is a function of the distance its look's source gives it, so each works on a box and on a field alike,
 * except the morphs, which are sources: one morphs boxes, the other two cells of one field atlas.
 *
 * <p>None of them reads a clock. What animates (a ripple's radius, a ring's offset, a gradient's colours) is a
 * per-instance value the application writes each frame from its own timeline, so a frame is a function of what was
 * drawn and nothing else: an offscreen capture of it is reproducible, and a loop with nothing moving can sleep.
 */
public final class Effects {

    private Effects() {
    }

    /** {@link WeightStyle}: the edge moved out by {@code weight}; the reach shrinks by as much. */
    public static final Shadelet.Modifier WEIGHT = new Shadelet.Modifier() {
        @Override
        public String name() {
            return "weight";
        }

        @Override
        public StyleLayout style() {
            return WeightStyleLayout.LAYOUT;
        }

        @Override
        public float reach(float[] shared) {
            return shared[0];
        }

        @Override
        public Expr modify(Expr d, Fragment fragment, StyleView style) {
            Expr weight = style.scalar("weight");
            return v2(add(x(d), weight), sub(y(d), weight));
        }
    };

    /** {@link OutlineStyle}: the band {@code |d| < width / 2} in its own colour, anti-aliased as a fill is. */
    public static final Shadelet.Painter OUTLINE = new Shadelet.Painter() {
        @Override
        public String name() {
            return "outline";
        }

        @Override
        public StyleLayout style() {
            return OutlineStyleLayout.LAYOUT;
        }

        @Override
        public float reach(float[] shared) {
            return 0.5f * shared[4];   // colour (4 floats), then width
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr colour = style.vector("colour");
            Expr half = mul(style.scalar("width"), f(0.5));
            Expr aa = c.fragment().aa();
            Expr fromBand = sub(abs(x(c.distance())), half);   // negative inside the band
            Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, fromBand));
            return Region.of(new Statement.Assign(layer, Layers.premultiplied(xyz(colour), mul(w(colour), cov))));
        }
    };

    /**
     * {@link BoxMorphStyle}: the element's own rounded box and a second about the same centre, their distances mixed
     * by {@code t}. Two boxes' distances are both true, so the mix is a true shape between them.
     */
    public static final Shadelet.Source BOX_MORPH = new Shadelet.Source() {
        @Override
        public String name() {
            return "boxMorph";
        }

        @Override
        public StyleLayout style() {
            return BoxMorphStyleLayout.LAYOUT;
        }

        /** As far as the second box sticks out past the element's own, whatever {@code t}: the mix lies between. */
        @Override
        public float reach(float[] shared, float[] instance, float halfW, float halfH) {
            return Math.max(0f, Math.max(instance[0] - halfW, instance[1] - halfH));
        }

        @Override
        public Expr distance(Fragment fragment, Expr offset, StyleView style) {
            Expr local = add(fragment.local(), offset);
            Expr a = neg(CoreShadelets.boxSdf(fragment, local));
            Expr r = style.scalar("radius");
            Expr b = neg(CoreShadelets.boxSdf(local, v2(style.scalar("halfW"), style.scalar("halfH")), r, r));
            return v2(mix(a, b, style.scalar("t")), f(1e9));
        }
    };

    /**
     * {@link FieldMorphStyle}: the element's own cell of a distance field and a second cell of the same texture,
     * their distances mixed by {@code t}, trusted as far as the field is. A field's
     * {@code filter = (range, v per unit, ...)} applies to both, so the two cells are drawn at one size.
     */
    public static final Shadelet.Source FIELD_MORPH = new Shadelet.Source() {
        @Override
        public String name() {
            return "fieldMorph";
        }

        @Override
        public StyleLayout style() {
            return FieldMorphStyleLayout.LAYOUT;
        }

        @Override
        public Expr distance(Fragment fragment, Expr offset, StyleView style) {
            Expr a = CoreShadelets.FIELD.distance(fragment, offset, style);
            Expr second = v2(style.scalar("du"), style.scalar("dv"));
            Fragment there = new Fragment(fragment.color(), add(fragment.uv(), second), fragment.local(),
                    fragment.shape(), fragment.filter(), fragment.aa(), fragment.pixelsPerUnit());
            Expr b = CoreShadelets.FIELD.distance(there, offset, style);
            return v2(mix(x(a), x(b), style.scalar("t")), min(y(a), y(b)));
        }
    };

    /**
     * {@link RippleStyle}: a disc of the ink about a point, inside the shape and nowhere else, both edges one pixel
     * soft. The point and the radius are the element's own, so a ripple spreads by its radius growing and leaves by its
     * colour's alpha falling: two curves, as a transition wants (eased out for the spread, linear for the fade).
     */
    public static final Shadelet.Painter RIPPLE = new Shadelet.Painter() {
        @Override
        public String name() {
            return "ripple";
        }

        @Override
        public StyleLayout style() {
            return RippleStyleLayout.LAYOUT;
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr colour = style.vector("colour");
            Expr aa = c.fragment().aa();
            Expr shape = Expr.MathCall.smoothstep(neg(aa), aa, x(c.distance()));
            Expr fromCentre = length(sub(c.fragment().local(), v2(style.scalar("x"), style.scalar("y"))));
            Expr disc = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa,
                    sub(fromCentre, style.scalar("radius"))));
            return Region.of(new Statement.Assign(layer,
                    Layers.premultiplied(xyz(colour), mul(w(colour), mul(shape, disc)))));
        }
    };

    /**
     * {@link RingStyle}: the band from {@code offset} to {@code offset + width} outside the edge, following the shape,
     * one pixel soft on both sides. On a field, the band is as true as the field's distance is that far out, which is
     * its reach; a ring wider than that is the field's business to allow.
     */
    public static final Shadelet.Painter RING = new Shadelet.Painter() {
        @Override
        public String name() {
            return "ring";
        }

        @Override
        public StyleLayout style() {
            return RingStyleLayout.LAYOUT;
        }

        /** Out to the band's far side: colour (4 floats), width, offset. */
        @Override
        public float reach(float[] shared, float[] instance, float halfW, float halfH) {
            return Math.max(0f, instance[4]) + Math.max(0f, instance[5]);
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr colour = style.vector("colour");
            Expr half = mul(style.scalar("width"), f(0.5));
            Expr outside = neg(x(c.distance()));
            Expr band = sub(abs(sub(outside, add(style.scalar("offset"), half))), half);   // negative in the band
            Expr aa = c.fragment().aa();
            Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, band));
            return Region.of(new Statement.Assign(layer, Layers.premultiplied(xyz(colour), mul(w(colour), cov))));
        }
    };

    /**
     * {@link LinearGradientStyle}: the shape filled from {@code from} to {@code to} along the direction, the ends at
     * the shape's own extent that way, mixed premultiplied so a colour fading to transparent does not pass through
     * grey.
     */
    public static final Shadelet.Painter LINEAR_GRADIENT = new Shadelet.Painter() {
        @Override
        public String name() {
            return "linearGradient";
        }

        @Override
        public StyleLayout style() {
            return LinearGradientStyleLayout.LAYOUT;
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Fragment fr = c.fragment();
            Expr dx = style.scalar("dx");
            Expr dy = style.scalar("dy");
            // How far the shape reaches along the direction from its centre: a box's half extents projected.
            Expr extent = max(add(mul(abs(dx), x(fr.shape())), mul(abs(dy), y(fr.shape()))), f(1e-3));
            Expr along = dot(fr.local(), v2(dx, dy));
            Expr t = clamp(add(f(0.5), div(along, mul(f(2.0), extent))), f(0.0), f(1.0));
            return Region.of(new Statement.Assign(layer,
                    covered(c, mixed(style.vector("from"), style.vector("to"), t))));
        }
    };

    /** {@link RadialGradientStyle}: the shape filled from {@code inner} at the centre to {@code outer} at the radius. */
    public static final Shadelet.Painter RADIAL_GRADIENT = new Shadelet.Painter() {
        @Override
        public String name() {
            return "radialGradient";
        }

        @Override
        public StyleLayout style() {
            return RadialGradientStyleLayout.LAYOUT;
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr fromCentre = length(sub(c.fragment().local(), v2(style.scalar("x"), style.scalar("y"))));
            Expr t = clamp(div(fromCentre, max(style.scalar("radius"), f(1e-3))), f(0.0), f(1.0));
            return Region.of(new Statement.Assign(layer,
                    covered(c, mixed(style.vector("inner"), style.vector("outer"), t))));
        }
    };

    /** Two straight colours mixed {@code t} of the way, premultiplied: a {@code vec4} premultiplied. */
    private static Expr mixed(Expr from, Expr to, Expr t) {
        return add(scale(Layers.premultiplied(xyz(from), w(from)), sub(f(1.0), t)),
                scale(Layers.premultiplied(xyz(to), w(to)), t));
    }

    /** A premultiplied colour at the shape's coverage, one pixel soft, as a fill's is. */
    private static Expr covered(PaintContext c, Expr premultiplied) {
        Expr aa = c.fragment().aa();
        return scale(premultiplied, Expr.MathCall.smoothstep(neg(aa), aa, x(c.distance())));
    }

    /** All of them, to add to the core set, {@link Condense}'s among them. */
    public static final List<Shadelet> ALL = List.of(WEIGHT, OUTLINE, BOX_MORPH, FIELD_MORPH, RIPPLE, RING,
            LINEAR_GRADIENT, RADIAL_GRADIENT, Condense.MTSDF, Condense.CONDENSE, Condense.ORB);
}
