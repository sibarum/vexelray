package dev.vexelray.technique.mesh;

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
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.Bindings;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Shading;
import dev.vexelray.shader.ShadingPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * The mesh shader, as {@code core} IR: a vertex stage that places a triangle in the world and projects it through
 * the march's own pinhole, and a fragment stage that hands the point to the scene's {@link Shading}.
 *
 * <h2>The projection is the march's, not a matrix</h2>
 *
 * <p>For the reason {@code PanelShader} gives: the scene's camera is an eye, a yaw, a pitch and a focal length, and
 * the march builds its rays from exactly those. A projection matrix would be a second description of the same
 * camera. So the vertex stage runs the march's rotation backwards — the camera's yaw, then its pitch, both
 * transposed — and applies the pinhole forwards: {@code ndc.x = focal*vx/(aspect*vz)},
 * {@code ndc.y = -focal*vy/vz}. A vertex drawn this way lands exactly where a marched surface at the same world
 * position would.
 *
 * <h2>Depth from the rasteriser, still the march's curve</h2>
 *
 * <p>{@link ClipDepth#clipZ} is {@code gl_Position.z}, so the hardware's interpolation of {@code z/w} reproduces
 * the curve the march writes to {@code gl_FragDepth}, and the two occlude each other per pixel. Nothing here
 * writes {@code gl_FragDepth}, so the early depth test survives — which, for opaque triangles that may overdraw
 * each other many times, is where the rasteriser earns its keep.
 *
 * <h2>Lit by the scene's own model</h2>
 *
 * <p>The fragment stage fills a {@link ShadingPoint} — world position, a normal turned toward the eye, the
 * direction to the eye, and an albedo — and calls the same {@link Shading#shade} the march calls at its hit point.
 * A mesh beside a marched sphere is therefore lit by the same light, with the same falloff, by construction.
 * {@code ShadingPoint}'s own javadoc anticipated this caller: <em>a rasteriser arrives with an interpolated normal
 * and a varying</em>.
 */
public final class MeshShader {

    /** Vertex input locations. */
    public static final int LOC_POSITION = 0;
    public static final int LOC_NORMAL = 1;
    public static final int LOC_COLOUR = 2;

    /** Varying locations, vertex out to fragment in. */
    public static final int LOC_WORLD = 0;
    public static final int LOC_WORLD_NORMAL = 1;
    public static final int LOC_ALBEDO = 2;

    /**
     * Push-constant members, in order: the model matrix (twelve), the eye (three), the camera's cos/sin of yaw and
     * pitch (four), focal length, aspect, and the instance's tint (three).
     *
     * <p>The camera's angles arrive as sines and cosines rather than as angles because every vertex of every
     * instance would otherwise compute the same four transcendentals; the CPU computes them once a frame.
     */
    public static final int PUSH_FLOATS = 24;

    public static final int PUSH_BYTES = PUSH_FLOATS * Float.BYTES;

    /** Index of the first of the {@link Placement#MATRIX_FLOATS} floats of the mesh-to-world map. */
    public static final int PUSH_MODEL = 0;

    /** Index of the first of the three floats of the eye's world position. */
    public static final int PUSH_EYE = 12;

    public static final int PUSH_COS_YAW = 15;
    public static final int PUSH_SIN_YAW = 16;
    public static final int PUSH_COS_PITCH = 17;
    public static final int PUSH_SIN_PITCH = 18;
    public static final int PUSH_FOCAL = 19;
    public static final int PUSH_ASPECT = 20;

    /** Index of the first of the three floats the instance multiplies its vertex colours by. */
    public static final int PUSH_TINT = 21;

    private MeshShader() {
    }

    /** The vertex stage, with {@code depth}'s near and far planes baked into the clip-space z it emits. */
    public static ComposedShader vertex(ClipDepth depth) {
        return ComposedShader.lower(ShaderStage.VERTEX, vertexModule(depth), "main");
    }

    /** The fragment stage, lit by {@code shading}. */
    public static ComposedShader fragment(Shading shading) {
        return ComposedShader.lower(ShaderStage.FRAGMENT, fragmentModule(shading), "main");
    }

    /** The push-constant block both stages declare. Built in one place so the two cannot disagree. */
    public static PushConstants block() {
        String[] names = {
                "xAxisX", "xAxisY", "xAxisZ", "yAxisX", "yAxisY", "yAxisZ", "zAxisX", "zAxisY", "zAxisZ",
                "originX", "originY", "originZ",
                "eyeX", "eyeY", "eyeZ",
                "cosYaw", "sinYaw", "cosPitch", "sinPitch",
                "focalLength", "aspect",
                "tintR", "tintG", "tintB"};
        List<PushConstants.Member> members = new ArrayList<>(PUSH_FLOATS);
        for (String name : names) {
            members.add(new PushConstants.Member(name, Ir.F32));
        }
        return new PushConstants(members);
    }

    private static CoreModule vertexModule(ClipDepth depth) {
        PushConstants pc = block();
        InterfaceVar inPosition = InterfaceVar.input("inPosition", LOC_POSITION, Ir.V4);
        InterfaceVar inNormal = InterfaceVar.input("inNormal", LOC_NORMAL, Ir.V4);
        InterfaceVar inColour = InterfaceVar.input("inColour", LOC_COLOUR, Ir.V4);
        InterfaceVar vWorld = InterfaceVar.output("vWorld", LOC_WORLD, Ir.V3);
        InterfaceVar vNormal = InterfaceVar.output("vNormal", LOC_WORLD_NORMAL, Ir.V3);
        InterfaceVar vAlbedo = InterfaceVar.output("vAlbedo", LOC_ALBEDO, Ir.V3);

        LocalVar local = new LocalVar("local", Ir.V4);
        LocalVar normal = new LocalVar("normal", Ir.V4);
        LocalVar world = new LocalVar("world", Ir.V3);
        LocalVar rel = new LocalVar("rel", Ir.V3);
        LocalVar ax = new LocalVar("ax", Ir.F32);
        LocalVar az = new LocalVar("az", Ir.F32);
        LocalVar vy = new LocalVar("vy", Ir.F32);
        LocalVar vz = new LocalVar("vz", Ir.F32);

        Expr lx = Ir.x(new Expr.Read(local));
        Expr ly = Ir.y(new Expr.Read(local));
        Expr lz = Ir.z(new Expr.Read(local));
        Expr nx = Ir.x(new Expr.Read(normal));
        Expr ny = Ir.y(new Expr.Read(normal));
        Expr nz = Ir.z(new Expr.Read(normal));

        Expr worldPoint = Ir.v3(
                Ir.add(linear(pc, 0, lx, ly, lz), pc.read(PUSH_MODEL + 9)),
                Ir.add(linear(pc, 1, lx, ly, lz), pc.read(PUSH_MODEL + 10)),
                Ir.add(linear(pc, 2, lx, ly, lz), pc.read(PUSH_MODEL + 11)));
        // Uniform scale, so the model's own 3x3 carries a normal to a vector still perpendicular to its surface;
        // the fragment stage renormalises after interpolation anyway, which also absorbs the scale.
        Expr worldNormal = Ir.v3(linear(pc, 0, nx, ny, nz), linear(pc, 1, nx, ny, nz), linear(pc, 2, nx, ny, nz));

        Expr rx = Ir.x(new Expr.Read(rel));
        Expr ry = Ir.y(new Expr.Read(rel));
        Expr rz = Ir.z(new Expr.Read(rel));
        Expr cy = pc.read(PUSH_COS_YAW);
        Expr sy = pc.read(PUSH_SIN_YAW);
        Expr cp = pc.read(PUSH_COS_PITCH);
        Expr sp = pc.read(PUSH_SIN_PITCH);

        // The march turns a camera-frame ray into the world by pitch, then yaw. This is that rotation transposed
        // -- yaw undone first, then pitch -- taking the world into the camera's frame.
        Expr unyawX = Ir.sub(Ir.mul(rx, cy), Ir.mul(rz, sy));
        Expr unyawZ = Ir.add(Ir.mul(rx, sy), Ir.mul(rz, cy));
        Expr viewY = Ir.add(Ir.mul(ry, cp), Ir.mul(new Expr.Read(az), sp));
        Expr viewZ = Ir.sub(Ir.mul(new Expr.Read(az), cp), Ir.mul(ry, sp));

        // The pinhole, run forwards. The march's screen y is up and Vulkan's device y is down -- the one sign
        // that has to match, written out rather than folded into a matrix.
        Expr focal = pc.read(PUSH_FOCAL);
        Expr position = new Expr.VectorConstruct(Ir.V4, List.of(
                Ir.div(Ir.mul(focal, new Expr.Read(ax)), pc.read(PUSH_ASPECT)),
                Ir.neg(Ir.mul(focal, new Expr.Read(vy))),
                depth.clipZ(new Expr.Read(vz)),
                new Expr.Read(vz)));

        Expr colour = Ir.v3(
                Ir.mul(Ir.x(new Expr.InterfaceRead(inColour)), pc.read(PUSH_TINT)),
                Ir.mul(Ir.y(new Expr.InterfaceRead(inColour)), pc.read(PUSH_TINT + 1)),
                Ir.mul(Ir.z(new Expr.InterfaceRead(inColour)), pc.read(PUSH_TINT + 2)));

        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(local, new Expr.InterfaceRead(inPosition)));
        body.add(new Statement.DeclareVar(normal, new Expr.InterfaceRead(inNormal)));
        body.add(new Statement.DeclareVar(world, worldPoint));
        body.add(new Statement.DeclareVar(rel, Ir.sub(new Expr.Read(world),
                Ir.v3(pc.read(PUSH_EYE), pc.read(PUSH_EYE + 1), pc.read(PUSH_EYE + 2)))));
        body.add(new Statement.DeclareVar(ax, unyawX));
        body.add(new Statement.DeclareVar(az, unyawZ));
        body.add(new Statement.DeclareVar(vy, viewY));
        body.add(new Statement.DeclareVar(vz, viewZ));
        body.add(new Statement.BuiltinWrite(Builtin.POSITION, position));
        body.add(new Statement.InterfaceWrite(vWorld, new Expr.Read(world)));
        body.add(new Statement.InterfaceWrite(vNormal, worldNormal));
        body.add(new Statement.InterfaceWrite(vAlbedo, colour));
        body.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.VERTEX));
    }

    /** Row {@code row} of the model's 3x3 applied to {@code (x, y, z)}: the axes are stored as columns. */
    private static Expr linear(PushConstants pc, int row, Expr x, Expr y, Expr z) {
        return Ir.add(Ir.mul(pc.read(PUSH_MODEL + row), x),
                Ir.add(Ir.mul(pc.read(PUSH_MODEL + 3 + row), y), Ir.mul(pc.read(PUSH_MODEL + 6 + row), z)));
    }

    private static CoreModule fragmentModule(Shading shading) {
        PushConstants pc = block();
        InterfaceVar vWorld = InterfaceVar.input("vWorld", LOC_WORLD, Ir.V3);
        InterfaceVar vNormal = InterfaceVar.input("vNormal", LOC_WORLD_NORMAL, Ir.V3);
        InterfaceVar vAlbedo = InterfaceVar.input("vAlbedo", LOC_ALBEDO, Ir.V3);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);

        LocalVarBindings bindings = new LocalVarBindings();
        Expr position = bindings.bind("position", new Expr.InterfaceRead(vWorld));
        Expr eye = Ir.v3(pc.read(PUSH_EYE), pc.read(PUSH_EYE + 1), pc.read(PUSH_EYE + 2));
        Expr view = bindings.bind("view", Expr.MathCall.normalize(Ir.sub(eye, position)));
        Expr interpolated = bindings.bind("interpolated",
                Expr.MathCall.normalize(new Expr.InterfaceRead(vNormal)));
        // Two-sided: the normal turned to face the eye. step() gives 0 or 1, so the factor is -1 or +1 and the
        // turn costs no branch. A face seen exactly edge-on gets +1, which is as good as -1 for a face nobody can
        // see.
        Expr facing = Ir.sub(Ir.mul(Ir.step(Ir.f(0.0), Ir.dot(interpolated, view)), Ir.f(2.0)), Ir.f(1.0));
        Expr normal = bindings.bind("normal", Ir.scale(interpolated, facing));
        Expr albedo = bindings.bind("albedo", new Expr.InterfaceRead(vAlbedo));

        Expr shaded = shading.shade(ShadingPoint.diffuse(position, normal, view, albedo), bindings);

        LocalVar colour = new LocalVar("colour", Ir.V3);
        List<Statement> body = new ArrayList<>(bindings.statements);
        body.add(new Statement.DeclareVar(colour, shaded));
        body.add(new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4, List.of(
                Ir.x(new Expr.Read(colour)), Ir.y(new Expr.Read(colour)), Ir.z(new Expr.Read(colour)),
                Ir.f(1.0)))));
        body.add(new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        return new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }

    /**
     * {@link Bindings} as declarations in front of the fragment body — the march's own arrangement. Names are made
     * unique by a counter in composition order, so the same shading composes to the same SPIR-V every time.
     */
    private static final class LocalVarBindings implements Bindings {

        final List<Statement> statements = new ArrayList<>();
        private int next;

        @Override
        public Expr bind(String name, Expr value) {
            LocalVar variable = new LocalVar(name + "_" + next++, value.type());
            statements.add(new Statement.DeclareVar(variable, value));
            return new Expr.Read(variable);
        }
    }
}
