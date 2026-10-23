package dev.vexelray.os.linux;

import dev.vexelray.os.linux.sys.DBus;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Optional;

/**
 * Whether the desktop has animations turned off, asked through the XDG desktop portal's {@code Settings} interface:
 * the one place on Linux that answers for whichever desktop is running, sandboxed or not, without reading another
 * program's configuration files.
 *
 * <p>Two keys, because the desktops do not share one: GNOME (and what follows it) has
 * {@code org.gnome.desktop.interface enable-animations}, false when animations are off; KDE has
 * {@code org.kde.kdeglobals.KDE AnimationDurationFactor}, zero when they are. The first desktop that answers decides;
 * none answering, or no portal at all, is "cannot say".
 */
final class MotionPreference {

    private static final String PORTAL = "org.freedesktop.portal.Desktop";
    private static final String PATH = "/org/freedesktop/portal/desktop";
    private static final String SETTINGS = "org.freedesktop.portal.Settings";
    private static final int TIMEOUT_MILLIS = 500;

    private MotionPreference() {
    }

    /** Whether the user has asked for reduced motion; empty when no desktop answers. */
    static Optional<Boolean> reducedMotion() {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bus = DBus.sessionBus(arena);
            Optional<Boolean> gnome = read(bus, "org.gnome.desktop.interface", "enable-animations", arena)
                    .filter(v -> v instanceof Boolean).map(v -> !(Boolean) v);
            if (gnome.isPresent()) {
                return gnome;
            }
            return read(bus, "org.kde.kdeglobals.KDE", "AnimationDurationFactor", arena)
                    .filter(v -> v instanceof Double).map(v -> (Double) v <= 0.0);
        } catch (RuntimeException | LinkageError e) {
            // No session bus, no portal, or no libdbus at all (which fails the binding's class initialisation, an
            // Error rather than an exception): the desktop cannot say.
            return Optional.empty();
        }
    }

    /**
     * One setting's value, a {@link Boolean} or a {@link Double}; empty when the portal does not have it.
     * {@code ReadOne} (portal version 2) answers with the value in a variant; {@code Read}, which older portals
     * have instead, with that variant in another, so the read unwraps however many there are.
     */
    private static Optional<Object> read(MemorySegment bus, String namespace, String key, Arena arena) {
        for (String method : new String[] {"ReadOne", "Read"}) {
            MemorySegment call = DBus.newMethodCall(PORTAL, PATH, SETTINGS, method, arena);
            MemorySegment reply;
            try {
                MemorySegment args = DBus.appendIter(call, arena);
                DBus.appendString(args, namespace, arena);
                DBus.appendString(args, key, arena);
                reply = DBus.call(bus, call, TIMEOUT_MILLIS, arena);
            } catch (RuntimeException e) {
                continue;   // not this method, or not this key: try the next, or say nothing
            } finally {
                DBus.unref(call);
            }
            try {
                MemorySegment it = DBus.readIter(reply, arena);
                while (it != null && DBus.argType(it) == DBus.TYPE_VARIANT) {
                    it = DBus.recurse(it, arena);
                }
                if (it == null) {
                    return Optional.empty();
                }
                int type = DBus.argType(it);
                if (type == DBus.TYPE_BOOLEAN) {
                    return Optional.of(DBus.getInt(it, arena) != 0);
                }
                if (type == DBus.TYPE_DOUBLE) {
                    return Optional.of(DBus.getDouble(it, arena));
                }
                return Optional.empty();
            } finally {
                DBus.unref(reply);
            }
        }
        return Optional.empty();
    }
}
