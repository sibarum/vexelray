package dev.vexelray.trace;

import sibarum.probe.Cause;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

/**
 * The entry woven code calls. Not for application code: a method says it is traced with {@link Trace}, and the
 * weaver writes the calls.
 *
 * <p>Every woven call is behind {@link #ON}, a {@code static final} decided once, as {@link Probe#ON} is, so on the
 * JVM an untraced run's guard is folded away by the JIT and costs nothing. Behind it, the {@code trace} lane is
 * asked, so a run that probes other lanes does not write trace rows.
 *
 * <p>A row's kind is the constant the weaver baked in, {@code trace.<label>}, and its detail is the site's id and
 * the arguments' raw values: integers widened, a {@code float} or {@code double} as its bits, an enum as its
 * ordinal, a string as its length, {@code -1} for null. The site's table says how each reads back
 * ({@link Site.Arg#format}); nothing is formatted for a person here but the decimal digits.
 */
public final class Tracing {

    /** Whether trace calls do anything in this process at all. */
    public static final boolean ON = Probe.ON;

    static {
        if (ON && Lane.TRACE.on()) {
            // Every site the class path knows, once, first: a recording then names its own sites, and needs no
            // table beside it to be read. In no cause: this runs inside whichever traced call came first, and what
            // the build contains is not that call's doing.
            long previous = Cause.enter(Cause.NONE);
            try {
                for (Site s : Sites.all()) {
                    StringBuilder args = new StringBuilder();
                    for (Site.Arg a : s.args()) {
                        args.append(args.isEmpty() ? " " : ",").append(a.name()).append(':').append(a.type());
                    }
                    Probe.mark(Lane.TRACE, "trace.site", s.key() + " " + s.label() + " " + s.where() + args);
                }
            } catch (RuntimeException e) {
                Probe.mark(Lane.TRACE, "trace.site", "tables not read: " + e.getMessage());
            } finally {
                Cause.exit(previous);
            }
        }
    }

    private Tracing() {
    }

    public static void enter(String kind, long site) {
        if (Lane.TRACE.on()) {
            Probe.mark(Lane.TRACE, kind, Site.hex(site));
        }
    }

    public static void enter(String kind, long site, long a) {
        if (Lane.TRACE.on()) {
            Probe.mark(Lane.TRACE, kind, Site.hex(site) + " " + a);
        }
    }

    public static void enter(String kind, long site, long a, long b) {
        if (Lane.TRACE.on()) {
            Probe.mark(Lane.TRACE, kind, Site.hex(site) + " " + a + " " + b);
        }
    }

    public static void enter(String kind, long site, long a, long b, long c) {
        if (Lane.TRACE.on()) {
            Probe.mark(Lane.TRACE, kind, Site.hex(site) + " " + a + " " + b + " " + c);
        }
    }

    public static void enter(String kind, long site, long a, long b, long c, long d) {
        if (Lane.TRACE.on()) {
            Probe.mark(Lane.TRACE, kind, Site.hex(site) + " " + a + " " + b + " " + c + " " + d);
        }
    }

    /** A string argument of a site with {@link Trace#content}, in full, on a row of its own after the entry. */
    public static void text(long site, String value) {
        if (Lane.TRACE.on()) {
            Probe.mark(Lane.TRACE, "trace.text", Site.hex(site) + " " + value);
        }
    }

    /** A {@link Trace#timed} site returning, or throwing, {@code System.nanoTime() - started} after it began. */
    public static void exit(long site, long started, boolean threw) {
        if (Lane.TRACE.on()) {
            Probe.mark(Lane.TRACE, "trace.exit", Site.hex(site) + " " + (System.nanoTime() - started)
                    + (threw ? " threw" : ""));
        }
    }

    /** An enum argument's raw value: its ordinal, or -1. */
    public static long ordinal(Enum<?> value) {
        return value == null ? -1L : value.ordinal();
    }

    /** A string argument's raw value: its length, or -1. */
    public static long length(String value) {
        return value == null ? -1L : value.length();
    }
}
