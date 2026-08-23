package dev.vexelray.os;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The precedence rules in {@link HitRegions#zone} — the portable half of client-drawn chrome, and the half every
 * platform backend shares. Each case here is a bug that custom window frames actually have in the wild.
 */
class HitRegionsTest {

    private static final int W = 800;
    private static final int H = 600;
    private static final int BORDER = 8;
    /** An application gutter — the margin a window leaves around its content, and the band it can ask for. */
    private static final int GUTTER = 16;

    /** A 32px title bar across the top, with three 46px caption buttons at its right end. */
    private static HitRegions bar() {
        return new HitRegions(
                List.of(new HitRegions.Rect(0, 0, W, 32)),
                List.of(new HitRegions.Rect(W - 138, 0, 46, 32), new HitRegions.Rect(W - 46, 0, 46, 32)),
                new HitRegions.Rect(W - 92, 0, 46, 32),
                BORDER);
    }

    private static HitRegions.Zone at(int x, int y) {
        return bar().zone(x, y, W, H, false, BORDER);
    }

    @Test
    void theTitleBarIsCaptionWhereNothingElseClaimsIt() {
        assertEquals(HitRegions.Zone.CAPTION, at(300, 20));
    }

    @Test
    void contentBelowTheTitleBarIsOrdinaryClient() {
        assertEquals(HitRegions.Zone.CLIENT, at(300, 300));
    }

    @Test
    void aButtonDrawnOnTheCaptionIsNotCaption() {
        // Without this the window manager starts a window drag from the close button and the click never lands.
        assertEquals(HitRegions.Zone.CLIENT, at(W - 20, 20));
        assertEquals(HitRegions.Zone.CLIENT, at(W - 120, 20));
    }

    @Test
    void theMaximizeButtonIsReportedAsItself() {
        // What earns the platform's own window-arrangement affordance on hover (Snap Layouts).
        assertEquals(HitRegions.Zone.MAXIMIZE_BUTTON, at(W - 70, 20));
    }

    @Test
    void theResizeBandWinsOverTheTitleBarAtTheTopEdge() {
        // The classic custom-chrome bug: a caption that runs to y=0 swallows the top resize band, and the window
        // can no longer be grabbed by its top edge.
        assertEquals(HitRegions.Zone.TOP, at(300, 2));
        assertEquals(HitRegions.Zone.TOP_LEFT, at(2, 2));
        assertEquals(HitRegions.Zone.TOP_RIGHT, at(W - 2, 2));
    }

    @Test
    void theResizeBandWinsOverACaptionButtonToo() {
        // Same rule, and it has to beat the buttons as well, or the top-right corner is ungrabbable — which is
        // exactly where a user reaches to size a window.
        assertEquals(HitRegions.Zone.TOP_RIGHT, at(W - 4, 3));
    }

    @Test
    void everyEdgeAndCornerIsReachable() {
        assertEquals(HitRegions.Zone.LEFT, at(3, 300));
        assertEquals(HitRegions.Zone.RIGHT, at(W - 3, 300));
        assertEquals(HitRegions.Zone.BOTTOM, at(300, H - 3));
        assertEquals(HitRegions.Zone.BOTTOM_LEFT, at(3, H - 3));
        assertEquals(HitRegions.Zone.BOTTOM_RIGHT, at(W - 3, H - 3));
    }

    @Test
    void aMaximizedWindowHasNoResizeBand() {
        // Its edges belong to the screen. Keeping the band would make the title bar of a maximized window
        // undraggable along its top row and would offer a resize that cannot happen.
        assertEquals(HitRegions.Zone.CAPTION, bar().zone(300, 2, W, H, true, BORDER));
        assertEquals(HitRegions.Zone.CLIENT, bar().zone(3, 300, W, H, true, BORDER));
    }

    @Test
    void noRegionsMeansEveryPixelIsContent() {
        assertEquals(HitRegions.Zone.CLIENT, HitRegions.NONE.zone(300, 4, W, H, false, 0));
    }

    @Test
    void aZeroBorderDisablesTheResizeBandWithoutDisablingTheCaption() {
        assertEquals(HitRegions.Zone.CAPTION, bar().zone(300, 2, W, H, false, 0));
    }

    /** The same window, asked for a band as wide as a 16px gutter everywhere the application drew nothing. */
    private static HitRegions.Zone atWide(int x, int y) {
        return bar().zone(x, y, W, H, false, GUTTER, BORDER);
    }

    @Test
    void aWidenedBandReachesTheWholeGutter() {
        // The point of it: the dead space a margin leaves is resize surface, so the edge is found a whole gutter
        // early instead of on the last three pixels.
        assertEquals(HitRegions.Zone.LEFT, atWide(GUTTER - 1, 300));
        assertEquals(HitRegions.Zone.RIGHT, atWide(W - GUTTER, 300));
        assertEquals(HitRegions.Zone.BOTTOM, atWide(300, H - GUTTER));
        assertEquals(HitRegions.Zone.BOTTOM_LEFT, atWide(GUTTER - 1, H - GUTTER));
        assertEquals(HitRegions.Zone.BOTTOM_RIGHT, atWide(W - GUTTER, H - GUTTER));
        // And it stops there: a pixel further in is the application's again.
        assertEquals(HitRegions.Zone.CLIENT, atWide(GUTTER, 300));
    }

    @Test
    void aWidenedBandDoesNotEatTheTitleBar() {
        // What makes widening safe. Inside the caption the system metric still applies, so a bar 32 tall keeps
        // all but its top few pixels draggable rather than losing half of itself to a resize band.
        assertEquals(HitRegions.Zone.TOP, atWide(300, BORDER - 1));
        assertEquals(HitRegions.Zone.CAPTION, atWide(300, BORDER));
        assertEquals(HitRegions.Zone.CAPTION, atWide(300, GUTTER));
        // Including at the left end of the bar, where the wide left band would otherwise run down through it.
        assertEquals(HitRegions.Zone.CAPTION, atWide(GUTTER - 1, 20));
    }

    @Test
    void aWidenedBandDoesNotEatACaptionButton() {
        // A close button that stops working within a gutter of the corner is worse than no widening at all.
        assertEquals(HitRegions.Zone.CLIENT, atWide(W - 20, 20));
        assertEquals(HitRegions.Zone.MAXIMIZE_BUTTON, atWide(W - 70, 20));
        // The system band over the buttons is untouched, so the top-right corner is still grabbable.
        assertEquals(HitRegions.Zone.TOP_RIGHT, atWide(W - 4, 3));
    }

    @Test
    void oneBandIsWhatTheOldSignatureAsksFor() {
        // The six-argument form is the same call with both thicknesses equal — nothing that already worked moved.
        assertEquals(bar().zone(300, 2, W, H, false, BORDER),
                bar().zone(300, 2, W, H, false, BORDER, BORDER));
        assertEquals(HitRegions.Zone.TOP, bar().zone(300, GUTTER - 1, W, H, false, GUTTER, GUTTER));
    }

    @Test
    void resizeZonesAreDistinguishableFromTheRest() {
        assertEquals(true, HitRegions.Zone.TOP_LEFT.isResize());
        assertEquals(false, HitRegions.Zone.CAPTION.isResize());
        assertEquals(false, HitRegions.Zone.MAXIMIZE_BUTTON.isResize());
    }
}
