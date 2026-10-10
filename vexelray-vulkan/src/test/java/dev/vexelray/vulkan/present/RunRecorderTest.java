package dev.vexelray.vulkan.present;

import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The rules every path draws runs by, checked against the commands they record. No device: the two Vulkan commands are
 * stand-ins that write down what they were called with.
 */
class RunRecorderTest {

    private static final long LAYOUT = 7;

    /** Writes down each bind as {@code set N = handle} and each draw as {@code draw first+count}. */
    static final class Commands {
        final List<String> log = new ArrayList<>();

        void bind(MemorySegment cmd, int bindPoint, long layout, int firstSet, int count, MemorySegment sets,
                  int dynamicCount, MemorySegment dynamic) {
            assertEquals(LAYOUT, layout);
            assertEquals(1, count);
            log.add("set " + firstSet + " = " + sets.get(JAVA_LONG, 0));
        }

        void draw(MemorySegment cmd, int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
            log.add("draw " + firstVertex + "+" + vertexCount);
        }
    }

    private static List<String> record(long defaultSet0, List<WindowedPresenter.Run> runs, int vertexCount)
            throws ReflectiveOperationException {
        Commands commands = new Commands();
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        MethodHandle bind = lookup.findVirtual(Commands.class, "bind", MethodType.methodType(void.class,
                MemorySegment.class, int.class, long.class, int.class, int.class, MemorySegment.class, int.class,
                MemorySegment.class)).bindTo(commands);
        MethodHandle draw = lookup.findVirtual(Commands.class, "draw", MethodType.methodType(void.class,
                MemorySegment.class, int.class, int.class, int.class, int.class)).bindTo(commands);
        try (Arena arena = Arena.ofConfined()) {
            new RunRecorder(bind, draw, arena).record(MemorySegment.NULL, LAYOUT, defaultSet0, runs, vertexCount);
        }
        return commands.log;
    }

    private static WindowedPresenter.Run run(long set0, long set1, int first, int count) {
        return new WindowedPresenter.Run(set0, set1, first, count);
    }

    /** The defect this class exists for: two faces, so two atlases, and each run must draw from its own. */
    @Test
    void eachFaceDrawsFromItsOwnAtlas() throws ReflectiveOperationException {
        List<String> log = record(100, List.of(
                run(100, 9, 0, 6),      // sans, face 0's atlas: already bound
                run(200, 9, 6, 12),     // mono: its own atlas
                run(100, 9, 18, 6)), 0);
        assertEquals(List.of(
                "set 0 = 100",
                "set 1 = 9", "draw 0+6",
                "set 0 = 200", "draw 6+12",
                "set 0 = 100", "draw 18+6"), log);
    }

    @Test
    void withNoRunsTheBufferIsOneDraw() throws ReflectiveOperationException {
        assertEquals(List.of("set 0 = 100", "draw 0+30"), record(100, List.of(), 30));
        assertEquals(List.of(), record(0, List.of(), 0));
    }

    @Test
    void aSetIsBoundOnlyWhenItChanges() throws ReflectiveOperationException {
        List<String> log = record(0, List.of(run(100, 9, 0, 3), run(100, 9, 3, 3), run(100, 8, 6, 3)), 0);
        assertEquals(List.of("set 0 = 100", "set 1 = 9", "draw 0+3", "draw 3+3", "set 1 = 8", "draw 6+3"), log);
    }

    /** A run naming no set leaves the one bound: binding a null set is never what it means. */
    @Test
    void aZeroSetLeavesTheBoundOneInPlace() throws ReflectiveOperationException {
        List<String> log = record(100, List.of(run(0, 9, 0, 3), run(0, 0, 3, 3)), 0);
        assertEquals(List.of("set 0 = 100", "set 1 = 9", "draw 0+3", "draw 3+3"), log);
    }

    @Test
    void aRunWithNoVerticesRecordsNothing() throws ReflectiveOperationException {
        List<String> log = record(100, List.of(run(200, 9, 0, 0), run(100, 9, 0, 3)), 0);
        assertEquals(List.of("set 0 = 100", "set 1 = 9", "draw 0+3"), log);
    }
}
