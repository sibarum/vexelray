package dev.vexelray.os.windows.sys;

import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;

import java.lang.foreign.Arena;
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
    /** {@code WS_POPUP} — a frameless top-level window: no caption, no borders, no window-manager sizing. */
    public static final int WS_POPUP            = 0x80000000;
    public static final int CW_USEDEFAULT       = 0x80000000;

    public static final int SW_SHOW = 5;

    public static final int PM_REMOVE = 0x0001;

    // MsgWaitForMultipleObjectsEx, for a loop that would rather block than spin. QS_ALLINPUT is every
    // queue class, so the wait ends on anything the window would have pumped anyway.
    public static final int QS_ALLINPUT = 0x04FF;
    /**
     * Count input that arrived <em>before</em> the wait began as a reason to return.
     *
     * <p>Load-bearing rather than an optimisation. Without it the call reports only input that becomes
     * available after entering the wait, so anything posted in the window between the last
     * {@link #peekMessageRemove} and the wait is not new — and the loop sleeps through the very event
     * it was about to handle. That is a hang whose frequency depends on timing, which is the worst kind.
     */
    public static final int MWMO_INPUTAVAILABLE = 0x0004;
    public static final int INFINITE = 0xFFFFFFFF;

    /** {@code WM_NULL} — dispatched and discarded, which is exactly what a bare wakeup wants. */
    public static final int WM_NULL    = 0x0000;
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

    private static final VarHandle RECT_left   = fieldHandle(RECT, "left");
    private static final VarHandle RECT_top    = fieldHandle(RECT, "top");
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
    private static final MethodHandle GetWindowRect = Ffi.downcall(LIB, "GetWindowRect",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle SetWindowPos = Ffi.downcall(LIB, "SetWindowPos",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
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

    /** Allocate a {@code RECT} spanning {@code (x, y)} to {@code (x + w, y + h)}. */
    public static MemorySegment allocRect(SegmentAllocator allocator, int x, int y, int w, int h) {
        MemorySegment rect = allocator.allocate(RECT);
        RECT_left.set(rect, x);
        RECT_top.set(rect, y);
        RECT_right.set(rect, x + w);
        RECT_bottom.set(rect, y + h);
        return rect;
    }

    /** The {@code left} of a filled {@code RECT} (screen x for GetWindowRect). */
    public static int rectLeft(MemorySegment rect) {
        return (int) RECT_left.get(rect);
    }

    /** The {@code top} of a filled {@code RECT} (screen y for GetWindowRect). */
    public static int rectTop(MemorySegment rect) {
        return (int) RECT_top.get(rect);
    }

    /** The width of a filled {@code RECT} (right − left) — outer width for GetWindowRect. */
    public static int rectSpanX(MemorySegment rect) {
        return (int) RECT_right.get(rect) - (int) RECT_left.get(rect);
    }

    /** The height of a filled {@code RECT} (bottom − top) — outer height for GetWindowRect. */
    public static int rectSpanY(MemorySegment rect) {
        return (int) RECT_bottom.get(rect) - (int) RECT_top.get(rect);
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

    /** Fill {@code rect} with the window's outer rect, in screen coordinates. */
    public static void getWindowRect(MemorySegment hwnd, MemorySegment rect) {
        try {
            int ok = (int) GetWindowRect.invokeExact(hwnd, rect);
            if (ok == 0) {
                throw new NativeException("GetWindowRect failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("GetWindowRect", t);
        }
    }

    /** SetWindowPos flags: keep size, keep z-order, don't steal activation — a pure move. */
    public static final int SWP_NOSIZE     = 0x0001;
    public static final int SWP_NOZORDER   = 0x0004;
    public static final int SWP_NOACTIVATE = 0x0010;

    /** Move the window's outer top-left to screen {@code (x, y)} without resizing, re-stacking or activating. */
    public static void moveWindow(MemorySegment hwnd, int x, int y) {
        try {
            int ok = (int) SetWindowPos.invokeExact(hwnd, MemorySegment.NULL, x, y, 0, 0,
                    SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
            if (ok == 0) {
                throw new NativeException("SetWindowPos failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("SetWindowPos", t);
        }
    }

    /**
     * Move and size the window's outer rect in one {@code SetWindowPos}, without re-stacking or activating.
     * One call rather than a move plus a resize: two would be two {@code WM_WINDOWPOSCHANGED} rounds, and the
     * intermediate rect is a visible flicker.
     */
    public static void setWindowBounds(MemorySegment hwnd, int x, int y, int width, int height) {
        try {
            int ok = (int) SetWindowPos.invokeExact(hwnd, MemorySegment.NULL, x, y, width, height,
                    SWP_NOZORDER | SWP_NOACTIVATE);
            if (ok == 0) {
                throw new NativeException("SetWindowPos failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("SetWindowPos", t);
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

    // ---- Client-drawn chrome (Decorations.CLIENT) -----------------------------------------------------------
    //
    // The non-client messages a window must answer to draw its own frame while Windows keeps running the frame:
    // WM_NCCALCSIZE decides how much of the window is client area, WM_NCHITTEST names what is under the pointer,
    // and the size/move loop messages let a pull-style render loop keep painting while Windows drags the window.

    public static final int WM_MOVE          = 0x0003;
    public static final int WM_GETMINMAXINFO = 0x0024;
    public static final int WM_NCCALCSIZE    = 0x0083;
    public static final int WM_NCHITTEST     = 0x0084;
    public static final int WM_NCLBUTTONDOWN = 0x00A1;
    public static final int WM_TIMER         = 0x0113;
    public static final int WM_ENTERSIZEMOVE = 0x0231;
    public static final int WM_EXITSIZEMOVE  = 0x0232;

    /** {@code WM_SIZE} wParam: the window was minimized (its client size is 0 and must not be presented to). */
    public static final int SIZE_MINIMIZED = 1;


    // Hit-test codes returned from WM_NCHITTEST. HTCLIENT is declared above with WM_SETCURSOR.
    public static final int HTCAPTION     = 2;
    public static final int HTMAXBUTTON   = 9;
    public static final int HTLEFT        = 10;
    public static final int HTRIGHT       = 11;
    public static final int HTTOP         = 12;
    public static final int HTTOPLEFT     = 13;
    public static final int HTTOPRIGHT    = 14;
    public static final int HTBOTTOM      = 15;
    public static final int HTBOTTOMLEFT  = 16;
    public static final int HTBOTTOMRIGHT = 17;

    /** {@code GetSystemMetrics} indices: the resize frame thickness, plus the invisible padding around it. */
    public static final int SM_CXSIZEFRAME    = 32;
    public static final int SM_CYSIZEFRAME    = 33;
    public static final int SM_CXPADDEDBORDER = 92;

    public static final int SW_HIDE     = 0;
    public static final int SW_MAXIMIZE = 3;
    public static final int SW_MINIMIZE = 6;
    public static final int SW_RESTORE  = 9;

    public static final int SWP_NOMOVE       = 0x0002;
    public static final int SWP_FRAMECHANGED = 0x0020;

    /** {@code POINT} — x/y, 8 bytes. */
    public static final GroupLayout POINT = MemoryLayout.structLayout(
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y")
    ).withName("POINT");

    private static final VarHandle POINT_x = fieldHandle(POINT, "x");
    private static final VarHandle POINT_y = fieldHandle(POINT, "y");

    /**
     * {@code MINMAXINFO} — five {@code POINT}s, 40 bytes, passed by pointer in {@code WM_GETMINMAXINFO}'s
     * lParam. Only {@code ptMinTrackSize} is ever written from here: the rest is Windows' own answer, and
     * overwriting {@code ptMaxSize} or {@code ptMaxPosition} is how a window loses its maximize geometry.
     */
    private static final GroupLayout MINMAXINFO = MemoryLayout.structLayout(
            POINT.withName("ptReserved"),
            POINT.withName("ptMaxSize"),
            POINT.withName("ptMaxPosition"),
            POINT.withName("ptMinTrackSize"),
            POINT.withName("ptMaxTrackSize")
    ).withName("MINMAXINFO");

    private static final VarHandle MMI_minTrackX = minTrackField("x");
    private static final VarHandle MMI_minTrackY = minTrackField("y");

    private static VarHandle minTrackField(String axis) {
        VarHandle vh = MINMAXINFO.varHandle(
                PathElement.groupElement("ptMinTrackSize"),
                PathElement.groupElement(axis));
        return MethodHandles.insertCoordinates(vh, 1, 0L).withInvokeExactBehavior();
    }

    /**
     * Write {@code ptMinTrackSize} into the {@code MINMAXINFO} at {@code address} — the smallest outer size the
     * window manager will let a drag reach. Sizes are pixels of the window rect, the same rect
     * {@link #getWindowRect} reports.
     *
     * <p>Zero on either axis leaves that axis as Windows computed it, so a caller may bound one dimension
     * without having to invent a number for the other.
     */
    public static void setMinTrackSize(long address, int width, int height) {
        MemorySegment info = MemorySegment.ofAddress(address).reinterpret(MINMAXINFO.byteSize());
        if (width > 0) {
            MMI_minTrackX.set(info, width);
        }
        if (height > 0) {
            MMI_minTrackY.set(info, height);
        }
    }

    /** A zeroed {@code MINMAXINFO}, for asking a live window the question Windows asks it. */
    public static MemorySegment allocMinMaxInfo(SegmentAllocator allocator) {
        return allocator.allocate(MINMAXINFO);
    }

    /** {@code ptMinTrackSize.x} of a filled {@code MINMAXINFO}. */
    public static int minTrackWidth(MemorySegment info) {
        return (int) MMI_minTrackX.get(info);
    }

    /** {@code ptMinTrackSize.y} of a filled {@code MINMAXINFO}. */
    public static int minTrackHeight(MemorySegment info) {
        return (int) MMI_minTrackY.get(info);
    }

    private static final MethodHandle GetSystemMetrics = Ffi.downcall(LIB, "GetSystemMetrics",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));
    private static final MethodHandle ScreenToClient = Ffi.downcall(LIB, "ScreenToClient",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle IsZoomed = Ffi.downcall(LIB, "IsZoomed",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle IsIconic = Ffi.downcall(LIB, "IsIconic",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SetTimer = Ffi.downcall(LIB, "SetTimer",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, ADDRESS));
    private static final MethodHandle KillTimer = Ffi.downcall(LIB, "KillTimer",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle PostMessageW = Ffi.downcall(LIB, "PostMessageW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle GetForegroundWindow = Ffi.downcall(LIB, "GetForegroundWindow",
            FunctionDescriptor.of(ADDRESS));
    private static final MethodHandle MsgWaitForMultipleObjectsEx =
            Ffi.downcall(LIB, "MsgWaitForMultipleObjectsEx",
                    FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle IsWindowVisible = Ffi.downcall(LIB, "IsWindowVisible",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle EnableWindow = Ffi.downcall(LIB, "EnableWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle SetForegroundWindow = Ffi.downcall(LIB, "SetForegroundWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle SetActiveWindow = Ffi.downcall(LIB, "SetActiveWindow",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle BringWindowToTop = Ffi.downcall(LIB, "BringWindowToTop",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /** A system metric ({@code SM_*}). */
    public static int getSystemMetrics(int index) {
        try {
            return (int) GetSystemMetrics.invokeExact(index);
        } catch (Throwable t) {
            throw NativeException.rethrow("GetSystemMetrics", t);
        }
    }

    /** Allocate a {@code POINT} holding {@code (x, y)}. */
    public static MemorySegment allocPoint(SegmentAllocator allocator, int x, int y) {
        MemorySegment p = allocator.allocate(POINT);
        POINT_x.set(p, x);
        POINT_y.set(p, y);
        return p;
    }

    public static int pointX(MemorySegment point) {
        return (int) POINT_x.get(point);
    }

    public static int pointY(MemorySegment point) {
        return (int) POINT_y.get(point);
    }

    /** Convert a screen-space {@code POINT} in place to the window's client space. */
    public static void screenToClient(MemorySegment hwnd, MemorySegment point) {
        try {
            int ok = (int) ScreenToClient.invokeExact(hwnd, point);
            if (ok == 0) {
                throw new NativeException("ScreenToClient failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("ScreenToClient", t);
        }
    }

    /** Whether the window is maximized. */
    public static boolean isZoomed(MemorySegment hwnd) {
        try {
            return (int) IsZoomed.invokeExact(hwnd) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("IsZoomed", t);
        }
    }

    /** Whether the window is minimized. */
    public static boolean isIconic(MemorySegment hwnd) {
        try {
            return (int) IsIconic.invokeExact(hwnd) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("IsIconic", t);
        }
    }

    /** Start (or restart) a window timer of {@code id} firing every {@code elapseMillis}. */
    public static void setTimer(MemorySegment hwnd, long id, int elapseMillis) {
        try {
            long created = (long) SetTimer.invokeExact(hwnd, id, elapseMillis, MemorySegment.NULL);
            if (created == 0L) {
                throw new NativeException("SetTimer failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("SetTimer", t);
        }
    }

    /** Stop a window timer. Silent if it was never started — stopping twice is not an error worth raising. */
    public static void killTimer(MemorySegment hwnd, long id) {
        try {
            int ignored = (int) KillTimer.invokeExact(hwnd, id);
        } catch (Throwable t) {
            throw NativeException.rethrow("KillTimer", t);
        }
    }

    /** Post a message to the window's queue (asynchronous — it is handled by a later pump). */
    /** Whether {@code hwnd} is the foreground window - the one the user is typing into. */
    public static boolean isForeground(MemorySegment hwnd) {
        try {
            MemorySegment fg = (MemorySegment) GetForegroundWindow.invokeExact();
            return fg.equals(hwnd);
        } catch (Throwable t) {
            throw NativeException.rethrow("GetForegroundWindow", t);
        }
    }

    /**
     * Block until a message is available or {@code timeoutMillis} elapses. {@link #INFINITE} to wait
     * indefinitely.
     *
     * <p>The other half of {@link #peekMessageRemove}: peek drains what is there, this waits for there
     * to be something. A loop with only the former has no choice but to spin.
     *
     * <p>Spurious returns are permitted and harmless — the caller pumps, finds nothing, and comes back.
     * Never assume a return means a message arrived.
     */
    public static void msgWaitForInput(int timeoutMillis) {
        try {
            int ignored = (int) MsgWaitForMultipleObjectsEx.invokeExact(
                    0, MemorySegment.NULL, timeoutMillis, QS_ALLINPUT, MWMO_INPUTAVAILABLE);
        } catch (Throwable t) {
            throw NativeException.rethrow("MsgWaitForMultipleObjectsEx", t);
        }
    }

    public static void postMessageW(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            int ok = (int) PostMessageW.invokeExact(hwnd, msg, wParam, lParam);
            if (ok == 0) {
                throw new NativeException("PostMessageW failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("PostMessageW", t);
        }
    }

    /**
     * Tell Windows the window's frame has changed, so it recalculates the non-client area (a fresh
     * WM_NCCALCSIZE) without moving, sizing, re-stacking or activating the window. This is what makes a
     * client-drawn frame take effect on a window that has already been created.
     */
    public static void frameChanged(MemorySegment hwnd) {
        try {
            int ok = (int) SetWindowPos.invokeExact(hwnd, MemorySegment.NULL, 0, 0, 0, 0,
                    SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
            if (ok == 0) {
                throw new NativeException("SetWindowPos failed (GetLastError=" + Kernel32.getLastError() + ")");
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("SetWindowPos", t);
        }
    }

    /** Inset a {@code RECT} in place by {@code dx} horizontally and {@code dy} vertically. */
    public static void insetRect(MemorySegment rect, int dx, int dy) {
        RECT_left.set(rect, (int) RECT_left.get(rect) + dx);
        RECT_top.set(rect, (int) RECT_top.get(rect) + dy);
        RECT_right.set(rect, (int) RECT_right.get(rect) - dx);
        RECT_bottom.set(rect, (int) RECT_bottom.get(rect) - dy);
    }

    /**
     * View the {@code RECT} at a raw address — for the structs Windows passes by pointer in a message parameter
     * (the {@code NCCALCSIZE_PARAMS} whose first member is the proposed client rect). Keeping the reinterpret
     * inside the binding is what stops message handlers from doing pointer arithmetic of their own.
     */
    public static MemorySegment rectAt(long address) {
        return MemorySegment.ofAddress(address).reinterpret(RECT.byteSize());
    }

    private static final MethodHandle SendMessageW = Ffi.downcall(LIB, "SendMessageW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));

    /**
     * Send a message and return the window procedure's result, synchronously. Used to ask a window the same
     * questions Windows asks it — notably {@code WM_NCHITTEST}, which is how a client-drawn frame can be
     * verified against a live window rather than only against the pure region logic.
     */
    public static long sendMessageW(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (long) SendMessageW.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            throw NativeException.rethrow("SendMessageW", t);
        }
    }

    // ---- Icons ----------------------------------------------------------------------------------------------

    /** {@code WM_SETICON} — hand a window an {@code HICON}; {@code wParam} says which of its two slots. */
    public static final int WM_SETICON = 0x0080;
    /** {@code ICON_SMALL} — the caption and the taskbar's small views. */
    public static final int ICON_SMALL = 0;
    /** {@code ICON_BIG} — Alt-Tab and the taskbar's large views. */
    public static final int ICON_BIG = 1;

    /** {@code SM_CXICON} — the width Windows wants a large icon to be, on this display, at this scale. */
    public static final int SM_CXICON = 11;
    /** {@code SM_CXSMICON} — the same for a small icon. */
    public static final int SM_CXSMICON = 49;

    /** {@code ICONINFO} — fIcon, hotspot, then the mask and colour bitmaps. 32 bytes on x64. */
    public static final GroupLayout ICONINFO = MemoryLayout.structLayout(
            JAVA_INT.withName("fIcon"),
            JAVA_INT.withName("xHotspot"),
            JAVA_INT.withName("yHotspot"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hbmMask"),
            ADDRESS.withName("hbmColor")
    ).withName("ICONINFO");

    private static final VarHandle II_fIcon    = fieldHandle(ICONINFO, "fIcon");
    private static final VarHandle II_hbmMask  = fieldHandle(ICONINFO, "hbmMask");
    private static final VarHandle II_hbmColor = fieldHandle(ICONINFO, "hbmColor");

    private static final MethodHandle CreateIconIndirect = Ffi.downcall(LIB, "CreateIconIndirect",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle DestroyIcon = Ffi.downcall(LIB, "DestroyIcon",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));

    /**
     * Build an {@code HICON} from a colour bitmap and its mask. Both bitmaps are copied into the icon, so the
     * caller deletes them afterwards and the returned handle stands alone.
     *
     * @return the {@code HICON}; the caller owns it and must {@link #destroyIcon} it
     */
    public static MemorySegment createIconIndirect(MemorySegment colorBitmap, MemorySegment maskBitmap) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment info = temp.allocate(ICONINFO);
            II_fIcon.set(info, 1);   // TRUE — an icon, not a cursor; the hotspot fields are then ignored
            II_hbmMask.set(info, maskBitmap);
            II_hbmColor.set(info, colorBitmap);
            MemorySegment icon;
            try {
                icon = (MemorySegment) CreateIconIndirect.invokeExact(info);
            } catch (Throwable t) {
                throw NativeException.rethrow("CreateIconIndirect", t);
            }
            if (icon.equals(MemorySegment.NULL)) {
                throw new NativeException("CreateIconIndirect failed (GetLastError="
                        + Kernel32.getLastError() + ")");
            }
            return icon;
        }
    }

    /** Release an {@code HICON} created by {@link #createIconIndirect}. Null-safe. */
    public static void destroyIcon(MemorySegment icon) {
        if (icon.equals(MemorySegment.NULL)) {
            return;
        }
        try {
            int ignored = (int) DestroyIcon.invokeExact(icon);
        } catch (Throwable t) {
            throw NativeException.rethrow("DestroyIcon", t);
        }
    }


    // ---- Monitors -------------------------------------------------------------------------------------------

    /** {@code MonitorFromRect} flag: never fail — a rect on no monitor resolves to the closest one. */
    public static final int MONITOR_DEFAULTTONEAREST = 0x00000002;

    /** {@code MONITORINFO} — cbSize, the monitor rect, the work rect, flags. 40 bytes. */
    public static final GroupLayout MONITORINFO = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            RECT.withName("rcMonitor"),
            RECT.withName("rcWork"),
            JAVA_INT.withName("dwFlags")
    ).withName("MONITORINFO");

    private static final VarHandle MI_cbSize = fieldHandle(MONITORINFO, "cbSize");
    private static final long MI_RCWORK_OFFSET = MONITORINFO.byteOffset(PathElement.groupElement("rcWork"));

    private static final MethodHandle MonitorFromRect = Ffi.downcall(LIB, "MonitorFromRect",
            FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle GetMonitorInfoW = Ffi.downcall(LIB, "GetMonitorInfoW",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));

    /**
     * The {@code HMONITOR} for a screen {@code RECT}: the monitor it overlaps most, or the nearest one when it
     * overlaps none. {@code MONITOR_DEFAULTTONEAREST} is why this never returns null — an application restoring
     * bounds saved on a monitor since unplugged gets the screen that replaced it, not a failure to handle.
     */
    public static MemorySegment monitorFromRect(MemorySegment rect) {
        try {
            return (MemorySegment) MonitorFromRect.invokeExact(rect, MONITOR_DEFAULTTONEAREST);
        } catch (Throwable t) {
            throw NativeException.rethrow("MonitorFromRect", t);
        }
    }

    /**
     * Fill {@code info} (a {@code MONITORINFO}) for {@code hMonitor}, stamping {@code cbSize} first as the call
     * requires. Returns false if Windows declined — which the caller reports as "cannot say" rather than
     * throwing, because a work-area query is an improvement on letting the OS place the window, not a
     * prerequisite for it.
     */
    public static boolean getMonitorInfoW(MemorySegment hMonitor, MemorySegment info) {
        MI_cbSize.set(info, (int) MONITORINFO.byteSize());
        try {
            return (int) GetMonitorInfoW.invokeExact(hMonitor, info) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("GetMonitorInfoW", t);
        }
    }

    /** The {@code rcWork} member of a filled {@code MONITORINFO}, as a {@code RECT} view into it. */
    public static MemorySegment monitorWorkRect(MemorySegment info) {
        return info.asSlice(MI_RCWORK_OFFSET, RECT.byteSize());
    }

    /** Whether the window carries {@code WS_VISIBLE} — false while hidden with {@link #SW_HIDE}. */
    public static boolean isWindowVisible(MemorySegment hwnd) {
        try {
            return (int) IsWindowVisible.invokeExact(hwnd) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("IsWindowVisible", t);
        }
    }

    /**
     * Enable or disable input to a window. A disabled window takes no mouse or keyboard input and cannot be
     * activated — the mechanism behind a modal dialog: disable the owner while the dialog is up, and Windows
     * itself flashes the dialog when the user clicks the dead window.
     */
    public static void enableWindow(MemorySegment hwnd, boolean enabled) {
        try {
            int wasDisabled = (int) EnableWindow.invokeExact(hwnd, enabled ? 1 : 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("EnableWindow", t);
        }
    }

    /**
     * Bring a window to the front and give it the keyboard. All three calls, because none is sufficient alone:
     * {@code SetForegroundWindow} is the one Windows may refuse (a process that does not own the foreground
     * cannot steal it), {@code BringWindowToTop} still raises the window in that case, and
     * {@code SetActiveWindow} gives the keyboard within this thread's own windows, which is the common case here
     * — one application activating a window it already owns.
     */
    public static void focusWindow(MemorySegment hwnd) {
        try {
            int ignoredFg = (int) SetForegroundWindow.invokeExact(hwnd);
            int ignoredTop = (int) BringWindowToTop.invokeExact(hwnd);
            MemorySegment ignoredPrev = (MemorySegment) SetActiveWindow.invokeExact(hwnd);
        } catch (Throwable t) {
            throw NativeException.rethrow("SetForegroundWindow", t);
        }
    }
}
