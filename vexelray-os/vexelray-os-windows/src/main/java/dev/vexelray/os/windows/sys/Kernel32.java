package dev.vexelray.os.windows.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Binding for {@code kernel32.dll}. One library, one class (see {@code docs/native-bindings.md} §4).
 */
public final class Kernel32 {

    private static final SymbolLookup LIB = Ffi.library("kernel32");

    private static final MethodHandle GetModuleHandleW = Ffi.downcall(LIB, "GetModuleHandleW",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static final MethodHandle GetLastError = Ffi.downcall(LIB, "GetLastError",
            FunctionDescriptor.of(JAVA_INT));

    private static final MethodHandle GetCurrentProcess = Ffi.downcall(LIB, "GetCurrentProcess",
            FunctionDescriptor.of(ADDRESS));

    private static final MethodHandle SetProcessInformation = Ffi.downcall(LIB, "SetProcessInformation",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    /** {@code PROCESS_INFORMATION_CLASS.ProcessPowerThrottling}. */
    public static final int PROCESS_POWER_THROTTLING = 4;

    /** {@code PROCESS_POWER_THROTTLING_CURRENT_VERSION}. */
    public static final int PROCESS_POWER_THROTTLING_CURRENT_VERSION = 1;

    /**
     * {@code PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION}: in the control mask, this process decides whether
     * Windows may ignore its timer resolution requests; clear in the state mask, it says <em>do not ignore them</em>.
     */
    public static final int PROCESS_POWER_THROTTLING_IGNORE_TIMER_RESOLUTION = 0x4;

    private Kernel32() {
    }

    /** The pseudo-handle for the current process, which needs no closing. */
    public static MemorySegment getCurrentProcess() {
        try {
            return (MemorySegment) GetCurrentProcess.invokeExact();
        } catch (Throwable t) {
            throw NativeException.rethrow("GetCurrentProcess", t);
        }
    }

    /**
     * {@code SetProcessInformation}: a process-wide setting, here for {@link #PROCESS_POWER_THROTTLING}.
     *
     * @return whether it succeeded; {@link #getLastError} says why not
     */
    public static boolean setProcessInformation(MemorySegment process, int informationClass,
                                                MemorySegment information, int size) {
        try {
            return (int) SetProcessInformation.invokeExact(process, informationClass, information, size) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("SetProcessInformation", t);
        }
    }

    /** The module handle (HINSTANCE) for {@code moduleName}, or for the current process when {@code NULL}. */
    public static MemorySegment getModuleHandleW(MemorySegment moduleName) {
        try {
            return (MemorySegment) GetModuleHandleW.invokeExact(moduleName);
        } catch (Throwable t) {
            throw NativeException.rethrow("GetModuleHandleW", t);
        }
    }

    /** The calling thread's last-error code ({@code GetLastError}). */
    public static int getLastError() {
        try {
            return (int) GetLastError.invokeExact();
        } catch (Throwable t) {
            throw NativeException.rethrow("GetLastError", t);
        }
    }
}
