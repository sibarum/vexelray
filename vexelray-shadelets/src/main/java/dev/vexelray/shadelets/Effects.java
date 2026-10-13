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
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.min;
import static dev.vexelray.ir.Ir.mix;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.neg;
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

    /** All of them, to add to the core set. */
    public static final List<Shadelet> ALL = List.of(WEIGHT, OUTLINE, BOX_MORPH, FIELD_MORPH);
}
