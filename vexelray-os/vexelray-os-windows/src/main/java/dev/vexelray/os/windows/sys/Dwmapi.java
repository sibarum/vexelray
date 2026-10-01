package dev.vexelray.os.windows.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

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

    private static final MethodHandle DwmFlush =
            Ffi.downcall(LIB, "DwmFlush", FunctionDescriptor.of(JAVA_INT));

    /** Block until the compositor's next pass, which is the next vblank this display takes a frame at. */
    public static void flush() {
        try {
            int hr = (int) DwmFlush.invokeExact();
        } catch (Throwable t) {
            throw NativeException.rethrow("DwmFlush", t);
        }
    }

    private static final MethodHandle DwmGetCompositionTimingInfo =
            Ffi.downcall(LIB, "DwmGetCompositionTimingInfo", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * {@code DWM_TIMING_INFO}'s size, found rather than written down: the call refuses any other {@code cbSize}
     * with {@code E_INVALIDARG}, and the SDK's struct has grown a field at a time, so the first size it accepts
     * is the one this machine's compositor means. Zero until one has been.
     */
    private static int timingInfoSize;

    /**
     * Byte offsets into {@code DWM_TIMING_INFO} of the values that say what reached the glass. The struct is
     * <b>packed on four bytes</b>, not eight: its 64-bit fields start at 4-byte offsets, and the size the
     * compositor accepts on this Windows is 292. Read from a dump of the whole struct, where the refresh rate
     * came out as exactly 10 000 000 / 69 448 = 144 Hz, which is what fixed the layout.
     */
    public static final int T_PERIOD_QPC = 12, T_VBLANK_QPC = 28, T_REFRESH = 36, T_FRAME = 56,
            T_FRAME_SUBMITTED = 76, T_FRAME_CONFIRMED = 88, T_FRAMES_LATE = 112, T_FRAMES_OUTSTANDING = 120,
            T_FRAME_DISPLAYED = 124;

    private Dwmapi() {
    }

    /**
     * The compositor's own account of this window's frames, for diagnostics: the vblank count, the frames handed
     * to it, confirmed and displayed, and how many it has called late. Fills {@code out} with those eight values
     * in the order of the {@code T_*} constants, and returns whether the compositor answered. Allocates, so it is
     * for a probe and never for the frame budget.
     */
    public static boolean timingInfo(MemorySegment hwnd, long[] out) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment info = temp.allocate(512);
            if (!queryTiming(info)) {
                return false;
            }
            int[] at = {T_PERIOD_QPC, T_VBLANK_QPC, T_REFRESH, T_FRAME, T_FRAME_SUBMITTED, T_FRAME_CONFIRMED,
                    T_FRAMES_LATE, T_FRAMES_OUTSTANDING, T_FRAME_DISPLAYED};
            for (int i = 0; i < at.length; i++) {
                out[i] = info.get(java.lang.foreign.ValueLayout.JAVA_LONG_UNALIGNED, at[i]);
            }
            return true;
        } catch (Throwable t) {
            timingFailure = t.toString();
            return false;
        }
    }

    /**
     * Fill {@code info} with the compositor's global timing, finding the size it accepts the first time. The
     * window argument is null on purpose: asked about a Vulkan window the compositor answers
     * {@code 0x88980090}, and asked about none it answers for itself.
     */
    private static boolean queryTiming(MemorySegment info) throws Throwable {
        int hr = -1;
        if (timingInfoSize != 0) {
            info.set(JAVA_INT, 0, timingInfoSize);
            hr = (int) DwmGetCompositionTimingInfo.invokeExact(MemorySegment.NULL, info);
        } else {
            for (int size = 200; size <= 400 && hr != 0; size += 4) {
                info.set(JAVA_INT, 0, size);
                hr = (int) DwmGetCompositionTimingInfo.invokeExact(MemorySegment.NULL, info);
                if (hr == 0) {
                    timingInfoSize = size;
                }
            }
        }
        if (hr != 0) {
            timingFailure = "HRESULT 0x" + Integer.toHexString(hr) + " for every size tried";
            return false;
        }
        return true;
    }

    /**
     * How long one refresh of the display takes, in nanoseconds, or 0 when the compositor will not say. The
     * struct's {@code rateRefresh} is a ratio of the performance counter's ticks a second to the ticks one
     * refresh lasts (10 000 000 over 69 448 for a 144 Hz panel), so the interval is the second ratio inverted.
     *
     * <p>The compositor's, not the window's: on a machine with displays of different rates it is the rate the
     * compositor itself runs at, which is not necessarily the one this window is on.
     */
    public static long refreshIntervalNanos() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment info = temp.allocate(512);
            if (!queryTiming(info)) {
                return 0L;
            }
            long ticksPerSecond = Integer.toUnsignedLong(info.get(JAVA_INT, 4));
            long ticksPerRefresh = Integer.toUnsignedLong(info.get(JAVA_INT, 8));
            return ticksPerSecond == 0 ? 0L : ticksPerRefresh * 1_000_000_000L / ticksPerSecond;
        } catch (Throwable t) {
            timingFailure = t.toString();
            return 0L;
        }
    }

    /** Why the last {@link #timingInfo} returned false. */
    public static String timingFailure() {
        return timingFailure;
    }

    private static String timingFailure = "";

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
