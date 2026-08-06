package dev.vexelray.os.ffi;

/**
 * Thrown when a native call fails — a missing symbol, a null/failure return code, or a {@code Throwable} escaping
 * an {@code invokeExact}. Every binding wrapper funnels failures here so native errors are never swallowed and
 * always carry the offending call's name. See {@code docs/native-bindings.md} §4.
 */
public final class NativeException extends RuntimeException {

    public NativeException(String message) {
        super(message);
    }

    public NativeException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Wrap a {@code Throwable} thrown out of an {@code invokeExact} for {@code call}. An existing
     * {@link NativeException} passes through unchanged so the original message survives.
     */
    public static NativeException rethrow(String call, Throwable cause) {
        if (cause instanceof NativeException ne) {
            return ne;
        }
        return new NativeException("native call failed: " + call, cause);
    }
}
