package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.Texture;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.ImageFilters;

import java.util.List;

import static dev.vexelray.ir.Ir.V4;
import static dev.vexelray.ir.Ir.abs;
import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.clamp;
import static dev.vexelray.ir.Ir.div;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.length;
import static dev.vexelray.ir.Ir.max;
import static dev.vexelray.ir.Ir.min;
import static dev.vexelray.ir.Ir.mix;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.neg;
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
 * The core set: what every canvas shader is composed of, whatever an application adds. Three sources (the analytic
 * box, MSDF text, a distance field) and the painters that draw what the canvas drew before shadelets, one for each of
 * its kinds, with the same arithmetic.
 *
 * <p>They read their parameters from the vertex, as the kinds did ({@link Fragment#uv()} carries a shadow's blur, a
 * stroke's width, a bevel; {@link Fragment#filter()} an image's filter and a field's effects), so none has a style:
 * the core set draws a canvas exactly as before, and effects with styles are added on top of it.
 */
public final class CoreShadelets {

    /** The glyph atlas, set 0: bound once per window, rebound per font face. */
    public static final Texture ATLAS = new Texture("uAtlas", CanvasShader.ATLAS_SET, CanvasShader.ATLAS_BINDING);

    /** The image slot, set 1: rebound per run of images. A field is an image whose red channel is a distance. */
    public static final Texture IMAGE = new Texture("uImage", CanvasShader.IMAGE_SET, CanvasShader.IMAGE_BINDING);

    private CoreShadelets() {
    }

    // --- sources ---

    /** The analytic rounded box of {@link Fragment#shape()}, at {@link Fragment#local()}: true everywhere. */
    public static final Shadelet.Source BOX = new Shadelet.Source() {
        @Override
        public String name() {
            return "box";
        }

        @Override
        public Expr distance(Fragment fragment, Expr offset, StyleView style) {
            return v2(neg(boxSdf(fragment, add(fragment.local(), offset))), f(1e9));
        }
    };

    /**
     * MSDF text: the median of the atlas texel, in canvas units by the glyph's {@code screenPxRange} ({@code shape.x}).
     * True to about half the pixel range. It does not yet look elsewhere: the vertex carries no atlas texels per unit
     * to move by, so {@code offset} is ignored until text has effects that need it.
     */
    public static final Shadelet.Source MSDF = new Shadelet.Source() {
        @Override
        public String name() {
            return "msdf";
        }

        @Override
        public Expr distance(Fragment fragment, Expr offset, StyleView style) {
            Expr msd = new Expr.SampleTexture(ATLAS, fragment.uv());
            Expr median = max(min(x(msd), y(msd)), min(max(x(msd), y(msd)), z(msd)));
            Expr range = x(fragment.shape());
            return v2(mul(range, sub(median, f(0.5))), mul(f(0.5), range));
        }
    };

    /**
     * A distance field in the image slot: its red channel, made canvas units from the edge by {@code filter.x}. True to
     * half of that, where the field's spread ends. An offset moves the UV by {@code filter.y}, the field's V per unit,
     * on both axes, so a field cell is square.
     */
    public static final Shadelet.Source FIELD = new Shadelet.Source() {
        @Override
        public String name() {
            return "field";
        }

        @Override
        public Expr distance(Fragment fragment, Expr offset, StyleView style) {
            Expr fx = fragment.filter();
            Expr uv = fragment.uv();
            Expr moved = v2(add(x(uv), mul(x(offset), y(fx))), add(y(uv), mul(y(offset), y(fx))));
            Expr d = mul(sub(x(new Expr.SampleTexture(IMAGE, moved)), f(0.5)), x(fx));
            return v2(d, mul(f(0.5), x(fx)));
        }
    };

    // --- painters: the canvas's kinds ---

    /** A flat fill in the vertex colour, one pixel soft: the shape kind. */
    public static final Shadelet.Painter FILL = painter("fill", (c, layer) -> {
        Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(neg(c.fragment().aa()), c.fragment().aa(), sdf(c)));
        return assign(layer, tinted(c.fragment(), cov));
    });

    /** A soft falloff over {@code uv.x} px about the edge, squared so it eases out: the shadow kind. */
    public static final Shadelet.Painter SHADOW = painter("shadow", (c, layer) -> {
        Expr blur = max(x(c.fragment().uv()), c.fragment().aa());
        Expr s = sub(f(1.0), Expr.MathCall.smoothstep(neg(blur), blur, sdf(c)));
        return assign(layer, tinted(c.fragment(), mul(s, s)));
    });

    /** A ring {@code uv.x} wide inside the edge: the stroke kind. */
    public static final Shadelet.Painter STROKE = painter("stroke", (c, layer) -> {
        Expr halfW = mul(x(c.fragment().uv()), f(0.5));
        Expr ring = sub(abs(add(sdf(c), halfW)), halfW);
        Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(neg(c.fragment().aa()), c.fragment().aa(), ring));
        return assign(layer, tinted(c.fragment(), cov));
    });

    /**
     * The fill, lit: the distance a step toward a fixed top-left light, against the distance here, is +1 on edges that
     * face it and -1 on those that do not (a distance is 1-Lipschitz); confined to {@code uv.x} px inside the edge, with
     * {@code uv.y} a vertical gradient. The lit kind.
     */
    public static final Shadelet.Painter EMBOSS = painter("emboss", (c, layer) -> {
        Fragment fr = c.fragment();
        Expr uv = fr.uv();
        Expr sdf = sdf(c);
        Expr bevel = max(x(uv), f(1.0));
        Expr lightOff = f(1.5);
        Expr shift = mul(lightOff, f(0.7071));
        Expr sdf2 = neg(x(c.distanceAt(v2(neg(shift), neg(shift)))));
        Expr light = div(sub(sdf2, sdf), lightOff);
        Expr band = clamp(add(f(1.0), div(sdf, bevel)), f(0.0), f(1.0));
        Expr grad = y(uv);
        Expr halfY = y(fr.shape());
        Expr gy = clamp(div(add(y(fr.local()), halfY), mul(f(2.0), max(halfY, f(1.0)))), f(0.0), f(1.0));
        Expr brightness = mul(add(f(1.0), mul(mul(light, band), f(0.45))), mix(add(f(1.0), grad), sub(f(1.0), grad), gy));
        Expr rgb = xyz(fr.color());
        Expr lit = clamp(v3(mul(x(rgb), brightness), mul(y(rgb), brightness), mul(z(rgb), brightness)),
                v3(f(0.0), f(0.0), f(0.0)), v3(f(1.0), f(1.0), f(1.0)));
        Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(neg(fr.aa()), fr.aa(), sdf));
        return assign(layer, Layers.premultiplied(lit, mul(w(fr.color()), cov)));
    });

    /** Text: coverage linear across a pixel of the median's distance, rescaled to screen pixels. The glyph kind. */
    public static final Shadelet.Painter GLYPH = painter("glyph", (c, layer) -> {
        Expr px = mul(x(c.distance()), c.fragment().pixelsPerUnit());
        Expr cov = clamp(add(px, f(0.5)), f(0.0), f(1.0));
        return assign(layer, tinted(c.fragment(), cov));
    });

    /**
     * The box's coverage times a texel of the image, through the image's filter, tinted by the vertex colour: the
     * image kind. Straight-alpha texel: its alpha multiplies coverage, so a transparent picture and a rounded corner
     * compose rather than fight.
     */
    public static final Shadelet.Painter IMAGE_FILL = new Shadelet.Painter() {
        @Override
        public String name() {
            return "image";
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Fragment fr = c.fragment();
            LocalVar texel = new LocalVar("imageTexel", V4);
            Expr t = new Expr.Read(texel);
            Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(neg(fr.aa()), fr.aa(), sdf(c)));
            Expr rgb = xyz(fr.color());
            return Region.of(
                    new Statement.DeclareVar(texel, new Expr.SampleTexture(IMAGE, fr.uv())),
                    ImageFilters.filtered(IMAGE, fr.uv(), fr.filter(), texel),
                    new Statement.Assign(layer, Layers.premultiplied(v3(mul(x(rgb), x(t)), mul(y(rgb), y(t)),
                            mul(z(rgb), z(t))), mul(mul(w(fr.color()), cov), w(t)))));
        }
    };

    /**
     * A field's shape in the tint, cut one pixel soft; a glow of the tint falling off past the edge; a shadow sampled
     * further up the field, so it falls below. Composited front to back within this one layer, and the whole cut by the
     * quad's own box. The field kind, with {@code filter = (range, v per unit, glow, shadow)}.
     */
    public static final Shadelet.Painter FIELD_FILL = painter("fieldFill", (c, layer) -> {
        Fragment fr = c.fragment();
        Expr fx = fr.filter();
        Expr fieldD = x(c.distance());
        Expr fieldCov = clamp(add(fieldD, f(0.5)), f(0.0), f(1.0));
        Expr glowR = z(fx);
        // The field holds distance only so far out, and the quad ends there: the glow reaches nothing by then, or the
        // quad's edge would show as a square.
        Expr reach = y(c.distance());
        Expr fade = Expr.MathCall.smoothstep(neg(mul(f(0.95), reach)), neg(mul(f(0.35), reach)), fieldD);
        Expr glow = mul(mul(mul(f(0.6), Expr.MathCall.exp(div(neg(max(neg(fieldD), f(0.0))),
                max(glowR, f(1e-3))))), step(f(1e-3), glowR)), fade);
        Expr shadowPx = w(fx);
        Expr shadowD = x(c.distanceAt(v2(f(0.0), neg(mul(f(0.6), shadowPx)))));
        Expr shade = mul(mul(f(0.45), Expr.MathCall.smoothstep(neg(shadowPx), mul(f(0.25), shadowPx), shadowD)),
                step(f(1e-3), shadowPx));
        Expr aGlow = mul(glow, sub(f(1.0), fieldCov));
        Expr aShade = mul(shade, max(sub(sub(f(1.0), fieldCov), aGlow), f(0.0)));
        Expr aField = add(add(fieldCov, aGlow), aShade);
        Expr lit = div(add(fieldCov, aGlow), max(aField, f(1e-6)));   // the tint's share; the rest is shadow
        Expr boxCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(fr.aa()), fr.aa(), boxSdf(fr, fr.local())));
        Expr rgb = xyz(fr.color());
        return assign(layer, Layers.premultiplied(v3(mul(x(rgb), lit), mul(y(rgb), lit), mul(z(rgb), lit)),
                mul(mul(w(fr.color()), boxCov), aField)));
    });

    /** The core set, in the order that fixes their ids: an application's composition starts with these. */
    public static final List<Shadelet> ALL = List.of(BOX, MSDF, FIELD,
            FILL, GLYPH, SHADOW, STROKE, EMBOSS, IMAGE_FILL, FIELD_FILL);

    /** The core composition. */
    public static Composition composition() {
        return Composition.of(ALL);
    }

    // --- helpers ---

    /**
     * The analytic rounded-box signed distance at {@code local}, positive <em>outside</em> as the canvas has always
     * computed it, for {@code shape = (halfW, halfH, rTop, rBottom)}; the radius chosen by vertical half ({@code local}
     * is y-down, so the top is negative y).
     */
    public static Expr boxSdf(Fragment fragment, Expr local) {
        Expr shape = fragment.shape();
        return boxSdf(local, v2(x(shape), y(shape)), z(shape), w(shape));
    }

    /** As {@link #boxSdf(Fragment, Expr)}, for a box given outright: half extents {@code half}, the two radii. */
    public static Expr boxSdf(Expr local, Expr half, Expr rTop, Expr rBottom) {
        Expr r = mix(rTop, rBottom, step(f(0.0), y(local)));
        Expr q = add(sub(abs(local), half), v2(r, r));
        Expr outside = length(max(q, v2(0.0, 0.0)));
        Expr inside = min(max(x(q), y(q)), f(0.0));
        return sub(add(outside, inside), r);
    }

    /** The distance at the fragment, positive outside, as the kinds' arithmetic is written. */
    private static Expr sdf(PaintContext c) {
        return neg(x(c.distance()));
    }

    /** The vertex colour at {@code coverage}, premultiplied. */
    private static Expr tinted(Fragment fragment, Expr coverage) {
        return Layers.premultiplied(xyz(fragment.color()), mul(w(fragment.color()), coverage));
    }

    private static Region assign(LocalVar layer, Expr value) {
        return Region.of(new Statement.Assign(layer, value));
    }

    private interface Paint {
        Region paint(PaintContext context, LocalVar layer);
    }

    private static Shadelet.Painter painter(String name, Paint paint) {
        return new Shadelet.Painter() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Region paint(PaintContext context, StyleView style, LocalVar layer) {
                return paint.paint(context, layer);
            }
        };
    }
}
