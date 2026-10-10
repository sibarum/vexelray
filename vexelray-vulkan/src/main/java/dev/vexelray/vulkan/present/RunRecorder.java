package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Records a frame's {@link WindowedPresenter.Run}s into a command buffer: the descriptor sets each run names, and its
 * draw. The one place that turns runs into commands, for every path that draws them: {@link WindowedPresenter},
 * {@link OffscreenDraw}, and a {@link PresenterProvider}'s presenter.
 *
 * <p><b>Why there is one.</b> There used to be three copies of this loop. When runs gained a set 0 (one font atlas per
 * face), two of them learned to bind it and the DXGI presenter's did not, so on Windows every face drew out of face 0's
 * atlas: glyphs laid out right and sampled from the wrong cells. A screenshot went through a copy that had the fix, so it
 * showed none of it. A rule written once cannot be fixed in some of its copies.
 *
 * <p>The rules:
 * <ul>
 *   <li>{@code defaultSet0}, when not 0, is bound at set 0 before anything is drawn.</li>
 *   <li>With no runs, the whole buffer is one draw of {@code vertexCount} vertices, if there are any.</li>
 *   <li>Each run with vertices binds its set 0 and set 1 where they differ from what is bound, and draws. A run whose
 *       set is 0 leaves that set as it is: binding a null set is never what a run means.</li>
 * </ul>
 * Runs are contiguous and in submission order, so drawing them back to back is the picture one draw would have made;
 * the split is a binding concern, never a layering one.
 *
 * <p>Not thread-safe: the two set slots are scratch for one recording at a time, which is how every caller records.
 * {@code vkCmdBindDescriptorSets} reads them when it is recorded, so reusing them between calls is safe.
 */
public final class RunRecorder {

    /** {@code vkCmdBindDescriptorSets}' shape. */
    public static final FunctionDescriptor BIND_DESCRIPTOR_SETS = FunctionDescriptor.ofVoid(
            ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS);
    /** {@code vkCmdDraw}'s shape. */
    public static final FunctionDescriptor DRAW = FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT);

    private final MethodHandle bindDescriptorSets;
    private final MethodHandle draw;
    private final MemorySegment set0;
    private final MemorySegment set1;

    /** A recorder for {@code device}'s command buffers, with its scratch allocated in {@code arena}. */
    public RunRecorder(VulkanDevice device, Arena arena) {
        this(device.command("vkCmdBindDescriptorSets", BIND_DESCRIPTOR_SETS), device.command("vkCmdDraw", DRAW), arena);
    }

    /** A recorder over these two commands, of the shapes {@link #BIND_DESCRIPTOR_SETS} and {@link #DRAW}. */
    RunRecorder(MethodHandle bindDescriptorSets, MethodHandle draw, Arena arena) {
        this.bindDescriptorSets = bindDescriptorSets;
        this.draw = draw;
        this.set0 = arena.allocate(JAVA_LONG);
        this.set1 = arena.allocate(JAVA_LONG);
    }

    /**
     * Record {@code runs}, or one draw of {@code vertexCount} vertices when there are none, into {@code cmd}, inside a
     * render pass with the pipeline whose layout is {@code pipelineLayout} bound and the vertex buffer already bound.
     */
    public void record(MemorySegment cmd, long pipelineLayout, long defaultSet0, List<WindowedPresenter.Run> runs,
                       int vertexCount) {
        long bound0 = 0;
        if (defaultSet0 != 0) {
            bind(cmd, pipelineLayout, 0, set0, defaultSet0);
            bound0 = defaultSet0;
        }
        if (runs.isEmpty()) {
            if (vertexCount > 0) {
                invokeVoid(draw, cmd, vertexCount, 1, 0, 0);
            }
            return;
        }
        long bound1 = 0;
        for (WindowedPresenter.Run r : runs) {
            if (r.vertexCount() <= 0) {
                continue;
            }
            if (r.descriptorSet0() != 0 && r.descriptorSet0() != bound0) {
                bound0 = r.descriptorSet0();
                bind(cmd, pipelineLayout, 0, set0, bound0);
            }
            if (r.descriptorSet1() != 0 && r.descriptorSet1() != bound1) {
                bound1 = r.descriptorSet1();
                bind(cmd, pipelineLayout, 1, set1, bound1);
            }
            invokeVoid(draw, cmd, r.vertexCount(), 1, r.firstVertex(), 0);
        }
    }

    private void bind(MemorySegment cmd, long pipelineLayout, int set, MemorySegment slot, long descriptorSet) {
        slot.set(JAVA_LONG, 0, descriptorSet);
        invokeVoid(bindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, set, 1, slot, 0,
                MemorySegment.NULL);
    }
}
