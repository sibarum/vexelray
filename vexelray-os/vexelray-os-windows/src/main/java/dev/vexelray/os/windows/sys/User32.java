package dev.vexelray.os.windows.sys;

import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Binding for {@code user32.dll}: window class registration, window creation, and the message pump. One library,
 * one class. Struct field access goes through named-layout {@link VarHandle}s (never literal offsets), and the
 * struct-building helpers keep that access inside this binding. See {@code docs/native-bindings.md} §4.
 */
public final class User32 {

    private static final SymbolLookup LIB = Ffi.library("user32");

    // ---- Window class / style / message constants (Win32 SDK values) ----------------------------------------

    public static final int CS_VREDRAW = 0x0001;
    public static final int CS_HREDRAW = 0x0002;
    public static final int CS_OWNDC   = 0x0020;

    public static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
    public static final int WS_VISIBLE          = 0x10000000;
    public static final int CW_USEDEFAULT       = 0x80000000;

    public static final int SW_SHOW = 5;

    public static final int PM_REMOVE = 0x0001;

    public static final int WM_DESTROY = 0x0002;
    public static final int WM_SIZE    = 0x0005;
    public static final int WM_CLOSE   = 0x0010;
    public static final int WM_QUIT    = 0x0012;
    public static final int WM_KEYDOWN   = 0x0100;
    public static final int WM_KEYUP     = 0x0101;
    public static final int WM_SETCURSOR = 0x0020;

    /** {@code HTCLIENT} — the WM_SETCURSOR hit-test code (LOWORD of lParam) for the window's client area. */
    public static final int HTCLIENT = 1;

    /** {@code IDC_ARROW} — the standard arrow cursor, passed to LoadCursorW as a MAKEINTRESOURCE pseudo-pointer. */
    public static final int IDC_ARROW = 32512;
    /** {@code IDC_IBEAM} — the text-placement (I-beam) cursor. */
    public static final int IDC_IBEAM = 32513;

    // ---- Struct layouts (named fields; padding explicit to match the x64 C ABI) -----------------------------

    /** {@code WNDCLASSEXW} — 80 bytes on x64. */
    public static final GroupLayout WNDCLASSEXW = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            JAVA_INT.withName("style"),
            ADDRESS.withName("lpfnWndProc"),
            JAVA_INT.withName("cbClsExtra"),
            JAVA_INT.withName("cbWndExtra"),
            ADDRESS.withName("hInstance"),
            ADDRESS.withName("hIcon"),
            ADDRESS.withName("hCursor"),
            ADDRESS.withName("hbrBackground"),
            ADDRESS.withName("lpszMenuName"),
            ADDRESS.withName("lpszClassName"),
            ADDRESS.withName("hIconSm")
    ).withName("WNDCLASSEXW");

    /** {@code MSG} — 48 bytes on x64. Passed opaquely to the pump; no field access needed here. */
    public static final GroupLayout MSG = MemoryLayout.structLayout(
            ADDRESS.withName("hwnd"),
            JAVA_INT.withName("message"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("wParam"),
            JAVA_LONG.withName("lParam"),
            JAVA_INT.withName("time"),
            JAVA_INT.withName("pt_x"),
            JAVA_INT.withName("pt_y"),
            MemoryLayout.paddingLayout(4)
    ).withName("MSG");

    /** {@code RECT} — left/top/right/bottom, 16 bytes. */
    public static final GroupLayout RECT = MemoryLayout.structLayout(
            JAVA_INT.withName("left"),
            JAVA_INT.withName("top"),
            JAVA_INT.withName("right"),
            JAVA_INT.withName("bottom")
    ).withName("RECT");

    private static final VarHandle WC_cbSize        = fieldHandle(WNDCLASSEXW, "cbSize");
    private static final VarHandle WC_style         = fieldHandle(WNDCLASSEXW, "style");
    private static final VarHandle WC_lpfnWndProc   = fieldHandle(WNDCLASSEXW, "lpfnWndProc");
    private static final VarHandle WC_hInstance     = fieldHandle(WNDCLASSEXW, "hInstance");
    private static final VarHandle WC_hCursor       = fieldHandle(WNDCLASSEXW, "hCursor");
    private static final VarHandle WC_hbrBackground = fieldHandle(WNDCLASSEXW, "hbrBackground");
    private static final VarHandle WC_lpszClassName = fieldHandle(WNDCLASSEXW, "lpszClassName");

    private static final VarHandle RECT_right  = fieldHandle(RECT, "right");
    private static final VarHandle RECT_bottom = fieldHandle(RECT, "bottom");

    private static VarHandle fieldHandle(GroupLayout layout, String field) {
        // Offsets come from the layout, never a literal. A layout varHandle has a (MemorySegment, long base)
        // coordinate pair; bind the base to 0 so callers use the simple (MemorySegment, value) form.
        VarHandle vh = layout.varHandle(PathElement.groupElement(field));
        return MethodHandles.insertCoordinates(vh, 1, 0L).withInvokeExactBehavior();
    }

    // ---- Downcalls (one MethodHandle per C function, named exactly like the symbol) --------------------------

    private static final MethodHandle RegisterClassExW = Ffi.downcall(LIB, "RegisterClassExW",
            FunctionDescriptor.of(JAVA_SHORT, ADDRESS));
    private static final MethodHandle LoadCursorW = Ffi.downcall(LIB, "LoadCursorW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle SetCursor = Ffi.downcall(LIB, "SetCursor",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle CreateWindowExW = Ffi.downcall(LIB, "CreateWindowExW",
            FunctionDescriptor.of(ADDRESS,
                    JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle DestroyWindow = Ffi.downcall(LIB, "DestroyWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DefWindowProcW = Ffi.downcall(LIB, "DefWindowProcW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle ShowWindow = Ffi.downcall(LIB, "ShowWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle GetClientRect = Ffi.downcall(LIB, "GetClientRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle PeekMessageW = Ffi.downcall(LIB, "PeekMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle TranslateMessage = Ffi.downcall(LIB, "TranslateMessage",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle DispatchMessageW = Ffi.downcall(LIB, "DispatchMessageW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS));

    private User32() {
    }

    // ---- Struct builders / readers (keep field access inside the binding) -----------------------------------

    /**
     * Allocate and populate a {@code WNDCLASSEXW} with the fields VexelRay sets: size, redraw style, the window
     * procedure, instance, cursor, class name, and background brush. The background lets the OS erase the client
     * area to a solid colour the instant the window is shown, before Vulkan presents its first frame; the swapchain
     * owns every pixel thereafter. Pass {@code hbrBackground == MemorySegment.NULL} to leave it unset. Icon and
     * menu are left zero.
     */
    public static MemorySegment allocWndClassExW(SegmentAllocator allocator, MemorySegment wndProc,
                                                 MemorySegment hInstance, MemorySegment hCursor,
                                                 MemorySegment className, MemorySegment hbrBackground) {
        MemorySegment wc = allocator.allocate(WNDCLASSEXW);
        WC_cbSize.set(wc, (int) WNDCLASSEXW.byteSize());
        WC_style.set(wc, CS_HREDRAW | CS_VREDRAW | CS_OWNDC);
        WC_lpfnWndProc.set(wc, wndProc);
        WC_hInstance.set(wc, hInstance);
        WC_hCursor.set(wc, hCursor);
        WC_hbrBackground.set(wc, hbrBackground);
        WC_lpszClassName.set(wc, className);
        return wc;
    }

    /** The client width of a filled {@code RECT} (right − left, and left is 0 for GetClientRect). */
    public static int rectWidth(MemorySegment rect) {
        return (int) RECT_right.get(rect);
    }

    /** The client height of a filled {@code RECT}. */
    public static int rectHeight(MemorySegment rect) {
        return (int) RECT_bottom.get(rect);
    }

    // ---- Downcall wrappers ----------------------------------------------------------------------------------

    /** Register a window class; returns the class ATOM. Throws on failure (ATOM 0). */
    public static short registerClassExW(MemorySegment wndClass) {
        try {
            short atom = (short) RegisterClassExW.invokeExact(wndClass);
            if (atom == 0) {
                throw new NativeException("RegisterClassExW failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
            return atom;
        } catch (Throwable t) {
            throw NativeException.rethrow("RegisterClassExW", t);
        }
    }

    /** Load a system cursor. {@code hInstance} is NULL for the predefined IDC_* cursors. */
    public static MemorySegment loadCursorW(MemorySegment hInstance, int cursorId) {
        try {
            return (MemorySegment) LoadCursorW.invokeExact(hInstance, MemorySegment.ofAddress(cursorId));
        } catch (Throwable t) {
            throw NativeException.rethrow("LoadCursorW", t);
        }
    }

    /** Set the cursor for the calling thread; returns the previous cursor handle (ignored). */
    public static void setCursor(MemorySegment hCursor) {
        try {
            MemorySegment ignored = (MemorySegment) SetCursor.invokeExact(hCursor);
        } catch (Throwable t) {
            throw NativeException.rethrow("SetCursor", t);
        }
    }

    /** Create a window; returns the HWND. Throws on a NULL result. */
    public static MemorySegment createWindowExW(int exStyle, MemorySegment className, MemorySegment title,
                                                int style, int x, int y, int width, int height,
                                                MemorySegment parent, MemorySegment menu,
                                                MemorySegment hInstance, MemorySegment param) {
        try {
            MemorySegment hwnd = (MemorySegment) CreateWindowExW.invokeExact(exStyle, className, title, style,
                    x, y, width, height, parent, menu, hInstance, param);
            if (hwnd.equals(MemorySegment.NULL)) {
                throw new NativeException("CreateWindowExW returned NULL (GetLastError="
                        + Kernel32.getLastError() + ")");
            }
            return hwnd;
        } catch (Throwable t) {
            throw NativeException.rethrow("CreateWindowExW", t);
        }
    }

    public static void destroyWindow(MemorySegment hwnd) {
        try {
            int ok = (int) DestroyWindow.invokeExact(hwnd);
            if (ok == 0) {
                throw new NativeException("DestroyWindow failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("DestroyWindow", t);
        }
    }

    public static long defWindowProcW(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (long) DefWindowProcW.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            throw NativeException.rethrow("DefWindowProcW", t);
        }
    }

    public static void showWindow(MemorySegment hwnd, int cmdShow) {
        try {
            int ignored = (int) ShowWindow.invokeExact(hwnd, cmdShow);
        } catch (Throwable t) {
            throw NativeException.rethrow("ShowWindow", t);
        }
    }

    /** Fill {@code rect} with the window's client area. */
    public static void getClientRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            int ok = (int) GetClientRect.invokeExact(hwnd, rect);
            if (ok == 0) {
                throw new NativeException("GetClientRect failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("GetClientRect", t);
        }
    }

    /** Peek and remove one message into {@code msg}. Returns true if one was available. */
    public static boolean peekMessageRemove(MemorySegment msg) {
        try {
            return (int) PeekMessageW.invokeExact(msg, MemorySegment.NULL, 0, 0, PM_REMOVE) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("PeekMessageW", t);
        }
    }

    public static void translateMessage(MemorySegment msg) {
        try {
            int ignored = (int) TranslateMessage.invokeExact(msg);
        } catch (Throwable t) {
            throw NativeException.rethrow("TranslateMessage", t);
        }
    }

    public static void dispatchMessageW(MemorySegment msg) {
        try {
            long ignored = (long) DispatchMessageW.invokeExact(msg);
        } catch (Throwable t) {
            throw NativeException.rethrow("DispatchMessageW", t);
        }
    }
}
