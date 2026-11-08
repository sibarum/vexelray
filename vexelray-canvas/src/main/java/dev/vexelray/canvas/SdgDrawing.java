package dev.vexelray.canvas;

import dev.vexelray.fields.sdg.Sdg;

import java.util.List;

/**
 * An SDG drawing as the canvas draws it: its canvas, its layers, and the size of each page, with the texels wherever
 * they were uploaded. {@link Sdg} holds its pages' texels too, which a drawing read from a file needs once to upload;
 * this does not, so a drawing over texels already on the device (an icon atlas, uploaded once for every icon in it)
 * costs nothing to make.
 *
 * @param width       the drawing's canvas across, in its units
 * @param height      its canvas down, in its units
 * @param layers      back to front, each reading pages by number
 * @param pageWidths  page {@code i} is {@code pageWidths[i]} texels across
 * @param pageHeights and {@code pageHeights[i]} down
 */
public record SdgDrawing(double width, double height, List<Sdg.Layer> layers, int[] pageWidths, int[] pageHeights) {

    public SdgDrawing {
        if (!(width > 0) || !(height > 0) || pageWidths.length != pageHeights.length) {
            throw new IllegalArgumentException("a drawing " + width + " × " + height + " on " + pageWidths.length
                    + " pages");
        }
        layers = List.copyOf(layers);
        pageWidths = pageWidths.clone();
        pageHeights = pageHeights.clone();
        for (Sdg.Layer l : layers) {
            if (l.stencil().page() >= pageWidths.length
                    || l.paint() instanceof Sdg.Paint.Sampled s && s.page() >= pageWidths.length) {
                throw new IllegalArgumentException("layer " + l.name() + " reads a page the drawing does not have");
            }
        }
    }

    /** {@code sdg}'s canvas, layers and page sizes, as they are: colocate it first ({@link SdgPages#colocated}). */
    public static SdgDrawing of(Sdg sdg) {
        int n = sdg.pages().size();
        int[] w = new int[n], h = new int[n];
        for (int i = 0; i < n; i++) {
            w[i] = sdg.pages().get(i).width();
            h[i] = sdg.pages().get(i).height();
        }
        return new SdgDrawing(sdg.width(), sdg.height(), sdg.layers(), w, h);
    }

    /** How many pages it reads. */
    public int pages() {
        return pageWidths.length;
    }

    /** Page {@code i}'s width in texels. */
    public int pageWidth(int i) {
        return pageWidths[i];
    }

    /** Page {@code i}'s height in texels. */
    public int pageHeight(int i) {
        return pageHeights[i];
    }

    @Override
    public int[] pageWidths() {
        return pageWidths.clone();
    }

    @Override
    public int[] pageHeights() {
        return pageHeights.clone();
    }
}
