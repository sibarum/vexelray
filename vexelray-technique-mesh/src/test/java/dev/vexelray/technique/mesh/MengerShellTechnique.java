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
    private final float[] pushFloats = new float[MeshShader.PUSH_FLOATS];

    MengerShellTechnique(Lens lens, Shading shading) {
        this.focalLength = lens.focalLength();
        this.clipDepth = lens.clipDepth();
        this.shading = shading;
    }

    /** A shell, by index for {@link #place}. */
    int add(Mesh mesh, Placement placement) {
        meshes.add(mesh);
        placements.add(placement);
        return meshes.size() - 1;
    }

    Placement placement(int shell) {
        return placements.get(shell);
    }

    void place(int shell, Placement placement) {
        placements.set(shell, placement);
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
                Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT, MeshShader.PUSH_BYTES, true)
                .withDepth(GraphicsPipeline.Config.Depth.TEST_AND_WRITE);
        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                MeshShader.vertex(clipDepth).spirv(), "main",
                MengerShellShader.fragment(shading, clipDepth).spirv(), "main", config);
        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(MeshShader.PUSH_BYTES);
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
            MemorySegment.copy(p, 0, push, JAVA_FLOAT, 0, p.length);
            cmds.push(cmd, pipeline, stages, push, MeshShader.PUSH_BYTES);
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
