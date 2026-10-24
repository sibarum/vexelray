package dev.vexelray.trace;

/**
 * One module's trace sites. The weaver generates an implementation per woven module and lists it in
 * {@code META-INF/services}, so {@link Sites} finds every module's table with {@link java.util.ServiceLoader}: a
 * class rather than a resource, which a native image includes with no reachability metadata.
 */
public interface TraceSites {

    /** The module's sites, one a line, in the form {@link Site#parse} reads. */
    String table();
}
