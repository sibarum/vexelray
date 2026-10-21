package dev.vexelray.os.linux;

import dev.vexelray.os.BackgroundRun;
import dev.vexelray.os.Icon;
import dev.vexelray.os.linux.sys.DBus;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.List;

/**
 * A background run's handle on Linux: the person always sees that something an agent started is running, and can
 * always end it. What the notification-area icon is on Windows ({@code Win32Tray}).
 *
 * <p><b>Never gone while the run is not.</b> It starts as a desktop notification ({@code org.freedesktop.Notifications},
 * which every Linux desktop serves) with two buttons: <b>Move to System Tray</b> and <b>Exit Program</b>. Dismissed,
 * it is posted again. Moved to the tray, it becomes a {@link TrayItem} whose menu holds the run's whole
 * {@link BackgroundRun#menu menu}; where there is no tray host, or it will not take the item, the notification stays
 * and says so; and if the tray host goes away later, the notification comes back. It is withdrawn, whichever it is,
 * when the process ends. Being hard to get rid of is the point: an agent's run is never invisible.
 *
 * <p>Everything runs on one daemon thread of its own, the D-Bus connection's only user: the posts, the button presses,
 * the tray host's calls. The shutdown hook only asks it to withdraw, so the hook never waits on a connection another
 * thread holds. Where there is no session bus or no notification service, it says so on stderr and the run goes on;
 * the log says from the start how to end it from the terminal.
 */
final class BackgroundNotice {

    private static final String SERVICE = "org.freedesktop.Notifications";
    private static final String PATH = "/org/freedesktop/Notifications";

    private static final String MOVE_TO_TRAY = "tray";
    private static final String EXIT = "exit";

    /** How long the notice thread waits on the bus before looking whether the process is ending. */
    private static final int POLL_MILLIS = 200;
    /** A notification closed sooner than this after it was posted is posted again after this, not at once. */
    private static final long REPOST_MILLIS = 1000;

    private static volatile boolean started;
    /** Set by the shutdown hook; the notice thread withdraws whatever it shows and stops. */
    private static volatile boolean ending;

    private final String application;
    private final List<BackgroundRun.MenuItem> menu;
    private final TrayItem tray;

    /** The notification showing, or 0 when there is none (in the tray, or between a close and its re-post). */
    private int notification;
    private long postedAt;
    /** When to post the notification again, or 0 for not pending. */
    private long repostAt;
    private boolean inTray;
    /** Set once the tray could not be used, so the notification can say why it is still there. */
    private boolean noTray;

    private BackgroundNotice(String application, Icon icon) {
        this.application = application;
        this.menu = BackgroundRun.menu(application);
        this.tray = new TrayItem(application, menu, icon);
    }

    /** Show the handle for {@code application}, wearing {@code icon} in the tray (null for a generic one); once. */
    static synchronized void show(String application, Icon icon) {
        if (started) {
            return;
        }
        started = true;
        BackgroundNotice notice = new BackgroundNotice(application, icon);
        Thread t = new Thread(notice::run, "vexelray-background-notice");
        t.setDaemon(true);
        t.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ending = true;
            try {
                t.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "vexelray-background-notice-close"));
    }

    private void run() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bus = DBus.sessionBus(arena);
            DBus.addMatch(bus, "type='signal',interface='" + SERVICE + "',member='ActionInvoked'", arena);
            DBus.addMatch(bus, "type='signal',interface='" + SERVICE + "',member='NotificationClosed'", arena);
            DBus.addMatch(bus, "type='signal',sender='org.freedesktop.DBus',interface='org.freedesktop.DBus',"
                    + "member='NameOwnerChanged',arg0='" + TrayItem.WATCHER + "'", arena);
            post(bus, arena);
            while (!ending && DBus.readWrite(bus, POLL_MILLIS)) {
                for (MemorySegment msg = DBus.pop(bus); !msg.equals(MemorySegment.NULL); msg = DBus.pop(bus)) {
                    try (Arena read = Arena.ofConfined()) {
                        handle(bus, msg, read);
                    } finally {
                        DBus.unref(msg);
                    }
                }
                if (repostAt != 0 && System.currentTimeMillis() >= repostAt) {
                    post(bus, arena);
                }
            }
            if (ending && notification != 0) {
                withdraw(bus, notification);
            }
        } catch (RuntimeException | LinkageError e) {
            System.err.println("vexelray: no background-run notification (" + e.getMessage() + "); end this run from"
                    + " the terminal that started it, or with kill " + ProcessHandle.current().pid());
        }
    }

    private void handle(MemorySegment bus, MemorySegment msg, Arena arena) {
        if (inTray && tray.answer(bus, msg, arena)) {
            return;
        }
        if (DBus.type(msg) != DBus.MESSAGE_SIGNAL) {
            return;
        }
        if (DBus.isSignal(msg, SERVICE, "ActionInvoked", arena)) {
            pressed(bus, msg, arena);
        } else if (DBus.isSignal(msg, SERVICE, "NotificationClosed", arena)) {
            closed(msg, arena);
        } else if (DBus.isSignal(msg, "org.freedesktop.DBus", "NameOwnerChanged", arena)) {
            trayHostChanged(bus, msg, arena);
        }
    }

    /** {@code ActionInvoked(u id, s key)} on our notification. */
    private void pressed(MemorySegment bus, MemorySegment msg, Arena arena) {
        MemorySegment it = DBus.readIter(msg, arena);
        if (it == null || DBus.argType(it) != DBus.TYPE_UINT32 || DBus.getInt(it, arena) != notification
                || notification == 0 || !DBus.next(it) || DBus.argType(it) != DBus.TYPE_STRING) {
            return;
        }
        String key = DBus.getString(it, arena);
        if (key.equals(EXIT)) {
            menu.getLast().run().run();   // Exit Program is always the last entry
        } else if (key.equals(MOVE_TO_TRAY)) {
            moveToTray(bus, arena);
        }
    }

    private void moveToTray(MemorySegment bus, Arena arena) {
        int shown = notification;
        notification = 0;
        repostAt = 0;
        if (tray.register(bus, arena)) {
            inTray = true;
            withdraw(bus, shown);
        } else {
            // No tray here: the notification stays, and says why, posted again with that text in its place.
            noTray = true;
            withdraw(bus, shown);
            post(bus, arena);
        }
    }

    /** {@code NotificationClosed(u id, u reason)}: ours, dismissed or expired while it should be showing. Back it comes. */
    private void closed(MemorySegment msg, Arena arena) {
        MemorySegment it = DBus.readIter(msg, arena);
        if (it == null || DBus.argType(it) != DBus.TYPE_UINT32) {
            return;
        }
        int id = DBus.getInt(it, arena);
        if (id == 0 || id != notification || inTray || ending) {
            return;   // not ours, or one we withdrew ourselves
        }
        notification = 0;
        long since = System.currentTimeMillis() - postedAt;
        // Straight back, unless it was closed as soon as it appeared: then after a pause, so a notification service
        // that closes everything at once is not answered with posts as fast as the bus can carry them.
        repostAt = System.currentTimeMillis() + (since < REPOST_MILLIS ? REPOST_MILLIS : 0);
    }

    /** {@code NameOwnerChanged(s name, s old, s new)} for the tray host: gone means back to the notification. */
    private void trayHostChanged(MemorySegment bus, MemorySegment msg, Arena arena) {
        MemorySegment it = DBus.readIter(msg, arena);
        if (it == null || !DBus.next(it) || !DBus.next(it) || DBus.argType(it) != DBus.TYPE_STRING) {
            return;
        }
        boolean gone = DBus.getString(it, arena).isEmpty();
        if (inTray && gone) {
            inTray = false;
            noTray = true;
            post(bus, arena);
        } else if (inTray) {
            tray.register(bus, arena);   // a new host: show it the item again
        }
    }

    /** {@code Notify}: post the notification, remembering its id for the presses and closes that come back. */
    private void post(MemorySegment bus, Arena arena) {
        repostAt = 0;
        MemorySegment msg = DBus.newMethodCall(SERVICE, PATH, SERVICE, "Notify", arena);
        try {
            MemorySegment args = DBus.appendIter(msg, arena);
            DBus.appendString(args, application, arena);                  // app_name
            DBus.appendInt(args, DBus.TYPE_UINT32, 0, arena);              // replaces_id
            DBus.appendString(args, "system-run", arena);                  // app_icon, a themed name
            DBus.appendString(args, application + " is running in the background", arena);
            DBus.appendString(args, "An agent started it (pid " + ProcessHandle.current().pid() + "). Its windows are"
                    + " real but not shown, and take no input from you."
                    + (noTray ? " There is no system tray on this desktop to move it to, so it stays here." : ""),
                    arena);
            MemorySegment actions = DBus.open(args, DBus.TYPE_ARRAY, "s", arena);
            DBus.appendString(actions, MOVE_TO_TRAY, arena);
            DBus.appendString(actions, "Move to System Tray", arena);
            DBus.appendString(actions, EXIT, arena);
            DBus.appendString(actions, BackgroundRun.EXIT_LABEL, arena);
            DBus.close(args, actions);
            MemorySegment hints = DBus.open(args, DBus.TYPE_ARRAY, "{sv}", arena);
            boolHint(hints, "resident", arena);
            DBus.close(args, hints);
            DBus.appendInt(args, DBus.TYPE_INT32, 0, arena);               // expire_timeout: never
            MemorySegment reply = DBus.call(bus, msg, 5000, arena);
            try {
                MemorySegment it = DBus.readIter(reply, arena);
                notification = it != null && DBus.argType(it) == DBus.TYPE_UINT32 ? DBus.getInt(it, arena) : 0;
                postedAt = System.currentTimeMillis();
            } finally {
                DBus.unref(reply);
            }
        } finally {
            DBus.unref(msg);
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

    /** {@code CloseNotification(id)}, without waiting for the reply: best effort. */
    private static void withdraw(MemorySegment bus, int id) {
        if (id == 0) {
            return;
        }
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
}
