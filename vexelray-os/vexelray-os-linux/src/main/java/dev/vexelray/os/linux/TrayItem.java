package dev.vexelray.os.linux;

import dev.vexelray.os.BackgroundRun;
import dev.vexelray.os.Icon;
import dev.vexelray.os.linux.sys.DBus;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/**
 * A background run in the system tray: a {@code StatusNotifierItem} (the icon, its title and tooltip) with a
 * {@code com.canonical.dbusmenu} menu of the run's {@link BackgroundRun#menu menu}, {@code Exit Program} last — what
 * {@code Win32Tray} is on Windows. Shown by any desktop with a tray host: KDE, Ubuntu's GNOME (its AppIndicator
 * extension), Cinnamon, XFCE, Budgie, LXQt, waybar. Stock GNOME has none, which {@link #register} reports.
 *
 * <p>No object is registered with libdbus and nothing calls back into Java: the notice thread pops every message off
 * the connection, and the calls addressed to this item's two paths are answered here, on that thread, one reply each.
 */
final class TrayItem {

    static final String WATCHER = "org.kde.StatusNotifierWatcher";
    private static final String ITEM_PATH = "/StatusNotifierItem";
    private static final String MENU_PATH = "/MenuBar";
    private static final String ITEM_IFACE = "org.kde.StatusNotifierItem";
    private static final String MENU_IFACE = "com.canonical.dbusmenu";
    private static final String PROPERTIES = "org.freedesktop.DBus.Properties";
    private static final String INTROSPECTABLE = "org.freedesktop.DBus.Introspectable";

    /** Tray icons are drawn small; larger images are left out rather than sent for nothing. */
    private static final int MAX_ICON = 64;

    private final String application;
    private final List<BackgroundRun.MenuItem> menu;
    private final Icon icon;
    /** A menu entry to run once its click has been answered, or null. */
    private BackgroundRun.MenuItem clicked;

    TrayItem(String application, List<BackgroundRun.MenuItem> menu, Icon icon) {
        this.application = application;
        this.menu = menu;
        this.icon = icon;
    }

    /** Ask the tray host to show this item. False, quietly, where there is no tray host or it refuses. */
    boolean register(MemorySegment bus, Arena arena) {
        try {
            if (!DBus.nameHasOwner(bus, WATCHER, arena)) {
                return false;
            }
            MemorySegment msg = DBus.newMethodCall(WATCHER, "/StatusNotifierWatcher", WATCHER,
                    "RegisterStatusNotifierItem", arena);
            try {
                DBus.appendString(DBus.appendIter(msg, arena), DBus.uniqueName(bus), arena);
                DBus.unref(DBus.call(bus, msg, 3000, arena));
                return true;
            } finally {
                DBus.unref(msg);
            }
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Answer {@code call} if it is addressed to this item or its menu; whether it was. */
    boolean answer(MemorySegment bus, MemorySegment call, Arena arena) {
        if (DBus.type(call) != DBus.MESSAGE_METHOD_CALL) {
            return false;
        }
        String path = DBus.path(call);
        if (!path.equals(ITEM_PATH) && !path.equals(MENU_PATH)) {
            return false;
        }
        String iface = DBus.iface(call);
        String member = DBus.member(call);
        MemorySegment reply = DBus.newReturn(call);
        try {
            boolean known = path.equals(ITEM_PATH) ? item(call, iface, member, reply, arena)
                    : menu(call, iface, member, reply, arena);
            if (known) {
                DBus.sendAndFlush(bus, reply);
            } else {
                MemorySegment error = DBus.newError(call, "org.freedesktop.DBus.Error.UnknownMethod",
                        "no " + iface + "." + member + " on " + path, arena);
                DBus.sendAndFlush(bus, error);
                DBus.unref(error);
            }
        } finally {
            DBus.unref(reply);
        }
        if (clicked != null) {
            BackgroundRun.MenuItem run = clicked;
            clicked = null;
            run.run().run();
        }
        return true;
    }

    // ---- The item ---------------------------------------------------------------------------------------------------

    private boolean item(MemorySegment call, String iface, String member, MemorySegment reply, Arena arena) {
        MemorySegment out = DBus.appendIter(reply, arena);
        if (iface.equals(PROPERTIES) && member.equals("GetAll")) {
            MemorySegment dict = DBus.open(out, DBus.TYPE_ARRAY, "{sv}", arena);
            for (String name : List.of("Category", "Id", "Title", "Status", "IconName", "IconPixmap", "ToolTip",
                    "ItemIsMenu", "Menu")) {
                MemorySegment entry = DBus.open(dict, DBus.TYPE_DICT_ENTRY, null, arena);
                DBus.appendString(entry, name, arena);
                itemProperty(entry, name, arena);
                DBus.close(dict, entry);
            }
            DBus.close(out, dict);
            return true;
        }
        if (iface.equals(PROPERTIES) && member.equals("Get")) {
            itemProperty(out, secondString(call, arena), arena);
            return true;
        }
        if (iface.equals(ITEM_IFACE)) {
            // Activate, SecondaryActivate, ContextMenu, Scroll: the item is a menu (ItemIsMenu), so the host opens it.
            return true;
        }
        if (iface.equals(INTROSPECTABLE) && member.equals("Introspect")) {
            DBus.appendString(out, ITEM_XML, arena);
            return true;
        }
        return false;
    }

    /** One property of the item, as a variant appended to {@code it}. */
    private void itemProperty(MemorySegment it, String name, Arena arena) {
        switch (name) {
            case "Category" -> variantString(it, "ApplicationStatus", arena);
            case "Id" -> variantString(it, "vexelray-" + ProcessHandle.current().pid(), arena);
            case "Title" -> variantString(it, application + " (in the background)", arena);
            case "Status" -> variantString(it, "Active", arena);
            case "IconName" -> variantString(it, pixmaps().isEmpty() ? "application-x-executable" : "", arena);
            case "IconPixmap" -> {
                MemorySegment v = DBus.open(it, DBus.TYPE_VARIANT, "a(iiay)", arena);
                appendPixmaps(v, arena);
                DBus.close(it, v);
            }
            case "ToolTip" -> {
                MemorySegment v = DBus.open(it, DBus.TYPE_VARIANT, "(sa(iiay)ss)", arena);
                MemorySegment s = DBus.open(v, DBus.TYPE_STRUCT, null, arena);
                DBus.appendString(s, "", arena);
                MemorySegment none = DBus.open(s, DBus.TYPE_ARRAY, "(iiay)", arena);
                DBus.close(s, none);
                DBus.appendString(s, application + " is running in the background", arena);
                DBus.appendString(s, "An agent started it (pid " + ProcessHandle.current().pid() + ").", arena);
                DBus.close(v, s);
                DBus.close(it, v);
            }
            case "ItemIsMenu" -> variantInt(it, DBus.TYPE_BOOLEAN, "b", 1, arena);
            case "Menu" -> {
                MemorySegment v = DBus.open(it, DBus.TYPE_VARIANT, "o", arena);
                DBus.appendObjectPath(v, MENU_PATH, arena);
                DBus.close(it, v);
            }
            default -> variantString(it, "", arena);
        }
    }

    /** The icon's images small enough for a tray, as ARGB32 in network byte order. */
    private List<Icon.Image> pixmaps() {
        List<Icon.Image> out = new ArrayList<>();
        if (icon != null) {
            for (Icon.Image image : icon.images()) {
                if (image.width() <= MAX_ICON && image.height() <= MAX_ICON) {
                    out.add(image);
                }
            }
            if (out.isEmpty() && !icon.images().isEmpty()) {
                out.add(icon.bestFor(MAX_ICON));
            }
        }
        return out;
    }

    private void appendPixmaps(MemorySegment it, Arena arena) {
        MemorySegment array = DBus.open(it, DBus.TYPE_ARRAY, "(iiay)", arena);
        for (Icon.Image image : pixmaps()) {
            MemorySegment s = DBus.open(array, DBus.TYPE_STRUCT, null, arena);
            DBus.appendInt(s, DBus.TYPE_INT32, image.width(), arena);
            DBus.appendInt(s, DBus.TYPE_INT32, image.height(), arena);
            int[] argb = image.argb();
            byte[] bytes = new byte[argb.length * 4];
            for (int i = 0; i < argb.length; i++) {
                bytes[i * 4] = (byte) (argb[i] >>> 24);
                bytes[i * 4 + 1] = (byte) (argb[i] >>> 16);
                bytes[i * 4 + 2] = (byte) (argb[i] >>> 8);
                bytes[i * 4 + 3] = (byte) argb[i];
            }
            MemorySegment ay = DBus.open(s, DBus.TYPE_ARRAY, "y", arena);
            DBus.appendBytes(ay, bytes, arena);
            DBus.close(s, ay);
            DBus.close(array, s);
        }
        DBus.close(it, array);
    }

    // ---- The menu ---------------------------------------------------------------------------------------------------

    /** Item ids: 0 is the root, and entry {@code i} of the run's menu is {@code i + 1}. */
    private boolean menu(MemorySegment call, String iface, String member, MemorySegment reply, Arena arena) {
        MemorySegment out = DBus.appendIter(reply, arena);
        if (iface.equals(MENU_IFACE)) {
            switch (member) {
                case "GetLayout" -> {
                    DBus.appendInt(out, DBus.TYPE_UINT32, 1, arena);   // the revision: the menu never changes
                    layout(out, 0, arena);
                    return true;
                }
                case "GetGroupProperties" -> {
                    MemorySegment array = DBus.open(out, DBus.TYPE_ARRAY, "(ia{sv})", arena);
                    for (int id : firstInts(call, arena)) {
                        if (id >= 0 && id <= menu.size()) {
                            MemorySegment s = DBus.open(array, DBus.TYPE_STRUCT, null, arena);
                            DBus.appendInt(s, DBus.TYPE_INT32, id, arena);
                            properties(s, id, arena);
                            DBus.close(array, s);
                        }
                    }
                    DBus.close(out, array);
                    return true;
                }
                case "GetProperty" -> {
                    variantString(out, "", arena);
                    return true;
                }
                case "Event" -> {
                    clickedOn(call, arena);
                    return true;
                }
                case "EventGroup" -> {
                    MemorySegment errors = DBus.open(out, DBus.TYPE_ARRAY, "i", arena);
                    DBus.close(out, errors);
                    return true;
                }
                case "AboutToShow" -> {
                    DBus.appendInt(out, DBus.TYPE_BOOLEAN, 0, arena);
                    return true;
                }
                case "AboutToShowGroup" -> {
                    DBus.close(out, DBus.open(out, DBus.TYPE_ARRAY, "i", arena));
                    DBus.close(out, DBus.open(out, DBus.TYPE_ARRAY, "i", arena));
                    return true;
                }
                default -> {
                    return false;
                }
            }
        }
        if (iface.equals(PROPERTIES) && member.equals("GetAll")) {
            MemorySegment dict = DBus.open(out, DBus.TYPE_ARRAY, "{sv}", arena);
            entry(dict, "Version", e -> variantInt(e, DBus.TYPE_UINT32, "u", 3, arena), arena);
            entry(dict, "TextDirection", e -> variantString(e, "ltr", arena), arena);
            entry(dict, "Status", e -> variantString(e, "normal", arena), arena);
            entry(dict, "IconThemePath", e -> {
                MemorySegment v = DBus.open(e, DBus.TYPE_VARIANT, "as", arena);
                DBus.close(v, DBus.open(v, DBus.TYPE_ARRAY, "s", arena));
                DBus.close(e, v);
            }, arena);
            DBus.close(out, dict);
            return true;
        }
        if (iface.equals(PROPERTIES) && member.equals("Get")) {
            String name = secondString(call, arena);
            if (name.equals("Version")) {
                variantInt(out, DBus.TYPE_UINT32, "u", 3, arena);
            } else {
                variantString(out, name.equals("TextDirection") ? "ltr" : name.equals("Status") ? "normal" : "",
                        arena);
            }
            return true;
        }
        if (iface.equals(INTROSPECTABLE) && member.equals("Introspect")) {
            DBus.appendString(out, MENU_XML, arena);
            return true;
        }
        return false;
    }

    /** A {@code (ia{sv}av)} node: the root holds every entry, each a leaf. */
    private void layout(MemorySegment it, int id, Arena arena) {
        MemorySegment node = DBus.open(it, DBus.TYPE_STRUCT, null, arena);
        DBus.appendInt(node, DBus.TYPE_INT32, id, arena);
        properties(node, id, arena);
        MemorySegment children = DBus.open(node, DBus.TYPE_ARRAY, "v", arena);
        if (id == 0) {
            for (int i = 1; i <= menu.size(); i++) {
                MemorySegment v = DBus.open(children, DBus.TYPE_VARIANT, "(ia{sv}av)", arena);
                layout(v, i, arena);
                DBus.close(children, v);
            }
        }
        DBus.close(node, children);
        DBus.close(it, node);
    }

    private void properties(MemorySegment it, int id, Arena arena) {
        MemorySegment dict = DBus.open(it, DBus.TYPE_ARRAY, "{sv}", arena);
        if (id == 0) {
            entry(dict, "children-display", e -> variantString(e, "submenu", arena), arena);
        } else {
            String label = menu.get(id - 1).label();
            entry(dict, "label", e -> variantString(e, label, arena), arena);
            entry(dict, "enabled", e -> variantInt(e, DBus.TYPE_BOOLEAN, "b", 1, arena), arena);
            entry(dict, "visible", e -> variantInt(e, DBus.TYPE_BOOLEAN, "b", 1, arena), arena);
        }
        DBus.close(it, dict);
    }

    /** {@code Event(i id, s eventId, v data, u timestamp)}: a click on an entry, run once it has been answered. */
    private void clickedOn(MemorySegment call, Arena arena) {
        MemorySegment it = DBus.readIter(call, arena);
        if (it == null || DBus.argType(it) != DBus.TYPE_INT32) {
            return;
        }
        int id = DBus.getInt(it, arena);
        if (!DBus.next(it) || DBus.argType(it) != DBus.TYPE_STRING) {
            return;
        }
        if (DBus.getString(it, arena).equals("clicked") && id >= 1 && id <= menu.size()) {
            clicked = menu.get(id - 1);
        }
    }

    // ---- Writing and reading values ---------------------------------------------------------------------------------

    private interface Value {
        void into(MemorySegment entry);
    }

    private static void entry(MemorySegment dict, String name, Value value, Arena arena) {
        MemorySegment entry = DBus.open(dict, DBus.TYPE_DICT_ENTRY, null, arena);
        DBus.appendString(entry, name, arena);
        value.into(entry);
        DBus.close(dict, entry);
    }

    private static void variantString(MemorySegment it, String value, Arena arena) {
        MemorySegment v = DBus.open(it, DBus.TYPE_VARIANT, "s", arena);
        DBus.appendString(v, value, arena);
        DBus.close(it, v);
    }

    private static void variantInt(MemorySegment it, int type, String signature, int value, Arena arena) {
        MemorySegment v = DBus.open(it, DBus.TYPE_VARIANT, signature, arena);
        DBus.appendInt(v, type, value, arena);
        DBus.close(it, v);
    }

    /** The second argument as a string: the property name of {@code Properties.Get(s iface, s name)}. */
    private static String secondString(MemorySegment call, Arena arena) {
        MemorySegment it = DBus.readIter(call, arena);
        if (it == null || !DBus.next(it) || DBus.argType(it) != DBus.TYPE_STRING) {
            return "";
        }
        return DBus.getString(it, arena);
    }

    /** The first argument as an array of ints ({@code ai}). */
    private static List<Integer> firstInts(MemorySegment call, Arena arena) {
        List<Integer> ids = new ArrayList<>();
        MemorySegment it = DBus.readIter(call, arena);
        if (it == null || DBus.argType(it) != DBus.TYPE_ARRAY) {
            return ids;
        }
        MemorySegment sub = DBus.recurse(it, arena);
        while (DBus.argType(sub) == DBus.TYPE_INT32) {
            ids.add(DBus.getInt(sub, arena));
            if (!DBus.next(sub)) {
                break;
            }
        }
        return ids;
    }

    private static final String ITEM_XML = """
            <node>
              <interface name="org.kde.StatusNotifierItem">
                <property name="Category" type="s" access="read"/>
                <property name="Id" type="s" access="read"/>
                <property name="Title" type="s" access="read"/>
                <property name="Status" type="s" access="read"/>
                <property name="IconName" type="s" access="read"/>
                <property name="IconPixmap" type="a(iiay)" access="read"/>
                <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
                <property name="ItemIsMenu" type="b" access="read"/>
                <property name="Menu" type="o" access="read"/>
                <method name="ContextMenu"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
                <method name="Activate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
                <method name="SecondaryActivate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
                <method name="Scroll"><arg name="delta" type="i" direction="in"/><arg name="orientation" type="s" direction="in"/></method>
              </interface>
            </node>
            """;

    private static final String MENU_XML = """
            <node>
              <interface name="com.canonical.dbusmenu">
                <property name="Version" type="u" access="read"/>
                <property name="TextDirection" type="s" access="read"/>
                <property name="Status" type="s" access="read"/>
                <property name="IconThemePath" type="as" access="read"/>
                <method name="GetLayout"><arg type="i" direction="in"/><arg type="i" direction="in"/><arg type="as" direction="in"/><arg type="u" direction="out"/><arg type="(ia{sv}av)" direction="out"/></method>
                <method name="GetGroupProperties"><arg type="ai" direction="in"/><arg type="as" direction="in"/><arg type="a(ia{sv})" direction="out"/></method>
                <method name="GetProperty"><arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="out"/></method>
                <method name="Event"><arg type="i" direction="in"/><arg type="s" direction="in"/><arg type="v" direction="in"/><arg type="u" direction="in"/></method>
                <method name="EventGroup"><arg type="a(isvu)" direction="in"/><arg type="ai" direction="out"/></method>
                <method name="AboutToShow"><arg type="i" direction="in"/><arg type="b" direction="out"/></method>
                <method name="AboutToShowGroup"><arg type="ai" direction="in"/><arg type="ai" direction="out"/><arg type="ai" direction="out"/></method>
              </interface>
            </node>
            """;
}
