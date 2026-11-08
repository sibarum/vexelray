package dev.vexelray.canvas;

import dev.vexelray.fields.sdg.Sdg;

import java.util.ArrayList;
import java.util.List;

/**
 * An SDG drawing's pages as the canvas needs them: each layer's stencil and sampled paint on <b>one</b> page, since a
 * canvas run binds one image. A file need not be written that way (vexelray-fields' encoders keep colour on pages of
 * its own), so a drawing is made so once, when it is loaded, before its pages are uploaded: a layer whose paint is on
 * another page than its stencil has both copied, side by side, onto a page added for such pairs. Layers already
 * together, and every solid one, keep the pages they had, and a drawing with none to move is returned as it is.
 */
public final class SdgPages {

    /** The largest page made, a side: what every Vulkan device takes, as the encoders keep to. */
    static final int MAX = 4096;

    private SdgPages() {
    }

    /** Whether {@code layer} reads one page alone. */
    public static boolean together(Sdg.Layer layer) {
        return !(layer.paint() instanceof Sdg.Paint.Sampled s) || s.page() == layer.stencil().page();
    }

    /** {@code drawing} with every layer's stencil and paint on one page; itself if they already are. */
    public static Sdg colocated(Sdg drawing) {
        if (!apart(drawing.layers())) {
            return drawing;
        }
        Placing p = new Placing(drawing);
        List<Sdg.Layer> layers = p.layers(drawing.layers());
        if (p.shelf != null) {
            p.pages.add(p.shelf.page());
        }
        return new Sdg(drawing.width(), drawing.height(), p.pages, layers);
    }

    /** Whether any layer of {@code layers}, in a group or its mask too, has its paint apart from its stencil. */
    private static boolean apart(List<Sdg.Layer> layers) {
        for (Sdg.Layer l : layers) {
            if (l.isGroup() ? apart(l.group().layers())
                    || l.group().mask() != null && apart(l.group().mask().layers())
                    : l.known() && !together(l)) {
                return true;
            }
        }
        return false;
    }

    /** The pages as they are, and the shelf of pairs being filled: through the layers, groups opened. */
    private static final class Placing {

        final Sdg drawing;
        final List<Sdg.Page> pages;
        Shelf shelf;

        Placing(Sdg drawing) {
            this.drawing = drawing;
            this.pages = new ArrayList<>(drawing.pages());
        }

        List<Sdg.Layer> layers(List<Sdg.Layer> from) {
            List<Sdg.Layer> out = new ArrayList<>(from.size());
            for (Sdg.Layer l : from) {
                if (l.isGroup()) {
                    Sdg.Group g = l.group();
                    Sdg.Mask m = g.mask() == null ? null : new Sdg.Mask(g.mask().kind(), layers(g.mask().layers()));
                    out.add(Sdg.Layer.group(l.name(), l.opacity(), new Sdg.Group(layers(g.layers()), g.clip(), m)));
                } else if (l.known() && !together(l)) {
                    out.add(place(l));
                } else {
                    out.add(l);
                }
            }
            return out;
        }

        Sdg.Layer place(Sdg.Layer layer) {
            Sdg.Stencil s = layer.stencil();
            Sdg.Paint.Sampled p = (Sdg.Paint.Sampled) layer.paint();
            int w = s.width() + p.width(), h = Math.max(s.height(), p.height());
            if (w > MAX || h > MAX) {
                throw new IllegalArgumentException("layer " + layer.name() + ": its stencil and paint side by side are "
                        + w + " × " + h + " texels, past a page's " + MAX);
            }
            if (shelf == null || !shelf.fits(w, h)) {
                if (shelf != null) {
                    pages.add(shelf.page());
                }
                shelf = new Shelf();
            }
            int[] at = shelf.place(w, h);
            Sdg.Page from = drawing.pages().get(s.page());
            Sdg.Page paintFrom = drawing.pages().get(p.page());
            int page = pages.size();
            shelf.copy(from, s.x(), s.y(), s.width(), s.height(), at[0], at[1]);
            shelf.copy(paintFrom, p.x(), p.y(), p.width(), p.height(), at[0] + s.width(), at[1]);
            return new Sdg.Layer(layer.name(),
                    new Sdg.Stencil(s.kind(), page, s.channel(), at[0], at[1], s.width(), s.height(), s.originX(),
                            s.originY(), s.scale(), s.range()),
                    new Sdg.Paint.Sampled(page, at[0] + s.width(), at[1], p.width(), p.height(), p.originX(),
                            p.originY(), p.scale()),
                    layer.opacity());
        }
    }

    /**
     * One page being filled in shelves, left to right, a new shelf below when a row is full. Its texels are kept at the
     * page's largest size and cut down to what was used when it is done.
     */
    private static final class Shelf {
        private byte[] rgba = new byte[0];
        private int stride;          // texels across the buffer, grown as rows widen
        private int used;            // rows down to the bottom of the open shelf
        private int width;           // texels across the widest row so far
        private int x, y, rowHeight;

        boolean fits(int w, int h) {
            if (x + w <= MAX) {
                return y + Math.max(rowHeight, h) <= MAX;
            }
            return y + rowHeight + h <= MAX;
        }

        int[] place(int w, int h) {
            if (x + w > MAX) {
                y += rowHeight;
                x = 0;
                rowHeight = 0;
            }
            int[] at = {x, y};
            x += w;
            rowHeight = Math.max(rowHeight, h);
            width = Math.max(width, x);
            grow(width, y + rowHeight);
            return at;
        }

        void copy(Sdg.Page from, int fx, int fy, int w, int h, int tx, int ty) {
            for (int row = 0; row < h; row++) {
                System.arraycopy(from.rgba(), ((fy + row) * from.width() + fx) * 4, rgba, ((ty + row) * stride + tx) * 4,
                        w * 4);
            }
        }

        private void grow(int w, int h) {
            if (w <= stride && h <= used) {
                return;
            }
            int ns = Math.max(stride, w), nu = Math.max(used, h);
            byte[] grown = new byte[ns * nu * 4];
            for (int row = 0; row < used; row++) {
                System.arraycopy(rgba, row * stride * 4, grown, row * ns * 4, stride * 4);
            }
            rgba = grown;
            stride = ns;
            used = nu;
        }

        Sdg.Page page() {
            if (stride == width) {
                return new Sdg.Page(width, used, rgba);
            }
            byte[] cut = new byte[width * used * 4];
            for (int row = 0; row < used; row++) {
                System.arraycopy(rgba, row * stride * 4, cut, row * width * 4, width * 4);
            }
            return new Sdg.Page(width, used, cut);
        }
    }
}
