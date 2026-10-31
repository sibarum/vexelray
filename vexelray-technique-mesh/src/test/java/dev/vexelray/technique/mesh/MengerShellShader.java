package dev.vexelray.technique.mesh;

import dev.supirvast.vastir.core.BinaryOp;
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
import dev.supirvast.vastir.core.UnaryOp;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.Bindings;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Shading;
import dev.vexelray.shader.ShadingPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * Prototype (test scope): a fragment stage for {@link MeshShader#vertex} that shows an endless Menger lattice
 * <em>through</em> a mesh. The mesh is a clip shell: it says where a ray starts, never where it stops.
 *
 * <p>At the point where the fragment's ray meets the polygon:
 * <ul>
 *   <li>inside the fractal already: a cut face, lit with the polygon's own normal and tinted by how deep the
 *       entry lies inside the solid;</li>
 *   <li>outside it: sphere-trace on from there for a fixed budget of steps, straight through the back of the mesh
 *       if that is where the ray goes. A hit is lit with the field's gradient and darkened by how many steps it
 *       took, so deep tunnels fade out; a ray that spends its whole budget is drawn as that darkness at the
 *       depth it reached; a ray that runs past {@link #T_MAX} writes the far plane, which the depth test drops.</li>
 * </ul>
 *
 * <p>The lattice is fixed in world space, so a mesh moved or turned shows another slice of the same fractal.
 */
final class MengerShellShader {

    /** Half the lattice period: a sponge's unit cube is {@code 2*CELL} on a side, centred on multiples of it. */
    static final double CELL = 0.6;

    /** Menger levels: holes down to {@code 2*CELL/3^LEVELS}. */
    static final int LEVELS = 4;

    /** The march's whole budget. Low on purpose: what it does not reach fades to dark rather than costing more. */
    static final int STEPS = 48;

    static final double T_MAX = 30.0;

    /** Hit threshold, as a fraction of the distance from the eye: a pixel's worth, roughly. */
    private static final double EPSILON = 0.0006;

    private static final Function MENGER = menger();

    private MengerShellShader() {
    }

    static ComposedShader fragment(Shading shading, ClipDepth depth) {
        return ComposedShader.lower(ShaderStage.FRAGMENT, fragmentModule(shading, depth), "main");
    }

    /**
     * The endless sponge's distance at a world point: Quilez's Menger field with the bounding box left out, so the
     * holes repeat forever. Unrolled, since the level count is a constant.
     */
    private static Function menger() {
        List<Statement> body = new ArrayList<>();
        LocalVar q = new LocalVar("q", Ir.V3);
        LocalVar d = new LocalVar("d", Ir.F32);
        body.add(new Statement.DeclareVar(q, Ir.scale(new Expr.Param(0, Ir.V3), Ir.f(1.0 / CELL))));
        body.add(new Statement.DeclareVar(d, Ir.f(-1.0e9)));
        double s = 1.0;
        for (int level = 0; level < LEVELS; level++) {
            LocalVar a = new LocalVar("a" + level, Ir.V3);
            LocalVar r = new LocalVar("r" + level, Ir.V3);
            // mod(q*s, 2) - 1, as 2*fract(q*s/2) - 1.
            body.add(new Statement.DeclareVar(a, Ir.sub(
                    Ir.scale(Expr.MathCall.fract(Ir.scale(new Expr.Read(q), Ir.f(s * 0.5))), Ir.f(2.0)),
                    Ir.v3(1, 1, 1))));
            s *= 3.0;
            body.add(new Statement.DeclareVar(r, Ir.abs(Ir.sub(Ir.v3(1, 1, 1),
                    Ir.scale(Ir.abs(new Expr.Read(a)), Ir.f(3.0))))));
            Expr rx = Ir.x(new Expr.Read(r));
            Expr ry = Ir.y(new Expr.Read(r));
            Expr rz = Ir.z(new Expr.Read(r));
            Expr cross = Ir.min(Ir.max(rx, ry), Ir.min(Ir.max(ry, rz), Ir.max(rz, rx)));
            body.add(new Statement.Assign(d, Ir.max(new Expr.Read(d),
                    Ir.div(Ir.sub(cross, Ir.f(1.0)), Ir.f(s)))));
        }
        body.add(new Statement.Return(Ir.mul(new Expr.Read(d), Ir.f(CELL))));
        return new Function("menger", new Type.FunctionType(Ir.F32, List.of(Ir.V3)), new Region(body));
    }

    private static Expr field(Expr point) {
        return new Expr.Call(MENGER, List.of(point));
    }

    private static CoreModule fragmentModule(Shading shading, ClipDepth depth) {
        PushConstants pc = MeshShader.block();
        InterfaceVar vWorld = InterfaceVar.input("vWorld", MeshShader.LOC_WORLD, Ir.V3);
        InterfaceVar vNormal = InterfaceVar.input("vNormal", MeshShader.LOC_WORLD_NORMAL, Ir.V3);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);

        LocalVar entry = new LocalVar("entry", Ir.V3);
        LocalVar eye = new LocalVar("eye", Ir.V3);
        LocalVar tEntry = new LocalVar("tEntry", Ir.F32);
        LocalVar dir = new LocalVar("dir", Ir.V3);
        LocalVar cosForward = new LocalVar("cosForward", Ir.F32);
        LocalVar d0 = new LocalVar("d0", Ir.F32);
        LocalVar t = new LocalVar("t", Ir.F32);
        LocalVar d = new LocalVar("d", Ir.F32);
        LocalVar steps = new LocalVar("steps", Ir.F32);
        LocalVar colour = new LocalVar("colour", Ir.V3);
        LocalVar depthT = new LocalVar("depthT", Ir.F32);

        Expr cy = pc.read(MeshShader.PUSH_COS_YAW);
        Expr sy = pc.read(MeshShader.PUSH_SIN_YAW);
        Expr cp = pc.read(MeshShader.PUSH_COS_PITCH);
        Expr sp = pc.read(MeshShader.PUSH_SIN_PITCH);
        // The camera's forward axis in the world: MeshShader's viewZ is dot(rel, this).
        Expr forward = Ir.v3(Ir.mul(sy, cp), Ir.neg(sp), Ir.mul(cy, cp));

        Expr threshold = Ir.mul(Ir.f(EPSILON), Ir.add(new Expr.Read(tEntry), new Expr.Read(t)));
        Expr marching = and(and(
                Ir.lt(new Expr.Read(steps), Ir.f(STEPS)),
                Ir.lt(threshold, new Expr.Read(d))),
                Ir.lt(new Expr.Read(t), Ir.f(T_MAX)));
        Region step = Region.of(
                new Statement.Assign(t, Ir.add(new Expr.Read(t), new Expr.Read(d))),
                new Statement.Assign(d, field(at(entry, dir, new Expr.Read(t)))),
                new Statement.Assign(steps, Ir.add(new Expr.Read(steps), Ir.f(1.0))));

        Counter names = new Counter();
        Expr view = Ir.neg(new Expr.Read(dir));

        // A cut face: the polygon's normal, turned to the eye; deeper inside the solid, darker copper.
        Branch cut = new Branch(names);
        Expr polygonNormal = cut.bind("polygonNormal", Expr.MathCall.normalize(new Expr.InterfaceRead(vNormal)));
        Expr facing = Ir.sub(Ir.mul(Ir.step(Ir.f(0.0), Ir.dot(polygonNormal, view)), Ir.f(2.0)), Ir.f(1.0));
        Expr depthInside = Ir.clamp(Ir.div(Ir.neg(new Expr.Read(d0)), Ir.f(CELL * 0.12)), Ir.f(0.0), Ir.f(1.0));
        Expr cutAlbedo = cut.bind("cutAlbedo", Ir.mix(Ir.v3(0.95, 0.62, 0.32), Ir.v3(0.45, 0.16, 0.10),
                Ir.v3(depthInside, depthInside, depthInside)));
        Expr cutShaded = shading.shade(ShadingPoint.diffuse(new Expr.Read(entry),
                Ir.scale(polygonNormal, facing), view, cutAlbedo), cut);
        cut.statements.add(new Statement.Assign(colour, cutShaded));
        cut.statements.add(new Statement.Assign(depthT, new Expr.Read(tEntry)));

        // A hit on the fractal: its gradient, by the tetrahedral four-tap; faded by the steps it took.
        Branch surface = new Branch(names);
        Expr hit = surface.bind("hit", at(entry, dir, new Expr.Read(t)));
        Expr normal = surface.bind("normal", gradient(hit));
        Expr shaded = surface.bind("shaded", shading.shade(ShadingPoint.diffuse(hit, normal, view,
                Ir.v3(0.80, 0.82, 0.86)), surface));
        Expr fade = Expr.MathCall.pow(Ir.sub(Ir.f(1.0), Ir.div(new Expr.Read(steps), Ir.f(STEPS))), Ir.f(1.5));
        surface.statements.add(new Statement.Assign(colour, Ir.mix(Ir.v3(0.015, 0.015, 0.03), shaded,
                Ir.v3(fade, fade, fade))));
        surface.statements.add(new Statement.Assign(depthT,
                Ir.add(new Expr.Read(tEntry), new Expr.Read(t))));

        Region hitRegion = Region.of(new Statement.If(Ir.lt(new Expr.Read(steps), Ir.f(0.5)),
                new Region(cut.statements), new Region(surface.statements)));
        // No hit: past T_MAX the far plane (dropped by the depth test); budget spent, darkness where it stopped.
        Region missRegion = Region.of(new Statement.If(Ir.lt(new Expr.Read(t), Ir.f(T_MAX)),
                Region.of(new Statement.Assign(depthT, Ir.add(new Expr.Read(tEntry), new Expr.Read(t)))),
                Region.of(new Statement.Assign(depthT, Ir.f(1.0e7)))));

        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(entry, new Expr.InterfaceRead(vWorld)));
        body.add(new Statement.DeclareVar(eye, Ir.v3(pc.read(MeshShader.PUSH_EYE),
                pc.read(MeshShader.PUSH_EYE + 1), pc.read(MeshShader.PUSH_EYE + 2))));
        body.add(new Statement.DeclareVar(tEntry, Ir.length(Ir.sub(new Expr.Read(entry), new Expr.Read(eye)))));
        body.add(new Statement.DeclareVar(dir, Ir.scale(Ir.sub(new Expr.Read(entry), new Expr.Read(eye)),
                Ir.div(Ir.f(1.0), new Expr.Read(tEntry)))));
        body.add(new Statement.DeclareVar(cosForward, Ir.dot(new Expr.Read(dir), forward)));
        body.add(new Statement.DeclareVar(d0, field(new Expr.Read(entry))));
        body.add(new Statement.DeclareVar(t, Ir.f(0.0)));
        body.add(new Statement.DeclareVar(d, new Expr.Read(d0)));
        body.add(new Statement.DeclareVar(steps, Ir.f(0.0)));
        body.add(new Statement.DeclareVar(colour, Ir.v3(0.015, 0.015, 0.03)));
        body.add(new Statement.DeclareVar(depthT, Ir.f(0.0)));
        body.add(new Statement.While(marching, step));
        body.add(new Statement.If(new Expr.Unary(UnaryOp.LOGICAL_NOT, Ir.lt(threshold, new Expr.Read(d))),
                hitRegion, missRegion));
        // One write, on every path: a fragment that writes gl_FragDepth anywhere must write it everywhere.
        body.add(depth.write(new Expr.Read(depthT), new Expr.Read(cosForward)));
        body.add(new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4, List.of(
                Ir.x(new Expr.Read(colour)), Ir.y(new Expr.Read(colour)), Ir.z(new Expr.Read(colour)),
                Ir.f(1.0)))));
        body.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        return new CoreModule().addFunction(MENGER).addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }

    private static Expr at(LocalVar origin, LocalVar dir, Expr t) {
        return Ir.add(new Expr.Read(origin), Ir.scale(new Expr.Read(dir), t));
    }

    private static Expr gradient(Expr p) {
        double e = 0.0008;
        Expr sum = null;
        double[][] taps = {{1, -1, -1}, {-1, -1, 1}, {-1, 1, -1}, {1, 1, 1}};
        for (double[] k : taps) {
            Expr tap = Ir.scale(Ir.v3(k[0], k[1], k[2]),
                    field(Ir.add(p, Ir.v3(k[0] * e, k[1] * e, k[2] * e))));
            sum = sum == null ? tap : Ir.add(sum, tap);
        }
        return Expr.MathCall.normalize(sum);
    }

    private static Expr and(Expr a, Expr b) {
        return new Expr.Binary(BinaryOp.LOGICAL_AND, a, b);
    }

    /** Unique local names across both branches, so the module never declares one twice. */
    private static final class Counter {
        int next;
    }

    /** {@link Bindings} as declarations at the head of one branch. */
    private static final class Branch implements Bindings {

        final List<Statement> statements = new ArrayList<>();
        private final Counter names;

        Branch(Counter names) {
            this.names = names;
        }

        @Override
        public Expr bind(String name, Expr value) {
            LocalVar variable = new LocalVar(name + "_" + names.next++, value.type());
            statements.add(new Statement.DeclareVar(variable, value));
            return new Expr.Read(variable);
        }
    }
}
