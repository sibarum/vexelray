package dev.vexelray.technique.mesh;

import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Lens;
import dev.vexelray.shader.Shading;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * Polygon meshes in the frame: triangles drawn by the rasteriser, projected through the scene's camera, lit by the
 * scene's lighting model and sharing the frame's depth buffer, so a mesh and a marched surface occlude each other
 * per pixel.
 *
 * <pre>{@code
 * SdfRaymarchTechnique march = new SdfRaymarchTechnique(scene);
 * MeshTechnique meshes = new MeshTechnique(scene.lens(), scene.shading());
 * MeshTechnique.Instance crate = meshes.add(Mesh.box(1, 1, 1), Placement.at(2, 0.5, 6));
 *
 * RenderPipeline pipeline = RenderPipeline.builder()
 *         .target(Target.windowed("Fathom", 1280, 720)
 *                 .color(AttachmentFormat.SWAPCHAIN)
 *                 .depth(AttachmentFormat.DEPTH32F))
 *         .technique(march)
 *         .technique(meshes)
 *         .build();
 *
 * engine.run(pipeline, frame -> {
 *     march.camera(eyeX, eyeY, eyeZ, yaw, pitch);
 *     meshes.camera(eyeX, eyeY, eyeZ, yaw, pitch);     // the same camera, said twice (see PanelTechnique)
 *     crate.place(crate.placement().turned(t, 0));
 * });
 * }</pre>
 *
 * <h2>Meshes are uploaded once; instances move every frame</h2>
 *
 * <p>Every mesh {@link #add added} before {@link #realize} is packed into one vertex buffer, once. What changes per
 * frame is an {@link Instance}'s placement, tint and visibility, each of which is a push constant, so moving a
 * thousand-triangle mesh costs the same as moving a triangle: 96 bytes and a draw. An instance may be added after
 * realise if its mesh was already uploaded — another of the same crate — but a <em>new</em> mesh after realise is
 * refused, because growing a device buffer mid-run is either a stall or a second buffer alive while the first is
 * in flight, and this technique does neither silently.
 *
 * <h2>Depth</h2>
 *
 * <p>Meshes are opaque and always test and write depth when the frame has it, so the result does not depend on
 * where in the pipeline this technique sits: before the march or after it, the same pixels survive. Without a
 * depth attachment they draw in submission order, like anything else.
 *
 * <h2>Two-sided, no culling</h2>
 *
 * <p>See {@link Mesh}: a polygon is lit from whichever side it is seen from, so a mesh author never has to know
 * this engine's handedness. Back faces of a closed solid are drawn and then lose the depth test to the front
 * ones, which costs fill rate a back-face cull would save; that cull is worth adding the day a scene is
 * fill-bound on meshes, and it needs a winding convention this module has so far been able not to impose.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link RenderTechnique}'s contract, unchanged: render thread only. {@link #camera}, {@link #add} and the
 * {@link Instance} setters from the frame callback; {@link #record} a few microseconds later.
 */
public final class MeshTechnique implements RenderTechnique {

    /** What {@link #camera(double, double, double, double, double, double)} takes to mean the frame's own aspect. */
    public static final double FRAME_ASPECT = 0;

    private final double focalLength;
    private final ClipDepth clipDepth;
    private final Shading shading;

    /** Instances in the order they were added, which is the order they draw. */
    private final List<Instance> instances = new ArrayList<>();

    /** Where each uploaded mesh starts in the vertex buffer, in vertices. Filled at realise. */
    private final Map<Mesh, Integer> firstVertex = new IdentityHashMap<>();

    private double camX;
    private double camY;
    private double camZ;
    private double yaw;
    private double pitch;
    private double aspect = FRAME_ASPECT;

    private GraphicsPipeline pipeline;
    private VertexBuffer vertices;
    private DrawCommands cmds;
    private MemorySegment push;
    private final float[] pushFloats = new float[MeshShader.PUSH_FLOATS];

    /**
     * @param lens    the scene's lens — {@code scene.lens()}, the one the rest of the frame projects and writes
     *                depth with
     * @param shading the lighting model; pass the scene's own for meshes lit like the marched surfaces
     */
    public MeshTechnique(Lens lens, Shading shading) {
        Objects.requireNonNull(lens, "lens");
        this.focalLength = lens.focalLength();
        this.clipDepth = lens.clipDepth();
        this.shading = Objects.requireNonNull(shading, "shading");
    }

    /** An instance of {@code mesh} at the world origin. See {@link #add(Mesh, Placement)}. */
    public Instance add(Mesh mesh) {
        return add(mesh, Placement.ORIGIN);
    }

    /**
     * Draw {@code mesh} at {@code placement}, from the next frame on. The same mesh may be added any number of
     * times; it is uploaded once.
     *
     * @throws IllegalStateException after realise, for a mesh that was not uploaded at realise — see the class note
     */
    public Instance add(Mesh mesh, Placement placement) {
        Objects.requireNonNull(mesh, "mesh");
        Objects.requireNonNull(placement, "placement");
        if (pipeline != null && !firstVertex.containsKey(mesh)) {
            throw new IllegalStateException("this mesh was not added before realise, so it is not in the vertex "
                    + "buffer; add every distinct mesh before the engine runs, then add instances of it freely");
        }
        Instance instance = new Instance(mesh, placement);
        instances.add(instance);
        return instance;
    }

    /** Stop drawing {@code instance}. Its mesh stays uploaded, so it may be added again. */
    public void remove(Instance instance) {
        instances.remove(instance);
    }

    /** The instances, in drawing order. */
    public List<Instance> instances() {
        return List.copyOf(instances);
    }

    /**
     * The camera to project through: the same five numbers, in the same convention, as the scene's own camera.
     * Per frame, from the engine's frame callback.
     */
    public MeshTechnique camera(double x, double y, double z, double yaw, double pitch) {
        return camera(x, y, z, yaw, pitch, FRAME_ASPECT);
    }

    /**
     * The same, for a frame that is <b>not presented at its own shape</b> — an offscreen target sampled into a box
     * of another aspect. {@code PanelTechnique.camera} has the whole argument; it applies unchanged.
     *
     * @param aspect width over height of the image as displayed, or {@link #FRAME_ASPECT} for the frame's own
     */
    public MeshTechnique camera(double x, double y, double z, double yaw, double pitch, double aspect) {
        if (aspect != FRAME_ASPECT && (!(aspect > 0) || !Double.isFinite(aspect))) {
            throw new IllegalArgumentException("aspect must be finite and positive, or FRAME_ASPECT, got " + aspect);
        }
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.aspect = aspect;
        return this;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanDevice device = ((VulkanTechniqueContext) ctx).device();

        // Every distinct mesh, once, in the order first added.
        int floats = 0;
        for (Instance instance : instances) {
            if (!firstVertex.containsKey(instance.mesh)) {
                firstVertex.put(instance.mesh, floats / Mesh.FLOATS_PER_VERTEX);
                floats += instance.mesh.floatCount();
            }
        }
        if (floats == 0) {
            throw new IllegalStateException("a MeshTechnique with no meshes; add at least one before realise");
        }
        float[] data = new float[floats];
        for (Map.Entry<Mesh, Integer> entry : firstVertex.entrySet()) {
            entry.getKey().copyInto(data, entry.getValue() * Mesh.FLOATS_PER_VERTEX);
        }
        this.vertices = new VertexBuffer(device, data);

        List<GraphicsPipeline.VertexAttribute> attrs = List.of(
                GraphicsPipeline.VertexAttribute.floats(MeshShader.LOC_POSITION, 4, Mesh.POSITION * Float.BYTES),
                GraphicsPipeline.VertexAttribute.floats(MeshShader.LOC_NORMAL, 4, Mesh.NORMAL * Float.BYTES),
                GraphicsPipeline.VertexAttribute.floats(MeshShader.LOC_COLOUR, 4, Mesh.COLOUR * Float.BYTES));
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                Mesh.STRIDE_BYTES, attrs, new long[0], false,
                Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT, MeshShader.PUSH_BYTES, true)
                .withDepth(ctx.hasDepth()
                        ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                        : GraphicsPipeline.Config.Depth.NONE);

        ComposedShader vs = MeshShader.vertex(clipDepth);
        ComposedShader fs = MeshShader.fragment(shading);
        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                vs.spirv(), "main", fs.spirv(), "main", config);

        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(MeshShader.PUSH_BYTES);
    }

    @Override
    public void record(FrameContext frame) {
        if (instances.isEmpty()) {
            return;
        }
        MemorySegment cmd = frame.commandBuffer();
        int width = frame.width();
        int height = frame.height();
        double shown = aspect != FRAME_ASPECT ? aspect : (height == 0 ? 1.0 : (double) width / height);

        float[] p = pushFloats;
        p[MeshShader.PUSH_EYE] = (float) camX;
        p[MeshShader.PUSH_EYE + 1] = (float) camY;
        p[MeshShader.PUSH_EYE + 2] = (float) camZ;
        p[MeshShader.PUSH_COS_YAW] = (float) Math.cos(yaw);
        p[MeshShader.PUSH_SIN_YAW] = (float) Math.sin(yaw);
        p[MeshShader.PUSH_COS_PITCH] = (float) Math.cos(pitch);
        p[MeshShader.PUSH_SIN_PITCH] = (float) Math.sin(pitch);
        p[MeshShader.PUSH_FOCAL] = (float) focalLength;
        p[MeshShader.PUSH_ASPECT] = (float) shown;

        cmds.bindPipeline(cmd, pipeline);
        cmds.bindVertexBuffer(cmd, vertices.handle());
        int stages = Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT;
        for (int i = 0; i < instances.size(); i++) {
            Instance instance = instances.get(i);
            if (!instance.visible) {
                continue;
            }
            instance.placement.modelMatrix(p, MeshShader.PUSH_MODEL);
            p[MeshShader.PUSH_TINT] = instance.r;
            p[MeshShader.PUSH_TINT + 1] = instance.g;
            p[MeshShader.PUSH_TINT + 2] = instance.b;
            // Pushed per draw: vkCmdPushConstants records the bytes into the command buffer at this point, so
            // reusing the one segment for the next instance does not change what this draw sees.
            MemorySegment.copy(p, 0, push, JAVA_FLOAT, 0, p.length);
            cmds.push(cmd, pipeline, stages, push, MeshShader.PUSH_BYTES);
            cmds.draw(cmd, instance.mesh.vertexCount(), firstVertex.get(instance.mesh));
        }
    }

    @Override
    public void close() {
        if (vertices != null) {
            vertices.close();
            vertices = null;
        }
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (cmds != null) {
            cmds.close();
            cmds = null;
        }
        firstVertex.clear();
    }

    /** One mesh, placed. Its setters are for the frame callback, like the rest of the technique's per-frame data. */
    public static final class Instance {

        private final Mesh mesh;
        private Placement placement;
        private float r = 1;
        private float g = 1;
        private float b = 1;
        private boolean visible = true;

        private Instance(Mesh mesh, Placement placement) {
            this.mesh = mesh;
            this.placement = placement;
        }

        public Mesh mesh() {
            return mesh;
        }

        public Placement placement() {
            return placement;
        }

        /** Move, turn or resize this instance. */
        public Instance place(Placement placement) {
            this.placement = Objects.requireNonNull(placement, "placement");
            return this;
        }

        /** Multiply this instance's vertex colours by {@code (r, g, b)} — one mesh, many colours. White is none. */
        public Instance tint(double r, double g, double b) {
            this.r = (float) r;
            this.g = (float) g;
            this.b = (float) b;
            return this;
        }

        /** Draw this instance, or skip it without removing it. */
        public Instance visible(boolean visible) {
            this.visible = visible;
            return this;
        }

        public boolean visible() {
            return visible;
        }
    }
}
