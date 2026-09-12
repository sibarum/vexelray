package dev.vexelray.technique.panel;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.shader.ClipDepth;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * A {@link Canvas} drawn on a plane in the world — the 2D API, projected through the scene's camera and sharing
 * the frame's depth buffer, so that shapes and text sit <em>in</em> a marched scene rather than in front of it.
 *
 * <pre>{@code
 * SdfRaymarchTechnique march = new SdfRaymarchTechnique(scene);
 * PanelTechnique panel = new PanelTechnique(new Canvas(800, 450),
 *         Panel.at(0, 1.5, 6, 0.002),          // 1.6m x 0.9m, six metres out
 *         scene.focalLength(), scene.clipDepth());
 *
 * RenderPipeline pipeline = RenderPipeline.builder()
 *         .target(Target.windowed("Fathom", 1280, 720)
 *                 .color(AttachmentFormat.SWAPCHAIN)
 *                 .depth(AttachmentFormat.DEPTH32F))
 *         .technique(march)
 *         .technique(panel)
 *         .build();
 *
 * engine.run(pipeline, frame -> {
 *     march.camera(eyeX, eyeY, eyeZ, yaw, pitch);
 *     panel.camera(eyeX, eyeY, eyeZ, yaw, pitch);      // the same camera, said twice (see below)
 *     panel.draw(c -> {
 *         c.fillRoundRect(0, 0, 800, 450, 24, slate);
 *         c.strokeLine(40, 400, 760, 400, 4, amber);   // 4 canvas units: 8mm on the surface, always
 *         c.text(layout, "READY", 40, 60, style, Color.WHITE);
 *     });
 * });
 * }</pre>
 *
 * <h2>Nothing here has a pixel size</h2>
 *
 * <p>The canvas is authored in canvas units and a {@link Panel} says how big one is in the world, so every
 * width, radius and glyph is a measurement on a surface. Approach the panel and the line gets thicker on screen
 * because it is closer, not because anything was re-authored; retreat and it thins. What does <em>not</em>
 * change is the sharpness: the edge is analytic and the shader computes its own anti-aliasing width from the
 * projection, so it is one pixel soft at arm's length and one pixel soft across the room. There is no texture
 * anywhere in this path and therefore no resolution to have chosen wrongly.
 *
 * <h2>Occlusion</h2>
 *
 * <p>The vertex stage emits {@link ClipDepth#clipZ}, the raster half of the convention the march writes by hand,
 * so the two agree about what depth means and the hardware interleaves them per pixel: a marched wall in front
 * of the panel hides it, a marched wall behind it does not. This is the whole reason to composite into one
 * shared target rather than into two images.
 *
 * <p>By default the panel <b>tests</b> depth and does not <b>write</b> it. That is the correct state for
 * something alpha-blended: a panel's translucent pixels should not stamp a depth that stops a later technique
 * from drawing through them, and its own overlapping shapes must composite in submission order rather than
 * fight over a depth they all share. {@link #occluding()} switches on the write for a panel that is opaque and
 * has to hide things drawn after it — which, because techniques record in pipeline order, is a question about
 * what comes later in the list, not about the panel.
 *
 * <h2>The camera is told to each technique, and that is on purpose</h2>
 *
 * <p>{@link #camera} takes the same five numbers as {@code SdfRaymarchTechnique.camera}, and an application with
 * both calls both. Per-frame data reaching a technique through the technique's own API is the engine's rule (D5)
 * and the alternative — a camera owned by the runtime — would make the runtime know what a camera is, which is
 * the thing it has stayed free of. What the two must share is the <em>convention</em>, and they do: same axes,
 * same order, plus a {@link ClipDepth} and a focal length handed in at construction so a panel cannot quietly
 * project differently from the scene it is standing in.
 *
 * <h2>Threading</h2>
 *
 * <p>{@link RenderTechnique}'s contract, unchanged: render thread only, fields plain, {@link #draw} and
 * {@link #camera} from the frame callback, {@link #record} a few microseconds later.
 */
public final class PanelTechnique implements RenderTechnique {

    /**
     * How many floats the vertex buffer is allocated for. Fixed at realise time for the reason
     * {@code CanvasTechnique} gives: a device allocation resized mid-frame is either a stall or a second buffer
     * alive while the first is in flight.
     */
    private static final int DEFAULT_CAPACITY_FLOATS = 1 << 20;

    private final Canvas canvas;
    private final int capacityFloats;
    private final double focalLength;
    private final ClipDepth clipDepth;

    private Panel panel;
    private boolean writesDepth;

    private double camX;
    private double camY;
    private double camZ;
    private double yaw;
    private double pitch;

    private VulkanDevice device;
    private AtlasTexture atlas;
    private boolean ownsAtlas;
    private AtlasSource atlasSource;
    private GraphicsPipeline pipeline;
    private VertexBuffer vertices;
    private int vertexCount;
    private List<Canvas.Run> runs = List.of();
    private float[] pending;

    private DrawCommands cmds;
    private MemorySegment push;
    private final float[] pushFloats = new float[PanelShader.PUSH_FLOATS];

    /**
     * @param canvas      the canvas to draw, whose width and height are the panel's extent in canvas units
     * @param panel       where that canvas hangs, and how big one of its units is
     * @param focalLength the scene's focal length — must be the one the rest of the frame projects with
     * @param clipDepth   the scene's depth convention — must be the one the rest of the frame writes
     */
    public PanelTechnique(Canvas canvas, Panel panel, double focalLength, ClipDepth clipDepth) {
        this(canvas, panel, focalLength, clipDepth, DEFAULT_CAPACITY_FLOATS);
    }

    /** As above, with room for {@code capacityFloats} of vertex data. */
    public PanelTechnique(Canvas canvas, Panel panel, double focalLength, ClipDepth clipDepth,
                          int capacityFloats) {
        this.canvas = Objects.requireNonNull(canvas, "canvas");
        this.panel = Objects.requireNonNull(panel, "panel");
        this.clipDepth = Objects.requireNonNull(clipDepth, "clipDepth");
        if (!(focalLength > 0) || !Double.isFinite(focalLength)) {
            throw new IllegalArgumentException("focalLength must be finite and positive, got " + focalLength);
        }
        this.focalLength = focalLength;
        this.capacityFloats = capacityFloats;
    }

    /**
     * Give this technique the glyph atlas its text is laid out against. Call before {@link #realize}; afterwards
     * the pipeline has been built against a descriptor set layout and swapping the atlas is a rebuild.
     *
     * <p>Without one, the placeholder atlas is used: shapes draw correctly and text draws as nothing, because
     * the uber-shader samples set 0 for glyph coverage and a placeholder has none.
     */
    public PanelTechnique atlas(AtlasTexture atlas) {
        if (pipeline != null) {
            throw new IllegalStateException("the pipeline was already built against an atlas layout; set the "
                    + "atlas before realise");
        }
        this.atlas = atlas;
        this.ownsAtlas = false;
        this.atlasSource = null;
        return this;
    }

    /**
     * Give this technique an atlas it cannot build yet: {@code source} is called at {@link #realize} with the
     * device, and the atlas it returns is owned and closed by this technique.
     *
     * <p>This overload exists because of a genuine ordering problem in the engine path. An
     * {@link AtlasTexture} needs a {@code VulkanDevice}, and an application that composes a
     * {@code RenderPipeline} and hands it to the engine does not have one — the device is created while the
     * pipeline is realised, which is after the last moment {@link #atlas(AtlasTexture)} may be called. Without
     * this, text on a panel is only available to an application that wires its own Vulkan, which is the thing
     * the engine exists to stop applications doing.
     *
     * @see #atlas(AtlasTexture) for a caller that already has a device and wants to share one atlas between
     *      several techniques
     */
    public PanelTechnique atlas(AtlasSource source) {
        Objects.requireNonNull(source, "source");
        if (pipeline != null) {
            throw new IllegalStateException("the pipeline was already built against an atlas layout; set the "
                    + "atlas before realise");
        }
        this.atlasSource = source;
        this.atlas = null;
        return this;
    }

    /** Builds the glyph atlas once the device exists. See {@link #atlas(AtlasSource)}. */
    @FunctionalInterface
    public interface AtlasSource {
        AtlasTexture create(VulkanDevice device);
    }

    /**
     * Write depth as well as testing it, so this panel occludes whatever is recorded after it.
     *
     * <p>Off by default, and see the class note: it is right for an opaque panel and wrong for a translucent
     * one, whose blended pixels would otherwise stamp a depth that stops later techniques drawing through them.
     * Call before {@link #realize}; depth-stencil state is baked into the pipeline.
     */
    public PanelTechnique occluding() {
        if (pipeline != null) {
            throw new IllegalStateException("depth state is baked into the pipeline; call occluding() before "
                    + "realise");
        }
        this.writesDepth = true;
        return this;
    }

    /** Move or turn the panel. Per frame, from the engine's frame callback. */
    public PanelTechnique panel(Panel panel) {
        this.panel = Objects.requireNonNull(panel, "panel");
        return this;
    }

    /** Where the panel currently hangs. */
    public Panel panel() {
        return panel;
    }

    /** The canvas this technique draws, for a caller that would rather hold it than pass a lambda. */
    public Canvas canvas() {
        return canvas;
    }

    /**
     * The camera to project through: the same five numbers, in the same convention, as the scene's own camera.
     * Per frame, from the engine's frame callback.
     */
    public PanelTechnique camera(double x, double y, double z, double yaw, double pitch) {
        this.camX = x;
        this.camY = y;
        this.camZ = z;
        this.yaw = yaw;
        this.pitch = pitch;
        return this;
    }

    /**
     * Build this frame's batch: {@code begin()} the canvas, run {@code drawing}, and keep the vertices for the
     * next {@link #record}. Call from the engine's frame callback, not from inside a technique's record.
     *
     * @throws IllegalStateException if the batch does not fit the buffer allocated at realise time — reported
     *         rather than truncated, because a canvas silently missing its last few shapes is the class of
     *         failure that reads as a layout bug
     */
    public PanelTechnique draw(Consumer<Canvas> drawing) {
        canvas.begin();
        drawing.accept(canvas);
        float[] data = canvas.toVertexArray();
        if (data.length > capacityFloats) {
            throw new IllegalStateException("this canvas batch is " + data.length + " floats and the buffer holds "
                    + capacityFloats + "; construct the technique with a larger capacity");
        }
        this.vertexCount = canvas.vertexCount();
        this.runs = canvas.runs();
        if (vertices != null) {
            vertices.update(data, data.length);
        } else {
            // Drawn before realise. Kept rather than dropped, so an application that builds its first frame
            // while assembling itself does not render one frame of nothing.
            this.pending = data;
        }
        return this;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanTechniqueContext vk = (VulkanTechniqueContext) ctx;
        this.device = vk.device();
        if (atlasSource != null) {
            this.atlas = Objects.requireNonNull(atlasSource.create(device), "the atlas source returned null");
            this.ownsAtlas = true;
        } else if (atlas == null) {
            this.atlas = AtlasTexture.placeholder(device);
            this.ownsAtlas = true;
        }

        List<GraphicsPipeline.VertexAttribute> attrs = new ArrayList<>();
        for (CanvasVertex.Attr a : CanvasVertex.ATTRIBUTES) {
            attrs.add(GraphicsPipeline.VertexAttribute.floats(a.location(), a.components(), a.offset()));
        }
        // Two set layouts for the same reason the screen-space canvas has two: set 0 is the glyph atlas and set 1
        // is whatever image a run binds, and the layout has to declare both even for a frame that draws no image.
        long[] setLayouts = {atlas.descriptorSetLayout(), atlas.descriptorSetLayout()};

        // Both stages read the push constants -- the vertex stage to place the quad, the fragment stage to
        // differentiate the projection for its anti-aliasing width.
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                CanvasVertex.STRIDE_BYTES, attrs, setLayouts, true,
                Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT, PanelShader.PUSH_BYTES, true)
                .withDepth(depthState(ctx));

        ComposedShader vs = PanelShader.vertex(clipDepth);
        ComposedShader fs = PanelShader.fragment();
        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                vs.spirv(), "main", fs.spirv(), "main", config);

        this.vertices = new VertexBuffer(device, capacityFloats);
        if (pending != null) {
            vertices.update(pending, pending.length);
            pending = null;
        }
        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(PanelShader.PUSH_BYTES);
    }

    /**
     * Depth state for the target this was realised against.
     *
     * <p>A panel in a frame with no depth attachment cannot be occluded by anything and must not declare depth
     * state, which is invalid against a pass that has none. It still draws — in submission order, like any other
     * 2D over 3D — so a target without depth degrades to the picture the ordering describes rather than to an
     * error.
     */
    private GraphicsPipeline.Config.Depth depthState(TechniqueContext ctx) {
        if (!ctx.hasDepth()) {
            return GraphicsPipeline.Config.Depth.NONE;
        }
        return writesDepth
                ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                : GraphicsPipeline.Config.Depth.TEST_ONLY;
    }

    @Override
    public void record(FrameContext frame) {
        if (vertexCount == 0) {
            return;
        }
        MemorySegment cmd = frame.commandBuffer();
        int width = frame.width();
        int height = frame.height();

        // The canvas is NOT resized to the frame, which is the one line where this differs most from drawing a
        // canvas on the screen: its extent is a measurement of a thing in the world, and a window getting wider
        // does not make a sign on a wall wider. What the frame does decide is the projection -- aspect, and the
        // pixel scale the anti-aliasing is computed against.
        double aspect = height == 0 ? 1.0 : (double) width / height;
        panel.viewBasis(canvas.width(), canvas.height(), camX, camY, camZ, yaw, pitch, pushFloats);
        pushFloats[PanelShader.PUSH_FOCAL] = (float) focalLength;
        pushFloats[PanelShader.PUSH_ASPECT] = (float) aspect;
        pushFloats[PanelShader.PUSH_HALF_HEIGHT] = height * 0.5f;

        cmds.bindPipeline(cmd, pipeline);
        MemorySegment.copy(pushFloats, 0, push, JAVA_FLOAT, 0, pushFloats.length);
        cmds.push(cmd, pipeline, Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT, push,
                PanelShader.PUSH_BYTES);
        cmds.bindDescriptorSet(cmd, pipeline, CanvasShader.ATLAS_SET, atlas.descriptorSet());
        cmds.bindVertexBuffer(cmd, vertices.handle());

        List<Canvas.Run> frameRuns = runs;
        if (frameRuns.isEmpty()) {
            cmds.draw(cmd, vertexCount);
            return;
        }
        // Set 1 is rebound only when a run changes it. A null image means "no image bound", and the placeholder
        // stands in so the descriptor is never unbound while a draw references it.
        long bound = 0;
        for (int i = 0; i < frameRuns.size(); i++) {
            Canvas.Run run = frameRuns.get(i);
            if (run.vertexCount() <= 0) {
                continue;
            }
            long set = run.image() instanceof SampledImage image ? image.descriptorSet() : atlas.descriptorSet();
            if (set != bound) {
                bound = set;
                cmds.bindDescriptorSet(cmd, pipeline, CanvasShader.IMAGE_SET, set);
            }
            cmds.draw(cmd, run.vertexCount(), run.firstVertex());
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
        if (ownsAtlas && atlas != null) {
            atlas.close();
            atlas = null;
        }
        if (cmds != null) {
            cmds.close();
            cmds = null;
        }
    }
}
