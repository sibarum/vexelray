package dev.vexelray.os.windows;

import dev.supirvast.ffi.Ffi;
import dev.vexelray.os.BackgroundRun;
import dev.vexelray.os.Icon;
import dev.vexelray.os.windows.sys.Kernel32;
import dev.vexelray.os.windows.sys.Shell32;
import dev.vexelray.os.windows.sys.User32;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A {@link BackgroundRun background run}'s one visible thing: the application's icon in the notification area, and
 * on it the run's menu ({@link BackgroundRun#menu}), whose last entry is always the person's way to end the run.
 *
 * <p>Its windows are cloaked, so they have no taskbar button, and a taskbar button could not carry a menu of the
 * application's own anyway (right-clicking one shows the shell's jump list). The notification area can, and it is
 * where a program running for somebody else belongs.
 *
 * <p>One per process, added with the first background window and removed when the last one closes or the process
 * ends. Its messages arrive at a hidden window of its own class, on the main thread, through the same pump as every
 * other window's ({@code PeekMessageW} takes the whole thread's queue). The icon is put back when Explorer restarts,
 * which announces itself with the registered {@code TaskbarCreated} message.
 */
final class Win32Tray {

    private static final String CLASS_NAME = "VexelRayTrayClass";
    private static final int ICON_ID = 1;
    private static final int CALLBACK = User32.WM_APP + 1;
    private static final FunctionDescriptor WNDPROC_DESC =
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG);

    private static Win32Tray instance;   // main thread
    private static int taskbarCreated;

    private final MemorySegment hwnd;
    private final String application;
    private MemorySegment hicon = MemorySegment.NULL;   // ours to destroy; the stock icon is not
    private boolean ownsIcon;
    private boolean shown;
    private Icon primary;   // the application's icon; null for the stock one
    private volatile Icon worn;

    // Kept here rather than on the instance: the background icon is usually set before the first window, and so
    // before there is a tray to give it to.
    private static volatile Icon background;
    private static volatile boolean idle;

    private Win32Tray(String application, Icon icon) {
        this.application = application;
        MemorySegment hInstance = Kernel32.getModuleHandleW(MemorySegment.NULL);
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment className = Ffi.GLOBAL.allocateFrom(CLASS_NAME, StandardCharsets.UTF_16LE);
            MemorySegment wndProc = Ffi.upcall(MethodHandles.lookup(), Win32Tray.class, "wndProc",
                    WNDPROC_DESC, Ffi.GLOBAL);
            User32.registerClassExW(User32.allocWndClassExW(temp, wndProc, hInstance, MemorySegment.NULL,
                    className, MemorySegment.NULL));
            // Never shown: it exists to receive the icon's messages and to own its menu.
            this.hwnd = User32.createWindowExW(0, className, temp.allocateFrom(application, StandardCharsets.UTF_16LE),
                    User32.WS_POPUP, 0, 0, 0, 0, MemorySegment.NULL, MemorySegment.NULL, hInstance,
                    MemorySegment.NULL);
        }
        taskbarCreated = User32.registerWindowMessage("TaskbarCreated");
        this.primary = icon;
        wear();
        add();
        // A backstop for an exit that skips close(): an icon left behind stays in the notification area until the
        // pointer passes over it, which looks like a run still going.
        Runtime.getRuntime().addShutdownHook(new Thread(this::remove, "vexelray-tray-remove"));
    }

    /** Show the icon for {@code application}, once per process; later calls do nothing. Main thread. */
    static void ensure(String application, Icon icon) {
        if (instance == null) {
            instance = new Win32Tray(application == null || application.isBlank() ? "VexelRay" : application, icon);
        } else if (!instance.shown) {
            instance.add();   // every window had closed, and the application opened another
        }
    }

    /** The application's icon changed: wear the new one. Main thread, or wherever the icon was set. */
    static void iconChanged(Icon icon) {
        Win32Tray tray = instance;
        if (tray != null) {
            tray.primary = icon;
            tray.wear();
            tray.modify();
        }
    }

    /** The application's background icon changed: wear it now if the run is idle, else from when it is. */
    static void backgroundChanged(Icon icon) {
        background = icon;
        rewear();
    }

    /**
     * Whether every window of the run is hidden. While it is, the icon is the background one (if there is one):
     * the run is still going, and nothing of it is up.
     */
    static void idle(boolean everyWindowHidden) {
        if (idle != everyWindowHidden) {
            idle = everyWindowHidden;
            rewear();
        }
    }

    private static void rewear() {
        Win32Tray tray = instance;
        if (tray != null) {
            tray.wear();
            tray.modify();
        }
    }

    /** The background icon while idle and there is one, else the application's. */
    private void wear() {
        Icon hidden = idle ? background : null;
        worn = hidden != null ? hidden : primary;
        setIcon(worn);
    }

    /** The icon the notification area was last given, or {@code null} for none or no tray. For tests. */
    static Icon wearing() {
        Win32Tray tray = instance;
        return tray == null ? null : tray.worn;
    }

    /** The last window closed: take the icon away. */
    static void lastWindowClosed() {
        Win32Tray tray = instance;
        if (tray != null) {
            tray.remove();
        }
    }

    private synchronized void setIcon(Icon icon) {
        MemorySegment old = ownsIcon ? hicon : MemorySegment.NULL;
        if (icon != null) {
            hicon = Win32Window.createHicon(icon.bestFor(User32.getSystemMetrics(User32.SM_CXSMICON)));
            ownsIcon = true;
        } else {
            hicon = User32.applicationIcon();
            ownsIcon = false;
        }
        // Its never-shown window carries the same icon, so what the notification area shows can be read back from
        // outside the process (WM_GETICON on the VexelRayTrayClass window), which the shell itself does not offer.
        User32.sendMessageW(hwnd, User32.WM_SETICON, User32.ICON_SMALL, hicon.address());
        User32.destroyIcon(old);
    }

    private synchronized void add() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment nid = data(temp);
            shown = Shell32.notifyIcon(Shell32.NIM_ADD, nid);
            if (shown) {
                Shell32.notifyIcon(Shell32.NIM_SETVERSION, nid);
                System.err.println("vexelray: background run: " + application + " has an icon in the notification "
                        + "area; the person can end it from there (" + BackgroundRun.EXIT_LABEL + ")");
            } else {
                System.err.println("vexelray: background run: the shell refused " + application + "'s notification "
                        + "area icon (is Explorer running?); it comes back when the taskbar does");
            }
        }
    }

    private synchronized void modify() {
        if (!shown) {
            return;
        }
        try (Arena temp = Arena.ofConfined()) {
            Shell32.notifyIcon(Shell32.NIM_MODIFY, data(temp));
        }
    }

    private synchronized void remove() {
        if (!shown) {
            return;
        }
        shown = false;
        try (Arena temp = Arena.ofConfined()) {
            Shell32.notifyIcon(Shell32.NIM_DELETE, data(temp));
        }
    }

    private MemorySegment data(Arena temp) {
        return Shell32.allocNotifyIconData(temp, hwnd, ICON_ID, CALLBACK, hicon,
                application + " (running in the background)");
    }

    /** The person asked for the menu: show it, and do what they chose. */
    private void showMenu() {
        List<BackgroundRun.MenuItem> items = BackgroundRun.menu(application);
        List<String> labels = new java.util.ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (i == items.size() - 1 && items.size() > 1) {
                labels.add(null);   // a separator above the exit, so it is never the one hit by accident
                labels.add(items.get(i).label());
            } else {
                labels.add(items.get(i).label());
            }
        }
        int chosen = User32.popupMenu(hwnd, labels);
        if (chosen < 0) {
            return;
        }
        // Back from the label list's index (which counts the separator) to the item's.
        int item = items.size() > 1 && chosen >= items.size() - 1 ? chosen - 1 : chosen;
        if (item < 0 || item >= items.size()) {
            return;
        }
        BackgroundRun.MenuItem picked = items.get(item);
        if (picked.label().equals(BackgroundRun.EXIT_LABEL)) {
            remove();   // gone the moment it is chosen, not when the process has finished going
        }
        picked.run().run();
    }

    private static long wndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        Win32Tray tray = instance;
        if (tray != null && hwnd.address() == tray.hwnd.address()) {
            if (msg == CALLBACK) {
                int event = (int) (lParam & 0xFFFF);
                if (event == User32.WM_CONTEXTMENU || event == User32.WM_RBUTTONUP || event == User32.WM_LBUTTONUP) {
                    tray.showMenu();
                }
                return 0;
            }
            if (msg == taskbarCreated && taskbarCreated != 0) {
                tray.add();   // Explorer restarted and forgot every icon
                return 0;
            }
        }
        return User32.defWindowProcW(hwnd, msg, wParam, lParam);
    }
}
