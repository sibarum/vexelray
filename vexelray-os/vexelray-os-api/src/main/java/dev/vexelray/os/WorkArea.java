package dev.vexelray.os;

/**
 * A monitor's usable area in screen coordinates — its full rectangle minus the space the desktop reserves for
 * itself (the taskbar, a dock, a panel). The rectangle a window should be inside.
 *
 * <p>This exists for one job: an application restoring a window where the user last left it has to answer
 * "is that still a place?". Monitors get unplugged, resolutions change, a laptop comes back from a dock — and
 * bounds saved on a three-monitor desk must not restore a window onto a screen that is no longer there. The
 * platform reports the area; {@link #fit} decides what to do about it.
 *
 * <p>Coordinates match {@link NativeWindow#screenX()} / {@link NativeWindow#outerWidth()} — the outer rect in
 * physical screen pixels — so a saved window rectangle and a work area are directly comparable with no scaling
 * step in between.
 *
 * @param x      screen x of the usable area's top-left
 * @param y      screen y of the usable area's top-left
 * @param width  usable width in pixels
 * @param height usable height in pixels
 */
public record WorkArea(int x, int y, int width, int height) {

    public WorkArea {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("work area must be positive, got " + width + "x" + height);
        }
    }

    /** Screen x just past the right edge. */
    public int right() {
        return x + width;
    }

    /** Screen y just past the bottom edge. */
    public int bottom() {
        return y + height;
    }

    /** Whether the point {@code (px, py)} is inside (left/top inclusive, right/bottom exclusive). */
    public boolean contains(int px, int py) {
        return px >= x && px < right() && py >= y && py < bottom();
    }

    /**
     * The nearest rectangle to {@code (wx, wy, w, h)} that fits inside this area: shrunk first if it is larger
     * than the area, then nudged until it is fully inside.
     *
     * <p>Shrink-before-nudge is the order that cannot fail. Nudging first leaves an oversized window with its
     * top-left in bounds and its bottom-right outside, and no amount of further nudging fixes that; shrinking
     * first means the nudge always has somewhere to go. Both steps preserve as much of the request as they can,
     * so a window that already fits comes back untouched — the common case is a no-op.
     *
     * @param minWidth  the smallest width worth restoring to, so shrinking cannot produce a sliver
     * @param minHeight the smallest height worth restoring to
     */
    public Bounds fit(int wx, int wy, int w, int h, int minWidth, int minHeight) {
        int fw = Math.max(Math.min(minWidth, width), Math.min(w, width));
        int fh = Math.max(Math.min(minHeight, height), Math.min(h, height));
        // Clamp the top-left into the range that keeps the whole rectangle inside. The lower bound wins where the
        // window is still wider than the area (only possible when minWidth exceeds it), which keeps the title bar
        // reachable rather than centring the overflow.
        int fx = Math.min(Math.max(wx, x), Math.max(x, right() - fw));
        int fy = Math.min(Math.max(wy, y), Math.max(y, bottom() - fh));
        return new Bounds(fx, fy, fw, fh);
    }

    /** An outer window rectangle in screen coordinates. */
    public record Bounds(int x, int y, int width, int height) {
    }
}
