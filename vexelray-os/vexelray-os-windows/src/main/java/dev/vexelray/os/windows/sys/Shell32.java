package dev.vexelray.os.windows.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;

/**
 * Binding for {@code shell32.dll}. One library, one class (see {@code docs/native-bindings.md} §4).
 */
public final class Shell32 {

    private static final SymbolLookup LIB = Ffi.library("shell32");

    private static final MethodHandle CommandLineToArgvW = Ffi.downcall(LIB, "CommandLineToArgvW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    private Shell32() {
    }

    /**
     * {@code CommandLineToArgvW}: split a UTF-16 command line by the C runtime's rules. Returns an array of
     * {@code argc} string pointers (written to {@code argc}), to be released with {@link Kernel32#localFree}, or
     * {@code NULL} on failure.
     */
    public static MemorySegment commandLineToArgvW(MemorySegment commandLine, MemorySegment argc) {
        try {
            return (MemorySegment) CommandLineToArgvW.invokeExact(commandLine, argc);
        } catch (Throwable t) {
            throw NativeException.rethrow("CommandLineToArgvW", t);
        }
    }
}
