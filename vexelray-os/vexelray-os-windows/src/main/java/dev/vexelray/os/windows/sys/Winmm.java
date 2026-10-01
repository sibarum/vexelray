package dev.vexelray.os.windows.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Binding for {@code winmm.dll}: the multimedia timer resolution, and nothing else. One library, one class (see
 * {@code docs/native-bindings.md} §4).
 */
public final class Winmm {

    /** {@code TIMERR_NOERROR}: what {@code timeBeginPeriod} and {@code timeEndPeriod} answer on success. */
    public static final int TIMERR_NOERROR = 0;

    private static final SymbolLookup LIB = Ffi.library("winmm");

    private static final MethodHandle TimeBeginPeriod = Ffi.downcall(LIB, "timeBeginPeriod",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    private static final MethodHandle TimeEndPeriod = Ffi.downcall(LIB, "timeEndPeriod",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    private Winmm() {
    }

    /**
     * Ask for a system timer resolution of {@code millis}. Every call has to be matched by a
     * {@link #timeEndPeriod} with the same value.
     *
     * @return {@link #TIMERR_NOERROR}, or the error code
     */
    public static int timeBeginPeriod(int millis) {
        try {
            return (int) TimeBeginPeriod.invokeExact(millis);
        } catch (Throwable t) {
            throw NativeException.rethrow("timeBeginPeriod", t);
        }
    }

    /** Release a resolution asked for with {@link #timeBeginPeriod}. */
    public static int timeEndPeriod(int millis) {
        try {
            return (int) TimeEndPeriod.invokeExact(millis);
        } catch (Throwable t) {
            throw NativeException.rethrow("timeEndPeriod", t);
        }
    }
}
