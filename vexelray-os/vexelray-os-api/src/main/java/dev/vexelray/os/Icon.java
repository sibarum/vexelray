package dev.vexelray.os;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The mark a window wears — in its title bar, in the task switcher, on the taskbar or dock.
 *
 * <p><b>An icon is a set of sizes, not an image.</b> That is why this is a type rather than a
 * {@code width, height, pixels} triple on {@link WindowConfig}. The window manager asks for several sizes of the
 * same mark at moments the application never sees: a 16-pixel one for the caption, a larger one for Alt-Tab, a
 * larger one again on a 200%-scaled display. Supply the sizes that were <em>drawn</em> at those sizes and each
 * request is answered with artwork meant for it; supply one and every other size is a resample of it. A mark
 * legible at 256 pixels is rarely legible reduced to 16 — the detail that survives the reduction is not the
 * detail a designer would have kept — so the useful set is small and deliberate: 16, 32, 48, and 256 for the
 * shell's large views.
 *
 * <p><b>Pixels are straight-alpha ARGB</b>, one {@code int} per pixel as {@code 0xAARRGGBB}, laid out row-major
 * from the top-left. Straight rather than premultiplied because that is what an authoring tool exports and what
 * every icon container stores; a platform premultiplies if its compositor wants that, which is a fact about the
 * platform and belongs on its side of the seam.
 *
 * <p>Immutable and cheap to share: pixel arrays are copied on the way in and out, so one {@code Icon} may be
 * handed to every window in a process, and to {@link NativePlatform#setApplicationIcon} as the default for all
 * of them.
 *
 * @param images the sizes this mark is available in — at least one, and no two of the same size
 */
public record Icon(List<Image> images) {

    /**
     * One size of a mark.
     *
     * @param width  pixel width, positive
     * @param height pixel height, positive
     * @param argb   {@code width * height} pixels, {@code 0xAARRGGBB}, row-major from the top-left
     */
    public record Image(int width, int height, int[] argb) {

        public Image {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("icon size must be positive, got " + width + "x" + height);
            }
            Objects.requireNonNull(argb, "argb");
            if (argb.length != width * height) {
                throw new IllegalArgumentException("icon pixel count " + argb.length
                        + " does not match " + width + "x" + height + " (" + width * height + ")");
            }
            argb = argb.clone();   // a record is a value; a caller's later write to its array must not reach it
        }

        /** The pixels, as a copy — the array this record holds is never handed out. */
        @Override
        public int[] argb() {
            return argb.clone();
        }

        /** How far this size is from {@code target}, for {@link Icon#bestFor}. */
        private int distanceTo(int target) {
            return Math.abs(Math.max(width, height) - target);
        }
    }

    public Icon {
        Objects.requireNonNull(images, "images");
        if (images.isEmpty()) {
            throw new IllegalArgumentException("an icon needs at least one size");
        }
        images = List.copyOf(images);
        for (int i = 0; i < images.size(); i++) {
            for (int j = i + 1; j < images.size(); j++) {
                Image a = images.get(i);
                Image b = images.get(j);
                if (a.width() == b.width() && a.height() == b.height()) {
                    throw new IllegalArgumentException("two images of the same size "
                            + a.width() + "x" + a.height() + " — an icon holds one image per size");
                }
            }
        }
    }

    /** An icon of the given sizes. */
    public static Icon of(Image... images) {
        return new Icon(List.of(images));
    }

    /** A single-size icon from raw {@code 0xAARRGGBB} pixels. */
    public static Icon of(int width, int height, int[] argb) {
        return new Icon(List.of(new Image(width, height, argb)));
    }

    /**
     * The size closest to {@code target} pixels — what a platform calls when the window manager asks for a
     * specific one. Never null: an icon always has at least one image, and the nearest of them is a better
     * answer than none. Ties go to the larger image, because scaling down loses less than scaling up.
     */
    public Image bestFor(int target) {
        Image best = images.get(0);
        for (Image candidate : images) {
            int distance = candidate.distanceTo(target);
            int bestDistance = best.distanceTo(target);
            if (distance < bestDistance || (distance == bestDistance && candidate.width() > best.width())) {
                best = candidate;
            }
        }
        return best;
    }

    // ---- Loading -------------------------------------------------------------------------------------------
    //
    // Decoding is not windowing, and this is the only place in VexelRay's OS layer that reads an image file. It
    // is here because the alternative is that every application hand-rolls a PNG decoder before it can put its
    // own mark on its own window — a poor trade against one call to a decoder the JDK already ships. It runs
    // once at startup; nothing in the render path goes near it.

    /**
     * An icon from image files, one per size — typically {@code icon-16.png}, {@code icon-32.png},
     * {@code icon-256.png}. Any format the JDK decodes will do; each file contributes its own natural size, so
     * the set of sizes is whatever was drawn rather than anything this method picks.
     *
     * @throws UncheckedIOException if a file cannot be read or decoded
     */
    public static Icon fromFiles(Path... files) {
        if (files.length == 0) {
            throw new IllegalArgumentException("an icon needs at least one file");
        }
        List<Image> images = new ArrayList<>(files.length);
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                images.add(decodeImage(in, file.toString()));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read icon " + file, e);
            }
        }
        return new Icon(images);
    }

    /**
     * An icon from encoded image bytes, one array per size — the classpath-resource form of {@link #fromFiles},
     * for an application that ships its mark inside its own jar.
     *
     * @throws UncheckedIOException if the bytes cannot be decoded
     */
    public static Icon fromBytes(byte[]... encoded) {
        if (encoded.length == 0) {
            throw new IllegalArgumentException("an icon needs at least one image");
        }
        List<Image> images = new ArrayList<>(encoded.length);
        for (int i = 0; i < encoded.length; i++) {
            try (InputStream in = new ByteArrayInputStream(encoded[i])) {
                images.add(decodeImage(in, "image " + i));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot decode icon image " + i, e);
            }
        }
        return new Icon(images);
    }

    private static Image decodeImage(InputStream in, String what) throws IOException {
        java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(in);
        if (decoded == null) {
            throw new IOException("no decoder recognised " + what);
        }
        int w = decoded.getWidth();
        int h = decoded.getHeight();
        // getRGB is defined to yield non-premultiplied 0xAARRGGBB in the default sRGB space, converting from
        // whatever the file actually stored — exactly this type's contract, so no colour handling is needed here.
        return new Image(w, h, decoded.getRGB(0, 0, w, h, new int[w * h], 0, w));
    }
}
