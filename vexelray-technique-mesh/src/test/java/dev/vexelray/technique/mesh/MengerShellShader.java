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
 * <p>The lattice lives in a {@link FieldSpace} of its own, placed per shell in the object or in the world. A
 * fragment maps its entry point and ray into that space once, and marches there: a point at world distance
 * {@code t} along the ray is {@code fieldEntry + fieldDir * t}, and a field distance is a world distance times the
 * map's scale {@code k}, so each step is the field's distance over {@code k}.
 */
final class MengerShellShader {

    /** {@link MeshShader}'s push constants, then the shell's {@link FieldSpace#worldToField} map. 128 bytes. */
    static final int PUSH_FLOATS = MeshShader.PUSH_FLOATS + FieldSpace.FLOATS;

    static final int PUSH_BYTES = PUSH_FLOATS * Float.BYTES;

    /** Index of the field map: quaternion (four), offset (three), scale. The fragment stage alone reads it. */
    static final int PUSH_FIELD = MeshShader.PUSH_FLOATS;

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

    /** What darkens a hit on the fractal toward the dark it fades into. */
    enum Fade {
        /** The steps the march took: {@code (1 - steps/STEPS)^1.5}. Brushing past an edge costs steps too. */
        STEPS,
        /**
         * The distance travelled from the entry, in field units so it scales with the field:
         * {@code exp(-distance / FADE_LENGTH)}. Depth reads as depth, however hard the march worked for it.
         */
        DISTANCE,
        /** Nothing: every hit at full strength. For {@link Mirror}, where depth is told by what is reflected. */
        NONE
    }

    /** Field distance over which {@link Fade#DISTANCE} falls to 1/e: one lattice half-period. */
    static final double FADE_LENGTH = CELL;

    /**
     * Polished surfaces, and the world they reflect beyond the lattice: the {@link Sky}, and a ground plane at
     * {@code y = 0} of albedo {@code ground} ({@code {r, g, b}}), lit by the scene's shading.
     *
     * <p>The fractal is metal: a reflected ray marches the lattice again, in field space, for
     * {@link #REFLECT_STEPS}, and takes the ground or the sky if it gets clear, or, with exits baked in, if it
     * leaves the shell. A cut face is polished copper that reflects only the ground and the sky: its reflected ray
     * leaves the shell at once, and outside the shell the fractal is clipped away.
     */
    record Mirror(double[] ground) {
    }

    /** The reflected ray's own budget. */
    static final int REFLECT_STEPS = 32;

    /** Reflectance head-on (Schlick's F0): the metal lattice, and the copper of a cut face. */
    private static final double METAL_F0 = 0.5;
    private static final double COPPER_F0 = 0.3;

    static ComposedShader fragment(Shading shading, ClipDepth depth) {
        return fragment(shading, depth, null);
    }

    /**
     * The same, except that a ray which leaves the shell is <b>transparent</b> rather than marching on: no re-entry,
     * so nothing is drawn beyond the first back face. {@code exitTriangles} is the mesh's triangles in its own
     * coordinates, three floats a corner ({@link #localTriangles}), outward-wound. Each fragment carries its ray into
     * the mesh's coordinates by the model matrix it already has, and finds its exit by testing every triangle,
     * unrolled: exact for any concave shell at any placement, so a shell can move and turn, but every shell drawn
     * with this shader must be that mesh. The cost is per triangle, which is why this is a prototype's way.
     */
    static ComposedShader fragment(Shading shading, ClipDepth depth, float[] exitTriangles) {
        return fragment(shading, depth, exitTriangles, Fade.STEPS);
    }

    /** Either of the above, darkened by {@code fade}. */
    static ComposedShader fragment(Shading shading, ClipDepth depth, float[] exitTriangles, Fade fade) {
        return fragment(shading, depth, exitTriangles, fade, null);
    }

    /** Any of the above, polished if {@code mirror} is not null. */
    static ComposedShader fragment(Shading shading, ClipDepth depth, float[] exitTriangles, Fade fade,
                                   Mirror mirror) {
        return ComposedShader.lower(ShaderStage.FRAGMENT,
                fragmentModule(shading, depth, exitTriangles, fade, mirror), "main");
    }

    /** {@code mesh}'s corners in its own coordinates, three floats each: what {@code exitTriangles} takes. */
    static float[] localTriangles(Mesh mesh) {
        float[] vertices = mesh.vertices();
        float[] xyz = new float[mesh.vertexCount() * 3];
        for (int i = 0; i < mesh.vertexCount(); i++) {
            System.arraycopy(vertices, i * Mesh.FLOATS_PER_VERTEX + Mesh.POSITION, xyz, i * 3, 3);
        }
        return xyz;
    }

    /**
     * The mesh's block with the field map after it. The vertex stage declares only the mesh's part, at the same
     * offsets, which the one push range both stages share covers.
     */
    static PushConstants block() {
        List<PushConstants.Member> members = new ArrayList<>(MeshShader.block().members());
        for (String name : new String[]{"fieldQx", "fieldQy", "fieldQz", "fieldQw",
                "fieldOx", "fieldOy", "fieldOz", "fieldScale"}) {
            members.add(new PushConstants.Member(name, Ir.F32));
        }
        return new PushConstants(members);
    }

    /**
     * The endless sponge's distance at a field point: Quilez's Menger field with the bounding box left out, so the
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

    /** The field's distance at a field point, as a world distance: field distances are world ones times k. */
    private static Expr worldDistance(Expr fieldPoint, LocalVar k) {
        return Ir.div(field(fieldPoint), new Expr.Read(k));
    }

    /**
     * {@code v} turned by the unit quaternion {@code (u, w)}: {@code v + 2(w(u×v) + u×(u×v))}. Its inverse is the
     * same with {@code u} negated.
     */
    private static Expr rotate(Expr u, Expr w, Expr v) {
        Expr uv = Expr.MathCall.cross(u, v);
        return Ir.add(v, Ir.scale(Ir.add(Ir.scale(uv, w), Expr.MathCall.cross(u, uv)), Ir.f(2.0)));
    }

    private static CoreModule fragmentModule(Shading shading, ClipDepth depth, float[] exitTriangles,
                                             Fade fadeBy, Mirror mirror) {
        PushConstants pc = block();
        Function exit = exitTriangles == null ? null : exitFunction(exitTriangles);
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
        // Where the ray leaves the shell, from the entry; unbounded unless the exit is baked in.
        LocalVar exitT = new LocalVar("exitT", Ir.F32);
        // The ray in field space: fieldEntry + fieldDir * t, for the same world distance t. fieldDir's length is k.
        LocalVar k = new LocalVar("k", Ir.F32);
        LocalVar fieldEntry = new LocalVar("fieldEntry", Ir.V3);
        LocalVar fieldDir = new LocalVar("fieldDir", Ir.V3);
        Expr fieldQ = Ir.v3(pc.read(PUSH_FIELD), pc.read(PUSH_FIELD + 1), pc.read(PUSH_FIELD + 2));
        Expr fieldW = pc.read(PUSH_FIELD + 3);
        Expr fieldOffset = Ir.v3(pc.read(PUSH_FIELD + 4), pc.read(PUSH_FIELD + 5), pc.read(PUSH_FIELD + 6));

        Expr cy = pc.read(MeshShader.PUSH_COS_YAW);
        Expr sy = pc.read(MeshShader.PUSH_SIN_YAW);
        Expr cp = pc.read(MeshShader.PUSH_COS_PITCH);
        Expr sp = pc.read(MeshShader.PUSH_SIN_PITCH);
        // The camera's forward axis in the world: MeshShader's viewZ is dot(rel, this).
        Expr forward = Ir.v3(Ir.mul(sy, cp), Ir.neg(sp), Ir.mul(cy, cp));

        Expr threshold = Ir.mul(Ir.f(EPSILON), Ir.add(new Expr.Read(tEntry), new Expr.Read(t)));
        Expr inside = Ir.lt(new Expr.Read(t), new Expr.Read(exitT));
        Expr marching = and(and(and(
                Ir.lt(new Expr.Read(steps), Ir.f(STEPS)),
                Ir.lt(threshold, new Expr.Read(d))),
                Ir.lt(new Expr.Read(t), Ir.f(T_MAX))),
                inside);
        Region step = Region.of(
                new Statement.Assign(t, Ir.add(new Expr.Read(t), new Expr.Read(d))),
                new Statement.Assign(d, worldDistance(at(fieldEntry, fieldDir, new Expr.Read(t)), k)),
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
        Expr cutNormal = cut.bind("cutNormal", Ir.scale(polygonNormal, facing));
        Expr cutShaded = cut.bind("cutShaded", shading.shade(ShadingPoint.diffuse(new Expr.Read(entry),
                cutNormal, view, cutAlbedo), cut));
        if (mirror == null) {
            cut.statements.add(new Statement.Assign(colour, cutShaded));
        } else {
            // Polished copper: the ground and the sky, tinted by the copper, as much as Fresnel says.
            LocalVar cutReflected = new LocalVar("cutReflected", Ir.V3);
            Expr cutRay = cut.bind("cutRay", Expr.MathCall.reflect(new Expr.Read(dir), cutNormal));
            cut.statements.add(new Statement.DeclareVar(cutReflected, Ir.v3(0, 0, 0)));
            cut.statements.addAll(environment(names, shading, mirror, new Expr.Read(entry), cutRay, cutReflected));
            Expr f = cut.bind("cutFresnel", fresnel(COPPER_F0, cutNormal, view));
            cut.statements.add(new Statement.Assign(colour, Ir.mix(cutShaded,
                    Ir.mul(new Expr.Read(cutReflected), cutAlbedo), Ir.v3(f, f, f))));
        }
        cut.statements.add(new Statement.Assign(depthT, new Expr.Read(tEntry)));

        // A hit on the fractal: its gradient, by the tetrahedral four-tap in field space, turned back into the
        // world; faded by the steps it took or the distance it went, as fadeBy says.
        Branch surface = new Branch(names);
        Expr hit = surface.bind("hit", at(entry, dir, new Expr.Read(t)));
        Expr fieldHit = surface.bind("fieldHit", at(fieldEntry, fieldDir, new Expr.Read(t)));
        Expr fieldNormal = surface.bind("fieldNormal", gradient(fieldHit, Ir.mul(Ir.f(0.0008), new Expr.Read(k))));
        Expr normal = surface.bind("normal", rotate(Ir.neg(fieldQ), fieldW, fieldNormal));
        Expr shaded = surface.bind("shaded", shading.shade(ShadingPoint.diffuse(hit, normal, view,
                Ir.v3(0.80, 0.82, 0.86)), surface));
        Expr fade = surface.bind("fade", switch (fadeBy) {
            case STEPS -> Expr.MathCall.pow(Ir.sub(Ir.f(1.0), Ir.div(new Expr.Read(steps), Ir.f(STEPS))),
                    Ir.f(1.5));
            // t is a world distance; times k it is the field's.
            case DISTANCE -> Expr.MathCall.exp(Ir.mul(Ir.f(-1.0 / FADE_LENGTH),
                    Ir.mul(new Expr.Read(t), new Expr.Read(k))));
            case NONE -> Ir.f(1.0);
        });
        Expr lit = shaded;
        if (mirror != null) {
            LocalVar reflected = new LocalVar("reflected", Ir.V3);
            surface.statements.add(new Statement.DeclareVar(reflected, Ir.v3(0, 0, 0)));
            surface.statements.addAll(reflection(names, shading, mirror, k, fieldQ, fieldW, hit, fieldHit,
                    fieldNormal, normal, new Expr.Read(dir), Ir.add(new Expr.Read(tEntry), new Expr.Read(t)),
                    exit, pc, reflected));
            Expr f = surface.bind("fresnel", fresnel(METAL_F0, normal, view));
            lit = surface.bind("polished", Ir.mix(shaded, new Expr.Read(reflected), Ir.v3(f, f, f)));
        }
        surface.statements.add(new Statement.Assign(colour, Ir.mix(Ir.v3(0.015, 0.015, 0.03), lit,
                Ir.v3(fade, fade, fade))));
        surface.statements.add(new Statement.Assign(depthT,
                Ir.add(new Expr.Read(tEntry), new Expr.Read(t))));

        Region hitRegion = Region.of(new Statement.If(Ir.lt(new Expr.Read(steps), Ir.f(0.5)),
                new Region(cut.statements), new Region(surface.statements)));
        // No hit: past T_MAX or out of the shell, the far plane (dropped by the depth test); budget spent,
        // darkness where it stopped.
        Region missRegion = Region.of(new Statement.If(and(Ir.lt(new Expr.Read(t), Ir.f(T_MAX)), inside),
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
        body.add(new Statement.DeclareVar(k, pc.read(PUSH_FIELD + 7)));
        body.add(new Statement.DeclareVar(fieldEntry, Ir.add(Ir.scale(rotate(fieldQ, fieldW, new Expr.Read(entry)),
                new Expr.Read(k)), fieldOffset)));
        body.add(new Statement.DeclareVar(fieldDir, Ir.scale(rotate(fieldQ, fieldW, new Expr.Read(dir)),
                new Expr.Read(k))));
        body.add(new Statement.DeclareVar(d0, worldDistance(new Expr.Read(fieldEntry), k)));
        body.add(new Statement.DeclareVar(t, Ir.f(0.0)));
        body.add(new Statement.DeclareVar(d, new Expr.Read(d0)));
        body.add(new Statement.DeclareVar(steps, Ir.f(0.0)));
        body.add(new Statement.DeclareVar(colour, Ir.v3(0.015, 0.015, 0.03)));
        body.add(new Statement.DeclareVar(depthT, Ir.f(0.0)));
        body.add(new Statement.DeclareVar(exitT, Ir.f(1.0e7)));
        if (exitTriangles != null) {
            // On a back face the ray is heading out, and exit says zero: with no re-entry, it is already outside.
            body.add(new Statement.Assign(exitT, new Expr.Call(exit, List.of(
                    toMesh(pc, new Expr.Read(entry), true), toMesh(pc, new Expr.Read(dir), false)))));
        }
        body.add(new Statement.While(marching, step));
        body.add(new Statement.If(and(new Expr.Unary(UnaryOp.LOGICAL_NOT, Ir.lt(threshold, new Expr.Read(d))),
                inside), hitRegion, missRegion));
        // One write, on every path: a fragment that writes gl_FragDepth anywhere must write it everywhere.
        body.add(depth.write(new Expr.Read(depthT), new Expr.Read(cosForward)));
        body.add(new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4, List.of(
                Ir.x(new Expr.Read(colour)), Ir.y(new Expr.Read(colour)), Ir.z(new Expr.Read(colour)),
                Ir.f(1.0)))));
        body.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        CoreModule module = new CoreModule().addFunction(MENGER);
        if (exit != null) {
            module = module.addFunction(exit);
        }
        if (mirror != null) {
            module = module.addFunction(Sky.FUNCTION);
        }
        return module.addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }

    /** Schlick's Fresnel: {@code f0} head-on, rising to one at grazing. */
    private static Expr fresnel(double f0, Expr normal, Expr view) {
        Expr grazing = Ir.sub(Ir.f(1.0), Ir.clamp(Ir.dot(normal, view), Ir.f(0.0), Ir.f(1.0)));
        return Ir.add(Ir.f(f0), Ir.mul(Ir.f(1.0 - f0), Expr.MathCall.pow(grazing, Ir.f(5.0))));
    }

    /** What a ray from {@code origin} along {@code ray} sees past the lattice: the ground, or else the sky. */
    private static List<Statement> environment(Counter names, Shading shading, Mirror mirror, Expr origin,
                                               Expr ray, LocalVar into) {
        Branch ground = new Branch(names);
        Expr tGround = ground.bind("tGround", Ir.div(Ir.neg(Ir.y(origin)), Ir.y(ray)));
        Expr onGround = ground.bind("onGround", Ir.add(origin, Ir.scale(ray, tGround)));
        double[] g = mirror.ground();
        ground.statements.add(new Statement.Assign(into, shading.shade(ShadingPoint.diffuse(onGround,
                Ir.v3(0, 1, 0), Ir.neg(ray), Ir.v3(g[0], g[1], g[2])), ground)));
        return List.of(new Statement.If(Ir.lt(Ir.y(ray), Ir.f(-1.0e-4)), new Region(ground.statements),
                Region.of(new Statement.Assign(into, Sky.colour(ray)))));
    }

    /**
     * The lattice as a mirror at {@code hit}: the reflected ray marched in field space from just off the surface,
     * no further than the ground, nor, with {@code exit}, than where it leaves the shell, for
     * {@link #REFLECT_STEPS}. A hit there is lit, not reflected again; a ray that gets clear takes
     * {@link #environment}; one that spends its budget in a tunnel takes the dark.
     *
     * @param travelled the world distance from the eye to {@code hit}, which the hit threshold grows with
     * @param exit      {@link #exitFunction}, or null for a ray that may leave the shell and march on
     */
    private static List<Statement> reflection(Counter names, Shading shading, Mirror mirror, LocalVar k,
                                              Expr fieldQ, Expr fieldW, Expr hit, Expr fieldHit,
                                              Expr fieldNormal, Expr normal, Expr dir, Expr travelled,
                                              Function exit, PushConstants pc, LocalVar into) {
        LocalVar ray = new LocalVar("rRay", Ir.V3);
        LocalVar fieldRay = new LocalVar("rFieldRay", Ir.V3);
        LocalVar start = new LocalVar("rStart", Ir.V3);
        LocalVar limit = new LocalVar("rLimit", Ir.F32);
        LocalVar t = new LocalVar("rT", Ir.F32);
        LocalVar d = new LocalVar("rD", Ir.F32);
        LocalVar steps = new LocalVar("rSteps", Ir.F32);

        Expr threshold = Ir.mul(Ir.f(EPSILON), Ir.add(travelled, new Expr.Read(t)));
        List<Statement> out = new ArrayList<>();
        out.add(new Statement.DeclareVar(ray, Expr.MathCall.reflect(dir, normal)));
        out.add(new Statement.DeclareVar(fieldRay, Ir.scale(rotate(fieldQ, fieldW, new Expr.Read(ray)),
                new Expr.Read(k))));
        // Off the surface by a few hit thresholds, in field units, so the march does not start on its own hit.
        out.add(new Statement.DeclareVar(start, Ir.add(fieldHit, Ir.scale(fieldNormal,
                Ir.mul(Ir.mul(Ir.f(4 * EPSILON), travelled), new Expr.Read(k))))));
        out.add(new Statement.DeclareVar(limit, Ir.f(T_MAX)));
        out.add(new Statement.If(Ir.lt(Ir.y(new Expr.Read(ray)), Ir.f(-1.0e-4)),
                Region.of(new Statement.Assign(limit, Expr.MathCall.min(Ir.f(T_MAX),
                        Ir.div(Ir.neg(Ir.y(hit)), Ir.y(new Expr.Read(ray)))))),
                Region.of()));
        if (exit != null) {
            // Clipped like the eye's ray: past the shell's surface the fractal is not there to reflect.
            out.add(new Statement.Assign(limit, Expr.MathCall.min(new Expr.Read(limit), new Expr.Call(exit,
                    List.of(toMesh(pc, hit, true), toMesh(pc, new Expr.Read(ray), false))))));
        }
        out.add(new Statement.DeclareVar(t, Ir.f(0.0)));
        out.add(new Statement.DeclareVar(d, worldDistance(new Expr.Read(start), k)));
        out.add(new Statement.DeclareVar(steps, Ir.f(0.0)));
        out.add(new Statement.While(and(and(
                Ir.lt(new Expr.Read(steps), Ir.f(REFLECT_STEPS)),
                Ir.lt(threshold, new Expr.Read(d))),
                Ir.lt(new Expr.Read(t), new Expr.Read(limit))),
                Region.of(
                        new Statement.Assign(t, Ir.add(new Expr.Read(t), new Expr.Read(d))),
                        new Statement.Assign(d, worldDistance(at(start, fieldRay, new Expr.Read(t)), k)),
                        new Statement.Assign(steps, Ir.add(new Expr.Read(steps), Ir.f(1.0))))));

        // A hit: lit by the scene, with the gradient turned back into the world.
        Branch lattice = new Branch(names);
        Expr where = lattice.bind("rHit", Ir.add(hit, Ir.scale(new Expr.Read(ray), new Expr.Read(t))));
        Expr fieldWhere = lattice.bind("rFieldHit", at(start, fieldRay, new Expr.Read(t)));
        Expr seen = lattice.bind("rNormal", rotate(Ir.neg(fieldQ), fieldW,
                gradient(fieldWhere, Ir.mul(Ir.f(0.0008), new Expr.Read(k)))));
        lattice.statements.add(new Statement.Assign(into, shading.shade(ShadingPoint.diffuse(where, seen,
                Ir.neg(new Expr.Read(ray)), Ir.v3(0.80, 0.82, 0.86)), lattice)));

        Region clear = new Region(environment(names, shading, mirror, hit, new Expr.Read(ray), into));
        Region spent = Region.of(new Statement.Assign(into, Ir.v3(0.07, 0.08, 0.10)));
        out.add(new Statement.If(new Expr.Unary(UnaryOp.LOGICAL_NOT, Ir.lt(threshold, new Expr.Read(d))),
                new Region(lattice.statements),
                Region.of(new Statement.If(Ir.lt(new Expr.Read(t), new Expr.Read(limit)), spent, clear))));
        return out;
    }

    /**
     * {@code float exit(vec3 origin, vec3 dir)}, both in the mesh's own coordinates: Möller–Trumbore against every
     * triangle, keeping the nearest hit past the origin's own, and counting them all. The distance is in units of
     * {@code dir}'s length, so a ray carried into the mesh by {@link #toMesh} keeps measuring world distance.
     *
     * <p>Whether the ray is going in or out is the count's parity, not any face's winding: a ray from a point on or
     * in a closed shell crosses it an odd number of times ahead if it is heading inside, an even number (none
     * included) if it is heading out. Heading in, the first crossing is the exit; heading out, the ray is already
     * outside and the exit is zero. So a mesh need not be wound consistently, and {@link Mesh#box} is not.
     *
     * <p>A triangle edge-on to the ray divides by zero, and the NaNs that follow fail every comparison, which drops
     * it. A ray through an edge or a corner shared by two triangles can count it twice and get the parity wrong;
     * for one pixel in a great many, that is a speck.
     */
    private static Function exitFunction(float[] xyz) {
        LocalVar origin = new LocalVar("origin", Ir.V3);
        LocalVar dir = new LocalVar("dir", Ir.V3);
        LocalVar exitT = new LocalVar("exitT", Ir.F32);
        LocalVar crossings = new LocalVar("crossings", Ir.F32);
        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(origin, new Expr.Param(0, Ir.V3)));
        body.add(new Statement.DeclareVar(dir, new Expr.Param(1, Ir.V3)));
        body.add(new Statement.DeclareVar(exitT, Ir.f(1.0e7)));
        body.add(new Statement.DeclareVar(crossings, Ir.f(0.0)));
        body.addAll(exitTests(xyz, origin, dir, exitT, crossings));
        Expr odd = Ir.lt(Ir.f(0.25), Expr.MathCall.fract(Ir.mul(new Expr.Read(crossings), Ir.f(0.5))));
        body.add(new Statement.If(odd, Region.of(new Statement.Return(new Expr.Read(exitT))),
                Region.of(new Statement.Return(Ir.f(0.0)))));
        return new Function("exit", new Type.FunctionType(Ir.F32, List.of(Ir.V3, Ir.V3)), new Region(body));
    }

    /**
     * A world point, or with {@code point} false a world direction, in the drawn mesh's own coordinates: the model
     * matrix in the push block inverted, as {@code A^T (w - o) / s^2} for a rotation scaled by {@code s}.
     */
    private static Expr toMesh(PushConstants pc, Expr world, boolean point) {
        Expr a = Ir.v3(pc.read(MeshShader.PUSH_MODEL), pc.read(MeshShader.PUSH_MODEL + 1),
                pc.read(MeshShader.PUSH_MODEL + 2));
        Expr b = Ir.v3(pc.read(MeshShader.PUSH_MODEL + 3), pc.read(MeshShader.PUSH_MODEL + 4),
                pc.read(MeshShader.PUSH_MODEL + 5));
        Expr c = Ir.v3(pc.read(MeshShader.PUSH_MODEL + 6), pc.read(MeshShader.PUSH_MODEL + 7),
                pc.read(MeshShader.PUSH_MODEL + 8));
        Expr rel = point ? Ir.sub(world, Ir.v3(pc.read(MeshShader.PUSH_MODEL + 9),
                pc.read(MeshShader.PUSH_MODEL + 10), pc.read(MeshShader.PUSH_MODEL + 11))) : world;
        return Ir.scale(Ir.v3(Ir.dot(a, rel), Ir.dot(b, rel), Ir.dot(c, rel)), Ir.div(Ir.f(1.0), Ir.dot(a, a)));
    }

    private static List<Statement> exitTests(float[] xyz, LocalVar origin, LocalVar dir, LocalVar exitT,
                                             LocalVar crossings) {
        List<Statement> tests = new ArrayList<>();
        for (int at = 0; at + 9 <= xyz.length; at += 9) {
            int n = at / 9;
            double[] e1 = {xyz[at + 3] - xyz[at], xyz[at + 4] - xyz[at + 1], xyz[at + 5] - xyz[at + 2]};
            double[] e2 = {xyz[at + 6] - xyz[at], xyz[at + 7] - xyz[at + 1], xyz[at + 8] - xyz[at + 2]};
            Expr edge1 = Ir.v3(e1[0], e1[1], e1[2]);
            Expr edge2 = Ir.v3(e2[0], e2[1], e2[2]);
            LocalVar p = new LocalVar("p" + n, Ir.V3);
            LocalVar inv = new LocalVar("inv" + n, Ir.F32);
            LocalVar s = new LocalVar("s" + n, Ir.V3);
            LocalVar q = new LocalVar("q" + n, Ir.V3);
            LocalVar u = new LocalVar("u" + n, Ir.F32);
            LocalVar v = new LocalVar("v" + n, Ir.F32);
            LocalVar h = new LocalVar("h" + n, Ir.F32);
            tests.add(new Statement.DeclareVar(p, Expr.MathCall.cross(new Expr.Read(dir), edge2)));
            tests.add(new Statement.DeclareVar(inv, Ir.div(Ir.f(1.0), Ir.dot(edge1, new Expr.Read(p)))));
            tests.add(new Statement.DeclareVar(s, Ir.sub(new Expr.Read(origin), Ir.v3(xyz[at], xyz[at + 1],
                    xyz[at + 2]))));
            tests.add(new Statement.DeclareVar(q, Expr.MathCall.cross(new Expr.Read(s), edge1)));
            tests.add(new Statement.DeclareVar(u, Ir.mul(Ir.dot(new Expr.Read(s), new Expr.Read(p)),
                    new Expr.Read(inv))));
            tests.add(new Statement.DeclareVar(v, Ir.mul(Ir.dot(new Expr.Read(dir), new Expr.Read(q)),
                    new Expr.Read(inv))));
            tests.add(new Statement.DeclareVar(h, Ir.mul(Ir.dot(edge2, new Expr.Read(q)), new Expr.Read(inv))));
            // Half-open bounds, so a ray through an edge two triangles share is less often counted by both.
            Expr ahead = and(and(and(
                    Ir.lt(Ir.f(0.0), new Expr.Read(u)),
                    new Expr.Unary(UnaryOp.LOGICAL_NOT, Ir.lt(new Expr.Read(v), Ir.f(0.0)))),
                    Ir.lt(Ir.add(new Expr.Read(u), new Expr.Read(v)), Ir.f(1.0))),
                    Ir.lt(Ir.f(1.0e-4), new Expr.Read(h)));
            tests.add(new Statement.If(ahead, Region.of(
                    new Statement.Assign(crossings, Ir.add(new Expr.Read(crossings), Ir.f(1.0))),
                    new Statement.Assign(exitT, Expr.MathCall.min(new Expr.Read(exitT), new Expr.Read(h)))),
                    Region.of()));
        }
        return tests;
    }

    private static Expr at(LocalVar origin, LocalVar dir, Expr t) {
        return Ir.add(new Expr.Read(origin), Ir.scale(new Expr.Read(dir), t));
    }

    private static Expr gradient(Expr p, Expr e) {
        Expr sum = null;
        double[][] taps = {{1, -1, -1}, {-1, -1, 1}, {-1, 1, -1}, {1, 1, 1}};
        for (double[] k : taps) {
            Expr tap = Ir.scale(Ir.v3(k[0], k[1], k[2]),
                    field(Ir.add(p, Ir.scale(Ir.v3(k[0], k[1], k[2]), e))));
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
