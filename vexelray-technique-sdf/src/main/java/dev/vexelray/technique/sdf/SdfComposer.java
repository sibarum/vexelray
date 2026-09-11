package dev.vexelray.technique.sdf;

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
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.shader.Bindings;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.ShaderComposer;
import dev.vexelray.shader.ShaderKey;
import dev.vexelray.shader.ShadingPoint;
import dev.vexelray.surface.Field;
import dev.vexelray.surface.NodeId;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.surface.ParamStore;
import dev.vexelray.surface.PayloadTable;
import dev.vexelray.surface.SurfaceLimits;
import dev.vexelray.ir.Ir;
import dev.vexelray.surface.Surface;
import dev.vexelray.surface.SurfaceCompiler;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns an {@link SdfScene} into the vertex+fragment pair that renders it — the composer
 * {@link ShaderComposer}'s javadoc has been describing all along: <em>"an SDF composer turns a signed-distance
 * scene into a fullscreen fragment for the ray-march path."</em>
 *
 * <p>The vertex stage is SupirVast's {@code Fullscreen} triangle, unchanged and independent of the scene. All
 * the scene-specific work is one fragment: sphere-trace the surface from a push-constant camera, shade the hit
 * through the scene's {@link dev.vexelray.shader.Shading} model, and miss to the sky.
 *
 * <p><b>The field is emitted once, as a called function.</b> A march touches the field at nine points per
 * pixel — every step, six normal taps, and the final hit test — and inlining it at each site multiplied a
 * field's shader size by roughly eight; D12 records one that reached 22 MB of SPIR-V that way. Emitting
 * {@code float sdf(vec3)} once and calling it moves the cost from code size to runtime, and it is what keeps a
 * normalised implicit affordable at all, given the derivative already multiplies the field (surface-compiler.md
 * §2.1). It also means the CPU lowers the identical function — render == sim, for a surface that may have been
 * typed in at runtime.
 */
public final class SdfComposer implements ShaderComposer<SdfScene> {

    /** The camera's own share of the push-constant block: {@code camX, camY, camZ, yaw, pitch, aspect}. */
    public static final int CAMERA_BYTES = 24;

    /**
     * Members the block spends before the first parameter: the camera's six, and {@code focalLength}.
     *
     * <p>The lens joins the block rather than staying a compile-time constant because it costs one member and
     * removes a whole class of recompile: two scenes that differ only in focal length now share a pipeline, and
     * a lens can be dragged at frame rate. It is the same mechanism as a surface parameter, proving it reaches
     * scene-level values and not only {@link dev.vexelray.surface.Surface} numerics.
     */
    public static final int FIRST_PARAM_MEMBER = 7;

    /**
     * The most parameters that fit in push constants on the <em>smallest</em> Vulkan device: {@code (128-28)/4}.
     *
     * <p>What a host assumes before it has asked its own device, and never a ceiling on a design. P0a stopped
     * here and threw; P0b gave the parameters a second road, so the number decides which road rather than
     * whether the design exists — see {@link ParamBacking}, which is what to ask, with
     * {@link ParamBacking#on} for a host that has read {@code maxPushConstantsSize} off the device it is
     * actually running on. The machine this was written on reports 256 bytes, and so holds 57.
     *
     * <p>The distinction that matters: <b>query the device to choose the road, never to decide what a design
     * may contain.</b> A design authored where the driver reports 256 opens where it reports 128, because
     * there its values travel by buffer instead. Scale a document on a per-device number and it stops opening
     * on a smaller machine, at load time, which is the worst place for it to happen.
     *
     * <p>It is spent faster than it reads: a sphere is four numbers and a translate is three, so a tree of
     * twenty primitives wants north of a hundred.
     */
    public static final int MAX_PUSH_CONSTANT_PARAMS = (128 - (FIRST_PARAM_MEMBER * 4)) / 4;

    /** The name of the generated field function, for hosts that lower the same module on the CPU. */
    public static final String SDF_FUNCTION = "sdf";

    /**
     * The name of the generated colour function, present only when the surface carries colour of its own.
     *
     * <p>Note what it costs, because the answer is counter-intuitive: the colour function is roughly as large as
     * the field — picking a colour out of a union means re-testing the same distances — but it is called
     * <b>once per pixel</b>, at the hit point, where the field is called nine times per pixel <em>per march
     * step</em>. Colour is close to free at runtime and merely bulky in the module.
     */
    public static final String ALBEDO_FUNCTION = "albedo";

    /**
     * The name of the generated identity function: {@code vec2 hit(vec3)}, distance in {@code x} and the
     * payload in {@code y}.
     *
     * <p>Present only in a module composed by {@link #identityFragmentSpirv}, which is the whole of the
     * second lowering mode being something a scene opts into.
     */
    public static final String PAYLOAD_FUNCTION = "hit";

    private static final Type.Float F32 = Ir.F32;

    /**
     * Which road this composer's parameters take — a property of the machine, set once when the device is
     * known, and never of the scene.
     *
     * <p>A field rather than an argument because it has to reach {@link #keyFor}: the backing changes the
     * emitted SPIR-V, so two composers on two devices key differently, and one composer keys the same way all
     * run long.
     */
    private final ParamBacking backing;

    /** A composer that assumes Vulkan's guaranteed floor, for a host that has not asked its device. */
    public SdfComposer() {
        this(ParamBacking.DEFAULT);
    }

    /** A composer on a host that has asked — see {@link ParamBacking#on}. */
    public SdfComposer(ParamBacking backing) {
        if (backing == null) {
            throw new IllegalArgumentException("a composer needs a parameter backing; ParamBacking.DEFAULT "
                    + "is the one to use before a device has been asked");
        }
        this.backing = backing;
    }

    /** Which road this composer sends parameters down. */
    public ParamBacking backing() {
        return backing;
    }

    @Override
    public List<ShaderStage> stages() {
        return List.of(ShaderStage.VERTEX, ShaderStage.FRAGMENT);
    }

    /**
     * The cache key: the scene, with everything the shader cannot see taken out of the surface.
     *
     * <p>Overridden rather than left to the default — the description's own {@code equals} — because two of
     * the things a {@link Surface} now carries are deliberately invisible to the lowering. A parameter's
     * live value lives in a {@link ParamBlock} and its identity is a slot (P0a); a node's {@link NodeId} is
     * which object it is, and a distance field does not depend on which object anything is (P2). Left to
     * structural equality, two designs that compose to byte-identical SPIR-V would compile twice — and the
     * second of those would be a five-second stall on the thread that presents.
     *
     * <p>{@link Surface#shaderKey()} is the normal form; everything else about the scene — shading, march
     * settings, colours, the planes — is compared as it stands, because all of it reaches the shader.
     *
     * <p>A whole {@link SdfScene} is rebuilt here rather than a list of the parts that matter being
     * assembled, and that is deliberate: a component added to the scene stops this compiling, which is a
     * demand that somebody decide whether it belongs in the key. A list would keep compiling and silently
     * leave the new field out — a cache hit that renders the wrong picture, which is exactly the failure the
     * differently-lit scene below is here to catch.
     */
    @Override
    public ShaderKey keyFor(SdfScene scene) {
        // The backing is in the key because it is in the SPIR-V: a PushConstantRead is not a BufferLoad, and
        // a cache that ignored the difference would hand a pipeline built for one road a shader that takes
        // the other. Not a slow picture — a wrong one, and only on the machines where the roads differ.
        return ShaderKey.of(getClass(), List.of(
                new SdfScene(scene.surface().shaderKey(), scene.shading(), scene.march(), scene.albedo(),
                        scene.sky(), scene.focalLength(), scene.nearPlane()),
                backing.roadFor(ParamBlock.of(scene.surface()).size())));
    }

    /**
     * The pair, and the {@code WithUv} half of that first line is load-bearing. {@link Fullscreen} offers two
     * fullscreen-triangle vertex stages: one that writes only {@code gl_Position}, and one that also emits the
     * {@code vUv} varying. The ray-march fragment reads {@code vUv} — it is where the pixel's screen position
     * comes from, and so the only thing that makes one ray differ from another — so it must be paired with the
     * second. Paired with the first, the input is simply never written: every pixel marches the same ray, the
     * frame comes out a single flat colour, and nothing anywhere reports an error, because a stage whose input
     * nothing writes is still perfectly valid SPIR-V and passes {@code spirv-val} on its own.
     */
    @Override
    public List<ComposedShader> compose(SdfScene scene) {
        return List.of(
                new ComposedShader(ShaderStage.VERTEX, Fullscreen.triangleVertexWithUvSpirv(),
                        Fullscreen.ENTRY_POINT),
                new ComposedShader(ShaderStage.FRAGMENT, fragmentSpirv(scene, backing), Fullscreen.ENTRY_POINT));
    }

    /**
     * The scene's parameters, in slot order, with their values at the initials the surface declared.
     *
     * <p>What a host holds on to: build sliders from {@link ParamBlock#params()}, write through
     * {@link ParamBlock#write}, and hand it back to {@link #pushConstantBytes} each frame. It is deliberately a
     * fresh block per call — values live in the object, so a caller that wanted to keep its values must keep the
     * object, and {@link ParamBlock#carryFrom} is how they cross a recompile.
     *
     * @throws IllegalArgumentException by name if the backing is forced to push constants and the scene has
     *                                  more parameters than they hold
     */
    public static ParamBlock paramBlock(SdfScene scene) {
        return paramBlock(scene, ParamBacking.DEFAULT);
    }

    /** The same, for a host that knows what its device reports. */
    public static ParamBlock paramBlock(SdfScene scene, ParamBacking backing) {
        ParamBlock block = ParamBlock.of(scene.surface());
        if (backing.mode() == ParamBacking.Mode.PUSH_CONSTANTS
                && block.size() > backing.pushConstantCapacity()) {
            throw new IllegalArgumentException(
                    "surface has " + block.size() + " parameters and push constants hold "
                            + backing.pushConstantCapacity() + " on this device ("
                            + backing.maxPushConstantBytes() + " bytes, less " + (FIRST_PARAM_MEMBER * 4)
                            + " for the camera and lens); the backing was forced to push constants, and "
                            + "ParamBacking.AUTO would have taken the storage buffer instead");
        }
        return block;
    }

    /**
     * The one push-constant block this composer emits: the camera, the lens, and — on the push road — the
     * scene's parameters in slot order.
     *
     * <p>One block, because SPIR-V permits one. On the push road the parameters share it with the camera
     * rather than having a block of their own, which is what made P0a reachable with no Vulkan work at all:
     * the same {@code vkCmdPushConstants} the camera already used carries them. On the buffer road the block
     * is the camera and the lens alone, and the parameters are an array at descriptor set 0.
     */
    private static PushConstants pushConstants(ParamBlock params, boolean usesBuffer) {
        int parameters = usesBuffer ? 0 : params.size();
        List<PushConstants.Member> members = new ArrayList<>(FIRST_PARAM_MEMBER + parameters);
        members.add(new PushConstants.Member("camX", F32));
        members.add(new PushConstants.Member("camY", F32));
        members.add(new PushConstants.Member("camZ", F32));
        members.add(new PushConstants.Member("yaw", F32));
        members.add(new PushConstants.Member("pitch", F32));
        members.add(new PushConstants.Member("aspect", F32));
        members.add(new PushConstants.Member("focalLength", F32));
        for (int i = 0; i < parameters; i++) {
            members.add(new PushConstants.Member("p" + i, F32));
        }
        return new PushConstants(members);
    }

    /**
     * The buffer a scene's parameters are read from when they take that road: {@code float} elements at
     * descriptor set 0, binding 0, in slot order.
     *
     * <p>Published so a host can bind the right thing — and shaped by nothing about the scene but the fact
     * that it is parameters, so two scenes with different geometry declare the identical buffer.
     */
    public static final dev.supirvast.vastir.core.Buffer PARAM_BUFFER =
            new dev.supirvast.vastir.core.Buffer("params", 0, Ir.F32);

    /**
     * The scene lowered: its field, the block its camera reads, and whether its parameters took the buffer.
     *
     * <p>The halves come from one place because they have to agree — a {@code PushConstantRead} carries the
     * whole block by value, so a field compiled against a block built a second time is only equal to the
     * fragment's reads if both were built the same way. Deterministic slot order (see {@link ParamBlock}) is
     * what makes that true rather than lucky.
     */
    private static Lowered lower(SdfScene scene, ParamBacking backing) {
        ParamBlock params = paramBlock(scene, backing);
        boolean usesBuffer = backing.usesBuffer(params.size());
        PushConstants block = pushConstants(params, usesBuffer);
        // The block is handed to the buffer store as well, so that an Implicit reading the composer's own
        // push constants is judged the same way on both roads. What a surface may express must not depend on
        // which device it was opened on.
        ParamStore store = usesBuffer
                ? params.inBuffer(PARAM_BUFFER, 0, block)
                : params.inPushConstants(block, FIRST_PARAM_MEMBER);
        return new Lowered(SurfaceCompiler.compile(scene.surface(), store), params, block, usesBuffer);
    }

    private static Lowered lower(SdfScene scene) {
        return lower(scene, ParamBacking.DEFAULT);
    }

    private record Lowered(Field field, ParamBlock params, PushConstants block, boolean usesBuffer) {
    }

    /**
     * The scene's distance field as a standalone {@code float sdf(vec3)} — the same function the fragment calls,
     * exposed so a host can lower it to the CPU and collide against exactly what it draws.
     *
     * <p>A parametric scene's function reads push constants, so a CPU lowering of it needs the same values the
     * shader is given; a scene of literals lowers to precisely what it always did.
     */
    public static Function sdfFunction(SdfScene scene) {
        return lower(scene).field().asFunction(SDF_FUNCTION);
    }

    /**
     * The scene's colour as a standalone {@code vec3 albedo(vec3)}, or {@code null} when nothing in the surface
     * named a colour and the scene's single albedo is the whole story.
     *
     * <p>Null rather than a constant function on purpose: a shape-only scene must compose byte-identically to
     * what it composed before surfaces could carry colour, and an extra function in the module — even an unused
     * one — is not that.
     */
    public static Function albedoFunction(SdfScene scene) {
        Field field = lower(scene).field();
        return field.hasAlbedo() ? field.albedoFunction(ALBEDO_FUNCTION, sceneAlbedo(scene)) : null;
    }

    /** The scene-wide albedo as a constant, and what {@link Ir#SCENE_ALBEDO} resolves to. */
    private static Expr sceneAlbedo(SdfScene scene) {
        Surface.Rgb rgb = scene.albedo();
        return Ir.v3(rgb.r(), rgb.g(), rgb.b());
    }

    /** The compiled field, if a caller wants the Lipschitz bound along with the expression. */
    public static Field field(SdfScene scene) {
        return lower(scene).field();
    }

    /** How many bytes {@code scene}'s block occupies — camera, lens, and one float per parameter. */
    public static int pushBytes(SdfScene scene) {
        return pushBytes(scene, ParamBacking.DEFAULT);
    }

    /**
     * The same, on the road this device takes: the camera and the lens alone once the parameters have moved
     * to the buffer, which is the point of moving them.
     */
    public static int pushBytes(SdfScene scene, ParamBacking backing) {
        ParamBlock params = paramBlock(scene, backing);
        return (FIRST_PARAM_MEMBER + (backing.usesBuffer(params.size()) ? 0 : params.size())) * 4;
    }

    /**
     * The whole push-constant block, little-endian, matching the layout the generated fragment reads: the
     * camera, the lens, then {@code values} in slot order.
     *
     * <p>Values are written through {@link ParamBlock}, by identity, and never by an offset a caller computed —
     * see that class on why an offset must not cross this boundary.
     *
     * @param aspect viewport width divided by height; kept a push constant so a window resize does not
     *               recompile the shader
     * @param values the scene's parameters, from {@link #paramBlock}; must declare the same parameters in the
     *               same slots as the scene being rendered
     */
    public static byte[] pushConstantBytes(SdfScene scene, double x, double y, double z,
                                           double yaw, double pitch, double aspect, ParamBlock values) {
        return pushConstantBytes(scene, x, y, z, yaw, pitch, aspect, values, ParamBacking.DEFAULT);
    }

    /**
     * The same, on the road this device takes — the camera and the lens alone once the parameters have moved
     * to {@link #PARAM_BUFFER}, which {@link #writeParams} fills.
     */
    public static byte[] pushConstantBytes(SdfScene scene, double x, double y, double z,
                                           double yaw, double pitch, double aspect, ParamBlock values,
                                           ParamBacking backing) {
        ParamBlock expected = paramBlock(scene, backing);
        if (!expected.sameLayout(values)) {
            throw new IllegalArgumentException(
                    "these values were built for a different surface: the scene declares " + expected
                            + " and the block holds " + values + "; rebuild it with paramBlock(scene) and carry "
                            + "the old values across with ParamBlock.carryFrom");
        }
        float[] floats = new float[FIRST_PARAM_MEMBER
                + (backing.usesBuffer(values.size()) ? 0 : values.size())];
        writePushConstants(scene, x, y, z, yaw, pitch, aspect, values, floats, backing);
        ByteBuffer buffer = ByteBuffer.allocate(floats.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float value : floats) {
            buffer.putFloat(value);
        }
        return buffer.array();
    }

    /**
     * The same block as {@link #pushConstantBytes}, written into {@code out} as floats and allocating nothing.
     *
     * <p>This is the overload a technique calls every frame. {@link #pushConstantBytes} cannot avoid garbage —
     * it rebuilds the scene's {@link ParamBlock} to check the layout, converts the values to a new
     * {@code float[]}, and returns a {@code byte[]} — which is fine for a one-shot render and is three
     * allocations and a walk of the surface tree per frame in a loop.
     *
     * <p>It therefore does <em>not</em> re-derive the scene's layout to compare against {@code values}. A
     * caller that obtained {@code values} from {@link #paramBlock paramBlock(scene)} for the same scene has
     * that guarantee by construction, and a caller that did not is checking a fact that cannot change between
     * frames once per frame. Use {@link #pushConstantBytes} if the block's provenance is genuinely unknown.
     *
     * @param out at least {@code FIRST_PARAM_MEMBER + values.size()} floats long; only that prefix is written
     * @throws IllegalArgumentException if {@code out} is too short
     */
    public static void writePushConstants(SdfScene scene, double x, double y, double z, double yaw,
                                          double pitch, double aspect, ParamBlock values, float[] out) {
        writePushConstants(scene, x, y, z, yaw, pitch, aspect, values, out, ParamBacking.DEFAULT);
    }

    /**
     * The same, on the road this device takes.
     *
     * <p>On the buffer road the block is the camera and the lens, and the parameters are not in it — they are
     * in the buffer, written by {@link #writeParams}. Writing them here as well would be harmless and wrong:
     * harmless because the shader does not read those members, wrong because it says the block has them.
     */
    public static void writePushConstants(SdfScene scene, double x, double y, double z, double yaw,
                                          double pitch, double aspect, ParamBlock values, float[] out,
                                          ParamBacking backing) {
        boolean usesBuffer = backing.usesBuffer(values.size());
        int count = FIRST_PARAM_MEMBER + (usesBuffer ? 0 : values.size());
        if (out.length < count) {
            throw new IllegalArgumentException("this scene's block is " + count + " floats and the array holds "
                    + out.length + "; size it with pushBytes(scene, backing) / 4");
        }
        // Members 0..FIRST_PARAM_MEMBER-1, in the order the generated fragment declares them.
        out[0] = (float) x;
        out[1] = (float) y;
        out[2] = (float) z;
        out[3] = (float) yaw;
        out[4] = (float) pitch;
        out[5] = (float) aspect;
        out[6] = (float) scene.focalLength();
        if (!usesBuffer) {
            values.writeFloats(out, FIRST_PARAM_MEMBER);
        }
    }

    /**
     * The parameter buffer's contents: the values in slot order, and nothing else.
     *
     * <p>The buffer road's other half. The camera and the lens stay in push constants — they change every
     * frame and are six floats, which is what push constants are for — while the parameters, which may be
     * thousands and change when a slider moves, live in {@link #PARAM_BUFFER} at descriptor set 0.
     *
     * @param out at least {@code values.size()} floats long; only that prefix is written
     */
    public static void writeParams(ParamBlock values, float[] out) {
        if (out.length < values.size()) {
            throw new IllegalArgumentException("this scene has " + values.size()
                    + " parameters and the array holds " + out.length);
        }
        values.writeFloats(out, 0);
    }

    /**
     * The camera's six floats alone.
     *
     * @deprecated The block is no longer six floats: it carries {@code focalLength} and the scene's parameters
     *         after them, and pushing only this leaves those members unwritten — a lens of whatever was last in
     *         the command buffer. Use {@link #pushConstantBytes}, which needs the scene and the values anyway.
     */
    @Deprecated
    public static byte[] cameraBytes(double x, double y, double z, double yaw, double pitch, double aspect) {
        ByteBuffer buffer = ByteBuffer.allocate(CAMERA_BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat((float) x).putFloat((float) y).putFloat((float) z);
        buffer.putFloat((float) yaw).putFloat((float) pitch).putFloat((float) aspect);
        return buffer.array();
    }

    /** Compose and lower the ray-march fragment for {@code scene}. */
    public static byte[] fragmentSpirv(SdfScene scene) {
        return fragmentSpirv(scene, ParamBacking.DEFAULT);
    }

    /** The same, on a host that knows which road its device wants the parameters to take. */
    public static byte[] fragmentSpirv(SdfScene scene, ParamBacking backing) {
        // Compiled once and used twice: the field the march walks, and — only if a surface asked for it — the
        // colour read at the hit point.
        Field field = lower(scene, backing).field();
        return fragmentSpirv(scene, field.asFunction(SDF_FUNCTION),
                field.hasAlbedo() ? field.albedoFunction(ALBEDO_FUNCTION, sceneAlbedo(scene)) : null,
                field.helpers(), null, backing);
    }

    /**
     * Compose the same march over a field that carries identity, so the shading model is told which surface
     * it hit (P2).
     *
     * <p>A second lowering rather than a channel the display path always carries, because the channel is not
     * free: every combinator binds its arms' distances so the comparison that picks the distance can pick the
     * payload too, and sharing narrows from shapes to nodes so that two lookalikes stay two answers. A scene
     * that does not ask pays none of it — {@link #fragmentSpirv(SdfScene)} composes exactly what it always
     * did.
     *
     * <p>What the payload <em>means</em> is {@link #payloadTable}, and a host resolves it there rather than
     * storing the number: a slot is an encoding and changes whenever the tree changes shape.
     */
    public static byte[] identityFragmentSpirv(SdfScene scene) {
        Field field = loweredWithPayload(scene, ParamBacking.DEFAULT);
        Function payloadFn = field.asPayloadFunction(PAYLOAD_FUNCTION);
        List<Function> helpers = new ArrayList<>(field.helpers());
        helpers.add(payloadFn);
        return fragmentSpirv(scene, distanceOf(payloadFn),
                field.hasAlbedo() ? field.albedoFunction(ALBEDO_FUNCTION, sceneAlbedo(scene)) : null,
                helpers, payloadFn);
    }

    /**
     * What the payload channel's numbers mean for this scene: slot → the node that owns the surface there.
     *
     * <p>Read a slot back and resolve it here, at once. Do not store one: it is assigned by walk order, so
     * the next edit to the tree gives it to a different shape — the same rule {@link ParamBlock} follows for
     * the same reason.
     */
    public static PayloadTable payloadTable(SdfScene scene) {
        return PayloadTable.of(scene.surface());
    }

    /** The scene's field with identity, lowered the second way. */
    private static Field loweredWithPayload(SdfScene scene, ParamBacking backing) {
        ParamBlock params = paramBlock(scene, backing);
        boolean usesBuffer = backing.usesBuffer(params.size());
        PushConstants block = pushConstants(params, usesBuffer);
        ParamStore store = usesBuffer
                ? params.inBuffer(PARAM_BUFFER, 0, block)
                : params.inPushConstants(block, FIRST_PARAM_MEMBER);
        return SurfaceCompiler.compileWithPayload(scene.surface(), SurfaceLimits.DEFAULT, store);
    }

    /**
     * {@code float sdf(vec3 p)} over a payload-carrying field: the {@code x} of it.
     *
     * <p>The march is not rewritten around {@code vec2} for the sake of a channel it never reads. A wrapper
     * costs one call the driver will inline and keeps one implementation of the march, which is the thing
     * that must not fork — three of this window's predecessors forked their transforms and spent the rest of
     * their lives keeping the copies in step.
     */
    private static Function distanceOf(Function payloadFn) {
        return new Function(SDF_FUNCTION, new Type.FunctionType(F32, List.of(Ir.V3)),
                Region.of(new Statement.Return(
                        Ir.x(new Expr.Call(payloadFn, List.of(Ir.POINT))))));
    }

    /**
     * The functions {@link #sdfFunction} calls — a repeated child, or any subtree the design used twice,
     * emitted once (P1).
     *
     * <p>Published because a host that assembles its own module needs them: a call to a function the module
     * does not define is the one way this split can fail, and it fails at pipeline creation rather than here.
     * Empty for a surface with nothing shared in it, which is every surface that lowered before P1 existed.
     */
    public static List<Function> helperFunctions(SdfScene scene) {
        return lower(scene).field().helpers();
    }

    /**
     * As {@link #fragmentSpirv(SdfScene)}, but marching a distance field supplied by the caller rather than one
     * compiled from {@code scene.surface()}.
     *
     * <p>Everything that is not the field itself is shared: the primary ray, the sphere-trace loop, the hit test,
     * the normal by central difference, the shading and the miss. Those are the renderer, and they do not become
     * a different renderer because the field arrived by another route — so a second composer that duplicated them
     * would be two copies of the march to keep in step, which is the mistake this window's three predecessors
     * made with their transforms.
     *
     * <p>What it is <em>for</em>: a field whose geometry lives in a storage buffer rather than in the shader. Such
     * a field is the same SPIR-V whatever it draws, so its pipeline is built once instead of once per scene —
     * and building one was measured at five seconds on the thread that presents. {@code scene.surface()} is not
     * read here and may describe the same geometry the caller put in the buffer; the scene is still consulted for
     * everything else about the picture.
     *
     * @param sdf      {@code float sdf(vec3 p)} — called by the march and, nine times over, by the normal
     * @param albedoFn {@code vec3 albedo(vec3 p)}, or null when the scene's own albedo is the whole story
     */
    public static byte[] fragmentSpirv(SdfScene scene, Function sdf, Function albedoFn) {
        return fragmentSpirv(scene, sdf, albedoFn, List.of());
    }

    /**
     * The same, for a field that calls functions of its own.
     *
     * <p>{@code helpers} are added to the module ahead of {@code sdf}, callees first, which is the order
     * {@link Field#helpers()} hands them over in. A caller supplying its own {@code sdf} — a buffer-driven
     * field, say — has none and passes an empty list.
     */
    public static byte[] fragmentSpirv(SdfScene scene, Function sdf, Function albedoFn,
                                       List<Function> helpers) {
        return fragmentSpirv(scene, sdf, albedoFn, helpers, null, ParamBacking.DEFAULT);
    }

    /**
     * The same march, over a field that also says <b>what</b> it hit (P2).
     *
     * <p>{@code payloadFn} is {@code vec2 hit(vec3 p)} — distance in {@code x}, payload in {@code y} — and it
     * is called <b>once, at the hit point</b>, where the shading model is handed the {@code y}. The march
     * itself never asks: what it needs to advance is the distance and nothing else, so identity costs one
     * call per pixel rather than nine per step.
     *
     * <p>{@code sdf} is still the float function the march walks, and for a payload-carrying field it is the
     * thin wrapper {@link #identityFragmentSpirv} makes: {@code hit(p).x}. Two functions rather than a march
     * rewritten around {@code vec2}, because the march is the part that must stay one implementation.
     */
    public static byte[] fragmentSpirv(SdfScene scene, Function sdf, Function albedoFn,
                                       List<Function> helpers, Function payloadFn) {
        return fragmentSpirv(scene, sdf, albedoFn, helpers, payloadFn, ParamBacking.DEFAULT);
    }

    /**
     * The same, told which road the parameters took — because the camera block this rebuilds has the
     * parameters after it on one road and not on the other, and it has to be the block the field was
     * compiled against.
     */
    public static byte[] fragmentSpirv(SdfScene scene, Function sdf, Function albedoFn,
                                       List<Function> helpers, Function payloadFn, ParamBacking backing) {
        MarchSettings march = scene.march();

        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, Ir.V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        // The same block the field was compiled against — rebuilt rather than passed in, because a
        // PushConstantRead carries its block by value and slot order is deterministic, so the two agree by
        // construction. A caller supplying its own sdf gets the block its scene describes, which is what keeps
        // a buffer-driven field (see the note above) reading the same camera it always did.
        ParamBlock cameraParams = paramBlock(scene, backing);
        PushConstants camera = pushConstants(cameraParams, backing.usesBuffer(cameraParams.size()));

        Expr eye = Ir.v3(camera.read(0), camera.read(1), camera.read(2));
        PrimaryRay ray = primaryRay(vUv, camera.read(3), camera.read(4), camera.read(5), camera.read(6));

        LocalVar ro = new LocalVar("ro", Ir.V3);
        LocalVar rd = new LocalVar("rd", Ir.V3);
        LocalVar t = new LocalVar("t", F32);
        // Bound once, outside the march: it depends only on the pixel, so recomputing it in each branch would
        // pay for a sqrt and a divide twice to get the same number.
        LocalVar cosForward = new LocalVar("cosForward", F32);
        LocalVar i = new LocalVar("i", Type.int32());
        LocalVar p = new LocalVar("p", Ir.V3);
        LocalVar d = new LocalVar("d", F32);

        // One step: sample the field, advance by it (clamped), count the iteration.
        Region step = Region.of(
                new Statement.Assign(p, Ir.add(read(ro), Ir.scale(read(rd), read(t)))),
                new Statement.Assign(d, call(sdf, read(p))),
                new Statement.Assign(t, Ir.add(read(t), Ir.min(read(d), Ir.f(march.maxStep())))),
                new Statement.Assign(i, new Expr.Binary(BinaryOp.ADD, read(i), new Expr.ConstInt(Type.int32(), 1))));

        // Stop on the step budget OR the far plane. The far-plane half is what bounds an unbounded or periodic
        // implicit: without it such a field never stops being "somewhere ahead" and every sky pixel costs the
        // full budget.
        Expr keepMarching = new Expr.Binary(BinaryOp.LOGICAL_AND,
                new Expr.Binary(BinaryOp.LESS_THAN, read(i), new Expr.ConstInt(Type.int32(), march.steps())),
                new Expr.Binary(BinaryOp.LESS_THAN, read(t), Ir.f(march.farPlane())));

        Region body = Region.of(
                new Statement.DeclareVar(ro, eye),
                new Statement.DeclareVar(rd, ray.direction()),
                new Statement.DeclareVar(cosForward, ray.cosForward()),
                new Statement.DeclareVar(t, Ir.f(0.0)),
                new Statement.DeclareVar(i, new Expr.ConstInt(Type.int32(), 0)),
                new Statement.DeclareVar(p, Ir.v3(0, 0, 0)),
                new Statement.DeclareVar(d, Ir.f(0.0)),
                new Statement.While(keepMarching, step),
                new Statement.Assign(p, Ir.add(read(ro), Ir.scale(read(rd), read(t)))),
                new Statement.Assign(d, call(sdf, read(p))),
                // Both branches write gl_FragDepth, and that is the rule rather than symmetry for its own
                // sake: a fragment shader that writes the built-in on one path leaves it undefined on every
                // path that did not, and the symptom is geometry occluding intermittently rather than
                // anything a validator reports. See Builtin.FRAG_DEPTH.
                new Statement.If(hitTest(march, read(d), read(t)),
                        hit(scene, sdf, albedoFn, payloadFn, fragColor, p, rd, t, cosForward),
                        miss(scene, fragColor)),
                new Statement.ReturnVoid());

        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule();
        for (Function helper : helpers) {
            module = module.addFunction(helper);        // a shared subtree: emitted once, called from each site
        }
        module = module.addFunction(sdf);               // emitted once; called nine times per pixel
        if (albedoFn != null) {
            module = module.addFunction(albedoFn);      // emitted once; called once, at the hit point
        }
        module = module.addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, Fullscreen.ENTRY_POINT).spirv();
    }

    /**
     * A pixel's primary ray, and the one other number a depth-writing march needs from the camera.
     *
     * @param direction  the unit ray to march along
     * @param cosForward cosine of the angle between that ray and the camera's forward axis, which is what
     *                   turns the march's radial {@code t} into the planar view-space depth a rasteriser
     *                   would have written. See {@link ClipDepth} for why conflating the two is a bug that
     *                   presents as bad modelling
     */
    private record PrimaryRay(Expr direction, Expr cosForward) {
    }

    /**
     * The primary ray for a pixel: screen coordinates through a focal length, pitched then yawed.
     *
     * <p>Horizontal screen coordinates are scaled by the aspect ratio, so a wide window shows more of the world
     * sideways rather than stretching what it already had.
     *
     * <p>{@code cosForward} comes out of the same three numbers and is computed <em>before</em> the rotation,
     * which is not an optimisation but the reason it is correct: the rotation is rigid, so the angle between
     * the ray and the camera's forward axis is the angle between {@code (sx, sy, focal)} and {@code (0, 0,
     * focal)} whichever way the camera is pointing, and that is {@code focal / length(sx, sy, focal)}. Yaw
     * and pitch never enter it.
     */
    private static PrimaryRay primaryRay(InterfaceVar vUv, Expr yaw, Expr pitch, Expr aspect, Expr focal) {
        Expr u = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 0);
        Expr v = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 1);
        Expr sx = Ir.mul(Ir.sub(Ir.mul(u, Ir.f(2.0)), Ir.f(1.0)), aspect);
        Expr sy = Ir.sub(Ir.f(1.0), Ir.mul(v, Ir.f(2.0)));

        Expr cosForward = Ir.div(focal, Ir.length(Ir.v3(sx, sy, focal)));

        Expr cosPitch = Expr.MathCall.cos(pitch);
        Expr sinPitch = Expr.MathCall.sin(pitch);
        Expr py = Ir.sub(Ir.mul(sy, cosPitch), Ir.mul(focal, sinPitch));
        Expr pz = Ir.add(Ir.mul(sy, sinPitch), Ir.mul(focal, cosPitch));

        Expr cosYaw = Expr.MathCall.cos(yaw);
        Expr sinYaw = Expr.MathCall.sin(yaw);
        Expr rx = Ir.add(Ir.mul(sx, cosYaw), Ir.mul(pz, sinYaw));
        Expr rz = Ir.sub(Ir.mul(pz, cosYaw), Ir.mul(sx, sinYaw));

        return new PrimaryRay(Expr.MathCall.normalize(Ir.v3(rx, py, rz)), cosForward);
    }

    /** Distance-relative hit threshold: a far pixel covers more world, so it may not demand the same precision. */
    private static Expr hitTest(MarchSettings march, Expr distance, Expr travelled) {
        return new Expr.Binary(BinaryOp.LESS_THAN, distance,
                Ir.add(Ir.f(march.hitEpsilon()), Ir.mul(Ir.f(march.hitEpsilonSlope()), travelled)));
    }

    private static Region hit(SdfScene scene, Function sdf, Function albedoFn, Function payloadFn,
                              InterfaceVar fragColor,
                              LocalVar p, LocalVar rd, LocalVar t, LocalVar cosForward) {
        // Finite-difference normal, sampled at a width that grows with distance. At a fixed near-field width a
        // far hit point's neighbours differ only by float noise, so normalize() amplifies it and the normal
        // flips sign — black scribbles across distant grazing slopes. Widening makes the normal describe the
        // surface at the pixel's actual scale: crisp near, broad far.
        MarchSettings march = scene.march();
        RegionBindings bindings = new RegionBindings();
        Expr width = Ir.add(Ir.f(march.normalEpsilon()), Ir.mul(Ir.f(march.normalEpsilonSlope()), read(t)));

        // Bound before it reaches the shading model. This expression is six calls into the distance field, and a
        // model is free to reference the normal more than once — unbound, each reference would drag another six
        // calls in with it.
        Expr normal = bindings.bind("normal", Expr.MathCall.normalize(Ir.v3(
                centralDifference(sdf, p, Ir.v3(width, Ir.f(0.0), Ir.f(0.0))),
                centralDifference(sdf, p, Ir.v3(Ir.f(0.0), width, Ir.f(0.0))),
                centralDifference(sdf, p, Ir.v3(Ir.f(0.0), Ir.f(0.0), width)))));

        // Bound for the same reason the normal is: a model may reference the albedo more than once, and where
        // the surface carries its own colour that expression is a call rather than a constant.
        Expr albedo = albedoFn == null
                ? sceneAlbedo(scene)
                : bindings.bind("albedo", new Expr.Call(albedoFn, List.of(read(p))));

        // Which surface was hit, where the field was lowered to say so (P2). One call at the hit point,
        // once per pixel — the march itself never asks, because what it needs to advance is the distance and
        // nothing else. Bound, since a model that keys off identity will read it more than once.
        Expr payload = payloadFn == null
                ? ShadingPoint.NO_PAYLOAD
                : bindings.bind("hit", Ir.y(new Expr.Call(payloadFn, List.of(read(p)))));
        Expr shaded = scene.shading().shade(
                ShadingPoint.diffuse(read(p), normal, Ir.neg(read(rd)), albedo, payload), bindings);

        LocalVar colour = new LocalVar("colour", Ir.V3);
        List<Statement> statements = new ArrayList<>(bindings.statements());
        statements.add(new Statement.DeclareVar(colour, shaded));
        statements.add(new Statement.InterfaceWrite(fragColor, opaque(read(colour))));
        // The march's own hit distance, as a depth anything sharing this attachment can be compared against.
        // t is radial — distance along a unit ray — and a depth buffer holds the planar distance, which is
        // what cosForward converts; ClipDepth is where that difference is written down and why it matters.
        statements.add(scene.clipDepth().write(read(t), read(cosForward)));
        return new Region(statements);
    }

    /**
     * {@link Bindings} backed by a list of declarations the composer splices in ahead of their uses.
     *
     * <p>Names are made unique by a counter rather than trusted from the caller, so two models — or one model
     * binding twice — cannot collide. The counter advances in composition order, which is deterministic, so
     * composing the same scene twice still yields byte-identical SPIR-V.
     */
    private static final class RegionBindings implements Bindings {

        private final List<Statement> statements = new ArrayList<>();
        private int next;

        @Override
        public Expr bind(String name, Expr value) {
            LocalVar variable = new LocalVar(name + "_" + next++, value.type());
            statements.add(new Statement.DeclareVar(variable, value));
            return new Expr.Read(variable);
        }

        List<Statement> statements() {
            return statements;
        }
    }

    private static Region miss(SdfScene scene, InterfaceVar fragColor) {
        Surface.Rgb sky = scene.sky();
        return Region.of(
                new Statement.InterfaceWrite(fragColor, opaque(Ir.v3(sky.r(), sky.g(), sky.b()))),
                // The far plane, not a skipped write. A ray that reached farPlane without hitting anything
                // has established that nothing occupies the space in front of it, which is what a depth of 1
                // means — and a branch that wrote no depth at all would leave this pixel's depth undefined.
                scene.clipDepth().missed());
    }

    /** {@code sdf(p + offset) - sdf(p - offset)} — one axis of the gradient, by central difference. */
    private static Expr centralDifference(Function sdf, LocalVar p, Expr offset) {
        return Ir.sub(call(sdf, Ir.add(read(p), offset)), call(sdf, Ir.sub(read(p), offset)));
    }

    private static Expr opaque(Expr colour) {
        return new Expr.VectorConstruct(Ir.V4, List.of(
                new Expr.VectorExtract(colour, 0),
                new Expr.VectorExtract(colour, 1),
                new Expr.VectorExtract(colour, 2),
                Ir.f(1.0)));
    }

    private static Expr read(LocalVar v) {
        return new Expr.Read(v);
    }

    private static Expr call(Function sdf, Expr point) {
        return new Expr.Call(sdf, List.of(point));
    }
}
