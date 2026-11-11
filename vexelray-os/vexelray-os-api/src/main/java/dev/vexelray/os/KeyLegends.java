package dev.vexelray.os;

/**
 * How a platform's keyboards print their modifier keys, which is how a shortcut should be shown to its people: the
 * caps a person looks down at are the ones a menu should draw.
 */
public enum KeyLegends {

    /** Words on the caps: Ctrl, Alt, and a logo for Super. PC keyboards, on Windows and Linux. */
    WORDS,

    /** Symbols on the caps: {@code ⌃ ⌥ ⇧ ⌘}. Apple keyboards, on macOS. */
    SYMBOLS
}
