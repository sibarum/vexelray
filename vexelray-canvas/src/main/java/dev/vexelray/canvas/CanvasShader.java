package dev.vexelray.canvas;

import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.shader.ShaderSource;
import dev.supirvast.vastir.shader.Shaders;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.CoreShadelets;
import dev.vexelray.diag.Diagnostics;
import dev.vexelray.shader.ComposedShader;

import java.util.ArrayList;
import java.util.List;

import static dev.vexelray.ir.Ir.F32;
import static dev.vexelray.ir.Ir.I32;
import static dev.vexelray.ir.Ir.V2;
import static dev.vexelray.ir.Ir.V4;
import static dev.vexelray.ir.Ir.abs;
import static dev.vexelray.ir.Ir.add;
import static dev.vexelray.ir.Ir.f;
import static dev.vexelray.ir.Ir.length;
import static dev.vexelray.ir.Ir.max;
import static dev.vexelray.ir.Ir.min;
import static dev.vexelray.ir.Ir.mul;
import static dev.vexelray.ir.Ir.neg;
import static dev.vexelray.ir.Ir.sub;
import static dev.vexelray.ir.Ir.toInt;
import static dev.vexelray.ir.Ir.v2;
import static dev.vexelray.ir.Ir.v4;
import static dev.vexelray.ir.Ir.w;
import static dev.vexelray.ir.Ir.x;
import static dev.vexelray.ir.Ir.y;
import static dev.vexelray.ir.Ir.z;

/**
 * The Canvas uber-shader, authored as SupirVast {@code core} IR. One vertex + fragment pair draws every primitive in
 * the {@link CanvasVertex} format. The fragment stage is a {@link Composition} of shadelets: each element names its
 * record in the shadelet tables ({@link CanvasVertex#LOC_ELEMENT}), the record names its look, and the look its
 * source of distance and its painters. The canvas's kinds are the {@link CoreShadelets core set}'s looks:
 *
 * <ul>
 *   <li><b>shape</b>, <b>shadow</b>, <b>stroke</b>, <b>lit</b> — the analytic rounded box of {@code shape}
 *       {@code (halfW, halfH, rTop, rBottom)} at {@code local}, filled, softened, ringed or embossed, with the
 *       effect's parameters in {@code uv}.</li>
 *   <li><b>glyph</b> — MSDF: the median of the atlas texel, by a per-vertex {@code screenPxRange} ({@code shape.x}).
 *       Colour and range are per-vertex, so mixed text sizes and colours coexist in one draw.</li>
 *   <li><b>image</b> — the box's coverage multiplied by a texel from the image sampler, tinted by the vertex colour.
 *       This is how a marched viewport, a decoded PNG, or a picture enters the batch: as a rounded box that
 *       samples.</li>
 *   <li><b>field</b> — a distance field in the image sampler, filled with a glow and a shadow.</li>
 * </ul>
 *
 * Every element is then cut by its clip, a rounded box at the fragment's canvas position.
 *
 * <p>The atlas is a combined image sampler at set 0, binding 0 (bound even for shape-only canvases; shapes ignore it).
 * The image sampler is set 1, binding 0 — see {@link #IMAGE_SET}. The shadelet tables are a storage buffer at set 2 —
 * see {@link Composition#TABLES}.
 */
public final class CanvasShader {

    public static final int ATLAS_SET = 0;
    public static final int ATLAS_BINDING = 0;

    /**
     * The image sampler — a <b>second</b> descriptor set, not a second binding in the first.
     *
     * <p>The atlas is bound once per window and never changes; an image changes per {@link CanvasVertex#KIND_IMAGE}
     * run. Splitting them by set is what lets the atlas stay bound across every run while set 1 is rebound between
     * them, instead of allocating a fresh two-binding set per image that re-points at the same atlas each time.
     * A canvas with no images binds a 1x1 opaque-white placeholder here, so the layout is uniform and the pipeline
     * never learns whether this frame had images in it.
     */
    public static final int IMAGE_SET = 1;
    public static final int IMAGE_BINDING = 0;

    private CanvasShader() {
    }

    /**
     * Vertex stage: pass clip-space position through and forward every per-vertex attribute as a varying.
     *
     * <p>Loads the SPIR-V pre-compiled at build time by the supirvast-maven-plugin (see {@link Vertex}); only a
     * build without the plugin (IDE, partial classpath) falls back to lowering the IR in-process.
     */
    public static ComposedShader vertex() {
        return new ComposedShader(ShaderStage.VERTEX, Shaders.loadOrLower(new Vertex()), "main");
    }

    /** As {@link #vertex()} for the fragment stage, of the core set: {@link #fragment(Composed)} of {@link Fragment}. */
    public static ComposedShader fragment() {
        return fragment(new Fragment());
    }

    /**
     * The fragment stage of a composition: the SPIR-V its build pre-compiled, or, if that was compiled against other
     * shadelets or layouts (an application built against an older framework), lowered here instead and said once.
     */
    public static ComposedShader fragment(Composed source) {
        if (!Shaders.isPrecompiled(source.name(), source.getClass().getClassLoader())) {
            Diagnostics.dropped("canvas-shader:" + source.name(),
                    "the pre-compiled canvas shader " + source.name(),
                    "the jar has none compiled for these shadelets and layouts (built against another framework, "
                            + "or without the supirvast plugin), so it was lowered at startup instead; rebuild the "
                            + "application to take this off its startup");
        }
        return new ComposedShader(ShaderStage.FRAGMENT, Shaders.loadOrLower(source), "main");
    }

    /** The canvas vertex stage as a build-discoverable {@link ShaderSource} ({@code canvas.vert.spv} in the jar). */
    public static final class Vertex implements ShaderSource {
        @Override
        public String name() {
            return "canvas.vert";
        }

        @Override
        public CoreModule module() {
            return vertexModule();
        }
    }

    /**
     * A canvas fragment stage composed of shadelets, as a build-discoverable {@link ShaderSource}. An application
     * that composes its own subclasses this with a public no-argument constructor, and its build pre-compiles it.
     *
     * <p>Its name is {@code canvas.frag.<manifest hash>}: the hash of which shadelets it holds, at which ids, reading
     * which layouts. So SPIR-V in a jar built against other layouts has another name and is never loaded for this
     * composition, which would read its parameters at the wrong offsets and draw nonsense without an error.
     */
    public abstract static class Composed implements ShaderSource {
        private final Composition composition;

        protected Composed(Composition composition) {
            this.composition = composition;
        }

        public Composition composition() {
            return composition;
        }

        @Override
        public String name() {
            return "canvas.frag." + Long.toHexString(composition.manifest().hash());
        }

        @Override
        public CoreModule module() {
            return fragmentModule(composition);
        }
    }

    /** The canvas fragment stage of the core set ({@code canvas.frag.<hash>.spv} in the jar). */
    public static final class Fragment extends Composed {
        public Fragment() {
            super(CoreShadelets.composition());
        }
    }

    private static CoreModule vertexModule() {
        Attributes in = Attributes.inputs();
        Varyings out = Varyings.outputs();

        Expr pos = new Expr.InterfaceRead(in.pos());
        Expr clip = v4(x(pos), y(pos), f(0.0), f(1.0));
        List<Statement> body = new ArrayList<>();
        body.add(new Statement.BuiltinWrite(Builtin.POSITION, clip));
        body.addAll(in.forwardTo(out));
        body.add(new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX));
    }

    /**
     * The vertex stage's inputs, one per {@link CanvasVertex} attribute, at the locations
     * {@link CanvasVertex#ATTRIBUTES} binds them to.
     *
     * <p>Public because the vertex stage is the <em>only</em> part of this shader that a second placement of the
     * same geometry has to replace. A canvas hung on a plane in the world reads the identical vertex format,
     * forwards the identical varyings, and differs in one expression - where the position goes. Declaring the
     * interface here means the two stages cannot drift apart by a location, which is the failure that produces a
     * black screen and no validation message.
     *
     * <p><b>{@link #clipRs} carries the canvas-space position</b> in {@code xy}, which is what makes a world
     * placement possible at all: {@link #pos} has already been divided down to the 2D target's clip space by
     * {@code Canvas}, but {@code clipRs.xy} is the untouched canvas coordinate the clip SDF is evaluated at. A
     * shader that wants to put this vertex somewhere other than the screen reads that, and ignores {@link #pos}.
     */
    public record Attributes(InterfaceVar pos, InterfaceVar color, InterfaceVar uv, InterfaceVar kind,
                             InterfaceVar local, InterfaceVar shape, InterfaceVar clipBox, InterfaceVar clipRs,
                             InterfaceVar filter, InterfaceVar element) {

        public static Attributes inputs() {
            return new Attributes(
                    InterfaceVar.input("inPos", CanvasVertex.LOC_POS, V2),
                    InterfaceVar.input("inColor", CanvasVertex.LOC_COLOR, V4),
                    InterfaceVar.input("inUv", CanvasVertex.LOC_UV, V2),
                    InterfaceVar.input("inKind", CanvasVertex.LOC_KIND, F32),
                    InterfaceVar.input("inLocal", CanvasVertex.LOC_LOCAL, V2),
                    InterfaceVar.input("inShape", CanvasVertex.LOC_SHAPE, V4),
                    InterfaceVar.input("inClipBox", CanvasVertex.LOC_CLIPBOX, V4),
                    InterfaceVar.input("inClipRs", CanvasVertex.LOC_CLIPRS, V4),
                    InterfaceVar.input("inFilter", CanvasVertex.LOC_FILTER, V4),
                    InterfaceVar.input("inElement", CanvasVertex.LOC_ELEMENT, F32));
        }

        /** The canvas-space position of this vertex, in canvas units - see the record's note on {@code clipRs}. */
        public Expr canvasPosition() {
            Expr rs = new Expr.InterfaceRead(clipRs);
            return v2(x(rs), y(rs));
        }

        /**
         * Copy every attribute except the position through to {@code out}, in location order. The element's record
         * arrives as a float, as every attribute of the canvas's vertex does, and leaves as the flat integer the
         * fragment stage indexes with.
         */
        public List<Statement> forwardTo(Varyings out) {
            return List.of(
                    new Statement.InterfaceWrite(out.color(), new Expr.InterfaceRead(color)),
                    new Statement.InterfaceWrite(out.uv(), new Expr.InterfaceRead(uv)),
                    new Statement.InterfaceWrite(out.kind(), new Expr.InterfaceRead(kind)),
                    new Statement.InterfaceWrite(out.local(), new Expr.InterfaceRead(local)),
                    new Statement.InterfaceWrite(out.shape(), new Expr.InterfaceRead(shape)),
                    new Statement.InterfaceWrite(out.clipBox(), new Expr.InterfaceRead(clipBox)),
                    new Statement.InterfaceWrite(out.clipRs(), new Expr.InterfaceRead(clipRs)),
                    new Statement.InterfaceWrite(out.filter(), new Expr.InterfaceRead(filter)),
                    new Statement.InterfaceWrite(out.element(), toInt(new Expr.InterfaceRead(element))));
        }
    }

    /** The varyings between the two stages: every attribute but the position, which became {@code gl_Position}. */
    public record Varyings(InterfaceVar color, InterfaceVar uv, InterfaceVar kind, InterfaceVar local,
                           InterfaceVar shape, InterfaceVar clipBox, InterfaceVar clipRs, InterfaceVar filter,
                           InterfaceVar element) {

        /** The fragment stage's side. */
        public static Varyings inputs() {
            return new Varyings(
                    InterfaceVar.input("vColor", CanvasVertex.LOC_COLOR, V4),
                    InterfaceVar.input("vUv", CanvasVertex.LOC_UV, V2),
                    InterfaceVar.input("vKind", CanvasVertex.LOC_KIND, F32),
                    InterfaceVar.input("vLocal", CanvasVertex.LOC_LOCAL, V2),
                    InterfaceVar.input("vShape", CanvasVertex.LOC_SHAPE, V4),
                    InterfaceVar.input("vClipBox", CanvasVertex.LOC_CLIPBOX, V4),
                    InterfaceVar.input("vClipRs", CanvasVertex.LOC_CLIPRS, V4),
                    InterfaceVar.input("vFilter", CanvasVertex.LOC_FILTER, V4),
                    InterfaceVar.input("vElement", CanvasVertex.LOC_ELEMENT, I32).asFlat());
        }

        /** The vertex stage's side. */
        public static Varyings outputs() {
            return new Varyings(
                    InterfaceVar.output("vColor", CanvasVertex.LOC_COLOR, V4),
                    InterfaceVar.output("vUv", CanvasVertex.LOC_UV, V2),
                    InterfaceVar.output("vKind", CanvasVertex.LOC_KIND, F32),
                    InterfaceVar.output("vLocal", CanvasVertex.LOC_LOCAL, V2),
                    InterfaceVar.output("vShape", CanvasVertex.LOC_SHAPE, V4),
                    InterfaceVar.output("vClipBox", CanvasVertex.LOC_CLIPBOX, V4),
                    InterfaceVar.output("vClipRs", CanvasVertex.LOC_CLIPRS, V4),
                    InterfaceVar.output("vFilter", CanvasVertex.LOC_FILTER, V4),
                    InterfaceVar.output("vElement", CanvasVertex.LOC_ELEMENT, I32).asFlat());
        }

        /** What a shadelet may read of these, for the given {@code aa} and {@code pixelsPerUnit}. */
        public dev.vexelray.canvas.shadelet.Fragment fragment(Expr aa, Expr pixelsPerUnit) {
            return new dev.vexelray.canvas.shadelet.Fragment(new Expr.InterfaceRead(color),
                    new Expr.InterfaceRead(uv), new Expr.InterfaceRead(local), new Expr.InterfaceRead(shape),
                    new Expr.InterfaceRead(filter), aa, pixelsPerUnit);
        }
    }

    /** Fragment stage: the composition, at a canvas drawn 1:1 in pixels. */
    public static CoreModule fragmentModule(Composition composition) {
        Varyings in = Varyings.inputs();
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);
        // Both scalars are the identity here, and that is the statement worth making: a canvas drawn straight into
        // the frame is authored 1:1 in pixels, so the AA half-width is one canvas unit and a glyph's screenPxRange
        // was already computed in the units it will be read in. Neither stays the identity once the same geometry
        // hangs on a plane in the world, which is why the body takes them instead of assuming.
        Composition.Composed composed = coverage(composition, in, fragColor, f(1.0), f(1.0));
        List<Statement> statements = new ArrayList<>(composed.body());
        statements.add(new Statement.ReturnVoid());
        Region body = new Region(statements);
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule();
        composed.functions().forEach(module::addFunction);
        return module.addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }

    /**
     * The uber-shader's fragment body: the composition's look, then the clip, writing {@code fragColor}; and the
     * functions it calls, which the caller adds to its module.
     *
     * <p>One body for every host, because the hosts differ in exactly two scalars and nothing else. Everything that
     * makes a canvas look like a canvas is authored once, in its shadelets, and read by both, so a fix to a corner
     * case is a fix in both places by construction.
     *
     * <h2>The two scalars, and why they are not constants</h2>
     *
     * <p>{@code aa} is the anti-aliasing half-width <b>in canvas units</b>: the distance either side of an edge
     * over which coverage ramps from 1 to 0. On a screen-aligned canvas it is 1, because a canvas unit is a
     * pixel and a one-pixel ramp is what analytic AA means. On a plane in the world, one canvas unit projects to
     * however many pixels perspective says it does, and the caller passes the reciprocal of that, per fragment -
     * which is what makes a line's <em>width</em> scale with distance while its <em>edge</em> stays one pixel
     * soft at every distance.
     *
     * <p>{@code pixelsPerUnit} is the same relationship the other way up, and only glyphs need it: an MSDF's
     * coverage is a distance in atlas texels converted to pixels by {@code screenPxRange}, which
     * {@code TextLayout} computed on the assumption that a canvas unit is a pixel. Multiplying by the true
     * pixels-per-unit restores the assumption wherever it stopped holding, and is what keeps text sharp rather
     * than soft as a panel is approached.
     *
     * @param composition   the shadelets
     * @param in            the fragment stage's varyings
     * @param fragColor     the colour output to write
     * @param aa            anti-aliasing half-width in canvas units
     * @param pixelsPerUnit screen pixels covered by one canvas unit
     */
    public static Composition.Composed coverage(Composition composition, Varyings in, InterfaceVar fragColor,
                                                Expr aa, Expr pixelsPerUnit) {
        LocalVar colour = new LocalVar("colour", V4);
        Composition.Composed composed = composition.fragment(in::fragment, aa, pixelsPerUnit,
                new Expr.InterfaceRead(in.element()), colour);

        // The clip: a rounded box at the fragment's canvas position. The vertex carries an AA in clipRs.w as well,
        // and it is ignored in favour of the argument: it is a constant of the Canvas (1 px) stamped into every
        // vertex, so reading it would be interpolating a constant - and on a plane in the world it would be the
        // wrong constant.
        Expr clipBox = new Expr.InterfaceRead(in.clipBox());
        Expr clipRs = new Expr.InterfaceRead(in.clipRs());
        Expr cl = sub(v2(x(clipRs), y(clipRs)), v2(x(clipBox), y(clipBox)));
        Expr clipR = z(clipRs);
        Expr qc = add(sub(abs(cl), v2(z(clipBox), w(clipBox))), v2(clipR, clipR));
        Expr dc = sub(add(length(max(qc, v2(0.0, 0.0))), min(max(x(qc), y(qc)), f(0.0))), clipR);
        Expr clipCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, dc));

        Expr c = new Expr.Read(colour);
        List<Statement> body = new ArrayList<>(composed.body());
        body.add(new Statement.InterfaceWrite(fragColor, v4(x(c), y(c), z(c), mul(w(c), clipCov))));
        return new Composition.Composed(body, composed.functions());
    }
}
