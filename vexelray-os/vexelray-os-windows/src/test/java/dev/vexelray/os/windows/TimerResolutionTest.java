package dev.vexelray.os.windows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The timer a window asks Windows for: that it is finer than the default while a window holds it, and that the
 * count is right when there is more than one.
 *
 * <p>The resolution is read back with {@code NtQueryTimerResolution}, which says what the system is doing and not
 * what this class believes it asked for. That is the only assertion here that could catch the failure the class
 * exists for, a request accepted and ignored. What it cannot show is the opt-out's own effect, which needs a
 * process whose window is not in front and is measured by the framework's pacing test instead.
 */
class TimerResolutionTest {

    /** 1 ms in the 100 ns units {@code NtQueryTimerResolution} speaks. The coarse default is 156 250. */
    private static final int ONE_MILLISECOND = 10_000;

    @BeforeEach
    void windowsOnlyAndNobodyHoldingIt() {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"));
        while (TimerResolution.holders() > 0) {
            TimerResolution.release();
        }
    }

    @AfterEach
    void giveItBack() {
        while (TimerResolution.holders() > 0) {
            TimerResolution.release();
        }
    }

    @Test
    @DisplayName("while a window holds it, the system timer is 1 ms or finer")
    void theTimerIsFine() throws Throwable {
        TimerResolution.acquire();

        assertTrue(TimerResolution.active(), "the fine timer was not granted: " + TimerResolution.problem());
        assertTrue(currentResolution() <= ONE_MILLISECOND,
                "the system timer is " + currentResolution() / 10_000.0 + " ms after asking for 1");
    }

    @Test
    @DisplayName("two windows hold it, and it stays until the last one goes")
    void theCountIsPerWindow() {
        TimerResolution.acquire();
        TimerResolution.acquire();
        assertEquals(2, TimerResolution.holders());

        TimerResolution.release();
        assertTrue(TimerResolution.active(), "the first window closing gave the timer back under the second");

        TimerResolution.release();
        assertFalse(TimerResolution.active());
        assertEquals(0, TimerResolution.holders());
    }

    @Test
    @DisplayName("a release with nobody holding it is ignored, not a negative count")
    void anUnbalancedReleaseChangesNothing() {
        TimerResolution.release();
        assertEquals(0, TimerResolution.holders());

        TimerResolution.acquire();
        assertEquals(1, TimerResolution.holders());
        assertTrue(TimerResolution.active());
    }

    @Test
    @DisplayName("a grant carries no problem")
    void noProblemWhenGranted() {
        TimerResolution.acquire();
        Assumptions.assumeTrue(TimerResolution.active());
        // The opt-out may legitimately be refused on a Windows older than 10 (1709), and then says so.
        assertTrue(TimerResolution.problem().map(p -> p.contains("SetProcessInformation")).orElse(true),
                "the only acceptable problem is the opt-out being unavailable: " + TimerResolution.problem());
    }

    /** The system's current timer resolution in 100 ns units, from ntdll. */
    private static int currentResolution() throws Throwable {
        Linker linker = Linker.nativeLinker();
        SymbolLookup ntdll = SymbolLookup.libraryLookup("ntdll", Arena.global());
        MethodHandle query = linker.downcallHandle(ntdll.find("NtQueryTimerResolution").orElseThrow(),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment minimum = temp.allocate(JAVA_INT);
            MemorySegment maximum = temp.allocate(JAVA_INT);
            MemorySegment current = temp.allocate(JAVA_INT);
            int status = (int) query.invokeExact(minimum, maximum, current);
            assertEquals(0, status, "NtQueryTimerResolution failed");
            return current.get(JAVA_INT, 0);
        }
    }
}
