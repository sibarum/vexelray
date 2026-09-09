package dev.vexelray.technique.canvas;

import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.SampledImage;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A {@link Canvas} batch as a {@link RenderTechnique} — 2D shapes, text and sampled images recorded into a frame
 * the runtime began, rather than into a window this owns.
 *
 * <h2>What moves, and what does not</h2>
 *
 * <p>Everything about <em>how</em> a canvas draws is unchanged: one fat-vertex format, one uber-shader, one
 * pipeline, and a frame of N images costs one bind plus N rebinds of set 1 rather than N pipelines. What changes
 * is that {@code WindowedPresenter} no longer needs to know any of it. The presenter grew
 * {@code configureDraw}, {@code setVertexCount} and {@code setRuns} because it was the only thing that could
 * issue a draw; those exist for the demos that predate this class, and a canvas driven through the engine uses
 * none of them.
 *
 * <h2>Per-frame drawing is this technique's own API (D5)</h2>
 *
 * <p>An application calls {@link #draw} from the engine's frame callback with the drawing it wants; the
 * technique keeps the resulting vertices and issues them in {@link #record}. The two are deliberately separate
 * calls in separate phases — building a batch touches CPU memory and can happen while the GPU is busy, whereas
 * recording must happen inside the render pass. Folding them together would put arbitrary application code
 * inside a command buffer.
 *
 * <h2>Depth</h2>
 *
 * <p>{@link GraphicsPipeline.Config.Depth#NONE}, and this one is not a compromise. A canvas is chrome: it is
 * meant to sit in front of whatever the frame already holds, its coverage is analytic and alpha-blended, and a
 * depth test would make a translucent edge occlude itself against its own neighbours. 2D over 3D is exactly the
 * case where submission order, not depth, is the right answer — which is why {@code RenderPipeline} makes order
 * explicit.
 */
public final class CanvasTechnique implements RenderTechnique {

    private final Canvas canvas;

    private VulkanDevice device;
    private AtlasTexture atlas;
    private boolean ownsAtlas;
    private GraphicsPipeline pipeline;
    private VertexBuffer vertices;
    private int vertexCount;
    private List<Canvas.Run> runs = List.of();

    private MethodHandle bindPipeline;
    private MethodHandle bindVertexBuffers;
    private MethodHandle bindDescriptorSets;
    private MethodHandle draw;
    private MethodHandle setViewport;
    private MethodHandle setScissor;
    private Arena arena;
    private MemorySegment pVertexBuffers;
    private MemorySegment pVertexOffsets;
    private MemorySegment pSet;
    private MemorySegment viewport;
    private MemorySegment scissor;

    /**
     * How many floats the vertex buffer is allocated for.
     *
     * <p>Fixed at realise time rather than grown per frame, because a vertex buffer is a device allocation and
     * reallocating one mid-frame means either a stall or a second buffer alive while the first is in flight.
     * A canvas that overruns it is told so, loudly — see {@link #draw}.
     */
    private static final int DEFAULT_CAPACITY_FLOATS = 1 << 20;

    private final int capacityFloats;

    /** A technique drawing into its own canvas of the given size, with the placeholder atlas (no text). */
    public CanvasTechnique(int width, int height) {
        this(new Canvas(width, height), DEFAULT_CAPACITY_FLOATS);
    }

    /** A technique drawing into {@code canvas}, with room for {@code capacityFloats} of vertex data. */
    public CanvasTechnique(Canvas canvas, int capacityFloats) {
        this.canvas = canvas;
        this.capacityFloats = capacityFloats;
    }

    /**
     * Give this technique the glyph atlas its text is laid out against.
     *
     * <p>Optional, and when it is not called the placeholder atlas is used instead — which draws shapes
     * correctly and text as nothing, because the uber-shader samples set 0 for glyph coverage and a placeholder
     * has none. Call it before {@link #realize}; afterwards the pipeline has been built against a descriptor
     * set layout and swapping the atlas is a rebuild.
     */
    public CanvasTechnique atlas(AtlasTexture atlas) {
        if (pipeline != null) {
            throw new IllegalStateException("the pipeline was already built against an atlas layout; set the "
                    + "atlas before realise");
        }
        this.atlas = atlas;
        this.ownsAtlas = false;
        return this;
    }

    /** The canvas this technique draws, for a caller that would rather hold it than pass a lambda. */
    public Canvas canvas() {
        return canvas;
    }

    /**
     * Build this frame's batch: {@code begin()} the canvas, run {@code drawing}, and keep the vertices for the
     * next {@link #record}. Call from the engine's frame callback, not from inside a technique's record.
     *
     * @throws IllegalStateException if the batch does not fit the buffer allocated at realise time — reported
     *         rather than truncated, because a canvas silently missing its last few shapes is the class of
     *         failure that reads as a layout bug
     */
    public CanvasTechnique draw(Consumer<Canvas> drawing) {
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
        }
        return this;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanTechniqueContext vk = (VulkanTechniqueContext) ctx;
        this.device = vk.device();
        if (atlas == null) {
            this.atlas = AtlasTexture.placeholder(device);
            this.ownsAtlas = true;
        }

        List<GraphicsPipeline.VertexAttribute> attrs = new ArrayList<>();
        for (CanvasVertex.Attr a : CanvasVertex.ATTRIBUTES) {
            attrs.add(new GraphicsPipeline.VertexAttribute(a.location(), vkFormat(a.components()), a.offset()));
        }
        // Two set layouts, not one: set 0 is the glyph atlas and set 1 is whatever image a run binds. The
        // pipeline layout has to declare both even for a canvas that draws no images, or a frame that later
        // draws one cannot bind it without a different pipeline.
        long[] setLayouts = {atlas.descriptorSetLayout(), atlas.descriptorSetLayout()};

        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                CanvasVertex.STRIDE_BYTES, attrs, setLayouts, true, Vk.SHADER_STAGE_FRAGMENT_BIT, 0, true);

        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                CanvasShader.vertex().spirv(), "main", CanvasShader.fragment().spirv(), "main", config);
        this.vertices = new VertexBuffer(device, capacityFloats);

        this.bindPipeline = device.command("vkCmdBindPipeline",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
        this.bindVertexBuffers = device.command("vkCmdBindVertexBuffers",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        this.bindDescriptorSets = device.command("vkCmdBindDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT,
                        ADDRESS));
        this.draw = device.command("vkCmdDraw",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        FunctionDescriptor setVs = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);
        this.setViewport = device.command("vkCmdSetViewport", setVs);
        this.setScissor = device.command("vkCmdSetScissor", setVs);

        this.arena = Arena.ofShared();
        this.pVertexBuffers = arena.allocate(JAVA_LONG);
        this.pVertexOffsets = arena.allocate(JAVA_LONG);
        this.pSet = arena.allocate(JAVA_LONG);
        this.viewport = arena.allocate(JAVA_FLOAT, 6);
        this.scissor = arena.allocate(JAVA_INT, 4);
        pVertexBuffers.set(JAVA_LONG, 0, vertices.handle());
        pVertexOffsets.set(JAVA_LONG, 0, 0L);
    }

    @Override
    public void record(FrameContext frame) {
        if (vertexCount == 0) {
            // Nothing was drawn this frame. Returning early rather than issuing a zero-vertex draw keeps the
            // command buffer honest about what happened.
            return;
        }
        MemorySegment cmd = frame.commandBuffer();
        // The canvas is authored in pixels, so a resize changes the coordinate space it draws into. Resizing the
        // canvas here rather than in draw() means a frame recorded after a resize is already correct.
        if (canvas.width() != frame.width() || canvas.height() != frame.height()) {
            canvas.resize(frame.width(), frame.height());
        }

        invoke(bindPipeline, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());

        viewport.setAtIndex(JAVA_FLOAT, 2, frame.width());
        viewport.setAtIndex(JAVA_FLOAT, 3, frame.height());
        viewport.setAtIndex(JAVA_FLOAT, 5, 1.0f);
        invoke(setViewport, cmd, 0, 1, viewport);
        scissor.setAtIndex(JAVA_INT, 2, frame.width());
        scissor.setAtIndex(JAVA_INT, 3, frame.height());
        invoke(setScissor, cmd, 0, 1, scissor);

        bindSet(cmd, CanvasShader.ATLAS_SET, atlas.descriptorSet());
        invoke(bindVertexBuffers, cmd, 0, 1, pVertexBuffers, pVertexOffsets);

        List<Canvas.Run> frameRuns = runs;
        if (frameRuns.isEmpty()) {
            invoke(draw, cmd, vertexCount, 1, 0, 0);
            return;
        }
        // Set 1 is rebound only when a run changes it — the same economy the presenter's Run loop had, moved to
        // the layer that actually knows what a run is. A null image means "no image bound", and the placeholder
        // stands in so the descriptor is never unbound while a draw references it.
        long bound = 0;
        for (Canvas.Run run : frameRuns) {
            if (run.vertexCount() <= 0) {
                continue;
            }
            long set = run.image() instanceof SampledImage image ? image.descriptorSet() : atlas.descriptorSet();
            if (set != bound) {
                bound = set;
                bindSet(cmd, CanvasShader.IMAGE_SET, set);
            }
            invoke(draw, cmd, run.vertexCount(), 1, run.firstVertex(), 0);
        }
    }

    private void bindSet(MemorySegment cmd, int index, long set) {
        pSet.set(JAVA_LONG, 0, set);
        invoke(bindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(),
                index, 1, pSet, 0, MemorySegment.NULL);
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
        if (arena != null) {
            arena.close();
            arena = null;
        }
    }

    private static int vkFormat(int components) {
        return switch (components) {
            case 1 -> Vk.FORMAT_R32_SFLOAT;
            case 2 -> Vk.FORMAT_R32G32_SFLOAT;
            case 4 -> Vk.FORMAT_R32G32B32A32_SFLOAT;
            default -> throw new IllegalArgumentException("unsupported component count " + components);
        };
    }

    private static void invoke(MethodHandle handle, Object... args) {
        try {
            handle.invokeWithArguments(args);
        } catch (Throwable t) {
            throw new IllegalStateException("downcall failed", t);
        }
    }
}
