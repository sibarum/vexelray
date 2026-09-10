package dev.vexelray.vulkan.present;

import dev.vexelray.vulkan.vk.Ffm;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The handful of Vulkan commands anything recording into somebody else's command buffer needs: bind a pipeline,
 * bind a vertex buffer, bind a descriptor set, push constants, draw. Resolved once, with the scratch native
 * memory those calls need allocated alongside them.
 *
 * <h2>Why this exists</h2>
 *
 * <p>The same forty lines — five or seven {@code device.command(...)} lookups with their
 * {@link FunctionDescriptor}s, one shared {@link Arena}, a {@code VkViewport} and a {@code VkRect2D} — were
 * written independently four times, in {@code SdfRaymarchTechnique}, {@code CanvasTechnique},
 * {@code FathomTechnique} and the engine's own test technique. Each of the four also grew a private
 * {@code invoke(MethodHandle, Object...)} while {@link Ffm#invokeVoid} had been public in {@code vexelray-vulkan}
 * the whole time. Four independent reinventions of an existing public helper is not four mistakes; it is one
 * missing class, and this is it.
 *
 * <p>It deliberately names no feature and no {@code RenderTechnique}: it is substrate, sitting beside
 * {@link GraphicsPipeline}, and a caller that is not a technique (an offscreen draw, a tool) uses it the same
 * way. That keeps {@code vexelray-vulkan} free of the engine's vocabulary, which is the property that lets the
 * substrate be tested without one.
 *
 * <h2>Threading</h2>
 *
 * <p><b>Not thread-safe, by construction.</b> The scratch segments below are reused across calls, so two threads
 * recording through one instance would interleave their arguments — a corrupted push constant rather than an
 * exception. One instance belongs to one recorder on one thread, which is exactly the contract
 * {@code RenderTechnique} states: {@code realize}, {@code record} and {@code close} are all the engine's render
 * thread. Create one per technique in {@code realize} and {@link #close} it in the technique's {@code close}.
 *
 * <h2>Boxing</h2>
 *
 * <p>These go through {@link MethodHandle#invokeWithArguments}, which boxes. That is a handful of short-lived
 * allocations per draw against a call that crosses into a driver, and it is what the whole codebase already
 * does; if a profile ever shows it, the fix is {@code invokeExact} with per-signature wrappers behind these same
 * method names, and no caller changes.
 */
public final class DrawCommands implements AutoCloseable {

    private static final FunctionDescriptor BIND_PIPELINE =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG);
    private static final FunctionDescriptor PUSH_CONSTANTS =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS);
    private static final FunctionDescriptor DRAW =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);
    private static final FunctionDescriptor BIND_VERTEX_BUFFERS =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS);
    private static final FunctionDescriptor BIND_DESCRIPTOR_SETS =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT,
                    ADDRESS);
    private static final FunctionDescriptor SET_VIEWPORT_SCISSOR =
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS);

    private final MethodHandle bindPipeline;
    private final MethodHandle pushConstants;
    private final MethodHandle draw;
    private final MethodHandle bindVertexBuffers;
    private final MethodHandle bindDescriptorSets;
    private final MethodHandle setViewport;
    private final MethodHandle setScissor;

    private final Arena arena;
    private final MemorySegment pBuffers;
    private final MemorySegment pOffsets;
    private final MemorySegment pSet;
    private final MemorySegment pViewport;
    private final MemorySegment pScissor;

    private boolean closed;

    /**
     * Resolve every command against {@code device} and allocate the scratch memory they need.
     *
     * <p>All seven are core Vulkan 1.0 with no extension or feature behind them, so resolving them eagerly
     * costs seven {@code vkGetDeviceProcAddr} calls at realise time and cannot fail on a device that already
     * has a graphics queue. Lazy resolution would trade that for a null check on every draw.
     */
    public DrawCommands(VulkanDevice device) {
        this.bindPipeline = device.command("vkCmdBindPipeline", BIND_PIPELINE);
        this.pushConstants = device.command("vkCmdPushConstants", PUSH_CONSTANTS);
        this.draw = device.command("vkCmdDraw", DRAW);
        this.bindVertexBuffers = device.command("vkCmdBindVertexBuffers", BIND_VERTEX_BUFFERS);
        this.bindDescriptorSets = device.command("vkCmdBindDescriptorSets", BIND_DESCRIPTOR_SETS);
        this.setViewport = device.command("vkCmdSetViewport", SET_VIEWPORT_SCISSOR);
        this.setScissor = device.command("vkCmdSetScissor", SET_VIEWPORT_SCISSOR);

        this.arena = Arena.ofShared();
        this.pBuffers = arena.allocate(JAVA_LONG);
        this.pOffsets = arena.allocate(JAVA_LONG);
        this.pSet = arena.allocate(JAVA_LONG);
        this.pViewport = arena.allocate(JAVA_FLOAT, 6);
        this.pScissor = arena.allocate(JAVA_INT, 4);
        pOffsets.set(JAVA_LONG, 0, 0L);
    }

    /**
     * Allocate a reusable native block of {@code bytes} for push-constant data, freed with this object.
     *
     * <p>Here rather than in every caller because the alternative each of the four wrote was a second
     * {@code Arena.ofShared()} per technique, with its own lifetime to get wrong. Write the frame's values into
     * the returned segment and hand the same segment to {@link #push}; it is not cleared between frames, so a
     * layout whose members are all written every frame needs no zeroing and one that is partly written must do
     * its own.
     */
    public MemorySegment allocatePushConstants(int bytes) {
        if (bytes <= 0) {
            throw new IllegalArgumentException("push constant block must be at least one byte, got " + bytes);
        }
        return arena.allocate(bytes);
    }

    /** Scratch native memory with this object's lifetime, for a caller that needs a block of its own. */
    public MemorySegment allocate(long bytes) {
        return arena.allocate(bytes);
    }

    /** Bind {@code pipeline} as the graphics pipeline for the draws that follow. */
    public void bindPipeline(MemorySegment cmd, GraphicsPipeline pipeline) {
        Ffm.invokeVoid(bindPipeline, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
    }

    /**
     * Set the dynamic viewport and scissor to the whole of a {@code width} x {@code height} frame.
     *
     * <p>The engine already does this once before the first technique records, so a technique drawing to the
     * whole frame does <em>not</em> need to call it. It is public for the caller that draws to a sub-rectangle
     * and then has to put the state back, and for a recorder driving {@code WindowedPresenter} directly.
     */
    public void viewportAndScissor(MemorySegment cmd, int width, int height) {
        // x, y, width, height, minDepth, maxDepth — x/y/minDepth stay 0.
        pViewport.setAtIndex(JAVA_FLOAT, 2, width);
        pViewport.setAtIndex(JAVA_FLOAT, 3, height);
        pViewport.setAtIndex(JAVA_FLOAT, 5, 1.0f);
        Ffm.invokeVoid(setViewport, cmd, 0, 1, pViewport);
        // offset.x, offset.y, extent.width, extent.height — offsets stay 0.
        pScissor.setAtIndex(JAVA_INT, 2, width);
        pScissor.setAtIndex(JAVA_INT, 3, height);
        Ffm.invokeVoid(setScissor, cmd, 0, 1, pScissor);
    }

    /** Push {@code bytes} of {@code data} to {@code stages}, from offset zero of {@code pipeline}'s layout. */
    public void push(MemorySegment cmd, GraphicsPipeline pipeline, int stages, MemorySegment data, int bytes) {
        Ffm.invokeVoid(pushConstants, cmd, pipeline.pipelineLayout(), stages, 0, bytes, data);
    }

    /** Push a fragment-stage block — the layout every technique in this repository uses so far. */
    public void pushFragment(MemorySegment cmd, GraphicsPipeline pipeline, MemorySegment data, int bytes) {
        push(cmd, pipeline, Vk.SHADER_STAGE_FRAGMENT_BIT, data, bytes);
    }

    /** Bind one vertex buffer at binding zero, offset zero. */
    public void bindVertexBuffer(MemorySegment cmd, long buffer) {
        pBuffers.set(JAVA_LONG, 0, buffer);
        Ffm.invokeVoid(bindVertexBuffers, cmd, 0, 1, pBuffers, pOffsets);
    }

    /** Bind one descriptor set at {@code setIndex} of {@code pipeline}'s layout, with no dynamic offsets. */
    public void bindDescriptorSet(MemorySegment cmd, GraphicsPipeline pipeline, int setIndex, long set) {
        pSet.set(JAVA_LONG, 0, set);
        Ffm.invokeVoid(bindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(),
                setIndex, 1, pSet, 0, MemorySegment.NULL);
    }

    /** One instance of {@code vertexCount} vertices from vertex zero — the fullscreen-triangle case. */
    public void draw(MemorySegment cmd, int vertexCount) {
        draw(cmd, vertexCount, 0);
    }

    /** One instance of {@code vertexCount} vertices starting at {@code firstVertex} — one canvas run. */
    public void draw(MemorySegment cmd, int vertexCount, int firstVertex) {
        Ffm.invokeVoid(draw, cmd, vertexCount, 1, firstVertex, 0);
    }

    /**
     * Free the scratch memory. Idempotent, so a technique's {@code close} may be called twice without the
     * {@link IllegalStateException} a second {@link Arena#close()} would raise.
     */
    @Override
    public void close() {
        if (!closed) {
            closed = true;
            arena.close();
        }
    }
}
