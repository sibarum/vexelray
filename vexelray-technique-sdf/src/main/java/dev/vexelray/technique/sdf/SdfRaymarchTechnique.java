package dev.vexelray.technique.sdf;

import dev.supirvast.vastir.tools.Fullscreen;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.shader.ComposedShader;
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
 */
public final class SdfRaymarchTechnique implements RenderTechnique {

    /** Floats per mask vertex in the vertex buffer: {@code x, y, z, 1}. */
    private static final int MASK_FLOATS_PER_VERTEX = 4;

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

    /** The scene this technique marches, for a caller that needs its shading or march settings back. */
    public SdfScene scene() {
        return scene;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanTechniqueContext vk = (VulkanTechniqueContext) ctx;
        VulkanDevice device = vk.device();

        boolean masked = mask != null;
        List<ComposedShader> composed = masked
                ? new SdfComposer().composeMasked(scene)
                : new SdfComposer().compose(scene);
        this.pushBytes = SdfComposer.pushBytes(scene);

        // A masked march reads its vertices from a buffer and its vertex stage reads the camera, so both stages
        // share the push range; the fullscreen one has no vertex input and only the fragment reads anything.
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                masked ? MASK_FLOATS_PER_VERTEX * Float.BYTES : 0,
                masked ? List.of(GraphicsPipeline.VertexAttribute.floats(
                        SdfComposer.MASK_POSITION_LOCATION, MASK_FLOATS_PER_VERTEX, 0)) : List.of(),
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
            this.maskBuffer = new VertexBuffer(device, maskCapacity * MASK_FLOATS_PER_VERTEX);
            maskBuffer.update(mask, maskVertices * MASK_FLOATS_PER_VERTEX);
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
            maskBuffer.update(mask, maskVertices * MASK_FLOATS_PER_VERTEX);
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
