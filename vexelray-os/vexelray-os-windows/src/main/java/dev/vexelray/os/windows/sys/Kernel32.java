package dev.vexelray.os.windows.sys;

import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;

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

    private Kernel32() {
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
