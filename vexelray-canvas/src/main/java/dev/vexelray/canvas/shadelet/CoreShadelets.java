package dev.vexelray.canvas.shadelet;

import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.Texture;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.ImageFilters;
import dev.vexelray.text.FieldType;

import java.util.List;

import static dev.vexelray.ir.Ir.V4;
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
 * box, text in whichever {@link FieldType} the fonts are, a distance field); the painters of the canvas's own primitives, which read what they need from the
 * vertex ({@link Fragment#filter()} carries an image's filter and a field's scale) and so have no style; a glow and a
 * drop shadow of any source; and the node painters a GUI draws a box's border, fill and shadow with, every value its
 * element's own. Effects an application chooses are added on top of it.
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
     * True to about half the pixel range. The core set's text source when the fonts are {@link FieldType#MSDF}. It does not yet look elsewhere: the vertex carries no atlas texels per unit
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
     * SDF text: the atlas texel's one channel, which is the distance, in canvas units as {@link #MSDF} makes its median.
     * The core set's text source when the fonts are {@link FieldType#SDF}: one channel read and no median, so a shader
     * composed for them carries no three-channel field anywhere.
     */
    public static final Shadelet.Source SDF = new Shadelet.Source() {
        @Override
        public String name() {
            return "sdf";
        }

        @Override
        public Expr distance(Fragment fragment, Expr offset, StyleView style) {
            Expr sd = x(new Expr.SampleTexture(ATLAS, fragment.uv()));
            Expr range = x(fragment.shape());
            return v2(mul(range, sub(sd, f(0.5))), mul(f(0.5), range));
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

    /**
     * {@link #SDG_STENCIL}'s style, every field the layer's own: the stencil's rectangle in texture coordinates, pulled
     * half a texel in so nothing beyond it is read; which channel holds an {@code sdf}'s distance (one-hot); whether it
     * is an {@code msdf}, the median of three; and screen pixels per unit of the texel's value.
     */
    public static final StyleLayout SDG_STENCIL_STYLE = StyleLayout.of(StyleLayout.perInstance("rect", 4),
            StyleLayout.perInstance("channel", 4), StyleLayout.perInstance("median", 1),
            StyleLayout.perInstance("pxPerValue", 1));

    /**
     * An SDG layer's stencil (vexelray-fields {@code docs/sdg.md} §2.2), in the image slot: read between texel centres
     * at the vertex UV clamped into its rectangle, each channel on its own, then one channel or the median of three, and
     * made canvas units from the edge by {@code pxPerValue}. Its effects would need distance far from the edge, which an
     * SDG stencil does not promise, so {@code offset} is not looked at.
     */
    public static final Shadelet.Source SDG_STENCIL = new Shadelet.Source() {
        @Override
        public String name() {
            return "sdgStencil";
        }

        @Override
        public StyleLayout style() {
            return SDG_STENCIL_STYLE;
        }

        @Override
        public Expr distance(Fragment fragment, Expr offset, StyleView style) {
            Expr rect = style.vector("rect");
            Expr uv = clamp(fragment.uv(), v2(x(rect), y(rect)), v2(z(rect), w(rect)));
            Expr s = new Expr.SampleTexture(IMAGE, uv);
            Expr median = max(min(x(s), y(s)), min(max(x(s), y(s)), z(s)));
            Expr e = mix(dot(s, style.vector("channel")), median, style.scalar("median"));
            Expr px = style.scalar("pxPerValue");
            return v2(mul(sub(e, f(0.5)), px), mul(f(0.5), px));
        }
    };

    // --- painters: the canvas's own primitives ---

    /** A flat fill in the vertex colour, one pixel soft: a box. */
    public static final Shadelet.Painter FILL = painter("fill", (c, layer) -> {
        Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(neg(c.fragment().aa()), c.fragment().aa(), sdf(c)));
        return assign(layer, tinted(c.fragment(), cov));
    });

    /** Text: coverage linear across a pixel of the median's distance, rescaled to screen pixels. A glyph. */
    public static final Shadelet.Painter GLYPH = painter("glyph", (c, layer) -> {
        Expr px = mul(x(c.distance()), c.fragment().pixelsPerUnit());
        Expr cov = clamp(add(px, f(0.5)), f(0.0), f(1.0));
        return assign(layer, tinted(c.fragment(), cov));
    });

    /**
     * The box's coverage times a texel of the image, through the image's filter, tinted by the vertex colour: an
     * image. Straight-alpha texel: its alpha multiplies coverage, so a transparent picture and a rounded corner
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
     * A field's shape in the tint, cut one pixel soft, and cut by the quad's own box. A field, with
     * {@code filter = (range, v per unit, 0, 0)}; its glow and its shadow are {@link #GLOW} and {@link #DROP_SHADOW}.
     */
    public static final Shadelet.Painter FIELD_FILL = painter("fieldFill", (c, layer) -> {
        Fragment fr = c.fragment();
        Expr fieldCov = clamp(add(x(c.distance()), f(0.5)), f(0.0), f(1.0));
        Expr boxCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(fr.aa()), fr.aa(), boxSdf(fr, fr.local())));
        return assign(layer, Layers.premultiplied(xyz(fr.color()), mul(mul(w(fr.color()), boxCov), fieldCov)));
    });

    /**
     * {@link #SDG_PAINT}'s style, every field the layer's own: {@code map} takes the stencil's texture coordinate to the
     * paint's ({@code paint = stencil * map.xy + map.zw}, both being the same canvas point); the paint's rectangle,
     * pulled half a texel in; and whether it is {@code sampled} (1) or the vertex colour alone (0).
     */
    public static final StyleLayout SDG_PAINT_STYLE = StyleLayout.of(StyleLayout.perInstance("map", 4),
            StyleLayout.perInstance("rect", 4), StyleLayout.perInstance("sampled", 1));

    /**
     * An SDG layer painted (vexelray-fields {@code docs/sdg.md} §2.3–2.5): coverage the distance cut half a pixel either
     * side, linear; colour the vertex colour (a solid paint, or white for a sampled one), times a texel of the paint for
     * a sampled one, read between texels inside its rectangle. The vertex alpha carries the layer's opacity. Over what
     * is under it as every element is, by the pipeline's blend, so the layers of a drawing stack in submission order.
     */
    public static final Shadelet.Painter SDG_PAINT = new Shadelet.Painter() {
        @Override
        public String name() {
            return "sdgPaint";
        }

        @Override
        public StyleLayout style() {
            return SDG_PAINT_STYLE;
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Fragment fr = c.fragment();
            Expr map = style.vector("map");
            Expr rect = style.vector("rect");
            Expr uv = fr.uv();
            Expr at = clamp(v2(add(mul(x(uv), x(map)), z(map)), add(mul(y(uv), y(map)), w(map))),
                    v2(x(rect), y(rect)), v2(z(rect), w(rect)));
            Expr sampled = style.scalar("sampled");
            Expr texel = mix(v4(f(1.0), f(1.0), f(1.0), f(1.0)), new Expr.SampleTexture(IMAGE, at),
                    v4(sampled, sampled, sampled, sampled));
            Expr cov = clamp(add(mul(x(c.distance()), fr.pixelsPerUnit()), f(0.5)), f(0.0), f(1.0));
            Expr colour = fr.color();
            Expr rgb = v3(mul(x(colour), x(texel)), mul(y(colour), y(texel)), mul(z(colour), z(texel)));
            return assign(layer, Layers.premultiplied(rgb, mul(mul(w(colour), w(texel)), cov)));
        }
    };

    /** {@link #GLOW}'s style: how far the halo reaches, in canvas units, as the falloff's length. */
    public static final StyleLayout GLOW_STYLE = StyleLayout.of(StyleLayout.shared("radius", 1));

    /**
     * A halo of the vertex colour falling off past the edge, {@code radius} its falloff's length; behind what is in
     * front of it, as a painter after the fill. Of any source: on a field it fades out before the field's own reach,
     * where the quad ends, so the quad's edge never shows as a square.
     */
    public static final Shadelet.Painter GLOW = new Shadelet.Painter() {
        @Override
        public String name() {
            return "glow";
        }

        @Override
        public StyleLayout style() {
            return GLOW_STYLE;
        }

        /** Where the falloff has dropped below one level of eight-bit alpha: {@code 0.6 e^(-x/r) < 1/255}. */
        @Override
        public float reach(float[] shared) {
            return 5f * Math.max(0f, shared[0]);
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr glowR = style.scalar("radius");
            Expr d = x(c.distance());
            Expr reach = y(c.distance());
            Expr fade = Expr.MathCall.smoothstep(neg(mul(f(0.95), reach)), neg(mul(f(0.35), reach)), d);
            Expr glow = mul(mul(mul(f(0.6), Expr.MathCall.exp(div(neg(max(neg(d), f(0.0))),
                    max(glowR, f(1e-3))))), step(f(1e-3), glowR)), fade);
            return assign(layer, tinted(c.fragment(), glow));
        }
    };

    /** {@link #DROP_SHADOW}'s style: how soft the shadow is, in canvas units, and so how far below it falls. */
    public static final StyleLayout DROP_SHADOW_STYLE = StyleLayout.of(StyleLayout.shared("softness", 1));

    /**
     * A soft dark shadow of the shape, sampled {@code 0.6 * softness} further up so it falls below: the distance looked
     * for elsewhere, which is what a painter that needs more than the distance here does. Of any source.
     */
    public static final Shadelet.Painter DROP_SHADOW = new Shadelet.Painter() {
        @Override
        public String name() {
            return "dropShadow";
        }

        @Override
        public StyleLayout style() {
            return DROP_SHADOW_STYLE;
        }

        /** Its offset below, plus its soft tail. */
        @Override
        public float reach(float[] shared) {
            return 1.6f * Math.max(0f, shared[0]);
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr shadowPx = style.scalar("softness");
            Expr shadowD = x(c.distanceAt(v2(f(0.0), neg(mul(f(0.6), shadowPx)))));
            Expr shade = mul(mul(f(0.45), Expr.MathCall.smoothstep(neg(shadowPx), mul(f(0.25), shadowPx), shadowD)),
                    step(f(1e-3), shadowPx));
            return assign(layer, Layers.premultiplied(v3(f(0.0), f(0.0), f(0.0)), mul(w(c.fragment().color()), shade)));
        }
    };

    // --- a node's box: its border, its fill, its shadow, in one quad ---
    //
    // The renderer draws a node's box as one element whose look is these, front to back. Every value is per instance:
    // a node's colours change with its state and its opacity, and a look per colour would be a look per frame. So the
    // looks are few (which of the parts a node has), and what differs node to node is in its record.

    /** {@link #NODE_STROKE}'s style: the ring's colour and width. */
    public static final StyleLayout NODE_STROKE_STYLE = StyleLayout.of(
            StyleLayout.perInstance("colour", 4), StyleLayout.perInstance("width", 1));

    /** A ring {@code width} wide hugging the inside of the box's edge: a node's border. */
    public static final Shadelet.Painter NODE_STROKE = new Shadelet.Painter() {
        @Override
        public String name() {
            return "nodeStroke";
        }

        @Override
        public StyleLayout style() {
            return NODE_STROKE_STYLE;
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr halfW = mul(style.scalar("width"), f(0.5));
            Expr ring = sub(abs(add(sdf(c), halfW)), halfW);
            Expr aa = c.fragment().aa();
            return assign(layer, coloured(style.vector("colour"),
                    sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, ring))));
        }
    };

    /** {@link #NODE_FILL}'s style: the fill's colour. */
    public static final StyleLayout NODE_FILL_STYLE = StyleLayout.of(StyleLayout.perInstance("colour", 4));

    /** The box filled, one pixel soft: a node's background. */
    public static final Shadelet.Painter NODE_FILL = new Shadelet.Painter() {
        @Override
        public String name() {
            return "nodeFill";
        }

        @Override
        public StyleLayout style() {
            return NODE_FILL_STYLE;
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr aa = c.fragment().aa();
            return assign(layer, coloured(style.vector("colour"),
                    sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, sdf(c)))));
        }
    };

    /** {@link #NODE_LIT}'s style: the fill's colour, the bevel's width, the vertical gradient's amplitude. */
    public static final StyleLayout NODE_LIT_STYLE = StyleLayout.of(StyleLayout.perInstance("colour", 4),
            StyleLayout.perInstance("bevel", 1), StyleLayout.perInstance("gradient", 1));

    /** The box filled and lit: a node's background under a lit theme. */
    public static final Shadelet.Painter NODE_LIT = new Shadelet.Painter() {
        @Override
        public String name() {
            return "nodeLit";
        }

        @Override
        public StyleLayout style() {
            return NODE_LIT_STYLE;
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Fragment fr = c.fragment();
            Expr colour = style.vector("colour");
            Expr sdf = sdf(c);
            Expr bevel = max(style.scalar("bevel"), f(1.0));
            Expr lightOff = f(1.5);
            Expr shift = mul(lightOff, f(0.7071));
            Expr sdf2 = neg(x(c.distanceAt(v2(neg(shift), neg(shift)))));
            Expr light = div(sub(sdf2, sdf), lightOff);
            Expr band = clamp(add(f(1.0), div(sdf, bevel)), f(0.0), f(1.0));
            Expr grad = style.scalar("gradient");
            Expr halfY = y(fr.shape());
            Expr gy = clamp(div(add(y(fr.local()), halfY), mul(f(2.0), max(halfY, f(1.0)))), f(0.0), f(1.0));
            Expr brightness = mul(add(f(1.0), mul(mul(light, band), f(0.45))),
                    mix(add(f(1.0), grad), sub(f(1.0), grad), gy));
            Expr rgb = xyz(colour);
            Expr lit = clamp(v3(mul(x(rgb), brightness), mul(y(rgb), brightness), mul(z(rgb), brightness)),
                    v3(f(0.0), f(0.0), f(0.0)), v3(f(1.0), f(1.0), f(1.0)));
            Expr cov = sub(f(1.0), Expr.MathCall.smoothstep(neg(fr.aa()), fr.aa(), sdf));
            return assign(layer, Layers.premultiplied(lit, mul(w(colour), cov)));
        }
    };

    /** {@link #NODE_SHADOW}'s style: the shadow's colour, and the lift it shows. */
    public static final StyleLayout NODE_SHADOW_STYLE = StyleLayout.of(
            StyleLayout.perInstance("colour", 4), StyleLayout.perInstance("lift", 1));

    /**
     * The box's soft shadow, dropped half its {@code lift} and blurred over the whole of it, squared so it eases out: a
     * node's elevation, read from the distance half a lift above.
     */
    public static final Shadelet.Painter NODE_SHADOW = new Shadelet.Painter() {
        @Override
        public String name() {
            return "nodeShadow";
        }

        @Override
        public StyleLayout style() {
            return NODE_SHADOW_STYLE;
        }

        /** The drop, the blur past it, and a pixel more, as a quad is padded by. */
        @Override
        public float reach(float[] shared, float[] instance, float halfW, float halfH) {
            float lift = Math.max(0f, instance[4]);
            return 1.5f * lift + 1f;
        }

        @Override
        public Region paint(PaintContext c, StyleView style, LocalVar layer) {
            Expr lift = style.scalar("lift");
            Expr below = neg(x(c.distanceAt(v2(f(0.0), neg(mul(lift, f(0.5)))))));
            Expr blur = max(lift, c.fragment().aa());
            Expr s = sub(f(1.0), Expr.MathCall.smoothstep(neg(blur), blur, below));
            return assign(layer, coloured(style.vector("colour"), mul(s, s)));
        }
    };

    /**
     * The core set for MSDF fonts, in the order that fixes their ids: an application's composition starts with these.
     * {@link #all(FieldType)} is the same set for either type of font.
     */
    public static final List<Shadelet> ALL = all(FieldType.MSDF);

    /** The core set for fonts of {@code text}: {@link #ALL} with {@link #text(FieldType) its text source} in place. */
    public static List<Shadelet> all(FieldType text) {
        return List.of(BOX, text(text), FIELD, SDG_STENCIL,
                FILL, GLYPH, IMAGE_FILL, FIELD_FILL, GLOW, DROP_SHADOW,
                NODE_STROKE, NODE_FILL, NODE_LIT, NODE_SHADOW, SDG_PAINT);
    }

    /** The core composition, for MSDF fonts. */
    public static Composition composition() {
        return composition(FieldType.MSDF);
    }

    /**
     * The core composition for fonts of {@code text}. One type per shader: its text source is the only one in it, so
     * an application's fonts are all of one type ({@code GuiApp} refuses a set that mixes them).
     */
    public static Composition composition(FieldType text) {
        return Composition.of(all(text));
    }

    /** The source that draws text from atlases of {@code type}. */
    public static Shadelet.Source text(FieldType type) {
        return switch (type) {
            case MSDF -> MSDF;
            case SDF -> SDF;
        };
    }

    /**
     * The type of font the shader with {@code manifest} draws text for: the one whose source it holds, MSDF where it
     * holds both (an application that added the other to the core set).
     *
     * @throws IllegalArgumentException if it holds neither, and so cannot draw the canvas's text at all
     */
    public static FieldType textType(Manifest manifest) {
        for (FieldType type : FieldType.values()) {
            if (manifest.find(text(type).name()).isPresent()) {
                return type;
            }
        }
        throw new IllegalArgumentException("this shader has no text source (" + MSDF.name() + " or " + SDF.name()
                + "), so the canvas's text cannot be drawn. Compose it as CoreShadelets.composition(type).with(...)");
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

    /** The distance at the fragment, positive outside, as the box painters' arithmetic is written. */
    private static Expr sdf(PaintContext c) {
        return neg(x(c.distance()));
    }

    /** {@code colour} (a {@code vec4}, straight alpha) at {@code coverage}, premultiplied. */
    private static Expr coloured(Expr colour, Expr coverage) {
        return Layers.premultiplied(xyz(colour), mul(w(colour), coverage));
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
