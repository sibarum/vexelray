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
 * Minimal binding for {@code gdi32.dll}. Currently just {@code CreateSolidBrush}, used to give the window class a
 * background so the client area is erased to a solid colour the instant the window is shown — before Vulkan has
 * presented a frame. One library, one class; see {@code docs/native-bindings.md}.
 */
public final class Gdi32 {

    private static final SymbolLookup LIB = Ffi.library("gdi32");

    private static final MethodHandle CreateSolidBrush = Ffi.downcall(LIB, "CreateSolidBrush",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));

    private Gdi32() {
    }

    /**
     * Create a solid-colour {@code HBRUSH}. The colour is a Win32 {@code COLORREF} (0x00BBGGRR). The brush is a GDI
     * object; when used as a window-class background it lives for the process (the class is never unregistered), so
     * the OS reclaims it at exit.
     *
     * @param colorRef 0x00BBGGRR (blue high byte, then green, then red)
     * @return the {@code HBRUSH} handle
     */
    public static MemorySegment createSolidBrush(int colorRef) {
        try {
            MemorySegment brush = (MemorySegment) CreateSolidBrush.invokeExact(colorRef);
            if (brush.equals(MemorySegment.NULL)) {
                throw new NativeException("CreateSolidBrush failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
            return brush;
        } catch (Throwable t) {
            throw NativeException.rethrow("CreateSolidBrush", t);
        }
    }

    /** Convert 8-bit R/G/B to a Win32 {@code COLORREF} (0x00BBGGRR). */
    public static int rgb(int r, int g, int b) {
        return (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
    }
}
