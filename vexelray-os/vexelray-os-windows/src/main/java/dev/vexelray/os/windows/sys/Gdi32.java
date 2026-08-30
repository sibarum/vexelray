package dev.vexelray.os.windows.sys;

import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Minimal binding for {@code gdi32.dll}: {@code CreateSolidBrush}, used to give the window class a background so
 * the client area is erased to a solid colour the instant the window is shown — before Vulkan has presented a
 * frame — and the two bitmaps an icon is made of. One library, one class; see {@code docs/native-bindings.md}.
 */
public final class Gdi32 {

    private static final SymbolLookup LIB = Ffi.library("gdi32");

    /** {@code DIB_RGB_COLORS} — the header carries colours directly rather than palette indices. */
    private static final int DIB_RGB_COLORS = 0;
    /** {@code BI_BITFIELDS} — channel positions come from the header's four masks. */
    private static final int BI_BITFIELDS = 3;

    /**
     * {@code BITMAPV5HEADER} — 124 bytes. Only the fields through {@code bV5AlphaMask} are ever set here; the
     * colour-space tail is left zero, which is what {@code LCS_CALIBRATED_RGB} (0) means and is the right answer
     * for pixels already in the display's space. Named through {@code bV5AlphaMask}, padded thereafter: a field
     * this binding never touches does not earn a name.
     */
    private static final GroupLayout BITMAPV5HEADER = MemoryLayout.structLayout(
            JAVA_INT.withName("bV5Size"),
            JAVA_INT.withName("bV5Width"),
            JAVA_INT.withName("bV5Height"),
            JAVA_SHORT.withName("bV5Planes"),
            JAVA_SHORT.withName("bV5BitCount"),
            JAVA_INT.withName("bV5Compression"),
            JAVA_INT.withName("bV5SizeImage"),
            JAVA_INT.withName("bV5XPelsPerMeter"),
            JAVA_INT.withName("bV5YPelsPerMeter"),
            JAVA_INT.withName("bV5ClrUsed"),
            JAVA_INT.withName("bV5ClrImportant"),
            JAVA_INT.withName("bV5RedMask"),
            JAVA_INT.withName("bV5GreenMask"),
            JAVA_INT.withName("bV5BlueMask"),
            JAVA_INT.withName("bV5AlphaMask"),
            MemoryLayout.paddingLayout(68)   // CSType, endpoints, gammas, intent, profile — all zero
    ).withName("BITMAPV5HEADER");

    private static final VarHandle BH_bV5Size        = fieldHandle("bV5Size");
    private static final VarHandle BH_bV5Width       = fieldHandle("bV5Width");
    private static final VarHandle BH_bV5Height      = fieldHandle("bV5Height");
    private static final VarHandle BH_bV5Planes      = fieldHandle("bV5Planes");
    private static final VarHandle BH_bV5BitCount    = fieldHandle("bV5BitCount");
    private static final VarHandle BH_bV5Compression = fieldHandle("bV5Compression");
    private static final VarHandle BH_bV5RedMask     = fieldHandle("bV5RedMask");
    private static final VarHandle BH_bV5GreenMask   = fieldHandle("bV5GreenMask");
    private static final VarHandle BH_bV5BlueMask    = fieldHandle("bV5BlueMask");
    private static final VarHandle BH_bV5AlphaMask   = fieldHandle("bV5AlphaMask");

    private static VarHandle fieldHandle(String field) {
        // Offsets come from the layout, never a literal — the convention the other bindings follow.
        VarHandle vh = BITMAPV5HEADER.varHandle(PathElement.groupElement(field));
        return MethodHandles.insertCoordinates(vh, 1, 0L).withInvokeExactBehavior();
    }

    private static final MethodHandle CreateSolidBrush = Ffi.downcall(LIB, "CreateSolidBrush",
            FunctionDescriptor.of(ADDRESS, JAVA_INT));
    private static final MethodHandle CreateDIBSection = Ffi.downcall(LIB, "CreateDIBSection",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle CreateBitmap = Ffi.downcall(LIB, "CreateBitmap",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
    private static final MethodHandle DeleteObject = Ffi.downcall(LIB, "DeleteObject",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

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

    /**
     * Create a 32-bit top-down {@code HBITMAP} holding {@code argb} — the colour half of an icon.
     *
     * <p>A DIB section rather than {@code CreateBitmap} with the bits inline, because only this route lets the
     * header state where the alpha channel is. A device-dependent bitmap has no alpha mask to state, so Windows
     * treats its top byte as padding and the icon comes out with hard edges on any background but the one it was
     * drawn against.
     *
     * <p>The height is passed negative, which is how a DIB says its first row is the top one. Without it the
     * rows are read bottom-up and the icon is drawn upside down — a silent, purely visual failure.
     *
     * <p>An {@code int} of {@code 0xAARRGGBB} is B, G, R, A in memory on a little-endian machine, which is
     * exactly the byte order the masks below declare, so the pixels copy across without rearrangement.
     *
     * @return the {@code HBITMAP}; the caller owns it and must {@link #deleteObject} it
     */
    public static MemorySegment createArgbBitmap(int width, int height, int[] argb) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment header = temp.allocate(BITMAPV5HEADER);
            BH_bV5Size.set(header, (int) BITMAPV5HEADER.byteSize());
            BH_bV5Width.set(header, width);
            BH_bV5Height.set(header, -height);   // negative: top-down rows
            BH_bV5Planes.set(header, (short) 1);
            BH_bV5BitCount.set(header, (short) 32);
            BH_bV5Compression.set(header, BI_BITFIELDS);
            BH_bV5RedMask.set(header, 0x00FF0000);
            BH_bV5GreenMask.set(header, 0x0000FF00);
            BH_bV5BlueMask.set(header, 0x000000FF);
            BH_bV5AlphaMask.set(header, 0xFF000000);

            MemorySegment ppvBits = temp.allocate(ADDRESS);
            MemorySegment bitmap;
            try {
                bitmap = (MemorySegment) CreateDIBSection.invokeExact(MemorySegment.NULL, header,
                        DIB_RGB_COLORS, ppvBits, MemorySegment.NULL, 0);
            } catch (Throwable t) {
                throw NativeException.rethrow("CreateDIBSection", t);
            }
            if (bitmap.equals(MemorySegment.NULL)) {
                throw new NativeException("CreateDIBSection failed for a " + width + "x" + height
                        + " icon image (GetLastError=" + Kernel32.getLastError() + ")");
            }
            // The pointer comes back with no size attached; the header above says how many bytes are behind it.
            MemorySegment pixels = ppvBits.get(ADDRESS, 0)
                    .reinterpret((long) width * height * Integer.BYTES);
            MemorySegment.copy(argb, 0, pixels, JAVA_INT, 0, width * height);
            return bitmap;
        }
    }

    /**
     * Create the 1-bit mask bitmap an {@code ICONINFO} requires, left entirely zero.
     *
     * <p>Required and ignored, both. The mask is how a pre-alpha icon said which pixels were transparent; a
     * 32-bit colour bitmap carries that in its alpha channel instead, and Windows uses the alpha when there is
     * one. The struct still has the field, so the bitmap still has to exist — all zeroes, meaning "every pixel
     * is the icon", with the alpha deciding what that actually looks like.
     *
     * @return the {@code HBITMAP}; the caller owns it and must {@link #deleteObject} it
     */
    public static MemorySegment createMaskBitmap(int width, int height) {
        try {
            MemorySegment mask = (MemorySegment) CreateBitmap.invokeExact(width, height, 1, 1,
                    MemorySegment.NULL);
            if (mask.equals(MemorySegment.NULL)) {
                throw new NativeException("CreateBitmap failed for a " + width + "x" + height
                        + " icon mask (GetLastError=" + Kernel32.getLastError() + ")");
            }
            return mask;
        } catch (Throwable t) {
            throw NativeException.rethrow("CreateBitmap", t);
        }
    }

    /**
     * Release a GDI object. Used for the two bitmaps an icon is built from: {@code CreateIconIndirect} copies
     * them, so they are scaffolding and leak a handle each if they outlive the call that consumed them.
     */
    public static void deleteObject(MemorySegment object) {
        if (object.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            int ignored = (int) DeleteObject.invokeExact(object);
        } catch (Throwable t) {
            throw NativeException.rethrow("DeleteObject", t);
        }
    }

    /** Convert 8-bit R/G/B to a Win32 {@code COLORREF} (0x00BBGGRR). */
    public static int rgb(int r, int g, int b) {
        return (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
    }
}
