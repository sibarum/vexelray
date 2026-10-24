package dev.vexelray.trace;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Every module's trace sites, merged: what a reader resolves a row's site id with. Loaded once, through
 * {@link ServiceLoader}, from the {@link TraceSites} the weaver generated in each woven module.
 */
public final class Sites {

    private static volatile Map<Long, Site> all;

    private Sites() {
    }

    /** Every site on the class path, by id. */
    public static Collection<Site> all() {
        return table().values();
    }

    /** The site with this id, or {@code null}. */
    public static Site byId(long id) {
        return table().get(id);
    }

    private static Map<Long, Site> table() {
        Map<Long, Site> t = all;
        if (t == null) {
            synchronized (Sites.class) {
                t = all;
                if (t == null) {
                    // The context loader, as ServiceLoader's own default: in an application it is the one that
                    // loaded the application, and a host that loads modules itself points it at them.
                    ClassLoader loader = Thread.currentThread().getContextClassLoader();
                    t = merge(ServiceLoader.load(TraceSites.class,
                                    loader != null ? loader : Sites.class.getClassLoader()).stream()
                            .map(p -> Site.parse(p.get().table())).toList());
                    all = t;
                }
            }
        }
        return t;
    }

    /**
     * {@code tables} merged by id. Two modules that bring one id are refused, naming both sites: the weaver
     * refuses it within a module, and this is the same rule across them, where only loading can see it.
     */
    static Map<Long, Site> merge(List<List<Site>> tables) {
        Map<Long, Site> merged = new HashMap<>();
        for (List<Site> table : tables) {
            for (Site s : table) {
                Site other = merged.putIfAbsent(s.id(), s);
                if (other != null && !other.equals(s)) {
                    throw new IllegalStateException("two trace sites share the id " + s.key() + ": "
                            + other.where() + " and " + s.where() + "; pin one with @Trace(id = ...)");
                }
            }
        }
        return Map.copyOf(merged);
    }
}
