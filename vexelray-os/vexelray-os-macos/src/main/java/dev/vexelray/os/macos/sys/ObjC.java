package dev.vexelray.os.macos.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Binding for {@code libobjc}: looking up a class and a selector, and sending a message. One library, one class (see
 * {@code docs/native-bindings.md} §4); the frameworks whose classes it reaches are loaded through {@link #framework}.
 *
 * <p>{@code objc_msgSend} is not variadic and has no single signature: it must be called through the exact function
 * type of the method it reaches, which on arm64 is not optional (the arguments go in different registers). So there
 * is one downcall handle per shape of message, named for what it returns.
 */
public final class ObjC {

    private static final SymbolLookup LIB = Ffi.libraryFile("/usr/lib/libobjc.A.dylib");

    private static final MethodHandle objc_getClass = Ffi.downcall(LIB, "objc_getClass",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle sel_registerName = Ffi.downcall(LIB, "sel_registerName",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    /** {@code id objc_msgSend(id, SEL)}: a message with no arguments that answers an object. */
    private static final MethodHandle msgSendObject = Ffi.downcall(LIB, "objc_msgSend",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    /** {@code BOOL objc_msgSend(id, SEL)}: a message with no arguments that answers a {@code BOOL}, one byte. */
    private static final MethodHandle msgSendBool = Ffi.downcall(LIB, "objc_msgSend",
            FunctionDescriptor.of(JAVA_BYTE, ADDRESS, ADDRESS));

    private ObjC() {
    }

    /**
     * Load the framework at {@code path} (its binary, e.g. {@code /System/Library/Frameworks/AppKit.framework/AppKit})
     * so its classes are registered with the runtime; {@link #getClass} finds nothing of a framework not loaded.
     */
    public static void framework(String path) {
        Ffi.libraryFile(path);
    }

    /** The class named {@code name}, or {@code NULL} when no loaded image defines it. */
    public static MemorySegment getClass(String name) {
        try (Arena temp = Arena.ofConfined()) {
            return (MemorySegment) objc_getClass.invokeExact(temp.allocateFrom(name));
        } catch (Throwable t) {
            throw NativeException.rethrow("objc_getClass", t);
        }
    }

    /** The selector named {@code name}. Selectors are interned by the runtime and live for the process. */
    public static MemorySegment selector(String name) {
        try (Arena temp = Arena.ofConfined()) {
            return (MemorySegment) sel_registerName.invokeExact(temp.allocateFrom(name));
        } catch (Throwable t) {
            throw NativeException.rethrow("sel_registerName", t);
        }
    }

    /** {@code [receiver selector]}, answering an object. */
    public static MemorySegment sendObject(MemorySegment receiver, MemorySegment selector) {
        try {
            return (MemorySegment) msgSendObject.invokeExact(receiver, selector);
        } catch (Throwable t) {
            throw NativeException.rethrow("objc_msgSend", t);
        }
    }

    /** {@code [receiver selector]}, answering a {@code BOOL}. */
    public static boolean sendBool(MemorySegment receiver, MemorySegment selector) {
        try {
            return (byte) msgSendBool.invokeExact(receiver, selector) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("objc_msgSend", t);
        }
    }
}
