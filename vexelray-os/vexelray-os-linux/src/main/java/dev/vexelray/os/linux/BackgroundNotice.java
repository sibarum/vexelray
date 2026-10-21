package dev.vexelray.os.linux;

import dev.vexelray.os.BackgroundRun;
import dev.vexelray.os.linux.sys.DBus;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * A background run's one visible thing on Linux, and the person's way to end it: a desktop notification that says an
 * agent started this application, with a button per {@link BackgroundRun#menu menu} entry — {@code Exit Program} last,
 * always. What the notification-area icon is on Windows ({@code Win32Tray}), through the freedesktop notification
 * service every Linux desktop has ({@code org.freedesktop.Notifications} on the session bus).
 *
 * <p>It is {@code resident}, so it stays in the desktop's notification list after it has popped up and after a button
 * is pressed, and it never expires. A person who dismisses it has dismissed the handle too; the run can still be ended
 * from the terminal that started it, which the log says from the start.
 *
 * <p>Everything runs on one daemon thread of its own: the post, and then waiting for {@code ActionInvoked}. Where there
 * is no session bus or no notification service, it says so on stderr and the run goes on without it. The notice is
 * withdrawn when the process ends.
 */
final class BackgroundNotice {

    private static final String SERVICE = "org.freedesktop.Notifications";
    private static final String PATH = "/org/freedesktop/Notifications";

    /** How long the notice thread waits on the bus before looking whether the process is ending. */
    private static final int POLL_MILLIS = 200;

    private static volatile boolean started;
    /** Set by the shutdown hook; the notice thread withdraws the notice and stops. */
    private static volatile boolean ending;

    private BackgroundNotice() {
    }

    /** Post the notice for {@code application}, once per process. */
    static synchronized void show(String application) {
        if (started) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> run(application), "vexelray-background-notice");
        t.setDaemon(true);
        t.start();
        // Withdrawn when the run ends, however it ends: a resident notice outliving its process would offer a button
        // that ends nothing. The hook only asks: the connection is the notice thread's alone, and a second thread
        // using it while that one waits on it would wait for ever, and hold the process open with it.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ending = true;
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "vexelray-background-notice-close"));
    }

    private static void run(String application) {
        List<BackgroundRun.MenuItem> menu = BackgroundRun.menu(application);
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bus = DBus.sessionBus(arena);
            DBus.addMatch(bus, "type='signal',interface='" + SERVICE + "',member='ActionInvoked'", arena);
            int id = post(bus, application, menu, arena);
            while (!ending && DBus.readWrite(bus, POLL_MILLIS)) {
                for (MemorySegment msg = DBus.pop(bus); !msg.equals(MemorySegment.NULL); msg = DBus.pop(bus)) {
                    try (Arena read = Arena.ofConfined()) {
                        if (DBus.isSignal(msg, SERVICE, "ActionInvoked", read)) {
                            invoked(msg, id, menu, read);
                        }
                    } finally {
                        DBus.unref(msg);
                    }
                }
            }
            if (ending) {
                withdraw(bus, id);
            }
        } catch (RuntimeException | LinkageError e) {
            System.err.println("vexelray: no background-run notification (" + e.getMessage() + "); end this run from"
                    + " the terminal that started it, or with kill " + ProcessHandle.current().pid());
        }
    }

    /** {@code Notify}: returns the notification's id, which the button presses come back with. */
    private static int post(MemorySegment bus, String application, List<BackgroundRun.MenuItem> menu, Arena arena) {
        MemorySegment msg = DBus.newMethodCall(SERVICE, PATH, SERVICE, "Notify", arena);
        try {
            MemorySegment args = DBus.appendIter(msg, arena);
            DBus.appendString(args, application, arena);                  // app_name
            DBus.appendInt(args, DBus.TYPE_UINT32, 0, arena);              // replaces_id
            DBus.appendString(args, "system-run", arena);                  // app_icon, a themed name
            DBus.appendString(args, application + " is running in the background", arena);
            DBus.appendString(args, "An agent started it (pid " + ProcessHandle.current().pid() + "). Its windows"
                    + " are real but not shown, and take no input from you.", arena);
            MemorySegment actions = DBus.open(args, DBus.TYPE_ARRAY, "s", arena);
            for (int i = 0; i < menu.size(); i++) {
                DBus.appendString(actions, Integer.toString(i), arena);    // the key a press comes back with
                DBus.appendString(actions, menu.get(i).label(), arena);
            }
            DBus.close(args, actions);
            MemorySegment hints = DBus.open(args, DBus.TYPE_ARRAY, "{sv}", arena);
            boolHint(hints, "resident", arena);
            DBus.close(args, hints);
            DBus.appendInt(args, DBus.TYPE_INT32, 0, arena);               // expire_timeout: never
            MemorySegment reply = DBus.call(bus, msg, 5000, arena);
            try {
                MemorySegment it = DBus.readIter(reply, arena);
                return it != null && DBus.argType(it) == DBus.TYPE_UINT32 ? DBus.getInt(it, arena) : 0;
            } finally {
                DBus.unref(reply);
            }
        } finally {
            DBus.unref(msg);
        }
    }

    /**
     * {@code CloseNotification(id)}, sent without waiting for the reply: best effort, since the process is going either
     * way. On the notice thread, the connection's only user.
     */
    private static void withdraw(MemorySegment bus, int id) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment msg = DBus.newMethodCall(SERVICE, PATH, SERVICE, "CloseNotification", arena);
            try {
                DBus.appendInt(DBus.appendIter(msg, arena), DBus.TYPE_UINT32, id, arena);
                DBus.sendAndFlush(bus, msg);
            } finally {
                DBus.unref(msg);
            }
        } catch (RuntimeException | LinkageError e) {
            // the notice outlives the run; pressing it does nothing harmful
        }
    }

    private static void boolHint(MemorySegment hints, String name, Arena arena) {
        MemorySegment entry = DBus.open(hints, DBus.TYPE_DICT_ENTRY, null, arena);
        DBus.appendString(entry, name, arena);
        MemorySegment value = DBus.open(entry, DBus.TYPE_VARIANT, "b", arena);
        DBus.appendInt(value, DBus.TYPE_BOOLEAN, 1, arena);
        DBus.close(entry, value);
        DBus.close(hints, entry);
    }

    /** {@code ActionInvoked(u id, s key)}: run the menu entry the key names, if the press was on our notification. */
    private static void invoked(MemorySegment msg, int id, List<BackgroundRun.MenuItem> menu, Arena arena) {
        MemorySegment it = DBus.readIter(msg, arena);
        if (it == null || DBus.argType(it) != DBus.TYPE_UINT32 || DBus.getInt(it, arena) != id || !DBus.next(it)
                || DBus.argType(it) != DBus.TYPE_STRING) {
            return;
        }
        String key = DBus.getString(it, arena);
        int index;
        try {
            index = Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return;
        }
        if (index >= 0 && index < menu.size()) {
            menu.get(index).run().run();
        }
    }
}
