package dev.vexelray.os.linux.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Binding for {@code libX11.so.6}. One library, one class (see {@code docs/native-bindings.md} §4).
 *
 * <p>On x86-64 and aarch64 Linux every X11 resource id ({@code Window}, {@code Atom}, {@code Cursor}, {@code Time},
 * {@code KeySym}) is an {@code unsigned long}, so all of them are {@code JAVA_LONG} here; a {@code Display*} is an
 * opaque {@link MemorySegment}.
 *
 * <p>Bound by its soname: {@code libX11.so} is a development symlink that only exists where {@code libx11-dev} is
 * installed.
 */
public final class X11 {

    private static final SymbolLookup LIB = Ffi.libraryFile("libX11.so.6");

    // ---- Event types (X.h) ---------------------------------------------------------------------------------------

    public static final int KeyPress = 2;
    public static final int KeyRelease = 3;
    public static final int ButtonPress = 4;
    public static final int ButtonRelease = 5;
    public static final int MotionNotify = 6;
    public static final int FocusIn = 9;
    public static final int FocusOut = 10;
    public static final int Expose = 12;
    public static final int DestroyNotify = 17;
    public static final int UnmapNotify = 18;
    public static final int MapNotify = 19;
    public static final int ConfigureNotify = 22;
    public static final int PropertyNotify = 28;
    public static final int ClientMessage = 33;

    // ---- Event masks (X.h) ---------------------------------------------------------------------------------------

    public static final long KeyPressMask = 1L;
    public static final long KeyReleaseMask = 1L << 1;
    public static final long ButtonPressMask = 1L << 2;
    public static final long ButtonReleaseMask = 1L << 3;
    public static final long PointerMotionMask = 1L << 6;
    public static final long ExposureMask = 1L << 15;
    public static final long StructureNotifyMask = 1L << 17;
    public static final long SubstructureNotifyMask = 1L << 19;
    public static final long SubstructureRedirectMask = 1L << 20;
    public static final long FocusChangeMask = 1L << 21;
    public static final long PropertyChangeMask = 1L << 22;

    // ---- Miscellaneous constants ---------------------------------------------------------------------------------

    public static final int PropModeReplace = 0;
    public static final int RevertToParent = 2;
    public static final long CurrentTime = 0L;
    public static final long None = 0L;
    public static final int Success = 0;
    public static final long XA_ATOM = 4L;
    public static final long XA_CARDINAL = 6L;

    /** {@code XSizeHints.flags}: the minimum size is set. */
    public static final long PMinSize = 1L << 4;

    /** Cursor-font glyphs (cursorfont.h). */
    public static final int XC_left_ptr = 68;
    public static final int XC_sb_h_double_arrow = 108;
    public static final int XC_sb_v_double_arrow = 116;
    public static final int XC_xterm = 152;

    // ---- Struct layouts. Offsets come from these, never from a literal (§4.3). -----------------------------------

    /** {@code XEvent} is a union padded to 24 longs; every member below is read out of a buffer this size. */
    public static final long XEVENT_SIZE = 24 * JAVA_LONG.byteSize();

    /** {@code XAnyEvent}: the header every event shares. */
    public static final GroupLayout XAnyEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("window")
    ).withName("XAnyEvent");

    /** {@code XKeyEvent}, which {@code XButtonEvent} and {@code XMotionEvent} share field for field. */
    public static final GroupLayout XKeyEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("window"),
            JAVA_LONG.withName("root"),
            JAVA_LONG.withName("subwindow"),
            JAVA_LONG.withName("time"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("x_root"),
            JAVA_INT.withName("y_root"),
            JAVA_INT.withName("state"),
            JAVA_INT.withName("keycode"),     // "button" in XButtonEvent, "is_hint" (a char) in XMotionEvent
            JAVA_INT.withName("same_screen"),
            MemoryLayout.paddingLayout(4)
    ).withName("XKeyEvent");

    /** {@code XConfigureEvent}. */
    public static final GroupLayout XConfigureEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("event"),
            JAVA_LONG.withName("window"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("border_width"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("above"),
            JAVA_INT.withName("override_redirect"),
            MemoryLayout.paddingLayout(4)
    ).withName("XConfigureEvent");

    /** {@code XClientMessageEvent}, with its data as the five longs of {@code data.l}. */
    public static final GroupLayout XClientMessageEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("window"),
            JAVA_LONG.withName("message_type"),
            JAVA_INT.withName("format"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("l0"),
            JAVA_LONG.withName("l1"),
            JAVA_LONG.withName("l2"),
            JAVA_LONG.withName("l3"),
            JAVA_LONG.withName("l4")
    ).withName("XClientMessageEvent");

    /** {@code XPropertyEvent}. */
    public static final GroupLayout XPropertyEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("serial"),
            JAVA_INT.withName("send_event"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("window"),
            JAVA_LONG.withName("atom"),
            JAVA_LONG.withName("time"),
            JAVA_INT.withName("state"),
            MemoryLayout.paddingLayout(4)
    ).withName("XPropertyEvent");

    /** {@code XErrorEvent}, as handed to an error handler. */
    public static final GroupLayout XErrorEvent = MemoryLayout.structLayout(
            JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("display"),
            JAVA_LONG.withName("resourceid"),
            JAVA_LONG.withName("serial"),
            JAVA_BYTE.withName("error_code"),
            JAVA_BYTE.withName("request_code"),
            JAVA_BYTE.withName("minor_code"),
            MemoryLayout.paddingLayout(5)
    ).withName("XErrorEvent");

    /** {@code XSizeHints}. */
    public static final GroupLayout XSizeHints = MemoryLayout.structLayout(
            JAVA_LONG.withName("flags"),
            JAVA_INT.withName("x"),
            JAVA_INT.withName("y"),
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"),
            JAVA_INT.withName("min_width"),
            JAVA_INT.withName("min_height"),
            JAVA_INT.withName("max_width"),
            JAVA_INT.withName("max_height"),
            JAVA_INT.withName("width_inc"),
            JAVA_INT.withName("height_inc"),
            JAVA_INT.withName("min_aspect_x"),
            JAVA_INT.withName("min_aspect_y"),
            JAVA_INT.withName("max_aspect_x"),
            JAVA_INT.withName("max_aspect_y"),
            JAVA_INT.withName("base_width"),
            JAVA_INT.withName("base_height"),
            JAVA_INT.withName("win_gravity"),
            MemoryLayout.paddingLayout(4)
    ).withName("XSizeHints");

    public static final VarHandle ANY_type = Ffi.field(XAnyEvent, "type");
    public static final VarHandle ANY_window = Ffi.field(XAnyEvent, "window");

    public static final VarHandle KEY_window = Ffi.field(XKeyEvent, "window");
    public static final VarHandle KEY_x = Ffi.field(XKeyEvent, "x");
    public static final VarHandle KEY_y = Ffi.field(XKeyEvent, "y");
    public static final VarHandle KEY_x_root = Ffi.field(XKeyEvent, "x_root");
    public static final VarHandle KEY_y_root = Ffi.field(XKeyEvent, "y_root");
    public static final VarHandle KEY_state = Ffi.field(XKeyEvent, "state");
    public static final VarHandle KEY_keycode = Ffi.field(XKeyEvent, "keycode");
    public static final VarHandle KEY_time = Ffi.field(XKeyEvent, "time");

    public static final VarHandle CONFIGURE_window = Ffi.field(XConfigureEvent, "window");
    public static final VarHandle CONFIGURE_x = Ffi.field(XConfigureEvent, "x");
    public static final VarHandle CONFIGURE_y = Ffi.field(XConfigureEvent, "y");
    public static final VarHandle CONFIGURE_width = Ffi.field(XConfigureEvent, "width");
    public static final VarHandle CONFIGURE_height = Ffi.field(XConfigureEvent, "height");
    public static final VarHandle CONFIGURE_send_event = Ffi.field(XConfigureEvent, "send_event");

    public static final VarHandle CLIENT_type = Ffi.field(XClientMessageEvent, "type");
    public static final VarHandle CLIENT_window = Ffi.field(XClientMessageEvent, "window");
    public static final VarHandle CLIENT_message_type = Ffi.field(XClientMessageEvent, "message_type");
    public static final VarHandle CLIENT_format = Ffi.field(XClientMessageEvent, "format");
    public static final VarHandle CLIENT_l0 = Ffi.field(XClientMessageEvent, "l0");
    public static final VarHandle CLIENT_l1 = Ffi.field(XClientMessageEvent, "l1");
    public static final VarHandle CLIENT_l2 = Ffi.field(XClientMessageEvent, "l2");
    public static final VarHandle CLIENT_l3 = Ffi.field(XClientMessageEvent, "l3");
    public static final VarHandle CLIENT_l4 = Ffi.field(XClientMessageEvent, "l4");

    public static final VarHandle PROPERTY_atom = Ffi.field(XPropertyEvent, "atom");

    public static final VarHandle ERROR_error_code = Ffi.field(XErrorEvent, "error_code");
    public static final VarHandle ERROR_request_code = Ffi.field(XErrorEvent, "request_code");
    public static final VarHandle ERROR_resourceid = Ffi.field(XErrorEvent, "resourceid");

    public static final VarHandle SIZE_flags = Ffi.field(XSizeHints, "flags");
    public static final VarHandle SIZE_min_width = Ffi.field(XSizeHints, "min_width");
    public static final VarHandle SIZE_min_height = Ffi.field(XSizeHints, "min_height");

    // ---- Downcalls, one per C function, named like the C symbol -------------------------------------------------

    private static final MethodHandle XInitThreads = Ffi.downcall(LIB, "XInitThreads",
            FunctionDescriptor.of(JAVA_INT));
    private static final MethodHandle XOpenDisplay = Ffi.downcall(LIB, "XOpenDisplay",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle XCloseDisplay = Ffi.downcall(LIB, "XCloseDisplay",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XDefaultScreen = Ffi.downcall(LIB, "XDefaultScreen",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XRootWindow = Ffi.downcall(LIB, "XRootWindow",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle XBlackPixel = Ffi.downcall(LIB, "XBlackPixel",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle XDisplayWidth = Ffi.downcall(LIB, "XDisplayWidth",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle XDisplayHeight = Ffi.downcall(LIB, "XDisplayHeight",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle XConnectionNumber = Ffi.downcall(LIB, "XConnectionNumber",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XCreateSimpleWindow = Ffi.downcall(LIB, "XCreateSimpleWindow",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    JAVA_LONG, JAVA_LONG));
    private static final MethodHandle XDestroyWindow = Ffi.downcall(LIB, "XDestroyWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle XSelectInput = Ffi.downcall(LIB, "XSelectInput",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle XMapWindow = Ffi.downcall(LIB, "XMapWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle XUnmapWindow = Ffi.downcall(LIB, "XUnmapWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle XRaiseWindow = Ffi.downcall(LIB, "XRaiseWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle XIconifyWindow = Ffi.downcall(LIB, "XIconifyWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle XMoveWindow = Ffi.downcall(LIB, "XMoveWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT));
    private static final MethodHandle XMoveResizeWindow = Ffi.downcall(LIB, "XMoveResizeWindow",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
    private static final MethodHandle XSetInputFocus = Ffi.downcall(LIB, "XSetInputFocus",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG));
    private static final MethodHandle XStoreName = Ffi.downcall(LIB, "XStoreName",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle XInternAtom = Ffi.downcall(LIB, "XInternAtom",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, ADDRESS, JAVA_INT));
    private static final MethodHandle XSetWMProtocols = Ffi.downcall(LIB, "XSetWMProtocols",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle XSetTransientForHint = Ffi.downcall(LIB, "XSetTransientForHint",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle XChangeProperty = Ffi.downcall(LIB, "XChangeProperty",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS,
                    JAVA_INT));
    private static final MethodHandle XDeleteProperty = Ffi.downcall(LIB, "XDeleteProperty",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle XGetWindowProperty = Ffi.downcall(LIB, "XGetWindowProperty",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_LONG,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle XSendEvent = Ffi.downcall(LIB, "XSendEvent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, ADDRESS));
    private static final MethodHandle XPending = Ffi.downcall(LIB, "XPending",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XNextEvent = Ffi.downcall(LIB, "XNextEvent",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle XFlush = Ffi.downcall(LIB, "XFlush",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XTranslateCoordinates = Ffi.downcall(LIB, "XTranslateCoordinates",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS,
                    ADDRESS));
    private static final MethodHandle XUngrabPointer = Ffi.downcall(LIB, "XUngrabPointer",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle XCreateFontCursor = Ffi.downcall(LIB, "XCreateFontCursor",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle XDefineCursor = Ffi.downcall(LIB, "XDefineCursor",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG));
    private static final MethodHandle XLookupKeysym = Ffi.downcall(LIB, "XLookupKeysym",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT));
    private static final MethodHandle XSetWMNormalHints = Ffi.downcall(LIB, "XSetWMNormalHints",
            FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
    private static final MethodHandle XFree = Ffi.downcall(LIB, "XFree",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle XSetErrorHandler = Ffi.downcall(LIB, "XSetErrorHandler",
            FunctionDescriptor.of(ADDRESS, ADDRESS));

    /** {@code int (*)(Display*, XErrorEvent*)}. */
    private static final FunctionDescriptor ERROR_HANDLER = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS);

    private X11() {
    }

    // ---- Typed wrappers -----------------------------------------------------------------------------------------

    /** Make Xlib safe to call from several threads; must precede every other Xlib call in the process. */
    public static void initThreads() {
        try {
            int ignored = (int) XInitThreads.invokeExact();
        } catch (Throwable t) {
            throw NativeException.rethrow("XInitThreads", t);
        }
    }

    /** The default display ({@code $DISPLAY}), or {@link MemorySegment#NULL} when there is none. */
    public static MemorySegment openDisplay() {
        try {
            return (MemorySegment) XOpenDisplay.invokeExact(MemorySegment.NULL);
        } catch (Throwable t) {
            throw NativeException.rethrow("XOpenDisplay", t);
        }
    }

    public static void closeDisplay(MemorySegment display) {
        try {
            int ignored = (int) XCloseDisplay.invokeExact(display);
        } catch (Throwable t) {
            throw NativeException.rethrow("XCloseDisplay", t);
        }
    }

    public static int defaultScreen(MemorySegment display) {
        try {
            return (int) XDefaultScreen.invokeExact(display);
        } catch (Throwable t) {
            throw NativeException.rethrow("XDefaultScreen", t);
        }
    }

    public static long rootWindow(MemorySegment display, int screen) {
        try {
            return (long) XRootWindow.invokeExact(display, screen);
        } catch (Throwable t) {
            throw NativeException.rethrow("XRootWindow", t);
        }
    }

    public static long blackPixel(MemorySegment display, int screen) {
        try {
            return (long) XBlackPixel.invokeExact(display, screen);
        } catch (Throwable t) {
            throw NativeException.rethrow("XBlackPixel", t);
        }
    }

    public static int displayWidth(MemorySegment display, int screen) {
        try {
            return (int) XDisplayWidth.invokeExact(display, screen);
        } catch (Throwable t) {
            throw NativeException.rethrow("XDisplayWidth", t);
        }
    }

    public static int displayHeight(MemorySegment display, int screen) {
        try {
            return (int) XDisplayHeight.invokeExact(display, screen);
        } catch (Throwable t) {
            throw NativeException.rethrow("XDisplayHeight", t);
        }
    }

    /** The file descriptor of the connection to the X server, for {@code poll}. */
    public static int connectionNumber(MemorySegment display) {
        try {
            return (int) XConnectionNumber.invokeExact(display);
        } catch (Throwable t) {
            throw NativeException.rethrow("XConnectionNumber", t);
        }
    }

    public static long createSimpleWindow(MemorySegment display, long parent, int x, int y, int width, int height,
                                          int borderWidth, long border, long background) {
        try {
            long window = (long) XCreateSimpleWindow.invokeExact(display, parent, x, y, width, height, borderWidth,
                    border, background);
            if (window == None) {
                throw new NativeException("XCreateSimpleWindow returned None");
            }
            return window;
        } catch (Throwable t) {
            throw NativeException.rethrow("XCreateSimpleWindow", t);
        }
    }

    public static void destroyWindow(MemorySegment display, long window) {
        try {
            int ignored = (int) XDestroyWindow.invokeExact(display, window);
        } catch (Throwable t) {
            throw NativeException.rethrow("XDestroyWindow", t);
        }
    }

    public static void selectInput(MemorySegment display, long window, long mask) {
        try {
            int ignored = (int) XSelectInput.invokeExact(display, window, mask);
        } catch (Throwable t) {
            throw NativeException.rethrow("XSelectInput", t);
        }
    }

    public static void mapWindow(MemorySegment display, long window) {
        try {
            int ignored = (int) XMapWindow.invokeExact(display, window);
        } catch (Throwable t) {
            throw NativeException.rethrow("XMapWindow", t);
        }
    }

    public static void unmapWindow(MemorySegment display, long window) {
        try {
            int ignored = (int) XUnmapWindow.invokeExact(display, window);
        } catch (Throwable t) {
            throw NativeException.rethrow("XUnmapWindow", t);
        }
    }

    public static void raiseWindow(MemorySegment display, long window) {
        try {
            int ignored = (int) XRaiseWindow.invokeExact(display, window);
        } catch (Throwable t) {
            throw NativeException.rethrow("XRaiseWindow", t);
        }
    }

    public static void iconifyWindow(MemorySegment display, long window, int screen) {
        try {
            int ignored = (int) XIconifyWindow.invokeExact(display, window, screen);
        } catch (Throwable t) {
            throw NativeException.rethrow("XIconifyWindow", t);
        }
    }

    public static void moveWindow(MemorySegment display, long window, int x, int y) {
        try {
            int ignored = (int) XMoveWindow.invokeExact(display, window, x, y);
        } catch (Throwable t) {
            throw NativeException.rethrow("XMoveWindow", t);
        }
    }

    public static void moveResizeWindow(MemorySegment display, long window, int x, int y, int width, int height) {
        try {
            int ignored = (int) XMoveResizeWindow.invokeExact(display, window, x, y, width, height);
        } catch (Throwable t) {
            throw NativeException.rethrow("XMoveResizeWindow", t);
        }
    }

    public static void setInputFocus(MemorySegment display, long window, int revertTo, long time) {
        try {
            int ignored = (int) XSetInputFocus.invokeExact(display, window, revertTo, time);
        } catch (Throwable t) {
            throw NativeException.rethrow("XSetInputFocus", t);
        }
    }

    /** The legacy {@code WM_NAME}, Latin-1; window managers prefer {@code _NET_WM_NAME}, which callers set too. */
    public static void storeName(MemorySegment display, long window, String name) {
        try (Arena temp = Arena.ofConfined()) {
            int ignored = (int) XStoreName.invokeExact(display, window,
                    temp.allocateFrom(name, StandardCharsets.ISO_8859_1));
        } catch (Throwable t) {
            throw NativeException.rethrow("XStoreName", t);
        }
    }

    public static long internAtom(MemorySegment display, String name) {
        try (Arena temp = Arena.ofConfined()) {
            return (long) XInternAtom.invokeExact(display, temp.allocateFrom(name), 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("XInternAtom(" + name + ")", t);
        }
    }

    public static void setWMProtocols(MemorySegment display, long window, long... protocols) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment atoms = temp.allocateFrom(JAVA_LONG, protocols);
            int ignored = (int) XSetWMProtocols.invokeExact(display, window, atoms, protocols.length);
        } catch (Throwable t) {
            throw NativeException.rethrow("XSetWMProtocols", t);
        }
    }

    public static void setTransientForHint(MemorySegment display, long window, long owner) {
        try {
            int ignored = (int) XSetTransientForHint.invokeExact(display, window, owner);
        } catch (Throwable t) {
            throw NativeException.rethrow("XSetTransientForHint", t);
        }
    }

    /**
     * {@code XChangeProperty} with {@link #PropModeReplace}. {@code data} holds {@code count} elements of
     * {@code format} bits each; format 32 means C {@code long}s, which is 64 bits wide here (an Xlib quirk).
     */
    public static void changeProperty(MemorySegment display, long window, long property, long type, int format,
                                      MemorySegment data, int count) {
        try {
            int ignored = (int) XChangeProperty.invokeExact(display, window, property, type, format,
                    PropModeReplace, data, count);
        } catch (Throwable t) {
            throw NativeException.rethrow("XChangeProperty", t);
        }
    }

    public static void deleteProperty(MemorySegment display, long window, long property) {
        try {
            int ignored = (int) XDeleteProperty.invokeExact(display, window, property);
        } catch (Throwable t) {
            throw NativeException.rethrow("XDeleteProperty", t);
        }
    }

    /**
     * A format-32 property of {@code type} as an array of longs, or an empty array when it is absent. Reads at most
     * {@code maxItems} elements.
     */
    public static long[] getLongProperty(MemorySegment display, long window, long property, long type, int maxItems) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment actualType = temp.allocate(JAVA_LONG);
            MemorySegment actualFormat = temp.allocate(JAVA_INT);
            MemorySegment nItems = temp.allocate(JAVA_LONG);
            MemorySegment bytesAfter = temp.allocate(JAVA_LONG);
            MemorySegment data = temp.allocate(ADDRESS);
            int status = (int) XGetWindowProperty.invokeExact(display, window, property, 0L, (long) maxItems, 0,
                    type, actualType, actualFormat, nItems, bytesAfter, data);
            MemorySegment values = data.get(ADDRESS, 0);
            if (status != Success || values.equals(MemorySegment.NULL)) {
                return new long[0];
            }
            try {
                long count = nItems.get(JAVA_LONG, 0);
                if (actualFormat.get(JAVA_INT, 0) != 32 || count <= 0) {
                    return new long[0];
                }
                return values.reinterpret(count * JAVA_LONG.byteSize()).toArray(JAVA_LONG);
            } finally {
                free(values);
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("XGetWindowProperty", t);
        }
    }

    public static boolean sendEvent(MemorySegment display, long window, boolean propagate, long mask,
                                    MemorySegment event) {
        try {
            return (int) XSendEvent.invokeExact(display, window, propagate ? 1 : 0, mask, event) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("XSendEvent", t);
        }
    }

    /** The number of events already queued, after flushing and reading whatever the connection has. */
    public static int pending(MemorySegment display) {
        try {
            return (int) XPending.invokeExact(display);
        } catch (Throwable t) {
            throw NativeException.rethrow("XPending", t);
        }
    }

    /** The next event into {@code event} (an {@link #XEVENT_SIZE} buffer); blocks if none is queued. */
    public static void nextEvent(MemorySegment display, MemorySegment event) {
        try {
            int ignored = (int) XNextEvent.invokeExact(display, event);
        } catch (Throwable t) {
            throw NativeException.rethrow("XNextEvent", t);
        }
    }

    public static void flush(MemorySegment display) {
        try {
            int ignored = (int) XFlush.invokeExact(display);
        } catch (Throwable t) {
            throw NativeException.rethrow("XFlush", t);
        }
    }

    /** {@code (x, y)} in {@code src} translated into {@code dst}'s coordinates, or null if they share no screen. */
    public static int[] translateCoordinates(MemorySegment display, long src, long dst, int x, int y) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment dx = temp.allocate(JAVA_INT);
            MemorySegment dy = temp.allocate(JAVA_INT);
            MemorySegment child = temp.allocate(JAVA_LONG);
            int sameScreen = (int) XTranslateCoordinates.invokeExact(display, src, dst, x, y, dx, dy, child);
            return sameScreen == 0 ? null : new int[]{dx.get(JAVA_INT, 0), dy.get(JAVA_INT, 0)};
        } catch (Throwable t) {
            throw NativeException.rethrow("XTranslateCoordinates", t);
        }
    }

    public static void ungrabPointer(MemorySegment display, long time) {
        try {
            int ignored = (int) XUngrabPointer.invokeExact(display, time);
        } catch (Throwable t) {
            throw NativeException.rethrow("XUngrabPointer", t);
        }
    }

    public static long createFontCursor(MemorySegment display, int shape) {
        try {
            return (long) XCreateFontCursor.invokeExact(display, shape);
        } catch (Throwable t) {
            throw NativeException.rethrow("XCreateFontCursor", t);
        }
    }

    public static void defineCursor(MemorySegment display, long window, long cursor) {
        try {
            int ignored = (int) XDefineCursor.invokeExact(display, window, cursor);
        } catch (Throwable t) {
            throw NativeException.rethrow("XDefineCursor", t);
        }
    }

    /** The unshifted keysym of a key event (index 0 of its keycode's mapping). */
    public static long lookupKeysym(MemorySegment keyEvent) {
        try {
            return (long) XLookupKeysym.invokeExact(keyEvent, 0);
        } catch (Throwable t) {
            throw NativeException.rethrow("XLookupKeysym", t);
        }
    }

    public static void setWMNormalHints(MemorySegment display, long window, MemorySegment hints) {
        try {
            XSetWMNormalHints.invokeExact(display, window, hints);
        } catch (Throwable t) {
            throw NativeException.rethrow("XSetWMNormalHints", t);
        }
    }

    public static void free(MemorySegment data) {
        try {
            int ignored = (int) XFree.invokeExact(data);
        } catch (Throwable t) {
            throw NativeException.rethrow("XFree", t);
        }
    }

    /**
     * Replace Xlib's default error handler, which prints and <em>exits the process</em>, with one that reports the
     * error on stderr and carries on. Asynchronous X errors are routine (a request racing a window the window manager
     * has just destroyed); none of them is a reason to end the application. The stub lives as long as the process.
     */
    public static void installErrorHandler() {
        MemorySegment stub = Ffi.upcall(MethodHandles.lookup(), X11.class, "onError", ERROR_HANDLER, Ffi.GLOBAL);
        try {
            MemorySegment ignored = (MemorySegment) XSetErrorHandler.invokeExact(stub);
        } catch (Throwable t) {
            throw NativeException.rethrow("XSetErrorHandler", t);
        }
    }

    /** The error handler upcall. Must not throw (§4.5): an exception escaping an upcall crashes the JVM. */
    private static int onError(MemorySegment display, MemorySegment event) {
        try {
            MemorySegment e = event.reinterpret(XErrorEvent.byteSize());
            System.err.println("vexelray: X error " + Byte.toUnsignedInt((byte) ERROR_error_code.get(e))
                    + " (request " + Byte.toUnsignedInt((byte) ERROR_request_code.get(e)) + ", resource 0x"
                    + Long.toHexString((long) ERROR_resourceid.get(e)) + ")");
        } catch (Throwable ignored) {
            // Nothing may escape an upcall.
        }
        return 0;
    }
}
