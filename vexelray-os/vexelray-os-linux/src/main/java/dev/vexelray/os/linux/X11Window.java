package dev.vexelray.os.linux;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;
import dev.vexelray.os.BackgroundRun;
import dev.vexelray.os.Decorations;
import dev.vexelray.os.HitRegions;
import dev.vexelray.os.Icon;
import dev.vexelray.os.Key;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.linux.sys.Libc;
import dev.vexelray.os.linux.sys.X11;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A {@link NativeWindow} on X11 through {@code libX11}, with a {@code VkSurfaceKHR} from {@code VK_KHR_xlib_surface}.
 * Under a Wayland session it runs on XWayland.
 *
 * <p><b>One connection for the process.</b> Every window shares one {@code Display*}, opened on first use, and is
 * driven from the main thread like the Win32 backend. Any window's {@link #pumpEvents} drains the shared queue and
 * routes each event to the window it names, so a process with several windows loses none of their events whichever
 * one the host happens to pump.
 *
 * <p><b>Background runs.</b> X11 has nothing like DWM cloaking: a mapped window is a visible one. So a background
 * run's window is never mapped. It is otherwise fully real — it has its surface, its swapchain and its frames — and
 * {@link #isVisible} reports what the host asked for, so the host behaves as it would on screen. The first one posts
 * the run's notification ({@link BackgroundNotice}), the person's way to end it, as the tray icon is on Windows.
 *
 * <p><b>Client-drawn chrome.</b> A {@link Decorations#CLIENT} or {@link Decorations#NONE} window asks the window manager
 * for no frame ({@code _MOTIF_WM_HINTS}). A press in a caption or resize zone of the {@link HitRegions} is handed to the
 * window manager as an {@code _NET_WM_MOVERESIZE}, so moving, resizing and snapping are the window manager's own.
 */
final class X11Window implements NativeWindow {

    /** Whether every window of this process is a background run's: real, but never mapped. */
    private static final boolean BACKGROUND = BackgroundRun.active();

    /** The resize band where the hit regions leave it to the platform ({@code resizeBorder == 0}). */
    private static final int DEFAULT_RESIZE_BORDER = 6;

    /** Two presses on the caption closer than this toggle maximize, as a double-click on a title bar does. */
    private static final long DOUBLE_CLICK_MILLIS = 400;

    // _NET_WM_MOVERESIZE directions (EWMH).
    private static final long MOVERESIZE_MOVE = 8;
    private static final Map<HitRegions.Zone, Long> MOVERESIZE_DIRECTION = new EnumMap<>(HitRegions.Zone.class);

    static {
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.TOP_LEFT, 0L);
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.TOP, 1L);
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.TOP_RIGHT, 2L);
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.RIGHT, 3L);
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.BOTTOM_RIGHT, 4L);
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.BOTTOM, 5L);
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.BOTTOM_LEFT, 6L);
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.LEFT, 7L);
        MOVERESIZE_DIRECTION.put(HitRegions.Zone.CAPTION, MOVERESIZE_MOVE);
    }

    // _NET_WM_STATE actions.
    private static final long STATE_REMOVE = 0;
    private static final long STATE_ADD = 1;

    // X11 keysyms (keysymdef.h) for the keys NativeWindow#isKeyDown answers.
    private static final Map<Long, Key> KEY_BY_KEYSYM = Map.ofEntries(
            Map.entry((long) 'w', Key.W), Map.entry((long) 'a', Key.A), Map.entry((long) 's', Key.S),
            Map.entry((long) 'd', Key.D), Map.entry((long) 'q', Key.Q), Map.entry((long) 'e', Key.E),
            Map.entry(0xFF52L, Key.UP), Map.entry(0xFF54L, Key.DOWN), Map.entry(0xFF51L, Key.LEFT),
            Map.entry(0xFF53L, Key.RIGHT), Map.entry(0x20L, Key.SPACE), Map.entry(0xFFE1L, Key.SHIFT),
            Map.entry(0xFFE2L, Key.SHIFT), Map.entry(0xFF1BL, Key.ESCAPE));

    // ---- VK_KHR_xlib_surface ----------------------------------------------------------------------------------------

    private static final int VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR = 1000004000;
    private static final int VK_SUCCESS = 0;

    private static final GroupLayout VK_XLIB_SURFACE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("dpy"),
            JAVA_LONG.withName("window")
    ).withName("VkXlibSurfaceCreateInfoKHR");

    private static final VarHandle SCI_sType = Ffi.field(VK_XLIB_SURFACE_CREATE_INFO, "sType");
    private static final VarHandle SCI_dpy = Ffi.field(VK_XLIB_SURFACE_CREATE_INFO, "dpy");
    private static final VarHandle SCI_window = Ffi.field(VK_XLIB_SURFACE_CREATE_INFO, "window");

    // ---- The process's connection ------------------------------------------------------------------------------------

    private static Connection connection;

    /** Every live window by XID, for routing the shared event queue. */
    private static final Map<Long, X11Window> WINDOWS = new ConcurrentHashMap<>();

    /** The application icon, worn by every window that has none of its own. */
    private static volatile Icon applicationIcon;

    /** The shared display, its atoms, and the self-pipe that ends a {@link #waitEvents} from another thread. */
    record Connection(MemorySegment display, int screen, long root, int fd, int wakeRead, int wakeWrite,
                              Atoms atoms, MemorySegment event) {
    }

    private record Atoms(long wmProtocols, long wmDeleteWindow, long netWmName, long utf8String, long motifWmHints,
                         long netWmState, long netWmStateMaximizedVert, long netWmStateMaximizedHorz,
                         long netWmStateFullscreen, long netWmStateHidden, long netWmMoveresize,
                         long netActiveWindow, long netWmIcon, long netFrameExtents, long netRequestFrameExtents,
                         long netWorkarea, long netWmPid) {

        static Atoms intern(MemorySegment d) {
            return new Atoms(
                    X11.internAtom(d, "WM_PROTOCOLS"), X11.internAtom(d, "WM_DELETE_WINDOW"),
                    X11.internAtom(d, "_NET_WM_NAME"), X11.internAtom(d, "UTF8_STRING"),
                    X11.internAtom(d, "_MOTIF_WM_HINTS"), X11.internAtom(d, "_NET_WM_STATE"),
                    X11.internAtom(d, "_NET_WM_STATE_MAXIMIZED_VERT"),
                    X11.internAtom(d, "_NET_WM_STATE_MAXIMIZED_HORZ"),
                    X11.internAtom(d, "_NET_WM_STATE_FULLSCREEN"), X11.internAtom(d, "_NET_WM_STATE_HIDDEN"),
                    X11.internAtom(d, "_NET_WM_MOVERESIZE"), X11.internAtom(d, "_NET_ACTIVE_WINDOW"),
                    X11.internAtom(d, "_NET_WM_ICON"), X11.internAtom(d, "_NET_FRAME_EXTENTS"),
                    X11.internAtom(d, "_NET_REQUEST_FRAME_EXTENTS"), X11.internAtom(d, "_NET_WORKAREA"),
                    X11.internAtom(d, "_NET_WM_PID"));
        }
    }

    /** The process's connection, opened on first use. Main thread only, like every window. */
    static synchronized Connection connection() {
        if (connection == null) {
            X11.initThreads();
            MemorySegment display = X11.openDisplay();
            if (display.equals(MemorySegment.NULL)) {
                throw new NativeException("XOpenDisplay failed: no X display (is $DISPLAY set?)");
            }
            X11.installErrorHandler();
            int screen = X11.defaultScreen(display);
            int[] wake = Libc.pipe();
            connection = new Connection(display, screen, X11.rootWindow(display, screen),
                    X11.connectionNumber(display), wake[0], wake[1], Atoms.intern(display),
                    Arena.global().allocate(X11.XEVENT_SIZE, 8));
            if (BACKGROUND) {
                // Said once, where the log starts. The person's handle on the run is the notification the first
                // window posts (BackgroundNotice); this is the agent's, and the fallback where there is none.
                System.err.println("vexelray: background run (" + "pid " + ProcessHandle.current().pid()
                        + "): its windows are never mapped. End it from its notification, from the terminal that"
                        + " started it, or with kill.");
            }
        }
        return connection;
    }

    static void setApplicationIcon(Icon icon) {
        applicationIcon = icon;
        for (X11Window w : WINDOWS.values()) {
            if (w.icon == null) {
                w.applyIcon();
            }
        }
    }

    // ---- This window ------------------------------------------------------------------------------------------------

    private final Connection c;
    private final MemorySegment display;
    private final long window;
    private final Decorations decorations;
    private final Arena arena = Arena.ofShared();

    private volatile int width;
    private volatile int height;
    private int screenX;
    private int screenY;
    private int frameLeft;
    private int frameRight;
    private int frameTop;
    private int frameBottom;

    private volatile boolean closeRequested;
    private boolean closeFromWindowManager;
    private boolean destroyed;
    private boolean visible;
    private boolean mapped;
    private volatile boolean focused;
    private boolean maximized;
    private boolean fullscreen;
    private boolean minimized;

    private volatile HitRegions hitRegions = HitRegions.NONE;
    private long lastCaptionPress;
    private Cursor cursor = Cursor.ARROW;
    private final Map<Cursor, Long> cursors = new EnumMap<>(Cursor.class);
    private final Set<Key> keysDown = EnumSet.noneOf(Key.class);
    private Icon icon;

    X11Window(WindowConfig config) {
        this.c = connection();
        this.display = c.display();
        if (BACKGROUND) {
            BackgroundNotice.show(config.title());
        }
        this.decorations = config.decorations();
        this.icon = config.icon();

        // The WindowConfig size is the outer rect. A frameless window's outer rect is its client area; a framed one
        // is smaller by the frame the window manager is about to add, which it reports ahead of mapping when asked.
        boolean framed = decorations == Decorations.SYSTEM;
        int x = config.x() == WindowConfig.UNPOSITIONED ? 0 : config.x();
        int y = config.y() == WindowConfig.UNPOSITIONED ? 0 : config.y();
        this.window = X11.createSimpleWindow(display, c.root(), x, y, config.width(), config.height(), 0,
                0L, X11.blackPixel(display, c.screen()));
        WINDOWS.put(window, this);

        X11.selectInput(display, window, X11.StructureNotifyMask | X11.FocusChangeMask | X11.PropertyChangeMask
                | X11.ExposureMask | X11.KeyPressMask | X11.KeyReleaseMask | X11.ButtonPressMask
                | X11.ButtonReleaseMask);
        X11.setWMProtocols(display, window, c.atoms().wmDeleteWindow());
        setTitle(config.title());
        setPid();
        if (!framed) {
            removeFrame();
        }
        if (config.owner() != 0L) {
            X11.setTransientForHint(display, window, config.owner());
        }
        if (framed) {
            readFrameExtentsAhead();
            int clientW = Math.max(1, config.width() - frameLeft - frameRight);
            int clientH = Math.max(1, config.height() - frameTop - frameBottom);
            X11.moveResizeWindow(display, window, x, y, clientW, clientH);
        }
        setMinSize(config, framed);
        applyIcon();

        this.width = framed ? Math.max(1, config.width() - frameLeft - frameRight) : config.width();
        this.height = framed ? Math.max(1, config.height() - frameTop - frameBottom) : config.height();
        this.screenX = x;
        this.screenY = y;
        X11.flush(display);

        if (!config.hidden()) {
            show();
        }
    }

    private void setTitle(String title) {
        X11.storeName(display, window, title);
        try (Arena temp = Arena.ofConfined()) {
            byte[] utf8 = title.getBytes(StandardCharsets.UTF_8);
            MemorySegment data = temp.allocateFrom(JAVA_BYTE, utf8);
            X11.changeProperty(display, window, c.atoms().netWmName(), c.atoms().utf8String(), 8, data, utf8.length);
        }
    }

    private void setPid() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pid = temp.allocateFrom(JAVA_LONG, ProcessHandle.current().pid());
            X11.changeProperty(display, window, c.atoms().netWmPid(), X11.XA_CARDINAL, 32, pid, 1);
        }
    }

    /** {@code _MOTIF_WM_HINTS} with no decorations: the one hint every X window manager honours for "no frame". */
    private void removeFrame() {
        try (Arena temp = Arena.ofConfined()) {
            long motifHintsDecorations = 1L << 1;
            MemorySegment hints = temp.allocateFrom(JAVA_LONG, motifHintsDecorations, 0L, 0L, 0L, 0L);
            X11.changeProperty(display, window, c.atoms().motifWmHints(), c.atoms().motifWmHints(), 32, hints, 5);
        }
    }

    /**
     * Ask the window manager what frame it will put on this window, before it is mapped, and wait briefly for the
     * answer ({@code _NET_REQUEST_FRAME_EXTENTS}). A window manager that does not answer leaves the frame at zero,
     * which only means the first launch's outer size comes out a frame larger than asked.
     */
    private void readFrameExtentsAhead() {
        sendToRoot(c.atoms().netRequestFrameExtents(), 0, 0, 0, 0, 0);
        long deadline = System.nanoTime() + 100_000_000L;
        while (System.nanoTime() < deadline) {
            if (readFrameExtents()) {
                return;
            }
            Libc.pollReadable(c.fd(), c.wakeRead(), 5);
            drainQueue();
        }
    }

    /** Read {@code _NET_FRAME_EXTENTS}; returns whether the window manager has set it. */
    private boolean readFrameExtents() {
        long[] e = X11.getLongProperty(display, window, c.atoms().netFrameExtents(), X11.XA_CARDINAL, 4);
        if (e.length < 4) {
            return false;
        }
        frameLeft = (int) e[0];
        frameRight = (int) e[1];
        frameTop = (int) e[2];
        frameBottom = (int) e[3];
        return true;
    }

    private void setMinSize(WindowConfig config, boolean framed) {
        if (config.minWidth() == WindowConfig.NO_MINIMUM && config.minHeight() == WindowConfig.NO_MINIMUM
                && config.resizable()) {
            return;
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment hints = temp.allocate(X11.XSizeHints);
            int frameW = framed ? frameLeft + frameRight : 0;
            int frameH = framed ? frameTop + frameBottom : 0;
            int minW = Math.max(1, config.minWidth() - frameW);
            int minH = Math.max(1, config.minHeight() - frameH);
            if (!config.resizable()) {
                // A fixed-size window is one whose minimum and maximum are both its size.
                minW = Math.max(1, config.width() - frameW);
                minH = Math.max(1, config.height() - frameH);
                long pMaxSize = 1L << 5;
                X11.SIZE_flags.set(hints, X11.PMinSize | pMaxSize);
                hints.set(JAVA_INT, X11.XSizeHints.byteOffset(MemoryLayout.PathElement.groupElement("max_width")),
                        minW);
                hints.set(JAVA_INT, X11.XSizeHints.byteOffset(MemoryLayout.PathElement.groupElement("max_height")),
                        minH);
            } else {
                X11.SIZE_flags.set(hints, X11.PMinSize);
            }
            X11.SIZE_min_width.set(hints, minW);
            X11.SIZE_min_height.set(hints, minH);
            X11.setWMNormalHints(display, window, hints);
        }
    }

    // ---- Events -----------------------------------------------------------------------------------------------------

    @Override
    public boolean pumpEvents() {
        if (destroyed) {
            return false;
        }
        drainQueue();
        return !closeRequested;
    }

    /** Route every queued event to the window it is for. */
    private static void drainQueue() {
        Connection c = connection;
        MemorySegment event = c.event();
        while (X11.pending(c.display()) > 0) {
            X11.nextEvent(c.display(), event);
            X11Window target = WINDOWS.get((long) X11.ANY_window.get(event));
            if (target != null) {
                target.handle(event);
            }
        }
    }

    private void handle(MemorySegment e) {
        int type = (int) X11.ANY_type.get(e);
        if (type == X11.ConfigureNotify) {
            onConfigure(e);
        } else if (type == X11.ClientMessage) {
            if ((long) X11.CLIENT_message_type.get(e) == c.atoms().wmProtocols()
                    && (long) X11.CLIENT_l0.get(e) == c.atoms().wmDeleteWindow()) {
                closeRequested = true;
                closeFromWindowManager = true;
            }
        } else if (type == X11.FocusIn || type == X11.FocusOut) {
            // Focus moving within a grab (a window-manager move, say) is not the user leaving the window.
            int mode = e.get(JAVA_INT, X11.XAnyEvent.byteSize());
            if (mode == 0 || mode == 3) {   // NotifyNormal, NotifyWhileGrabbed
                focused = type == X11.FocusIn;
                if (!focused) {
                    keysDown.clear();
                }
            }
        } else if (type == X11.PropertyNotify) {
            long atom = (long) X11.PROPERTY_atom.get(e);
            if (atom == c.atoms().netWmState()) {
                readState();
            } else if (atom == c.atoms().netFrameExtents()) {
                readFrameExtents();
            }
        } else if (type == X11.MapNotify) {
            mapped = true;
            minimized = false;
        } else if (type == X11.UnmapNotify) {
            mapped = false;
            // Unmapped by the window manager while the host still wants it shown: iconified.
            minimized = visible;
        } else if (type == X11.KeyPress || type == X11.KeyRelease) {
            Key key = KEY_BY_KEYSYM.get(X11.lookupKeysym(e));
            if (key != null) {
                if (type == X11.KeyPress) {
                    keysDown.add(key);
                } else {
                    keysDown.remove(key);
                }
            }
        } else if (type == X11.ButtonPress) {
            onButtonPress(e);
        } else if (type == X11.DestroyNotify) {
            destroyed = true;
            closeRequested = true;
            WINDOWS.remove(window);
        }
    }

    private void onConfigure(MemorySegment e) {
        if ((long) X11.CONFIGURE_window.get(e) != window) {
            return;
        }
        width = Math.max(1, (int) X11.CONFIGURE_width.get(e));
        height = Math.max(1, (int) X11.CONFIGURE_height.get(e));
        // A real ConfigureNotify's position is relative to the window manager's frame; ask the server for the
        // position on the root instead of trusting either flavour.
        int[] origin = X11.translateCoordinates(display, window, c.root(), 0, 0);
        if (origin != null) {
            screenX = origin[0];
            screenY = origin[1];
        }
    }

    private void readState() {
        long[] states = X11.getLongProperty(display, window, c.atoms().netWmState(), X11.XA_ATOM, 32);
        boolean vert = false;
        boolean horz = false;
        boolean full = false;
        boolean hidden = false;
        for (long s : states) {
            vert |= s == c.atoms().netWmStateMaximizedVert();
            horz |= s == c.atoms().netWmStateMaximizedHorz();
            full |= s == c.atoms().netWmStateFullscreen();
            hidden |= s == c.atoms().netWmStateHidden();
        }
        maximized = vert && horz;
        fullscreen = full;
        minimized = hidden;
    }

    /** A left press in the caption or a resize band is the window manager's to run, as the frame's would be. */
    private void onButtonPress(MemorySegment e) {
        if (decorations == Decorations.SYSTEM || (int) X11.KEY_keycode.get(e) != 1 || fullscreen) {
            return;
        }
        HitRegions regions = hitRegions;
        int border = regions.resizeBorder() > 0 ? regions.resizeBorder() : DEFAULT_RESIZE_BORDER;
        HitRegions.Zone zone = regions.zone((int) X11.KEY_x.get(e), (int) X11.KEY_y.get(e), width, height,
                maximized, border);
        Long direction = MOVERESIZE_DIRECTION.get(zone);
        if (direction == null) {
            return;
        }
        long now = (long) X11.KEY_time.get(e);
        if (direction == MOVERESIZE_MOVE && now - lastCaptionPress < DOUBLE_CLICK_MILLIS) {
            lastCaptionPress = 0;
            if (maximized) {
                restore();
            } else {
                maximize();
            }
            return;
        }
        if (direction == MOVERESIZE_MOVE) {
            lastCaptionPress = now;
        }
        // The press grabbed the pointer for this client; the window manager cannot take the drag over until it is
        // released.
        X11.ungrabPointer(display, X11.CurrentTime);
        sendToRoot(c.atoms().netWmMoveresize(), (int) X11.KEY_x_root.get(e), (int) X11.KEY_y_root.get(e),
                direction, 1, 1);
    }

    @Override
    public void waitEvents(long timeoutNanos) {
        if (destroyed || X11.pending(display) > 0) {
            return;
        }
        int millis = timeoutNanos == Long.MAX_VALUE ? -1
                : (int) Math.min(Integer.MAX_VALUE, Math.max(0, (timeoutNanos + 999_999) / 1_000_000));
        if (Libc.pollReadable(c.fd(), c.wakeRead(), millis)) {
            Libc.drain(c.wakeRead());
        }
    }

    @Override
    public void postWake() {
        Libc.writeByte(c.wakeWrite());
    }

    // ---- Sending to the window manager ------------------------------------------------------------------------------

    /** An EWMH client message about this window, to the root, as the specification asks clients to send them. */
    private void sendToRoot(long messageType, long l0, long l1, long l2, long l3, long l4) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment ev = temp.allocate(X11.XEVENT_SIZE, 8);
            X11.CLIENT_type.set(ev, X11.ClientMessage);
            X11.CLIENT_window.set(ev, window);
            X11.CLIENT_message_type.set(ev, messageType);
            X11.CLIENT_format.set(ev, 32);
            X11.CLIENT_l0.set(ev, l0);
            X11.CLIENT_l1.set(ev, l1);
            X11.CLIENT_l2.set(ev, l2);
            X11.CLIENT_l3.set(ev, l3);
            X11.CLIENT_l4.set(ev, l4);
            X11.sendEvent(display, c.root(), false, X11.SubstructureRedirectMask | X11.SubstructureNotifyMask, ev);
            X11.flush(display);
        }
    }

    private void setState(long action, long first, long second) {
        if (destroyed) {
            return;
        }
        sendToRoot(c.atoms().netWmState(), action, first, second, 1, 0);
    }

    // ---- Size and placement -----------------------------------------------------------------------------------------

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int screenX() {
        return screenX - frameLeft;
    }

    @Override
    public int screenY() {
        return screenY - frameTop;
    }

    @Override
    public int outerWidth() {
        return width + frameLeft + frameRight;
    }

    @Override
    public int outerHeight() {
        return height + frameTop + frameBottom;
    }

    @Override
    public void setPosition(int x, int y) {
        if (destroyed) {
            return;
        }
        // NorthWest gravity, the default: the reference point is the frame's top-left, which is the outer rect's.
        X11.moveWindow(display, window, x, y);
        X11.flush(display);
    }

    @Override
    public void setBounds(int x, int y, int outerWidth, int outerHeight) {
        if (destroyed) {
            return;
        }
        X11.moveResizeWindow(display, window, x, y, Math.max(1, outerWidth - frameLeft - frameRight),
                Math.max(1, outerHeight - frameTop - frameBottom));
        X11.flush(display);
    }

    // ---- Visibility, focus, state -----------------------------------------------------------------------------------

    @Override
    public void show() {
        if (destroyed || visible) {
            return;
        }
        visible = true;
        if (BACKGROUND) {
            return;
        }
        X11.mapWindow(display, window);
        X11.flush(display);
    }

    @Override
    public void hide() {
        if (destroyed || !visible) {
            return;
        }
        visible = false;
        if (!BACKGROUND) {
            X11.unmapWindow(display, window);
            X11.flush(display);
        }
    }

    @Override
    public boolean isVisible() {
        return visible;
    }

    @Override
    public boolean isFocused() {
        return focused;
    }

    @Override
    public void focus() {
        if (destroyed || BACKGROUND) {
            return;
        }
        if (!visible) {
            show();
        }
        X11.raiseWindow(display, window);
        // Source indication 1: an ordinary application asking. The window manager may decline, as EWMH allows.
        sendToRoot(c.atoms().netActiveWindow(), 1, X11.CurrentTime, 0, 0, 0);
    }

    @Override
    public boolean cancelClose() {
        if (destroyed || !closeFromWindowManager) {
            return false;
        }
        closeRequested = false;
        closeFromWindowManager = false;
        return true;
    }

    @Override
    public void requestClose() {
        if (destroyed) {
            return;
        }
        // The route a WM_DELETE_WINDOW takes: the host sees it on the next pump and closes on its own terms.
        closeRequested = true;
        closeFromWindowManager = true;
        postWake();
    }

    @Override
    public void minimize() {
        if (destroyed || BACKGROUND) {
            return;
        }
        X11.iconifyWindow(display, window, c.screen());
        X11.flush(display);
    }

    @Override
    public void maximize() {
        setState(STATE_ADD, c.atoms().netWmStateMaximizedVert(), c.atoms().netWmStateMaximizedHorz());
    }

    @Override
    public void restore() {
        if (minimized && !destroyed && !BACKGROUND) {
            X11.mapWindow(display, window);
            X11.flush(display);
            return;
        }
        setState(STATE_REMOVE, c.atoms().netWmStateMaximizedVert(), c.atoms().netWmStateMaximizedHorz());
    }

    @Override
    public boolean isMaximized() {
        return maximized;
    }

    @Override
    public boolean isMinimized() {
        return minimized;
    }

    @Override
    public void fullscreen(boolean on) {
        if (on != fullscreen) {
            setState(on ? STATE_ADD : STATE_REMOVE, c.atoms().netWmStateFullscreen(), 0);
        }
    }

    @Override
    public boolean isFullscreen() {
        return fullscreen;
    }

    @Override
    public void setHitRegions(HitRegions regions) {
        this.hitRegions = regions == null ? HitRegions.NONE : regions;
    }

    // ---- Cursor, icon, keys -----------------------------------------------------------------------------------------

    @Override
    public void setCursor(Cursor cursor) {
        Cursor wanted = cursor == null ? Cursor.ARROW : cursor;
        if (destroyed || wanted == this.cursor) {
            return;
        }
        this.cursor = wanted;
        long xCursor = cursors.computeIfAbsent(wanted, k -> X11.createFontCursor(display, fontGlyph(k)));
        X11.defineCursor(display, window, xCursor);
        X11.flush(display);
    }

    private static int fontGlyph(Cursor cursor) {
        return cursor == Cursor.TEXT ? X11.XC_xterm
                : cursor == Cursor.RESIZE_HORIZONTAL ? X11.XC_sb_h_double_arrow
                : cursor == Cursor.RESIZE_VERTICAL ? X11.XC_sb_v_double_arrow
                : X11.XC_left_ptr;
    }

    @Override
    public void setIcon(Icon icon) {
        this.icon = icon;
        if (!destroyed) {
            applyIcon();
        }
    }

    /**
     * {@code _NET_WM_ICON}: every image of the icon as {@code width, height, ARGB...}, format 32 (so one C long per
     * value). The window manager picks the size it wants for each place it shows the window.
     */
    private void applyIcon() {
        Icon worn = icon != null ? icon : applicationIcon;
        if (worn == null) {
            X11.deleteProperty(display, window, c.atoms().netWmIcon());
            X11.flush(display);
            return;
        }
        int count = 0;
        for (Icon.Image image : worn.images()) {
            count += 2 + image.width() * image.height();
        }
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment data = temp.allocate(JAVA_LONG, count);
            long i = 0;
            for (Icon.Image image : worn.images()) {
                data.setAtIndex(JAVA_LONG, i++, image.width());
                data.setAtIndex(JAVA_LONG, i++, image.height());
                for (int argb : image.argb()) {
                    data.setAtIndex(JAVA_LONG, i++, Integer.toUnsignedLong(argb));
                }
            }
            X11.changeProperty(display, window, c.atoms().netWmIcon(), X11.XA_CARDINAL, 32, data, count);
        }
        X11.flush(display);
    }

    @Override
    public boolean isKeyDown(Key key) {
        return keysDown.contains(key);
    }

    // ---- Vulkan -----------------------------------------------------------------------------------------------------

    @Override
    public long createVulkanSurface(long vkInstance, MemorySegment vkGetInstanceProcAddr) {
        if (destroyed) {
            throw new IllegalStateException("no surface for a destroyed window");
        }
        MemorySegment instance = MemorySegment.ofAddress(vkInstance);
        MethodHandle getProcAddr = Ffi.downcall(vkGetInstanceProcAddr,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment createFn;
            try {
                createFn = (MemorySegment) getProcAddr.invokeExact(instance,
                        temp.allocateFrom("vkCreateXlibSurfaceKHR"));
            } catch (Throwable t) {
                throw NativeException.rethrow("vkGetInstanceProcAddr", t);
            }
            if (createFn.equals(MemorySegment.NULL)) {
                throw new NativeException("vkGetInstanceProcAddr returned NULL for vkCreateXlibSurfaceKHR — "
                        + "is VK_KHR_xlib_surface enabled on the instance?");
            }
            MethodHandle create = Ffi.downcall(createFn,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
            MemorySegment info = temp.allocate(VK_XLIB_SURFACE_CREATE_INFO);
            SCI_sType.set(info, VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR);
            SCI_dpy.set(info, display);
            SCI_window.set(info, window);
            MemorySegment pSurface = temp.allocate(JAVA_LONG);
            int result;
            try {
                result = (int) create.invokeExact(instance, info, MemorySegment.NULL, pSurface);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkCreateXlibSurfaceKHR", t);
            }
            if (result != VK_SUCCESS) {
                throw new NativeException("vkCreateXlibSurfaceKHR failed: VkResult " + result);
            }
            return pSurface.get(JAVA_LONG, 0);
        }
    }

    @Override
    public long osHandle() {
        return window;
    }

    @Override
    public void close() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        WINDOWS.remove(window);
        X11.destroyWindow(display, window);
        X11.flush(display);
        arena.close();
    }
}
