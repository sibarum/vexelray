package dev.vexelray.trace.weave.bad;

import dev.vexelray.trace.Trace;

import java.util.List;

/** Sites the weaver must refuse, one class each, so a test weaves exactly the mistake it names. */
public final class BadSites {

    private BadSites() {
    }

    public static final class ObjectArgument {
        @Trace(args = {"items"})
        public void add(List<String> items) {
        }
    }

    public static final class NoSuchParameter {
        @Trace(args = {"nope"})
        public void go(int value) {
        }
    }

    public static final class TooMany {
        @Trace(args = {"a", "b", "c", "d", "e"})
        public void five(int a, int b, int c, int d, int e) {
        }
    }

    public static final class SharedId {
        @Trace(id = 7L)
        public void one() {
        }

        @Trace(id = 7L)
        public void two() {
        }
    }

    public static final class ContentWithoutText {
        @Trace(content = true, args = {"n"})
        public void count(int n) {
        }
    }
}
