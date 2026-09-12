package dev.vexelray.canvas;

import dev.supirvast.vastir.core.BinaryOp;
import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.core.Texture;
import dev.supirvast.vastir.shader.ShaderSource;
import dev.supirvast.vastir.shader.Shaders;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.shader.ComposedShader;

import java.util.ArrayList;
import java.util.List;

/**
 * The Canvas uber-shader, authored as SupirVast {@code core} IR. One vertex + fragment pair draws every primitive
 * in the {@link CanvasVertex} format; the fragment branches on {@code kind}:
 *
 * <ul>
 *   <li><b>shape</b> ({@link CanvasVertex#KIND_SHAPE}) — an analytic rounded-box signed distance evaluated in the
 *       primitive's local pixel frame ({@code local}), with {@code (halfW, halfH, cornerRadius, aa)} in
 *       {@code shape}. A rect is radius 0; a circle is a square with radius = half-size; a line is a thin rounded
 *       rect. Anti-aliased with a {@code smoothstep} across {@code aa} pixels (geometry is authored 1:1 in pixels).</li>
 *   <li><b>glyph</b> ({@link CanvasVertex#KIND_GLYPH}) — MSDF: median of the atlas texel, converted to coverage
 *       with a per-vertex {@code screenPxRange} ({@code shape.x}). Colour and range are per-vertex, so mixed text
 *       sizes and colours coexist in one draw.</li>
 *   <li><b>image</b> ({@link CanvasVertex#KIND_IMAGE}) — the shape SDF's coverage multiplied by a texel from the
 *       image sampler, tinted by the vertex colour. This is how a marched viewport, a decoded PNG, or an icon
 *       enters the batch: as a rounded box that samples.</li>
 * </ul>
 *
 * The atlas is a combined image sampler at set 0, binding 0 (bound even for shape-only canvases; shapes ignore it).
 * The image sampler is set 1, binding 0 — see {@link #IMAGE_SET}.
 */
public final class CanvasShader {

    private static final Type.Float F32 = Type.float32();
    private static final Type.Vector V2 = new Type.Vector(F32, 2);
    private static final Type.Vector V3 = new Type.Vector(F32, 3);
    private static final Type.Vector V4 = new Type.Vector(F32, 4);

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

    /** As {@link #vertex()} for the fragment stage: pre-compiled by the build, lowered in-process only as fallback. */
    public static ComposedShader fragment() {
        return new ComposedShader(ShaderStage.FRAGMENT, Shaders.loadOrLower(new Fragment()), "main");
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

    /** The canvas fragment stage as a build-discoverable {@link ShaderSource} ({@code canvas.frag.spv} in the jar). */
    public static final class Fragment implements ShaderSource {
        @Override
        public String name() {
            return "canvas.frag";
        }

        @Override
        public CoreModule module() {
            return fragmentModule();
        }
    }

    private static CoreModule vertexModule() {
        Attributes in = Attributes.inputs();
        Varyings out = Varyings.outputs();

        Expr pos = new Expr.InterfaceRead(in.pos());
        Expr clip = new Expr.VectorConstruct(V4, List.of(
                new Expr.VectorExtract(pos, 0), new Expr.VectorExtract(pos, 1), f(0.0), f(1.0)));
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
                             InterfaceVar local, InterfaceVar shape, InterfaceVar clipBox, InterfaceVar clipRs) {

        public static Attributes inputs() {
            return new Attributes(
                    InterfaceVar.input("inPos", CanvasVertex.LOC_POS, V2),
                    InterfaceVar.input("inColor", CanvasVertex.LOC_COLOR, V4),
                    InterfaceVar.input("inUv", CanvasVertex.LOC_UV, V2),
                    InterfaceVar.input("inKind", CanvasVertex.LOC_KIND, F32),
                    InterfaceVar.input("inLocal", CanvasVertex.LOC_LOCAL, V2),
                    InterfaceVar.input("inShape", CanvasVertex.LOC_SHAPE, V4),
                    InterfaceVar.input("inClipBox", CanvasVertex.LOC_CLIPBOX, V4),
                    InterfaceVar.input("inClipRs", CanvasVertex.LOC_CLIPRS, V4));
        }

        /** The canvas-space position of this vertex, in canvas units - see the record's note on {@code clipRs}. */
        public Expr canvasPosition() {
            Expr rs = new Expr.InterfaceRead(clipRs);
            return new Expr.VectorConstruct(V2,
                    List.of(new Expr.VectorExtract(rs, 0), new Expr.VectorExtract(rs, 1)));
        }

        /** Copy every attribute except the position through to {@code out}, in location order. */
        public List<Statement> forwardTo(Varyings out) {
            return List.of(
                    new Statement.InterfaceWrite(out.color(), new Expr.InterfaceRead(color)),
                    new Statement.InterfaceWrite(out.uv(), new Expr.InterfaceRead(uv)),
                    new Statement.InterfaceWrite(out.kind(), new Expr.InterfaceRead(kind)),
                    new Statement.InterfaceWrite(out.local(), new Expr.InterfaceRead(local)),
                    new Statement.InterfaceWrite(out.shape(), new Expr.InterfaceRead(shape)),
                    new Statement.InterfaceWrite(out.clipBox(), new Expr.InterfaceRead(clipBox)),
                    new Statement.InterfaceWrite(out.clipRs(), new Expr.InterfaceRead(clipRs)));
        }
    }

    /** The varyings between the two stages: every attribute but the position, which became {@code gl_Position}. */
    public record Varyings(InterfaceVar color, InterfaceVar uv, InterfaceVar kind, InterfaceVar local,
                           InterfaceVar shape, InterfaceVar clipBox, InterfaceVar clipRs) {

        /** The fragment stage's side. */
        public static Varyings inputs() {
            return new Varyings(
                    InterfaceVar.input("vColor", CanvasVertex.LOC_COLOR, V4),
                    InterfaceVar.input("vUv", CanvasVertex.LOC_UV, V2),
                    InterfaceVar.input("vKind", CanvasVertex.LOC_KIND, F32),
                    InterfaceVar.input("vLocal", CanvasVertex.LOC_LOCAL, V2),
                    InterfaceVar.input("vShape", CanvasVertex.LOC_SHAPE, V4),
                    InterfaceVar.input("vClipBox", CanvasVertex.LOC_CLIPBOX, V4),
                    InterfaceVar.input("vClipRs", CanvasVertex.LOC_CLIPRS, V4));
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
                    InterfaceVar.output("vClipRs", CanvasVertex.LOC_CLIPRS, V4));
        }
    }

    /** Fragment stage: branch on {@code kind} - one rounded-box SDF, several transfer functions; MSDF for glyphs. */
    private static CoreModule fragmentModule() {
        Varyings in = Varyings.inputs();
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, V4);
        // Both arguments are the identity here, and that is the statement worth making: a canvas drawn straight
        // into the frame is authored 1:1 in pixels, so the AA half-width is one canvas unit and a glyph's
        // screenPxRange was already computed in the units it will be read in. Neither stays the identity once the
        // same geometry hangs on a plane in the world, which is why coverage() takes them instead of assuming.
        Region body = Region.of(coverage(in, fragColor, f(1.0), f(1.0)), new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }

    /**
     * The uber-shader's coverage core: the {@code kind} dispatch and every transfer function over the one
     * rounded-box SDF, as a single statement writing {@code fragColor}.
     *
     * <p>Extracted from the fragment stage rather than copied into a second one, because the two differ in
     * exactly two scalars and nothing else. Everything that makes a canvas look like a canvas - the analytic
     * corner, the shadow's squared falloff, the stroke ring, the emboss, the MSDF median - is authored once and
     * read by both, so a fix to a corner case is a fix in both places by construction.
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
     * @param in            the fragment stage's varyings
     * @param fragColor     the colour output to write
     * @param aa            anti-aliasing half-width in canvas units
     * @param pixelsPerUnit screen pixels covered by one canvas unit
     */
    public static Statement coverage(Varyings in, InterfaceVar fragColor, Expr aa, Expr pixelsPerUnit) {
        Texture atlas = new Texture("uAtlas", ATLAS_SET, ATLAS_BINDING);
        Texture image = new Texture("uImage", IMAGE_SET, IMAGE_BINDING);

        Expr color = new Expr.InterfaceRead(in.color());
        Expr rgb = new Expr.VectorConstruct(V3, List.of(x(color), y(color), z(color)));

        // --- clip: rounded-box SDF coverage at the fragment's canvas position, multiplied into alpha ---
        Expr clipBox = new Expr.InterfaceRead(in.clipBox());
        Expr clipRs = new Expr.InterfaceRead(in.clipRs());
        Expr clipCenter = new Expr.VectorConstruct(V2, List.of(x(clipBox), y(clipBox)));
        Expr clipHalf = new Expr.VectorConstruct(V2, List.of(z(clipBox), w(clipBox)));
        Expr fragScreen = new Expr.VectorConstruct(V2, List.of(x(clipRs), y(clipRs)));
        Expr clipR = z(clipRs);
        // The vertex carries an AA in clipRs.w as well, and it is ignored in favour of the argument: it is a
        // constant of the Canvas (1 px) stamped into every vertex, so reading it would be interpolating a
        // constant - and on a plane in the world it would be the wrong constant.
        Expr cl = sub(fragScreen, clipCenter);
        Expr qc = add(sub(Expr.MathCall.abs(cl), clipHalf), new Expr.VectorConstruct(V2, List.of(clipR, clipR)));
        Expr outsideC = Expr.MathCall.length(Expr.MathCall.max(qc, v2(0.0, 0.0)));
        Expr insideC = Expr.MathCall.min(Expr.MathCall.max(x(qc), y(qc)), f(0.0));
        Expr dc = sub(add(outsideC, insideC), clipR);
        Expr clipCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, dc));

        Expr alpha = mul(w(color), clipCov);
        Expr shape = new Expr.InterfaceRead(in.shape());
        Expr uv = new Expr.InterfaceRead(in.uv());

        // --- shape kinds: one analytic rounded-box SDF, several transfer functions over its distance ---
        Expr local = new Expr.InterfaceRead(in.local());
        Expr half = new Expr.VectorConstruct(V2, List.of(x(shape), y(shape)));
        Expr rTop = z(shape);
        Expr rBottom = w(shape);
        Expr d = roundedBoxSdf(local, half, rTop, rBottom);

        // KIND_SHAPE - flat fill.
        Expr shapeCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, d));
        Expr shapeOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, shapeCov)));

        // KIND_SHADOW - coverage is a soft falloff over uv.x blur px around the edge; squared, so the tail eases
        // out gaussian-ish instead of stopping dead at the smoothstep edge. Also an outer glow when tinted.
        Expr blur = Expr.MathCall.max(x(uv), aa);
        Expr shadowS = sub(f(1.0), Expr.MathCall.smoothstep(neg(blur), blur, d));
        Expr shadowCov = mul(shadowS, shadowS);
        Expr shadowOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, shadowCov)));

        // KIND_STROKE - a ring of width uv.x hugging the inside of the edge: abs(d + w/2) - w/2 re-centres the
        // zero level set onto the ring, then the normal AA coverage applies.
        Expr halfWStroke = mul(x(uv), f(0.5));
        Expr dRing = sub(Expr.MathCall.abs(add(d, halfWStroke)), halfWStroke);
        Expr strokeCov = sub(f(1.0), Expr.MathCall.smoothstep(neg(aa), aa, dRing));
        Expr strokeOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, strokeCov)));

        // KIND_LIT - fill coverage, colour modulated by light. The emboss trick: evaluate the same SDF a second
        // time at the fragment shifted toward a fixed top-left light; the difference (bounded by the shift, since
        // an SDF is 1-Lipschitz) is +1 on light-facing edges and -1 on shaded ones. A band mask confines it to
        // uv.x bevel px inside the edge so the interior stays flat. uv.y adds a vertical luminance gradient.
        Expr bevel = Expr.MathCall.max(x(uv), f(1.0));
        Expr lightOff = f(1.5);
        Expr shift = mul(lightOff, f(0.7071));   // unit top-left light dir scaled to the offset
        Expr local2 = sub(local, new Expr.VectorConstruct(V2, List.of(shift, shift)));
        Expr d2 = roundedBoxSdf(local2, half, rTop, rBottom);
        Expr light = div(sub(d2, d), lightOff);                        // in [-1, 1]: an SDF is 1-Lipschitz
        Expr band = Expr.MathCall.clamp(add(f(1.0), div(d, bevel)), f(0.0), f(1.0));
        Expr grad = y(uv);
        Expr gy = Expr.MathCall.clamp(
                div(add(y(local), y(half)), mul(f(2.0), Expr.MathCall.max(y(half), f(1.0)))),
                f(0.0), f(1.0));
        Expr brightness = mul(add(f(1.0), mul(mul(light, band), f(0.45))),
                Expr.MathCall.mix(add(f(1.0), grad), sub(f(1.0), grad), gy));
        Expr litRgb = Expr.MathCall.clamp(
                new Expr.VectorConstruct(V3, List.of(mul(x(rgb), brightness), mul(y(rgb), brightness),
                        mul(z(rgb), brightness))),
                new Expr.VectorConstruct(V3, List.of(f(0.0), f(0.0), f(0.0))),
                new Expr.VectorConstruct(V3, List.of(f(1.0), f(1.0), f(1.0))));
        Expr litOut = new Expr.VectorConstruct(V4,
                List.of(x(litRgb), y(litRgb), z(litRgb), mul(alpha, shapeCov)));

        // KIND_GLYPH - MSDF median + screenPxRange, the latter carried per vertex in the units the canvas was
        // laid out in and rescaled here into the units this fragment is actually being drawn at.
        Expr msd = new Expr.SampleTexture(atlas, uv);
        Expr median = Expr.MathCall.max(Expr.MathCall.min(x(msd), y(msd)),
                Expr.MathCall.min(Expr.MathCall.max(x(msd), y(msd)), z(msd)));
        Expr spr = mul(x(shape), pixelsPerUnit);
        Expr glyphCov = Expr.MathCall.clamp(
                add(mul(spr, sub(median, f(0.5))), f(0.5)), f(0.0), f(1.0));
        Expr glyphOut = new Expr.VectorConstruct(V4, List.of(x(rgb), y(rgb), z(rgb), mul(alpha, glyphCov)));

        // KIND_IMAGE - the shape SDF's own coverage, multiplied by a texel from the image sampler and tinted by
        // the vertex colour. Straight-alpha texel: its alpha multiplies coverage, so a transparent PNG and a
        // rounded corner compose rather than fight. A white opaque tint is the identity, which is what a viewport
        // wants; anything else modulates, which is what an icon wants.
        Expr texel = new Expr.SampleTexture(image, uv);
        Expr imageOut = new Expr.VectorConstruct(V4, List.of(
                mul(x(rgb), x(texel)), mul(y(rgb), y(texel)), mul(z(rgb), z(texel)),
                mul(mul(alpha, shapeCov), w(texel))));

        // Dispatch: kind < 0.5 shape, < 1.5 glyph, < 2.5 shadow, < 3.5 stroke, < 4.5 lit, else image.
        Expr kind = new Expr.InterfaceRead(in.kind());
        Region imageRegion = Region.of(new Statement.InterfaceWrite(fragColor, imageOut));
        Region litRegion = Region.of(new Statement.InterfaceWrite(fragColor, litOut));
        Region strokeRegion = Region.of(new Statement.InterfaceWrite(fragColor, strokeOut));
        Region shadowRegion = Region.of(new Statement.InterfaceWrite(fragColor, shadowOut));
        Region glyphRegion = Region.of(new Statement.InterfaceWrite(fragColor, glyphOut));
        Region shapeRegion = Region.of(new Statement.InterfaceWrite(fragColor, shapeOut));
        return new Statement.If(lt(kind, 0.5), shapeRegion, Region.of(
                new Statement.If(lt(kind, 1.5), glyphRegion, Region.of(
                        new Statement.If(lt(kind, 2.5), shadowRegion, Region.of(
                                new Statement.If(lt(kind, 3.5), strokeRegion, Region.of(
                                        new Statement.If(lt(kind, 4.5), litRegion, imageRegion)))))))));
    }

    /**
     * The analytic rounded-box SDF at {@code local}, for half-extents {@code half}, with the corner radius
     * selected by vertical half: {@code rTop} above the centre line, {@code rBottom} below (local is y-down, so
     * top is negative y). A uniform box passes the same value twice; a tab passes {@code (r, 0)}.
     */
    private static Expr roundedBoxSdf(Expr local, Expr half, Expr rTop, Expr rBottom) {
        Expr r = Expr.MathCall.mix(rTop, rBottom, Expr.MathCall.step(f(0.0), y(local)));
        Expr q = add(sub(Expr.MathCall.abs(local), half), new Expr.VectorConstruct(V2, List.of(r, r)));
        Expr outside = Expr.MathCall.length(Expr.MathCall.max(q, v2(0.0, 0.0)));
        Expr inside = Expr.MathCall.min(Expr.MathCall.max(x(q), y(q)), f(0.0));
        return sub(add(outside, inside), r);
    }

    // --- tiny IR helpers ---
    private static Expr f(double v) {
        return new Expr.ConstFloat(F32, v);
    }

    private static Expr v2(double a, double b) {
        return new Expr.VectorConstruct(V2, List.of(f(a), f(b)));
    }

    private static Expr x(Expr v) {
        return new Expr.VectorExtract(v, 0);
    }

    private static Expr y(Expr v) {
        return new Expr.VectorExtract(v, 1);
    }

    private static Expr z(Expr v) {
        return new Expr.VectorExtract(v, 2);
    }

    private static Expr w(Expr v) {
        return new Expr.VectorExtract(v, 3);
    }

    private static Expr add(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.ADD, a, b);
    }

    private static Expr sub(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.SUB, a, b);
    }

    private static Expr mul(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.MUL, a, b);
    }

    private static Expr div(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.DIV, a, b);
    }

    private static Expr neg(Expr a) {
        return new Expr.Binary(BinaryOp.SUB, f(0.0), a);
    }

    private static Expr lt(Expr a, double b) {
        return new Expr.Binary(BinaryOp.LESS_THAN, a, f(b));
    }
}
