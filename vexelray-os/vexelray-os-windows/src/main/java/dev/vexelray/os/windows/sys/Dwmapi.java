package dev.vexelray.os.windows.sys;

import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Binding for {@code dwmapi.dll}: the desktop compositor. One library, one class. See
 * {@code docs/native-bindings.md} §4.
 *
 * <p>VexelRay needs exactly one thing from it. A window that answers WM_NCCALCSIZE with its whole rect has no
 * non-client area left for the compositor to draw, and so loses the drop shadow, the rounded corners and the
 * snap/minimize animations that every other window on the desktop has. Extending the frame back into the client
 * area by a single pixel restores all of them: the compositor believes it still owns a sliver of frame, while
 * the application draws over every pixel the user can see.
 */
public final class Dwmapi {

    private static final SymbolLookup LIB = Ffi.library("dwmapi");

    /** {@code MARGINS} — left/right/top/bottom frame extents, 16 bytes. */
    public static final GroupLayout MARGINS = MemoryLayout.structLayout(
            JAVA_INT.withName("cxLeftWidth"),
            JAVA_INT.withName("cxRightWidth"),
            JAVA_INT.withName("cyTopHeight"),
            JAVA_INT.withName("cyBottomHeight")
    ).withName("MARGINS");

    private static final VarHandle M_left   = Ffi.field(MARGINS, "cxLeftWidth");
    private static final VarHandle M_right  = Ffi.field(MARGINS, "cxRightWidth");
    private static final VarHandle M_top    = Ffi.field(MARGINS, "cyTopHeight");
    private static final VarHandle M_bottom = Ffi.field(MARGINS, "cyBottomHeight");

    private static final MethodHandle DwmExtendFrameIntoClientArea =
            Ffi.downcall(LIB, "DwmExtendFrameIntoClientArea", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    private Dwmapi() {
    }

    /**
     * Extend the compositor-drawn frame into the client area by the given extents (pixels per edge). One pixel on
     * a single edge is enough to keep the shadow and the system animations; the application still paints that
     * pixel, because the client area itself is unchanged.
     */
    public static void extendFrameIntoClientArea(MemorySegment hwnd, int left, int right, int top, int bottom) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment margins = temp.allocate(MARGINS);
            M_left.set(margins, left);
            M_right.set(margins, right);
            M_top.set(margins, top);
            M_bottom.set(margins, bottom);
            int hr = (int) DwmExtendFrameIntoClientArea.invokeExact(hwnd, margins);
            if (hr != 0) {
                throw new NativeException("DwmExtendFrameIntoClientArea failed: HRESULT 0x"
                        + Integer.toHexString(hr));
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("DwmExtendFrameIntoClientArea", t);
        }
    }
}
