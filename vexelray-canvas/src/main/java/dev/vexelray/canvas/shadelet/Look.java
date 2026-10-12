package dev.vexelray.canvas.shadelet;

import java.util.Arrays;
import java.util.List;

/**
 * What draws an element: a source, the modifiers that reshape its distance, and the painters that colour it, front to
 * back, each named with its shared parameters. A state change (hover, pressed) is a different look, not new data.
 *
 * @param source    the source and its style
 * @param modifiers in order
 * @param painters  front to back
 */
public record Look(Use source, List<Use> modifiers, List<Use> painters) {

    /**
     * One shadelet in a look.
     *
     * @param shadelet its name
     * @param shared   its shared fields' values, in its layout's order
     */
    public record Use(String shadelet, float[] shared) {
        public Use {
            shared = shared.clone();
        }

        public static Use of(String shadelet, float... shared) {
            return new Use(shadelet, shared);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Use u && u.shadelet.equals(shadelet) && Arrays.equals(u.shared, shared);
        }

        @Override
        public int hashCode() {
            return shadelet.hashCode() * 31 + Arrays.hashCode(shared);
        }

        @Override
        public String toString() {
            return shadelet + Arrays.toString(shared);
        }
    }

    public Look {
        modifiers = List.copyOf(modifiers);
        painters = List.copyOf(painters);
        if (painters.isEmpty()) {
            throw new IllegalArgumentException("a look with no painter draws nothing");
        }
    }

    /** A source and painters, no modifiers. */
    public static Look of(Use source, Use... painters) {
        return new Look(source, List.of(), List.of(painters));
    }
}
