package dev.vexelray.os;

/**
 * Who draws the window frame — the title bar, the borders, the caption buttons.
 *
 * <p>The choice is only about <em>pixels</em>, never about behaviour: dragging, resizing, snapping, maximizing
 * and the system menu remain the window manager's job in every mode. {@link #CLIENT} does not take those over;
 * it extends the client area over the frame and lets the application say, through {@link HitRegions}, which
 * parts of its own drawing are to be treated as caption and which as ordinary content. The OS then runs exactly
 * the same move/resize/snap machinery it runs for a system-drawn frame.
 *
 * <p>That distinction is what keeps the mode cheap. An application that draws its own frame by making the window
 * border-less and moving it itself must re-implement snap, keyboard move/size, double-click-to-maximize, the
 * work-area clamp when maximized and per-monitor DPI transitions — each of which is a behaviour users already
 * have, and each of which is a bug when it differs.
 */
public enum Decorations {

    /** The OS draws the frame: its title bar, its borders, its caption buttons. The default. */
    SYSTEM,

    /**
     * The application draws the frame. The client area is extended over the whole window, so the GUI paints
     * every pixel including where the title bar used to be, while the window keeps its ordinary window-manager
     * behaviour: the frame is still there, it is simply invisible and reported through {@link HitRegions}.
     */
    CLIENT,

    /**
     * No frame at all: no title bar, no borders, and no window-manager move or resize. For windows that are not
     * dragged or sized by the user — splash screens, tooltips, drop-down surfaces.
     */
    NONE
}
