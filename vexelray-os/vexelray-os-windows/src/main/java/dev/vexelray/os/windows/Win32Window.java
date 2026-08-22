package dev.vexelray.os.windows;

import dev.vexelray.os.Decorations;
import dev.vexelray.os.HitRegions;
import dev.vexelray.os.Key;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;
import dev.vexelray.os.windows.sys.Dwmapi;
import dev.vexelray.os.windows.sys.Gdi32;
import dev.vexelray.os.windows.sys.Kernel32;
import dev.vexelray.os.windows.sys.User32;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A Win32 window backing a {@link NativeWindow}: a {@code user32} window driven by a message pump, minting a
 * {@code VkSurfaceKHR} through {@code VK_KHR_win32_surface}. All native access follows the binding convention —
 * downcalls via {@link User32}/{@link Kernel32}, the window procedure as a single {@link Ffi#upcall} bound to the
 * process-lifetime arena, and every struct field reached through a named layout. See {@code docs/native-bindings.md}.
 *
 * <p><b>Client-drawn chrome</b> ({@link Decorations#CLIENT}) is three messages, and deliberately nothing more.
 * The window keeps the ordinary {@code WS_OVERLAPPEDWINDOW} style, so Windows keeps running the frame — snap,
 * Win+arrow, double-click-to-maximize, the system menu, the work-area clamp when maximized, per-monitor DPI
 * transitions. What changes is only who paints it, and what the pointer is told it is over:
 *
 * <ul>
 *   <li>{@code WM_NCCALCSIZE} hands the whole window rect to the client area, so the GUI draws where the title
 *       bar was;</li>
 *   <li>{@code WM_NCHITTEST} answers from the {@link HitRegions} the application pushes each frame, so the
 *       window manager still knows which of the application's own pixels are caption and which are content;</li>
 *   <li>the size/move loop messages pull frames through {@link #setFrameSink}, because Windows drags a window
 *       inside a nested message loop that would otherwise leave a pull-style render loop suspended — the window
 *       would freeze for as long as the drag lasted.</li>
 * </ul>
 */
public final class Win32Window implements NativeWindow {

    // ---- Shared window class (registered once per process) --------------------------------------------------

    private static final String CLASS_NAME = "VexelRayWindowClass";
    private static final AtomicBoolean CLASS_REGISTERED = new AtomicBoolean(false);
    private static MemorySegment classNameSeg;   // lives in Ffi.GLOBAL once the class is registered

    /** hwnd address → window, so the shared window procedure can route messages to the right instance. */
    private static final Map<Long, Win32Window> WINDOWS = new ConcurrentHashMap<>();

    // Predefined system cursors, loaded once and shared (process-lifetime; the OS owns them).
    private static MemorySegment arrowCursor = MemorySegment.NULL;
    private static MemorySegment ibeamCursor = MemorySegment.NULL;

    private static final FunctionDescriptor WNDPROC_DESC =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);

    /** Timer id for the frames pulled during a modal move/resize, and its period (a ~120 Hz ceiling). */
    private static final long SIZEMOVE_TIMER = 1L;
    private static final int SIZEMOVE_TIMER_MS = 8;

    // ---- VkWin32SurfaceCreateInfoKHR (40 bytes, x64) --------------------------------------------------------

    private static final int VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR = 1000009000;
    private static final int VK_SUCCESS = 0;

    private static final GroupLayout VK_WIN32_SURFACE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hinstance"),
            ADDRESS.withName("hwnd")
    ).withName("VkWin32SurfaceCreateInfoKHR");

    private static final VarHandle SCI_sType     = surfaceField("sType");
    private static final VarHandle SCI_hinstance = surfaceField("hinstance");
    private static final VarHandle SCI_hwnd      = surfaceField("hwnd");

    private static VarHandle surfaceField(String field) {
        VarHandle vh = VK_WIN32_SURFACE_CREATE_INFO.varHandle(PathElement.groupElement(field));
        return MethodHandles.insertCoordinates(vh, 1, 0L).withInvokeExactBehavior();
    }

    // ---- Instance state -------------------------------------------------------------------------------------

    private final Arena arena = Arena.ofShared();   // window-lifetime allocations (the reusable MSG buffer)
    private final MemorySegment hInstance;
    private final MemorySegment hwnd;
    private final MemorySegment msgBuffer;
    private final Decorations decorations;
    private int width;
    private int height;
    // Two facts, not one. WM_CLOSE is a *request* — this window procedure deliberately does not pass it to
    // DefWindowProc, so nothing is destroyed and the application may still refuse it (cancelClose). WM_DESTROY
    // is the window actually going away, which no one can refuse. Collapsing them into a single flag is what
    // makes a veto impossible: the host cannot tell "the user clicked X" from "this window no longer exists".
    private volatile boolean closeRequested;
    private volatile boolean destroyed;
    // Desired client-area cursor; read by the (message-pump-thread) window procedure on WM_SETCURSOR.
    private volatile Cursor desiredCursor = Cursor.ARROW;
    // Caption/content geometry, republished by the application every frame and read by the window procedure.
    private volatile HitRegions hitRegions = HitRegions.NONE;
    // Pulls one frame while Windows owns the loop (modal move/resize).
    private volatile Runnable frameSink;
    private boolean rendering;
    private boolean shown;
    private final boolean[] keyDown = new boolean[256];   // indexed by Win32 virtual-key code

    public Win32Window(WindowConfig config) {
        this.hInstance = Kernel32.getModuleHandleW(MemorySegment.NULL);
        this.decorations = config.decorations();
        ensureClassRegistered(hInstance);

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment title = temp.allocateFrom(config.title(), StandardCharsets.UTF_16LE);
            // Created hidden (no WS_VISIBLE): Vulkan bring-up runs off screen, then the present loop calls show()
            // once the first frame is ready, so the window never flashes blank/"Not Responding" during init.
            //
            // A CLIENT-decorated window keeps the overlapped style on purpose. The frame is what carries the
            // window-manager behaviour — snap, keyboard move/size, the maximize clamp to the work area — so
            // making the window a bare popup to be rid of the title bar would throw all of that away and hand us
            // the job of re-implementing it. The frame stays; it is simply not drawn (see WM_NCCALCSIZE).
            int style = decorations == Decorations.NONE ? User32.WS_POPUP : User32.WS_OVERLAPPEDWINDOW;
            // An owner makes this an *owned* window (hWndParent on a top-level style is ownership, not child-ness):
            // no taskbar button of its own, always above the owner, raised/minimized/destroyed with it. The whole
            // "many windows, one application" feel is this one argument — nothing else to manage.
            MemorySegment owner = config.owner() != 0L
                    ? MemorySegment.ofAddress(config.owner())
                    : MemorySegment.NULL;
            int x = config.x() == WindowConfig.UNPOSITIONED ? User32.CW_USEDEFAULT : config.x();
            int y = config.y() == WindowConfig.UNPOSITIONED ? User32.CW_USEDEFAULT : config.y();
            this.hwnd = User32.createWindowExW(0, classNameSeg, title, style,
                    x, y, config.width(), config.height(),
                    owner, MemorySegment.NULL, hInstance, MemorySegment.NULL);
        }

        WINDOWS.put(hwnd.address(), this);
        if (decorations == Decorations.CLIENT) {
            // Only now can WM_NCCALCSIZE reach this instance — the routing map is keyed by an hwnd that did not
            // exist until CreateWindowExW returned — so the frame Windows computed during creation is still the
            // system one. Ask for a recalculation to replace it with ours, and hand the compositor a one-pixel
            // frame back so the window keeps its shadow, its rounded corners and its system animations.
            Dwmapi.extendFrameIntoClientArea(hwnd, 0, 0, 1, 0);
            User32.frameChanged(hwnd);
        }
        this.msgBuffer = arena.allocate(User32.MSG);
        readClientSize();

        // Appear immediately, painted with the class background brush, and pump once so the OS actually erases the
        // client area now. The (potentially slow) Vulkan bring-up then runs with a clean coloured window on screen
        // instead of nothing — the swapchain takes over the pixels once the first frame presents.
        show();
        pumpEvents();
    }

    private static synchronized void ensureClassRegistered(MemorySegment hInstance) {
        if (!CLASS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        classNameSeg = Ffi.GLOBAL.allocateFrom(CLASS_NAME, StandardCharsets.UTF_16LE);
        MemorySegment wndProc = Ffi.upcall(MethodHandles.lookup(), Win32Window.class, "wndProc",
                WNDPROC_DESC, Ffi.GLOBAL);
        arrowCursor = User32.loadCursorW(MemorySegment.NULL, User32.IDC_ARROW);
        ibeamCursor = User32.loadCursorW(MemorySegment.NULL, User32.IDC_IBEAM);
        MemorySegment cursor = arrowCursor;
        // A neutral dark background painted before the first Vulkan present (0x11141b). Process-lifetime, like the
        // class itself — the OS reclaims it at exit.
        MemorySegment background = Gdi32.createSolidBrush(Gdi32.rgb(0x11, 0x14, 0x1b));
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment wc = User32.allocWndClassExW(temp, wndProc, hInstance, cursor, classNameSeg, background);
            User32.registerClassExW(wc);
        }
    }

    /** The shared window procedure. Routes to the owning window; unhandled messages fall through to Windows. */
    private static long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        Win32Window window = WINDOWS.get(hwnd.address());
        if (window != null) {
            switch (msg) {
                case User32.WM_SIZE -> {
                    if (wParam == User32.SIZE_MINIMIZED) {
                        // A minimized window reports a 0x0 client area. Keeping the last real size is what stops
                        // the swapchain being rebuilt at zero, and restores the window to the size it had.
                        return 0;
                    }
                    window.width = (int) (lParam & 0xFFFF);
                    window.height = (int) ((lParam >> 16) & 0xFFFF);
                    // A live resize runs inside Windows' own loop: paint from here, or the window shows stale,
                    // stretched pixels until the user lets go of the edge.
                    window.runFrameSink();
                    return 0;
                }
                case User32.WM_CLOSE -> {
                    // Not forwarded to DefWindowProc: the window stays alive and the host decides. That is what
                    // gives an application the chance to ask "save first?" before its window disappears.
                    window.closeRequested = true;
                    return 0;
                }
                case User32.WM_DESTROY -> {
                    window.destroyed = true;
                    return 0;
                }
                case User32.WM_KEYDOWN -> {
                    window.keyDown[(int) (wParam & 0xFF)] = true;
                    return 0;
                }
                case User32.WM_KEYUP -> {
                    window.keyDown[(int) (wParam & 0xFF)] = false;
                    return 0;
                }
                case User32.WM_SETCURSOR -> {
                    // Only override the client-area cursor; leave the non-client (resize borders, etc.) to Windows.
                    // Under CLIENT decorations the resize bands and the caption are non-client by hit-test, so
                    // this still covers exactly the pixels the GUI is answerable for.
                    if ((int) (lParam & 0xFFFF) == User32.HTCLIENT) {
                        User32.setCursor(window.desiredCursor == Cursor.TEXT ? ibeamCursor : arrowCursor);
                        return 1; // TRUE — we handled it, so Windows won't reset the class cursor
                    }
                }
                case User32.WM_NCCALCSIZE -> {
                    // wParam FALSE asks for a rect conversion only, which the default handling does correctly.
                    if (window.decorations == Decorations.CLIENT && wParam != 0) {
                        window.calcClientFrame(hwnd, lParam);
                        return 0;
                    }
                }
                case User32.WM_NCHITTEST -> {
                    if (window.decorations == Decorations.CLIENT) {
                        return window.hitTest(hwnd, lParam);
                    }
                }
                case User32.WM_NCLBUTTONDOWN -> {
                    // The maximize button is the application's own: it is reported as HTMAXBUTTON only so that
                    // Windows 11 offers its Snap Layouts flyout on hover. Swallow the press, or the default
                    // caption-button handling maximizes behind the application's back — the click reaches the GUI
                    // through the ordinary input path, and the application decides what its button does.
                    if (window.decorations == Decorations.CLIENT && wParam == User32.HTMAXBUTTON) {
                        return 0;
                    }
                }
                case User32.WM_ENTERSIZEMOVE -> {
                    // Windows is about to run a message loop of its own until the drag ends. The host's loop is
                    // suspended for the duration, so frames have to be pulled from in here instead.
                    User32.setTimer(hwnd, SIZEMOVE_TIMER, SIZEMOVE_TIMER_MS);
                    return 0;
                }
                case User32.WM_EXITSIZEMOVE -> {
                    User32.killTimer(hwnd, SIZEMOVE_TIMER);
                    return 0;
                }
                case User32.WM_TIMER -> {
                    if (wParam == SIZEMOVE_TIMER) {
                        window.runFrameSink();
                        return 0;
                    }
                }
                default -> { /* fall through */ }
            }
        }
        return User32.defWindowProcW(hwnd, msg, wParam, lParam);
    }

    /**
     * Answer {@code WM_NCCALCSIZE} for a client-drawn frame: the client area becomes the whole window rect, so
     * the proposed rectangle is returned untouched.
     *
     * <p>Except when maximized. A maximized window's rect deliberately overhangs the monitor by the frame
     * thickness — invisible while the frame is the OS's, and a title bar sliced off the top of the screen once it
     * is the application's. Insetting by that thickness is what puts the client area back on the monitor.
     */
    private void calcClientFrame(MemorySegment hwnd, long lParam) {
        if (!User32.isZoomed(hwnd)) {
            return;   // client == window: nothing to adjust
        }
        int frame = frameThickness();
        User32.insetRect(User32.rectAt(lParam), frame, frame);
    }

    /** Answer {@code WM_NCHITTEST} from the regions the application published. */
    private long hitTest(MemorySegment hwnd, long lParam) {
        HitRegions regions = hitRegions;
        int border = regions.resizeBorder() > 0 ? regions.resizeBorder() : frameThickness();
        int px;
        int py;
        try (Arena temp = Arena.ofConfined()) {
            // Screen coordinates, and signed: a window on a monitor left of or above the primary has negative ones.
            MemorySegment point = User32.allocPoint(temp,
                    (short) (lParam & 0xFFFF), (short) ((lParam >> 16) & 0xFFFF));
            User32.screenToClient(hwnd, point);
            px = User32.pointX(point);
            py = User32.pointY(point);
        }
        return switch (regions.zone(px, py, width, height, User32.isZoomed(hwnd), border)) {
            case CAPTION -> User32.HTCAPTION;
            case MAXIMIZE_BUTTON -> User32.HTMAXBUTTON;
            case TOP -> User32.HTTOP;
            case BOTTOM -> User32.HTBOTTOM;
            case LEFT -> User32.HTLEFT;
            case RIGHT -> User32.HTRIGHT;
            case TOP_LEFT -> User32.HTTOPLEFT;
            case TOP_RIGHT -> User32.HTTOPRIGHT;
            case BOTTOM_LEFT -> User32.HTBOTTOMLEFT;
            case BOTTOM_RIGHT -> User32.HTBOTTOMRIGHT;
            case CLIENT -> User32.HTCLIENT;
        };
    }

    /** The system frame thickness — the resize band, plus the invisible padded border Windows adds around it. */
    private static int frameThickness() {
        return User32.getSystemMetrics(User32.SM_CXSIZEFRAME) + User32.getSystemMetrics(User32.SM_CXPADDEDBORDER);
    }

    /**
     * Pull one frame from inside Windows' loop. Guarded against re-entry, so a frame that outruns the timer
     * period cannot stack renders inside one another, and skipped while minimized, where there is no client area
     * to present to.
     */
    private void runFrameSink() {
        Runnable sink = frameSink;
        if (sink == null || rendering || User32.isIconic(hwnd)) {
            return;
        }
        rendering = true;
        try {
            sink.run();
        } finally {
            rendering = false;
        }
    }

    @Override
    public void setCursor(Cursor cursor) {
        this.desiredCursor = cursor == null ? Cursor.ARROW : cursor;
    }

    @Override
    public void setHitRegions(HitRegions regions) {
        this.hitRegions = regions == null ? HitRegions.NONE : regions;
    }

    @Override
    public void setFrameSink(Runnable renderOneFrame) {
        this.frameSink = renderOneFrame;
    }

    @Override
    public void minimize() {
        User32.showWindow(hwnd, User32.SW_MINIMIZE);
    }

    @Override
    public void maximize() {
        User32.showWindow(hwnd, User32.SW_MAXIMIZE);
    }

    @Override
    public void restore() {
        User32.showWindow(hwnd, User32.SW_RESTORE);
    }

    @Override
    public boolean isMaximized() {
        return User32.isZoomed(hwnd);
    }

    @Override
    public boolean isMinimized() {
        return User32.isIconic(hwnd);
    }

    @Override
    public void requestClose() {
        // Posted, not sent: the close travels the same queue the system close button uses, so it is observed by
        // the next pumpEvents() and the host tears the window down on its own terms, not underneath this call.
        User32.postMessageW(hwnd, User32.WM_CLOSE, 0L, 0L);
    }

    private void readClientSize() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment rect = temp.allocate(User32.RECT);
            User32.getClientRect(hwnd, rect);
            this.width = User32.rectWidth(rect);
            this.height = User32.rectHeight(rect);
        }
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void show() {
        if (shown) {
            return;
        }
        shown = true;
        User32.showWindow(hwnd, User32.SW_SHOW);
    }

    @Override
    public void hide() {
        if (!shown) {
            return;
        }
        shown = false;
        User32.showWindow(hwnd, User32.SW_HIDE);
    }

    @Override
    public boolean isVisible() {
        return shown && User32.isWindowVisible(hwnd);
    }

    @Override
    public void focus() {
        // Restoring first, because a minimized window cannot be "focused" in any sense the user would accept:
        // the request means put this window in front of me, and a taskbar button is not in front of anything.
        if (User32.isIconic(hwnd)) {
            User32.showWindow(hwnd, User32.SW_RESTORE);
        }
        if (!shown) {
            show();
        }
        User32.focusWindow(hwnd);
    }

    @Override
    public void setEnabled(boolean enabled) {
        User32.enableWindow(hwnd, enabled);
    }

    @Override
    public boolean cancelClose() {
        if (destroyed) {
            return false;   // not a request — the window is already gone, and no flag can bring it back
        }
        closeRequested = false;
        return true;
    }

    @Override
    public boolean pumpEvents() {
        while (User32.peekMessageRemove(msgBuffer)) {
            User32.translateMessage(msgBuffer);
            User32.dispatchMessageW(msgBuffer);
        }
        return !(closeRequested || destroyed);
    }

    @Override
    public long createVulkanSurface(long vkInstance, MemorySegment vkGetInstanceProcAddr) {
        MemorySegment instance = MemorySegment.ofAddress(vkInstance);
        MethodHandle getProcAddr = Ffi.downcall(vkGetInstanceProcAddr,
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment name = temp.allocateFrom("vkCreateWin32SurfaceKHR");
            MemorySegment createFn;
            try {
                createFn = (MemorySegment) getProcAddr.invokeExact(instance, name);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkGetInstanceProcAddr", t);
            }
            if (createFn.equals(MemorySegment.NULL)) {
                throw new NativeException("vkGetInstanceProcAddr returned NULL for vkCreateWin32SurfaceKHR — "
                        + "is VK_KHR_win32_surface enabled on the instance?");
            }

            MethodHandle create = Ffi.downcall(createFn,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));

            MemorySegment info = temp.allocate(VK_WIN32_SURFACE_CREATE_INFO);
            SCI_sType.set(info, VK_STRUCTURE_TYPE_WIN32_SURFACE_CREATE_INFO_KHR);
            SCI_hinstance.set(info, hInstance);
            SCI_hwnd.set(info, hwnd);

            MemorySegment pSurface = temp.allocate(JAVA_LONG);
            int result;
            try {
                result = (int) create.invokeExact(instance, info, MemorySegment.NULL, pSurface);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkCreateWin32SurfaceKHR", t);
            }
            if (result != VK_SUCCESS) {
                throw new NativeException("vkCreateWin32SurfaceKHR failed: VkResult " + result);
            }
            return pSurface.get(JAVA_LONG, 0);
        }
    }

    @Override
    public boolean isKeyDown(Key key) {
        return keyDown[virtualKey(key)];
    }

    private static int virtualKey(Key key) {
        return switch (key) {
            case W -> 0x57;
            case A -> 0x41;
            case S -> 0x53;
            case D -> 0x44;
            case Q -> 0x51;
            case E -> 0x45;
            case UP -> 0x26;
            case DOWN -> 0x28;
            case LEFT -> 0x25;
            case RIGHT -> 0x27;
            case SPACE -> 0x20;
            case SHIFT -> 0x10;
            case ESCAPE -> 0x1B;
        };
    }

    @Override
    public int screenX() {
        return windowRect(User32::rectLeft);
    }

    @Override
    public int screenY() {
        return windowRect(User32::rectTop);
    }

    @Override
    public int outerWidth() {
        return windowRect(User32::rectSpanX);
    }

    @Override
    public int outerHeight() {
        return windowRect(User32::rectSpanY);
    }

    /** Read one value off the window's outer rect — same rect WindowConfig requests, so bounds round-trip. */
    private int windowRect(java.util.function.ToIntFunction<MemorySegment> field) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment rect = temp.allocate(User32.RECT);
            User32.getWindowRect(hwnd, rect);
            return field.applyAsInt(rect);
        }
    }

    @Override
    public void setPosition(int x, int y) {
        User32.moveWindow(hwnd, x, y);
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        User32.setWindowBounds(hwnd, x, y, width, height);
    }

    @Override
    public long osHandle() {
        return hwnd.address();
    }

    @Override
    public void close() {
        frameSink = null;   // nothing may pull a frame while the window is being destroyed
        WINDOWS.remove(hwnd.address());
        User32.destroyWindow(hwnd);
        arena.close();
    }
}
