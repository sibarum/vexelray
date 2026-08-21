package dev.vexelray.os;

import java.util.List;

/**
 * Where a {@link Decorations#CLIENT} window's own drawing is caption and where it is content — the whole of the
 * conversation between a GUI that paints its own frame and the window manager that still moves and sizes it.
 *
 * <p>This is <b>declared, not driven</b>. The application pushes the regions it currently draws (once per frame,
 * derived from its layout, {@link NativeWindow#setHitRegions}); the platform answers the window manager's
 * hit-test from them. Nothing here is an event and nothing here initiates anything, so a GUI keeps its input
 * going through whatever input stack it already uses — this describes geometry only.
 *
 * <p>All coordinates are pixels in the window's client space, the same space the GUI lays out in, with the
 * origin at the client area's top-left.
 *
 * @param caption        rectangles that behave as a title bar: drag to move, double-click to maximize,
 *                       right-click for the system menu
 * @param interactive    rectangles that are ordinary content even where they overlap a caption rectangle — the
 *                       holes a caption is punched with, for the buttons and controls drawn on it
 * @param maximizeButton the application's maximize button, or {@code null} if it draws none. Reported to the
 *                       window manager as such, which is what lets a platform offer its own window-arrangement
 *                       affordance on hover (Windows 11's Snap Layouts flyout). Clicks still arrive as ordinary
 *                       content clicks — the application decides what its button does.
 * @param resizeBorder   thickness, in pixels, of the resize band inside each edge; {@code 0} asks the platform
 *                       for its own default (the metric the system-drawn frame would use)
 */
public record HitRegions(List<Rect> caption, List<Rect> interactive, Rect maximizeButton, int resizeBorder) {

    /** No caption anywhere: every pixel is content, and the platform's default resize band applies. */
    public static final HitRegions NONE = new HitRegions(List.of(), List.of(), null, 0);

    public HitRegions {
        caption = List.copyOf(caption);
        interactive = List.copyOf(interactive);
        if (resizeBorder < 0) {
            throw new IllegalArgumentException("resizeBorder must not be negative, got " + resizeBorder);
        }
    }

    /** An integer rectangle in client space. */
    public record Rect(int x, int y, int w, int h) {

        /** Whether {@code (px, py)} lies within this rectangle (left/top inclusive, right/bottom exclusive). */
        public boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** What the point under the pointer is, as far as the window manager is concerned. */
    public enum Zone {
        /** Ordinary content: the application's own hit-testing decides what happens. */
        CLIENT,
        /** Title bar: the window manager moves the window from here. */
        CAPTION,
        /** The application's maximize button. */
        MAXIMIZE_BUTTON,
        TOP, BOTTOM, LEFT, RIGHT, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT;

        /** Whether this zone is one of the eight resize edges/corners. */
        public boolean isResize() {
            return ordinal() >= TOP.ordinal();
        }
    }

    /**
     * Resolve {@code (px, py)} in a client area of {@code clientW × clientH} against these regions. Pure — the
     * policy every platform shares, so each backend only maps the result onto its own hit-test codes.
     *
     * <p><b>Precedence, and why.</b> Resize edges come first: a title bar that runs to the top of the window
     * would otherwise swallow the top resize band, and a window you cannot grab the top edge of is the classic
     * custom-chrome bug. {@code maximizeButton} and {@code interactive} then beat {@code caption}, so a button
     * drawn on the title bar is a button; without that the caption would swallow its own controls. Everything
     * else is content.
     *
     * <p>A maximized window has no resize band — its edges belong to the screen, not to a drag.
     *
     * @param border the resize-band thickness to use, resolving {@link #resizeBorder} {@code == 0} against the
     *               platform's own metric before calling
     */
    public Zone zone(int px, int py, int clientW, int clientH, boolean maximized, int border) {
        if (!maximized && border > 0) {
            boolean left = px < border;
            boolean right = px >= clientW - border;
            boolean top = py < border;
            boolean bottom = py >= clientH - border;
            if (top && left) {
                return Zone.TOP_LEFT;
            }
            if (top && right) {
                return Zone.TOP_RIGHT;
            }
            if (bottom && left) {
                return Zone.BOTTOM_LEFT;
            }
            if (bottom && right) {
                return Zone.BOTTOM_RIGHT;
            }
            if (top) {
                return Zone.TOP;
            }
            if (bottom) {
                return Zone.BOTTOM;
            }
            if (left) {
                return Zone.LEFT;
            }
            if (right) {
                return Zone.RIGHT;
            }
        }
        if (maximizeButton != null && maximizeButton.contains(px, py)) {
            return Zone.MAXIMIZE_BUTTON;
        }
        for (Rect r : interactive) {
            if (r.contains(px, py)) {
                return Zone.CLIENT;
            }
        }
        for (Rect r : caption) {
            if (r.contains(px, py)) {
                return Zone.CAPTION;
            }
        }
        return Zone.CLIENT;
    }
}
