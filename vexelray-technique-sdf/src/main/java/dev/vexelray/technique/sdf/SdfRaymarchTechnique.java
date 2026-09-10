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
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

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
 * <h2>Depth: declared honestly as absent</h2>
 *
 * <p>This technique uses {@link GraphicsPipeline.Config.Depth#NONE} even when the shared target has depth, and
 * that is not an oversight. The march runs over a fullscreen triangle whose vertices carry one fixed z, so the
 * only depth it could write is that constant — which is not the distance to the surface it actually hit, and
 * would occlude a mesh at a plane rather than at the geometry. Writing a plausible wrong number is worse than
 * writing none: a cross-occlusion bug that looks like a modelling mistake.
 *
 * <p>Making this participate in depth means the fragment writing {@code gl_FragDepth} from the march's own hit
 * distance, which is a real feature with a real cost (it defeats early-z for this pipeline) and belongs in its
 * own change. Until then a marched scene composites by order, and this note is what stops the absence from
 * looking like a decision nobody made.
 */
public final class SdfRaymarchTechnique implements RenderTechnique {

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

    /** The scene this technique marches, for a caller that needs its shading or march settings back. */
    public SdfScene scene() {
        return scene;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanTechniqueContext vk = (VulkanTechniqueContext) ctx;
        VulkanDevice device = vk.device();

        List<ComposedShader> composed = new SdfComposer().compose(scene);
        this.pushBytes = SdfComposer.pushBytes(scene);

        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, pushBytes, true);

        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                composed.get(0).spirv(), "main", composed.get(1).spirv(), "main", config);

        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(pushBytes);
        this.pushFloats = new float[pushBytes / Float.BYTES];
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
        cmds.pushFragment(cmd, pipeline, push, pushBytes);

        cmds.draw(cmd, 3);
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
    }
}
