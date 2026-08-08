package dev.vexelray.os;

/**
 * Platform-agnostic keyboard keys VexelRay queries via {@link NativeWindow#isKeyDown(Key)}. Each platform maps
 * these to its own virtual-key codes. A small set for now — movement, a few actions — grown as the demo needs.
 */
public enum Key {
    W, A, S, D, Q, E,
    UP, DOWN, LEFT, RIGHT,
    SPACE, SHIFT, ESCAPE
}
