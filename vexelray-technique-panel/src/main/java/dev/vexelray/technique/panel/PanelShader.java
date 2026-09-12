package dev.vexelray.technique.panel;

import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;

import java.util.ArrayList;
import java.util.List;

/**
 * The canvas uber-shader with the camera in front of it: the same vertex format, the same coverage core, and a
 * vertex stage that puts each vertex on a plane in the world instead of on the screen.
 *
 * <h2>The projection is the march's, not a matrix</h2>
 *
 * <p>There is no model-view-projection matrix here, and that is deliberate. The scene's camera is a pinhole
 * described by an eye, a yaw, a pitch and a focal length, and its rays are built as {@code (sx, sy, focal)} — so
 * a matrix would be a second description of the same camera, correct until the day the two drift. Instead the
 * CPU resolves the rotation into an affine map from canvas coordinates to view space ({@link Panel#viewBasis}),
 * and this shader applies the same pinhole the march does: {@code ndc.x = focal*vx/(aspect*vz)},
 * {@code ndc.y = -focal*vy/vz}. A panel drawn this way lands exactly where a marched surface at the same world
 * position would.
 *
 * <h2>Depth comes from the rasteriser, and still agrees with the march</h2>
 *
 * <p>{@link ClipDepth#clipZ} is emitted as {@code gl_Position.z}, so hardware interpolation of {@code z/w}
 * reproduces the same hyperbolic curve the march writes to {@code gl_FragDepth}. The panel is therefore occluded
 * by marched geometry per pixel, occludes what is behind it, and pays nothing for the privilege — no
 * {@code gl_FragDepth} write, so the early depth test survives.
 *
 * <h2>Anti-aliasing is computed, which is the whole point</h2>
 *
 * <p>{@code CanvasShader}'s coverage assumes one canvas unit is one pixel, which is what makes a screen-space
 * canvas crisp and what would make a projected one either soft or jagged depending on distance. Here the
 * fragment stage computes the true screen-space Jacobian of the canvas coordinates — the exact derivative,
 * available in closed form because a panel is a plane under a pinhole — and inverts it to get canvas units per
 * pixel. Coverage then ramps over one pixel at every distance and every angle, while the shapes themselves keep
 * the size the world gives them. A 4-unit line is 4 units wide at arm's length and 4 units wide across the room;
 * only its pixel count changes.
 *
 * <p>Screen-space derivatives ({@code fwidth}) would answer the same question approximately, per 2x2 quad, and
 * are not in the IR. The closed form is exact, needs no new instruction, and degrades gracefully: edge-on, the
 * determinant collapses, the AA widens, and the panel fades rather than aliasing.
 */
public final class PanelShader {

    /** The view-space position varying, at the first location past the {@code CanvasVertex} attributes. */
    public static final int LOC_VIEW = 8;

    /** Push-constant members, in order: origin, right, down, focal, aspect, halfHeightPx. */
    public static final int PUSH_FLOATS = 12;

    public static final int PUSH_BYTES = PUSH_FLOATS * Float.BYTES;

    /** Index of the first of the three floats of the canvas origin in view space. */
    public static final int PUSH_ORIGIN = 0;

    /** Index of the first of the three floats of the view-space vector of one canvas unit along {@code +x}. */
    public static final int PUSH_RIGHT = 3;

    /** Index of the first of the three floats of the view-space vector of one canvas unit along {@code +y}. */
    public static final int PUSH_DOWN = 6;

    public static final int PUSH_FOCAL = 9;

    public static final int PUSH_ASPECT = 10;

    /** Half the frame's height in pixels — the scale that turns a normalised device coordinate into pixels. */
    public static final int PUSH_HALF_HEIGHT = 11;

    /**
     * The floor under the Jacobian's determinant, in pixels squared per canvas unit squared.
     *
     * <p>A panel seen exactly edge-on projects to a line: the determinant is zero, the inverse does not exist,
     * and every derived width is infinite. Flooring it keeps the arithmetic finite and turns the degenerate case
     * into a very wide anti-aliasing ramp, which is a panel fading out — the answer a reader would have wanted
     * anyway, and one that arrives without a branch.
     */
    private static final double DET_FLOOR = 1e-9;

    private PanelShader() {
    }

    /**
     * The vertex stage, with {@code depth}'s near and far planes baked into the clip-space z it emits.
     *
     * <p>Lowered here rather than pre-compiled by the build, because the depth convention is the scene's and a
     * pre-compiled module could only have carried one. The fragment stage has no such dependency and is lowered
     * beside it for symmetry; both happen once, at realise.
     */
    public static ComposedShader vertex(ClipDepth depth) {
        return ComposedShader.lower(ShaderStage.VERTEX, vertexModule(depth), "main");
    }

    /** The fragment stage: the canvas coverage core, over a computed anti-aliasing width. */
    public static ComposedShader fragment() {
        return ComposedShader.lower(ShaderStage.FRAGMENT, fragmentModule(), "main");
    }

    /** The push-constant block both stages declare. Built in one place so the two cannot disagree. */
    public static PushConstants block() {
        List<PushConstants.Member> members = new ArrayList<>(PUSH_FLOATS);
        members.add(new PushConstants.Member("originX", Ir.F32));
        members.add(new PushConstants.Member("originY", Ir.F32));
        members.add(new PushConstants.Member("originZ", Ir.F32));
        members.add(new PushConstants.Member("rightX", Ir.F32));
        members.add(new PushConstants.Member("rightY", Ir.F32));
        members.add(new PushConstants.Member("rightZ", Ir.F32));
        members.add(new PushConstants.Member("downX", Ir.F32));
        members.add(new PushConstants.Member("downY", Ir.F32));
        members.add(new PushConstants.Member("downZ", Ir.F32));
        members.add(new PushConstants.Member("focalLength", Ir.F32));
        members.add(new PushConstants.Member("aspect", Ir.F32));
        members.add(new PushConstants.Member("halfHeightPx", Ir.F32));
        return new PushConstants(members);
    }

    private static CoreModule vertexModule(ClipDepth depth) {
        PushConstants pc = block();
        CanvasShader.Attributes in = CanvasShader.Attributes.inputs();
        CanvasShader.Varyings out = CanvasShader.Varyings.outputs();
        InterfaceVar vView = InterfaceVar.output("vView", LOC_VIEW, Ir.V3);

        Expr canvas = in.canvasPosition();
        Expr u = Ir.x(canvas);
        Expr v = Ir.y(canvas);

        // The affine map the CPU resolved: a canvas point is the corner plus u rights plus v downs. Componentwise
        // rather than a vector scale, because the components are three separate push-constant members and
        // assembling two vectors to scale them would cost more than the six multiplies it saves.
        Expr viewX = axis(pc, PUSH_ORIGIN, PUSH_RIGHT, PUSH_DOWN, 0, u, v);
        Expr viewY = axis(pc, PUSH_ORIGIN, PUSH_RIGHT, PUSH_DOWN, 1, u, v);
        Expr viewZ = axis(pc, PUSH_ORIGIN, PUSH_RIGHT, PUSH_DOWN, 2, u, v);

        LocalVar view = new LocalVar("view", Ir.V3);
        Expr vx = Ir.x(new Expr.Read(view));
        Expr vy = Ir.y(new Expr.Read(view));
        Expr vz = Ir.z(new Expr.Read(view));

        // The march's pinhole, run forwards. Its ray is (sx, sy, focal) with sx = (2u-1)*aspect and sy = 1-2v, so
        // for a point at (vx, vy, vz) the screen coordinate is focal*vx/vz over aspect, and the device y is the
        // negative of that in vy — the one sign that has to match, and the reason it is written out here rather
        // than folded into a matrix nobody can read.
        Expr focal = pc.read(PUSH_FOCAL);
        Expr position = new Expr.VectorConstruct(Ir.V4, List.of(
                Ir.div(Ir.mul(focal, vx), pc.read(PUSH_ASPECT)),
                Ir.neg(Ir.mul(focal, vy)),
                depth.clipZ(vz),
                vz));

        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(view, Ir.v3(viewX, viewY, viewZ)));
        body.add(new Statement.BuiltinWrite(Builtin.POSITION, position));
        body.add(new Statement.InterfaceWrite(vView, new Expr.Read(view)));
        body.addAll(in.forwardTo(out));
        body.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX));
    }

    /** {@code origin[c] + u*right[c] + v*down[c]} — one component of the canvas-to-view map. */
    private static Expr axis(PushConstants pc, int origin, int right, int down, int c, Expr u, Expr v) {
        return Ir.add(pc.read(origin + c),
                Ir.add(Ir.mul(u, pc.read(right + c)), Ir.mul(v, pc.read(down + c))));
    }

    private static CoreModule fragmentModule() {
        PushConstants pc = block();
        CanvasShader.Varyings in = CanvasShader.Varyings.inputs();
        InterfaceVar vView = InterfaceVar.input("vView", LOC_VIEW, Ir.V3);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);

        LocalVar view = new LocalVar("view", Ir.V3);
        Expr vx = Ir.x(new Expr.Read(view));
        Expr vy = Ir.y(new Expr.Read(view));
        LocalVar vz = new LocalVar("vz", Ir.F32);
        Expr z = new Expr.Read(vz);

        // d(pixel)/d(canvas unit), exactly. A point projects to pixels at (halfHeightPx*focal) * (vx/vz, -vy/vz)
        // -- the horizontal scale is halfWidthPx/aspect, which is halfHeightPx, which is why one constant serves
        // both axes. Differentiating vx/vz along a canvas axis a gives (a.x*vz - vx*a.z)/vz^2, so the whole
        // Jacobian shares the factor below.
        LocalVar s = new LocalVar("s", Ir.F32);
        Expr scale = new Expr.Read(s);
        LocalVar jxx = new LocalVar("jxx", Ir.F32);
        LocalVar jxy = new LocalVar("jxy", Ir.F32);
        LocalVar jyx = new LocalVar("jyx", Ir.F32);
        LocalVar jyy = new LocalVar("jyy", Ir.F32);
        LocalVar aa = new LocalVar("aa", Ir.F32);

        Expr rx = pc.read(PUSH_RIGHT);
        Expr ry = pc.read(PUSH_RIGHT + 1);
        Expr rz = pc.read(PUSH_RIGHT + 2);
        Expr dx = pc.read(PUSH_DOWN);
        Expr dy = pc.read(PUSH_DOWN + 1);
        Expr dz = pc.read(PUSH_DOWN + 2);

        // The inverse of that 2x2, one-normed per row: the same quantity fwidth() estimates, computed rather
        // than sampled. Row 0 is d(u)/d(pixel) and row 1 is d(v)/d(pixel); the wider of the two is the ramp
        // that keeps the narrower axis from aliasing, which is the conservative choice fwidth also makes.
        Expr det = Ir.sub(Ir.mul(new Expr.Read(jxx), new Expr.Read(jyy)),
                Ir.mul(new Expr.Read(jyx), new Expr.Read(jxy)));
        Expr absDet = Ir.max(Ir.abs(det), Ir.f(DET_FLOOR));
        Expr aaU = Ir.div(Ir.add(Ir.abs(new Expr.Read(jyy)), Ir.abs(new Expr.Read(jyx))), absDet);
        Expr aaV = Ir.div(Ir.add(Ir.abs(new Expr.Read(jxy)), Ir.abs(new Expr.Read(jxx))), absDet);

        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(view, new Expr.InterfaceRead(vView)));
        body.add(new Statement.DeclareVar(vz, Ir.z(new Expr.Read(view))));
        body.add(new Statement.DeclareVar(s, Ir.div(Ir.mul(pc.read(PUSH_HALF_HEIGHT), pc.read(PUSH_FOCAL)),
                Ir.mul(z, z))));
        body.add(new Statement.DeclareVar(jxx, Ir.mul(scale, Ir.sub(Ir.mul(rx, z), Ir.mul(vx, rz)))));
        body.add(new Statement.DeclareVar(jxy, Ir.mul(scale, Ir.sub(Ir.mul(vy, rz), Ir.mul(ry, z)))));
        body.add(new Statement.DeclareVar(jyx, Ir.mul(scale, Ir.sub(Ir.mul(dx, z), Ir.mul(vx, dz)))));
        body.add(new Statement.DeclareVar(jyy, Ir.mul(scale, Ir.sub(Ir.mul(vy, dz), Ir.mul(dy, z)))));
        body.add(new Statement.DeclareVar(aa, Ir.max(aaU, aaV)));
        // Pixels per canvas unit is the reciprocal of the same number, and only the glyph branch reads it.
        body.add(CanvasShader.coverage(in, fragColor, new Expr.Read(aa),
                Ir.div(Ir.f(1.0), new Expr.Read(aa))));
        body.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }
}
