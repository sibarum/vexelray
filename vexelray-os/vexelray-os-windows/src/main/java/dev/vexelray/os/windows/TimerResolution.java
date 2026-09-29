package dev.vexelray.os.windows;

import dev.vexelray.os.ffi.NativeException;
import dev.vexelray.os.windows.sys.Kernel32;
import dev.vexelray.os.windows.sys.Winmm;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Keeps this process's timer at 1 ms for as long as a window exists, so that a timed wait returns when it was
 * told to and not a frame or two later.
 *
 * <h2>Why a window needs it</h2>
 *
 * <p>The render loop parks on {@code MsgWaitForMultipleObjectsEx} with a timeout, and how late that timeout can
 * return is the system timer's resolution: 15.6 ms at its coarsest. Measured in a generated application, a 16 ms
 * wait that ran to its end returned after <b>28 ms</b>, a stall of two or three frames at 144 Hz that came at the
 * start of every animation. Asking for a finer timer with {@code timeBeginPeriod(1)} was not enough, and that is
 * the part that is easy to miss.
 *
 * <h2>The two calls</h2>
 *
 * <ol>
 *   <li><b>Say the request is to be honoured.</b> Windows 11 ignores a timer resolution request from a process
 *       that has no visible, foreground window, as a power saving, unless the process opts out of that with
 *       {@code SetProcessInformation(ProcessPowerThrottling, IGNORE_TIMER_RESOLUTION)}. A window that is
 *       occluded, minimised or behind another counts, and so does one an automated test launched. Without this
 *       the next call is accepted and does nothing.</li>
 *   <li><b>Ask for 1 ms</b> with {@code timeBeginPeriod(1)}, released with {@code timeEndPeriod(1)} when the last
 *       window goes.</li>
 * </ol>
 *
 * <p>With both, the stall dropped from about 29 ms to about 18 ms, the remainder being the frame ceiling and not
 * the timer. <b>The cost is real and bounded</b>: since Windows 10 (2004) a resolution request is per-process, so
 * this raises this process's timer wakeups and no one else's, and only while it has a window.
 *
 * <p>Reference-counted, because the request is per process and windows are not: the timer stays fine until the
 * last window closes. Never fatal. A machine that refuses either call costs the application smoothness and not
 * function, so a refusal is kept for {@link #problem()} and the caller decides whether it is worth saying.
 */
final class TimerResolution {

    /** The resolution asked for, in milliseconds. One is the finest {@code timeBeginPeriod} offers. */
    static final int MILLIS = 1;

    private static int holders;
    private static boolean begun;
    private static String problem;

    private TimerResolution() {
    }

    /** A window now exists. The first one turns the fine timer on. */
    static synchronized void acquire() {
        if (holders++ > 0) {
            return;
        }
        problem = null;
        optOutOfThrottling();
        try {
            int result = Winmm.timeBeginPeriod(MILLIS);
            begun = result == Winmm.TIMERR_NOERROR;
            if (!begun) {
                note("timeBeginPeriod(" + MILLIS + ") answered " + result);
            }
        } catch (NativeException e) {
            note("timeBeginPeriod(" + MILLIS + ") failed: " + e.getMessage());
        }
    }

    /** A window is gone. The last one gives the timer back. */
    static synchronized void release() {
        if (holders == 0 || --holders > 0) {
            return;
        }
        if (begun) {
            begun = false;
            try {
                Winmm.timeEndPeriod(MILLIS);
            } catch (NativeException e) {
                note("timeEndPeriod(" + MILLIS + ") failed: " + e.getMessage());
            }
        }
    }

    /** How many windows are holding the fine timer. */
    static synchronized int holders() {
        return holders;
    }

    /** Whether the fine timer is currently on, which is true exactly when a window holds it and it was granted. */
    static synchronized boolean active() {
        return begun;
    }

    /** Why the timer could not be made fine, or empty if it was, or nothing has asked yet. */
    static synchronized Optional<String> problem() {
        return Optional.ofNullable(problem);
    }

    private static void optOutOfThrottling() {
        try (Arena temp = Arena.ofConfined()) {
            // PROCESS_POWER_THROTTLING_STATE { ULONG Version; ULONG ControlMask; ULONG StateMask; }
            MemorySegment state = temp.allocate(12, 4);
            state.set(JAVA_INT, 0, Kernel32.PROCESS_POWER_THROTTLING_CURRENT_VERSION);
            state.set(JAVA_INT, 4, Kernel32.PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION);
            state.set(JAVA_INT, 8, 0);
            if (!Kernel32.setProcessInformation(Kernel32.getCurrentProcess(),
                    Kernel32.PROCESS_POWER_THROTTLING, state, 12)) {
                // Windows before 10 (1709) has no such class. There is no throttling to opt out of there either.
                note("SetProcessInformation(ProcessPowerThrottling) failed, GetLastError="
                        + Kernel32.getLastError());
            }
        } catch (NativeException e) {
            note("SetProcessInformation(ProcessPowerThrottling) failed: " + e.getMessage());
        }
    }

    private static void note(String what) {
        problem = problem == null ? what : problem + "; " + what;
    }
}
