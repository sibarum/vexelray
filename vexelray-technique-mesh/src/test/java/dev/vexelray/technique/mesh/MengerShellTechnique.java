package dev.vexelray.technique.mesh;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.Lens;
import dev.vexelray.shader.Shading;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.VertexBuffer;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * Prototype (test scope): {@link MeshTechnique}'s drawing with {@link MengerShellShader} as the fragment stage, so
 * every mesh added is a clip shell onto the Menger lattice. Only what the demo needs: add before realise, place
 * per frame. The fragment writes its own depth, so this pipeline gives up the early depth test that
 * {@link MeshTechnique} keeps.
 */
final class MengerShellTechnique implements RenderTechnique {

    private final double focalLength;
    private final ClipDepth clipDepth;
    private final Shading shading;

    private final List<Mesh> meshes = new ArrayList<>();
    private final List<Placement> placements = new ArrayList<>();
    private final List<FieldSpace> fields = new ArrayList<>();
    private final Map<Mesh, Integer> firstVertex = new IdentityHashMap<>();

    private double camX;
    private double camY;
    private double camZ;
    private double yaw;
    private double pitch;

    private GraphicsPipeline pipeline;
    private VertexBuffer vertices;
    private DrawCommands cmds;
    private MemorySegment push;
    private final float[] pushFloats = new float[MengerShellShader.PUSH_FLOATS];

    /** The one mesh whose exits are baked in, or null for shells a ray marches on through. */
    private final Mesh exitMesh;

    MengerShellTechnique(Lens lens, Shading shading) {
        this(lens, shading, null);
    }

    /**
     * Shells that are transparent where a ray leaves them, eye's or reflected: no re-entry. Every shell added must
     * be {@code exitMesh}, whose triangles the shader tests in the mesh's own coordinates, so each may stand, move
     * and turn anywhere.
     */
    MengerShellTechnique(Lens lens, Shading shading, Mesh exitMesh) {
        this.focalLength = lens.focalLength();
        this.clipDepth = lens.clipDepth();
        this.shading = shading;
        this.exitMesh = exitMesh;
    }

    private MengerShellShader.Fade fade = MengerShellShader.Fade.STEPS;

    /** What darkens a hit; before realise, since it is compiled into the shader. */
    MengerShellTechnique fade(MengerShellShader.Fade fade) {
        if (pipeline != null) {
            throw new IllegalStateException("the fade is compiled in at realise; set it before");
        }
        this.fade = fade;
        return this;
    }

    private MengerShellShader.Mirror mirror;

    /** Polish the shells, reflecting {@code mirror}'s ground and sky; before realise, like {@link #fade}. */
    MengerShellTechnique mirror(MengerShellShader.Mirror mirror) {
        if (pipeline != null) {
            throw new IllegalStateException("the mirror is compiled in at realise; set it before");
        }
        this.mirror = mirror;
        return this;
    }

    /** A shell whose field moves with it ({@link FieldSpace#OBJECT}), by index for {@link #place}. */
    int add(Mesh mesh, Placement placement) {
        return add(mesh, placement, FieldSpace.OBJECT);
    }

    /** A shell, its field standing in {@code field}; by index for {@link #place} and {@link #field}. */
    int add(Mesh mesh, Placement placement, FieldSpace field) {
        if (exitMesh != null && mesh != exitMesh) {
            throw new IllegalArgumentException("this technique's exits are baked from one mesh; add only that one");
        }
        meshes.add(mesh);
        placements.add(placement);
        fields.add(field);
        return meshes.size() - 1;
    }

    Placement placement(int shell) {
        return placements.get(shell);
    }

    /** Move, turn or resize the shell; a field in the object goes with it, one in the world stays. */
    void place(int shell, Placement placement) {
        placements.set(shell, placement);
    }

    FieldSpace field(int shell) {
        return fields.get(shell);
    }

    /** Move the shell's field on its own, in the object or in the world: the shell stays where it is. */
    void field(int shell, FieldSpace field) {
        fields.set(shell, field);
    }

    void camera(double x, double y, double z, double yaw, double pitch) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanDevice device = ((VulkanTechniqueContext) ctx).device();
        int floats = 0;
        for (Mesh mesh : meshes) {
            if (!firstVertex.containsKey(mesh)) {
                firstVertex.put(mesh, floats / Mesh.FLOATS_PER_VERTEX);
                floats += mesh.floatCount();
            }
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
                Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT, MengerShellShader.PUSH_BYTES, true)
                .withDepth(GraphicsPipeline.Config.Depth.TEST_AND_WRITE);
        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                MeshShader.vertex(clipDepth).spirv(), "main",
                MengerShellShader.fragment(shading, clipDepth,
                        exitMesh == null ? null : MengerShellShader.localTriangles(exitMesh), fade, mirror).spirv(), "main", config);
        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(MengerShellShader.PUSH_BYTES);
    }

    @Override
    public void record(FrameContext frame) {
        MemorySegment cmd = frame.commandBuffer();
        float[] p = pushFloats;
        p[MeshShader.PUSH_EYE] = (float) camX;
        p[MeshShader.PUSH_EYE + 1] = (float) camY;
        p[MeshShader.PUSH_EYE + 2] = (float) camZ;
        p[MeshShader.PUSH_COS_YAW] = (float) Math.cos(yaw);
        p[MeshShader.PUSH_SIN_YAW] = (float) Math.sin(yaw);
        p[MeshShader.PUSH_COS_PITCH] = (float) Math.cos(pitch);
        p[MeshShader.PUSH_SIN_PITCH] = (float) Math.sin(pitch);
        p[MeshShader.PUSH_FOCAL] = (float) focalLength;
        p[MeshShader.PUSH_ASPECT] = frame.height() == 0 ? 1f : (float) frame.width() / frame.height();
        p[MeshShader.PUSH_TINT] = 1f;
        p[MeshShader.PUSH_TINT + 1] = 1f;
        p[MeshShader.PUSH_TINT + 2] = 1f;

        cmds.bindPipeline(cmd, pipeline);
        cmds.bindVertexBuffer(cmd, vertices.handle());
        int stages = Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT;
        for (int i = 0; i < meshes.size(); i++) {
            placements.get(i).modelMatrix(p, MeshShader.PUSH_MODEL);
            fields.get(i).worldToField(placements.get(i), p, MengerShellShader.PUSH_FIELD);
            MemorySegment.copy(p, 0, push, JAVA_FLOAT, 0, p.length);
            cmds.push(cmd, pipeline, stages, push, MengerShellShader.PUSH_BYTES);
            cmds.draw(cmd, meshes.get(i).vertexCount(), firstVertex.get(meshes.get(i)));
        }
    }

    @Override
    public void close() {
        if (vertices != null) {
            vertices.close();
        }
        if (pipeline != null) {
            pipeline.close();
        }
        if (cmds != null) {
            cmds.close();
        }
    }
}
