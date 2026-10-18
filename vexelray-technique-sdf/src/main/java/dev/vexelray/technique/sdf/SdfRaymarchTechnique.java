package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.tools.Fullscreen;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.surface.Bounds;
import dev.vexelray.surface.ParamBlock;
import dev.vexelray.surface.ParamId;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * The SDF ray-march as a {@link RenderTechnique} — the first real technique, and the one the whole pipeline
 * model was designed around.
 *
 * <p>What it is, mechanically, is the thing every march smoke and Fathom itself already did by hand: compose a
 * scene's fragment shader with {@link SdfComposer}, build a pipeline over a fullscreen triangle, push a camera,
 * and draw three vertices. What is new is that it does not own a window, a device, a swapchain, or a frame loop,
 * so it can appear in a frame beside something else.
 *
 * <h2>Per-frame data is this class's own API (D5)</h2>
 *
 * <p>The runtime never learns what a camera is. An application calls {@link #camera} and {@link #parameter}
 * from the engine's frame callback, and {@link #record} turns whatever was last set into the byte layout
 * {@code SdfComposer} declared. That is why the presenter could stop taking a {@code pushConstantBytes} count:
 * the layout belongs to the technique that authored it, and no two techniques need to agree on one.
 *
 * <h2>Depth: the march's own hit distance</h2>
 *
 * <p>This technique tests and writes depth whenever the shared target has it, and the depth it writes is the
 * distance to the surface the ray actually hit — {@code gl_FragDepth} from the march's {@code t}, through the
 * scene's {@link dev.vexelray.shader.ClipDepth} convention. That is what lets a marched surface and a
 * rasterised one interleave per pixel rather than merely paint in order, which is the entire argument for
 * compositing N techniques into one render pass instead of into N textures.
 *
 * <p>It was not always so, and the reason is worth keeping. The march runs over a fullscreen triangle whose
 * vertices carry one fixed z, so without writing depth the only value it could contribute is that constant —
 * not the distance to anything, and it would occlude a mesh at a flat plane. Writing a plausible wrong number
 * is worse than writing none, so until {@code core} could say {@code FRAG_DEPTH} this declared
 * {@link GraphicsPipeline.Config.Depth#NONE} and said why.
 *
 * <p>What it costs: <b>early-z, for this pipeline</b>. A shader that replaces its own depth cannot have the
 * depth test settled before it runs, so every marched pixel executes whether or not something nearer will
 * cover it. For a fullscreen march that is close to the whole frame's work, and it is the price of being
 * occludable at all.
 *
 * <p>A target with no depth attachment still gets {@code Depth.NONE} and the same shader. The fragment writes
 * {@code gl_FragDepth} either way — the value simply goes nowhere — which keeps one compiled shader per scene
 * rather than one per scene per kind of target, and costs a few instructions in the case where nothing reads
 * them.
 *
 * <h2>Drawn through a mask</h2>
 *
 * <p>{@link #mask} replaces the fullscreen triangle with triangles in the world — typically a mesh's, from
 * {@code Mesh.worldPositions} — projected by the march's own pinhole. The march then runs on exactly the pixels
 * they cover, and the scene shows there and nowhere else: inside the mask's silhouette, with the mask's hard,
 * faceted edge, while the mask itself is never drawn. Nothing about the field or its depth changes; only which
 * pixels ask.
 *
 * <p>Where a masked ray misses, the pixel is left as it was — with a depth attachment, that is. A miss writes the
 * far-plane depth, and the depth test is {@code LESS}, so it can never win. A target without depth has no test,
 * and there a miss paints the scene's sky inside the mask, which is the honest picture of "march here" with nothing
 * to stop it.
 *
 * <p>A closed mesh covers each pixel twice, front face and back, and the march runs for both. The second loses
 * the depth test at an equal depth and changes nothing, but it is paid for; a mask made only of the faces that
 * can be seen is half the work.
 *
 * <h2>Culled to its box (D31)</h2>
 *
 * <p>{@link #cullToBounds} masks the march with its scene's own conservative box, {@code Bounds.of}, and the
 * fragment then marches each ray only between where it enters that box and where it leaves. The box contains
 * everything the field draws, so the edges are invisible and the picture is the unculled one; what goes is the
 * work on pixels that could never hit and on the stretches of ray before and after the box. A scene with no box —
 * a plane, a lattice to the horizon — stays fullscreen.
 */
public final class SdfRaymarchTechnique implements RenderTechnique {

    /** Floats per mask vertex in the vertex buffer: {@code x, y, z, 1}. */
    private static final int MASK_FLOATS_PER_VERTEX = 4;

    /** Floats per box-mask vertex: the position as above, then the box's two corners, each padded to four. */
    private static final int BOX_FLOATS_PER_VERTEX = 12;

    /**
     * A box's twelve triangles, as corner indices — bit 0 of a corner is x, bit 1 y, bit 2 z, set for the max side.
     * Winding is immaterial: a mask is drawn with no culling.
     */
    private static final int[] BOX_TRIANGLES = {
            0, 1, 3, 0, 3, 2,   // z min
            4, 5, 7, 4, 7, 6,   // z max
            0, 1, 5, 0, 5, 4,   // y min
            2, 3, 7, 2, 7, 6,   // y max
            0, 2, 6, 0, 6, 4,   // x min
            1, 3, 7, 1, 7, 5};  // x max
    private static final int BOX_VERTICES = BOX_TRIANGLES.length;

    /** How much a culling box grows, relative to its size, so rounding cannot shave what touches it. */
    private static final double BOX_MARGIN = 1e-4;

    private final SdfScene scene;
    private final ParamBlock params;

    private double camX;
    private double camY;
    private double camZ;
    private double yaw;
    private double pitch;

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private MemorySegment push;
    private int pushBytes;

    /**
     * This frame's push-constant values, reused for the life of the technique.
     *
     * <p>{@code record} used to call {@link SdfComposer#pushConstantBytes}, which rebuilds the scene's
     * {@link ParamBlock} — a walk of the surface tree — and allocates a {@code float[]} and a {@code byte[]},
     * every frame, a few lines from a comment about how carefully the arena is reused.
     */
    private float[] pushFloats;

    /** The mask's vertices as {@code x, y, z, 1}, or null for a fullscreen march. */
    private float[] mask;
    /** How many mask vertices the buffer holds room for; fixed by the first {@link #mask} call. */
    private int maskCapacity;
    private int maskVertices;
    private boolean maskDirty;
    private VertexBuffer maskBuffer;
    /** Whether the mask is a culling box ({@link #cullTo}), whose vertices also carry the box. */
    private boolean boxed;

    /**
     * A technique that marches {@code scene}.
     *
     * <p>The scene is fixed for this technique's life, because its <em>shape</em> is what the shader was
     * compiled from — changing it is a new pipeline, which is the distinction 79ed04c drew between a slider and
     * a shape. Numbers the scene declared as {@link dev.vexelray.surface.Scalar.Param} are driven through
     * {@link #parameter} without recompiling anything.
     */
    public SdfRaymarchTechnique(SdfScene scene) {
        this.scene = scene;
        this.params = SdfComposer.paramBlock(scene);
    }

    /** Where the eye is and where it looks, in world space. Called from the engine's frame callback. */
    public SdfRaymarchTechnique camera(double x, double y, double z, double yaw, double pitch) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
        return this;
    }

    /** Drive one of the scene's declared parameters — a push, not a recompile. */
    public SdfRaymarchTechnique parameter(ParamId id, double value) {
        params.write(id, value);
        return this;
    }

    /**
     * Draw this scene only where these world-space triangles cover the screen — see the class note.
     * {@code xyz} is three numbers per vertex, three vertices per triangle, which is what
     * {@code Mesh.worldPositions} returns.
     *
     * <p>The first call must come before {@link #realize}: it decides that this technique is masked, which is a
     * different vertex stage, and how many vertices the mask buffer holds. Later calls, from the frame callback,
     * move or reshape the mask within that capacity — a mask that follows a moving mesh is one call a frame.
     *
     * @throws IllegalStateException for a first call after realise, or a later mask larger than the first
     */
    public SdfRaymarchTechnique mask(float[] xyz) {
        if (xyz.length == 0 || xyz.length % 9 != 0) {
            throw new IllegalArgumentException("a mask is whole triangles, nine numbers each; got " + xyz.length);
        }
        if (boxed) {
            throw new IllegalStateException("this march is culled to a box (cullTo); a mask of other triangles "
                    + "is a different vertex layout");
        }
        int vertices = xyz.length / 3;
        if (mask == null) {
            if (pipeline != null) {
                throw new IllegalStateException("this march was realised unmasked; a mask changes its vertex "
                        + "stage, so the first mask() must come before realise");
            }
            maskCapacity = vertices;
            mask = new float[vertices * MASK_FLOATS_PER_VERTEX];
        } else if (vertices > maskCapacity) {
            throw new IllegalStateException("this mask has " + vertices + " vertices and the buffer was sized for "
                    + maskCapacity + " by the first mask() call; give the largest mask first");
        }
        for (int i = 0; i < vertices; i++) {
            mask[i * 4] = xyz[i * 3];
            mask[i * 4 + 1] = xyz[i * 3 + 1];
            mask[i * 4 + 2] = xyz[i * 3 + 2];
            mask[i * 4 + 3] = 1;
        }
        maskVertices = vertices;
        maskDirty = true;
        return this;
    }

    /** Whether this march is drawn through a mask. */
    public boolean masked() {
        return mask != null;
    }

    /**
     * Cull this march to its scene's own conservative box, {@link Bounds#of} — see the class note. For a scene with
     * no box, a plane or anything else that reaches forever, this does nothing and the march stays fullscreen,
     * which {@link #culled()} reports.
     *
     * <p>The frame is the unculled one: the box contains everything the field draws, so no pixel it leaves out
     * could have hit, and no stretch of ray the slab test skips could either.
     *
     * @throws IllegalStateException as {@link #cullTo}
     */
    public SdfRaymarchTechnique cullToBounds() {
        Bounds.of(scene.surface()).ifPresent(this::cullTo);
        return this;
    }

    /**
     * Cull this march to {@code box}: draw it through the box's twelve triangles, so it runs only on the pixels the
     * box covers, and march each ray only over its stretch inside the box ({@link SdfComposer.Screen#BOX}).
     *
     * <p>The box must contain everything the scene draws. Where it does not, the scene is cut at the box, silently;
     * {@link #cullToBounds} is the call that cannot get that wrong. The box is grown by a hair before use, so a
     * surface that touches it — a sphere's box touches the sphere at six points — is not shaved by rounding.
     *
     * <p>Both faces are drawn and none culled, so a camera inside the box sees its back faces cover the screen
     * and marches from the eye, as an unculled march would. A camera outside pays for each pixel twice, front face
     * and back; the second loses the depth test and changes nothing.
     *
     * <p>Like {@link #mask}, the first call comes before {@link #realize}; later ones move the box.
     *
     * @throws IllegalStateException for a first call after realise, or on a march already given a {@link #mask}
     */
    public SdfRaymarchTechnique cullTo(Bounds box) {
        if (mask != null && !boxed) {
            throw new IllegalStateException("this march already has a mask of other triangles; a box mask is a "
                    + "different vertex layout");
        }
        if (mask == null && pipeline != null) {
            throw new IllegalStateException("this march was realised unculled; a box changes its vertex stage, so "
                    + "the first cullTo() must come before realise");
        }
        Bounds b = padded(box);
        float[] xyz = boxTriangles(b);
        if (mask == null) {
            boxed = true;
            maskCapacity = BOX_VERTICES;
            mask = new float[BOX_VERTICES * BOX_FLOATS_PER_VERTEX];
        }
        for (int v = 0; v < BOX_VERTICES; v++) {
            int at = v * BOX_FLOATS_PER_VERTEX;
            mask[at] = xyz[v * 3];
            mask[at + 1] = xyz[v * 3 + 1];
            mask[at + 2] = xyz[v * 3 + 2];
            mask[at + 3] = 1;
            mask[at + 4] = (float) b.minX();
            mask[at + 5] = (float) b.minY();
            mask[at + 6] = (float) b.minZ();
            mask[at + 7] = 0;
            mask[at + 8] = (float) b.maxX();
            mask[at + 9] = (float) b.maxY();
            mask[at + 10] = (float) b.maxZ();
            mask[at + 11] = 0;
        }
        maskVertices = BOX_VERTICES;
        maskDirty = true;
        return this;
    }

    /** {@code box} grown by the hair {@link #cullTo} grows it by. */
    static Bounds padded(Bounds box) {
        return box.expanded(BOX_MARGIN * (1 + box.largestHalfExtent()));
    }

    /** {@code box}'s twelve triangles as {@link #mask} takes them: three numbers a vertex, three vertices each. */
    static float[] boxTriangles(Bounds box) {
        float[] xyz = new float[BOX_VERTICES * 3];
        for (int v = 0; v < BOX_VERTICES; v++) {
            int c = BOX_TRIANGLES[v];
            xyz[v * 3] = (float) ((c & 1) == 0 ? box.minX() : box.maxX());
            xyz[v * 3 + 1] = (float) ((c & 2) == 0 ? box.minY() : box.maxY());
            xyz[v * 3 + 2] = (float) ((c & 4) == 0 ? box.minZ() : box.maxZ());
        }
        return xyz;
    }

    /** Whether this march is culled to a box — false for a scene {@link #cullToBounds} found no box for. */
    public boolean culled() {
        return boxed;
    }

    private int stride() {
        return boxed ? BOX_FLOATS_PER_VERTEX : MASK_FLOATS_PER_VERTEX;
    }

    /** The scene this technique marches, for a caller that needs its shading or march settings back. */
    public SdfScene scene() {
        return scene;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanTechniqueContext vk = (VulkanTechniqueContext) ctx;
        VulkanDevice device = vk.device();

        boolean masked = mask != null;
        List<ComposedShader> composed = boxed
                ? new SdfComposer().composeBoxed(scene)
                : masked
                ? new SdfComposer().composeMasked(scene)
                : new SdfComposer().compose(scene);
        this.pushBytes = SdfComposer.pushBytes(scene);

        // A masked march reads its vertices from a buffer and its vertex stage reads the camera, so both stages
        // share the push range; the fullscreen one has no vertex input and only the fragment reads anything.
        List<GraphicsPipeline.VertexAttribute> attributes = !masked ? List.of() : boxed
                ? List.of(
                        GraphicsPipeline.VertexAttribute.floats(SdfComposer.MASK_POSITION_LOCATION, 4, 0),
                        GraphicsPipeline.VertexAttribute.floats(SdfComposer.MASK_BOX_MIN_LOCATION, 4, 16),
                        GraphicsPipeline.VertexAttribute.floats(SdfComposer.MASK_BOX_MAX_LOCATION, 4, 32))
                : List.of(GraphicsPipeline.VertexAttribute.floats(SdfComposer.MASK_POSITION_LOCATION, 4, 0));
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                masked ? stride() * Float.BYTES : 0, attributes,
                new long[0], false, pushStages(), pushBytes, true)
                // Depth state exactly when the shared pass has depth. The fragment writes gl_FragDepth
                // regardless — see the class note — so this is the only thing that decides whether the march
                // participates in occlusion or paints over whatever came before it.
                .withDepth(ctx.hasDepth()
                        ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                        : GraphicsPipeline.Config.Depth.NONE);

        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                composed.get(0).spirv(), "main", composed.get(1).spirv(), "main", config);

        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(pushBytes);
        this.pushFloats = new float[pushBytes / Float.BYTES];
        if (masked) {
            this.maskBuffer = new VertexBuffer(device, maskCapacity * stride());
            maskBuffer.update(mask, maskVertices * stride());
            maskDirty = false;
        }
    }

    private int pushStages() {
        return mask != null
                ? Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT
                : Vk.SHADER_STAGE_FRAGMENT_BIT;
    }

    @Override
    public void record(FrameContext frame) {
        MemorySegment cmd = frame.commandBuffer();
        int width = frame.width();
        int height = frame.height();

        // Viewport and scissor are the runtime's: it sets them to this frame's extent before the first
        // technique records, being the only thing that knows the extent before anyone draws.
        cmds.bindPipeline(cmd, pipeline);

        // Aspect is the frame's, never the caller's. A camera API that took it would make every application
        // responsible for noticing a resize, and one that used the realise-time extent would stretch the scene
        // for the rest of the run.
        double aspect = height == 0 ? 1.0 : (double) width / height;
        SdfComposer.writePushConstants(scene, camX, camY, camZ, yaw, pitch, aspect, params, pushFloats);
        // JAVA_FLOAT is native order, which is the order a push constant is read in. The explicitly
        // little-endian ByteBuffer in pushConstantBytes says the same thing for a byte[] that might outlive
        // this machine.
        MemorySegment.copy(pushFloats, 0, push, JAVA_FLOAT, 0, pushFloats.length);
        if (mask == null) {
            cmds.pushFragment(cmd, pipeline, push, pushBytes);
            cmds.draw(cmd, 3);
            return;
        }
        // Updated here rather than in mask(): the frame callback runs after the previous frame's fence wait, and
        // so does record, and keeping the upload beside the draw keeps "the buffer the GPU reads" in one place.
        if (maskDirty) {
            maskBuffer.update(mask, maskVertices * stride());
            maskDirty = false;
        }
        cmds.push(cmd, pipeline, pushStages(), push, pushBytes);
        cmds.bindVertexBuffer(cmd, maskBuffer.handle());
        cmds.draw(cmd, maskVertices);
    }

    @Override
    public void close() {
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (cmds != null) {
            cmds.close();
            cmds = null;
        }
        if (maskBuffer != null) {
            maskBuffer.close();
            maskBuffer = null;
        }
    }
}
