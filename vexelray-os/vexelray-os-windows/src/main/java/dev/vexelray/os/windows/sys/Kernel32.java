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

    private static final MethodHandle GetCommandLineW = Ffi.downcall(LIB, "GetCommandLineW",
            FunctionDescriptor.of(ADDRESS));

    private static final MethodHandle LocalFree = Ffi.downcall(LIB, "LocalFree",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    private static final MethodHandle lstrlenW = Ffi.downcall(LIB, "lstrlenW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    private static final MethodHandle SetProcessInformation =Ffi.downcall(LIB, "SetProcessInformation",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));

    private static final MethodHandle GetStdHandle = Ffi.downcall(LIB, "GetStdHandle",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));

    private static final MethodHandle SetStdHandle = Ffi.downcall(LIB, "SetStdHandle",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));

    private static final MethodHandle CreateFileW = Ffi.downcall(LIB, "CreateFileW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));

    /** {@code STD_ERROR_HANDLE}. */
    public static final int STD_ERROR_HANDLE = -12;

    /** {@code FILE_APPEND_DATA}: every write goes to the end, whoever else writes the file. */
    public static final int FILE_APPEND_DATA = 0x4;

    /** {@code FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE}. */
    public static final int FILE_SHARE_ALL = 0x1 | 0x2 | 0x4;

    /** {@code OPEN_ALWAYS}: open the file, creating it if it is not there. */
    public static final int OPEN_ALWAYS = 4;

    /** {@code FILE_ATTRIBUTE_NORMAL}. */
    public static final int FILE_ATTRIBUTE_NORMAL = 0x80;

    /** {@code INVALID_HANDLE_VALUE}. */
    public static final MemorySegment INVALID_HANDLE_VALUE = MemorySegment.ofAddress(-1L);

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

    /** The process's command line as one UTF-16 string, owned by the process: not to be freed or written. */
    public static MemorySegment getCommandLineW() {
        try {
            return (MemorySegment) GetCommandLineW.invokeExact();
        } catch (Throwable t) {
            throw NativeException.rethrow("GetCommandLineW", t);
        }
    }

    /** {@code LocalFree}: release memory a call handed over to be freed this way. */
    public static void localFree(MemorySegment memory) {
        try {
            MemorySegment ignored = (MemorySegment) LocalFree.invokeExact(memory);
        } catch (Throwable t) {
            throw NativeException.rethrow("LocalFree", t);
        }
    }

    /** {@code lstrlenW}: the length in UTF-16 units of the null-terminated string at {@code string}. */
    public static int lstrlenW(MemorySegment string) {
        try {
            return (int) lstrlenW.invokeExact(string);
        } catch (Throwable t) {
            throw NativeException.rethrow("lstrlenW", t);
        }
    }

    /** {@code GetStdHandle}: NULL when the process has no such handle, {@link #INVALID_HANDLE_VALUE} on failure. */
    public static MemorySegment getStdHandle(int which) {
        try {
            return (MemorySegment) GetStdHandle.invokeExact(which);
        } catch (Throwable t) {
            throw NativeException.rethrow("GetStdHandle", t);
        }
    }

    /** {@code SetStdHandle}: whether it succeeded. */
    public static boolean setStdHandle(int which, MemorySegment handle) {
        try {
            return (int) SetStdHandle.invokeExact(which, handle) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("SetStdHandle", t);
        }
    }

    /** {@code CreateFileW}, with no security attributes or template: {@link #INVALID_HANDLE_VALUE} on failure. */
    public static MemorySegment createFileW(MemorySegment fileName, int access, int share, int disposition,
                                            int flags) {
        try {
            return (MemorySegment) CreateFileW.invokeExact(fileName, access, share, MemorySegment.NULL, disposition,
                    flags, MemorySegment.NULL);
        } catch (Throwable t) {
            throw NativeException.rethrow("CreateFileW", t);
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
