package dev.vexelray.os.windows;

import dev.vexelray.os.Key;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;
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
 */
public final class Win32Window implements NativeWindow {

    // ---- Shared window class (registered once per process) --------------------------------------------------

    private static final String CLASS_NAME = "VexelRayWindowClass";
    private static final AtomicBoolean CLASS_REGISTERED = new AtomicBoolean(false);
    private static MemorySegment classNameSeg;   // lives in Ffi.GLOBAL once the class is registered

    /** hwnd address → window, so the shared window procedure can route messages to the right instance. */
    private static final Map<Long, Win32Window> WINDOWS = new ConcurrentHashMap<>();

    private static final FunctionDescriptor WNDPROC_DESC =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);

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
    private int width;
    private int height;
    private volatile boolean shouldClose;
    private final boolean[] keyDown = new boolean[256];   // indexed by Win32 virtual-key code

    public Win32Window(WindowConfig config) {
        this.hInstance = Kernel32.getModuleHandleW(MemorySegment.NULL);
        ensureClassRegistered(hInstance);

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment title = temp.allocateFrom(config.title(), StandardCharsets.UTF_16LE);
            int style = User32.WS_OVERLAPPEDWINDOW | User32.WS_VISIBLE;
            this.hwnd = User32.createWindowExW(0, classNameSeg, title, style,
                    User32.CW_USEDEFAULT, User32.CW_USEDEFAULT, config.width(), config.height(),
                    MemorySegment.NULL, MemorySegment.NULL, hInstance, MemorySegment.NULL);
        }

        WINDOWS.put(hwnd.address(), this);
        this.msgBuffer = arena.allocate(User32.MSG);
        User32.showWindow(hwnd, User32.SW_SHOW);
        readClientSize();
    }

    private static synchronized void ensureClassRegistered(MemorySegment hInstance) {
        if (!CLASS_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        classNameSeg = Ffi.GLOBAL.allocateFrom(CLASS_NAME, StandardCharsets.UTF_16LE);
        MemorySegment wndProc = Ffi.upcall(MethodHandles.lookup(), Win32Window.class, "wndProc",
                WNDPROC_DESC, Ffi.GLOBAL);
        MemorySegment cursor = User32.loadCursorW(MemorySegment.NULL, User32.IDC_ARROW);
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment wc = User32.allocWndClassExW(temp, wndProc, hInstance, cursor, classNameSeg);
            User32.registerClassExW(wc);
        }
    }

    /** The shared window procedure. Routes to the owning window; unhandled messages fall through to Windows. */
    private static long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        Win32Window window = WINDOWS.get(hwnd.address());
        if (window != null) {
            switch (msg) {
                case User32.WM_SIZE -> {
                    window.width = (int) (lParam & 0xFFFF);
                    window.height = (int) ((lParam >> 16) & 0xFFFF);
                    return 0;
                }
                case User32.WM_CLOSE, User32.WM_DESTROY -> {
                    window.shouldClose = true;
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
                default -> { /* fall through */ }
            }
        }
        return User32.defWindowProcW(hwnd, msg, wParam, lParam);
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
    public boolean pumpEvents() {
        while (User32.peekMessageRemove(msgBuffer)) {
            User32.translateMessage(msgBuffer);
            User32.dispatchMessageW(msgBuffer);
        }
        return !shouldClose;
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
    public long osHandle() {
        return hwnd.address();
    }

    @Override
    public void close() {
        WINDOWS.remove(hwnd.address());
        User32.destroyWindow(hwnd);
        arena.close();
    }
}
