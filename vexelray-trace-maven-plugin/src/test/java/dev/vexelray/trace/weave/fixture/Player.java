package dev.vexelray.trace.weave.fixture;

import dev.vexelray.trace.Trace;

/** Traced methods of every shape the weaver handles. Woven by the tests, from a copy; never run unwoven by them. */
public class Player {

    /** What a mode says, so a test can tell the method still ran. */
    public enum Mode { PLAY, PAUSE }

    private long at;

    @Trace(value = "seek", args = {"position"})
    public long seek(long position) {
        at = position;
        return at;
    }

    /** A pinned id, and an argument of each recorded kind. */
    @Trace(id = 0x5EEDL, args = {"mode", "name", "rate"})
    public static String mode(Mode mode, String name, double rate) {
        return mode + " " + name + " " + rate;
    }

    /** Timed, with its string written in full, and two ways out besides a throw. */
    @Trace(timed = true, content = true, args = {"title"})
    public int open(String title) {
        if (title.equals("boom")) {
            throw new IllegalStateException("cannot open " + title);
        }
        if (title.isEmpty()) {
            return -1;
        }
        return title.length();
    }

    public long untraced(long x) {
        return x + 1;
    }
}
