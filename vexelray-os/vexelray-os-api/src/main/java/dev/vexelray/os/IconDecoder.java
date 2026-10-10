package dev.vexelray.os;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * The two containers a mark ships in, {@code .ico} and {@code .png}, decoded with nothing but {@code java.base}.
 *
 * <p><b>Why not ImageIO, when {@link Icon#fromBytes} already uses it.</b> ImageIO is {@code java.desktop}, and in a
 * GraalVM native image on Windows that is AWT: the image grows by the toolkit and native-image writes its DLLs beside
 * the executable, which is no longer one file. An application's mark is decoded on every launch of every binary, so
 * it is the one decode that has to cost nothing to the build. The formats are small enough to read here: an
 * {@code .ico} is a directory of PNGs or of bitmaps, and a PNG an icon tool writes is eight bits a channel, not
 * interlaced, and one of five colour types.
 *
 * <p>What this refuses, it refuses by name: a 16-bit or interlaced PNG, and a bitmap entry of fewer than 24 bits.
 * Every tool on this stack writes PNG entries ({@code vex-suite-common/tools/Ico.java}), so neither is a mark anybody
 * here has. An entry that cannot be decoded fails the whole icon, rather than being skipped, because a mark missing
 * its 16-pixel size is worse to find out about on a taskbar than from an exception.
 */
final class IconDecoder {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** No mark is this large; the bound is there so a corrupt header cannot ask for gigabytes. */
    private static final int MAX_PIXELS = 4096 * 4096;

    private IconDecoder() {
    }

    // ---- .ico ----------------------------------------------------------------------------------------------

    /**
     * Every size in an {@code .ico}. Two entries of one size (the same mark at two colour depths, which an older
     * tool writes) keep the deeper one, since an {@link Icon} holds one image per size.
     */
    static List<Icon.Image> ico(byte[] bytes) {
        ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        need(bytes.length >= 6, "an .ico is at least a 6-byte header, this is " + bytes.length + " bytes");
        need(b.getShort(0) == 0 && b.getShort(2) == 1, "not an .ico: the header does not say icon");
        int count = b.getShort(4) & 0xFFFF;
        need(count > 0, "an .ico with no images");
        need(bytes.length >= 6 + 16 * count, "an .ico whose directory runs past the end of the file");

        List<Icon.Image> images = new ArrayList<>(count);
        List<Integer> depths = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int entry = 6 + 16 * i;
            int depth = b.getShort(entry + 6) & 0xFFFF;
            int size = b.getInt(entry + 8);
            int offset = b.getInt(entry + 12);
            need(offset >= 0 && size > 0 && (long) offset + size <= bytes.length,
                    "image " + i + " of the .ico lies outside the file");
            byte[] data = new byte[size];
            System.arraycopy(bytes, offset, data, 0, size);
            Icon.Image image;
            try {
                image = isPng(data) ? png(data) : bitmap(data);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("image " + i + " of the .ico: " + e.getMessage(), e);
            }
            int same = sameSize(images, image);
            if (same < 0) {
                images.add(image);
                depths.add(depth);
            } else if (depth > depths.get(same)) {
                images.set(same, image);
                depths.set(same, depth);
            }
        }
        return images;
    }

    private static int sameSize(List<Icon.Image> images, Icon.Image image) {
        for (int i = 0; i < images.size(); i++) {
            if (images.get(i).width() == image.width() && images.get(i).height() == image.height()) {
                return i;
            }
        }
        return -1;
    }

    /**
     * An {@code .ico}'s bitmap entry: a {@code BITMAPINFOHEADER}, the colour rows bottom-up, then a one-bit AND
     * mask. The header's height counts both, so it is twice the image's.
     */
    private static Icon.Image bitmap(byte[] data) {
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        need(data.length >= 40 && b.getInt(0) >= 40, "neither a PNG nor a bitmap");
        int w = b.getInt(4);
        int h = b.getInt(8) / 2;
        int bits = b.getShort(14) & 0xFFFF;
        int compression = b.getInt(16);
        need(compression == 0, "a compressed bitmap entry (compression " + compression + ")");
        need(bits == 32 || bits == 24, "a " + bits + "-bit bitmap entry; only 24 and 32 are read");
        checkSize(w, h);

        int colourStride = (w * bits / 8 + 3) & ~3;
        int maskStride = ((w + 31) / 32) * 4;
        int colours = b.getInt(0);
        int mask = colours + colourStride * h;
        need((long) mask + (long) maskStride * h <= data.length, "a bitmap entry shorter than its header says");

        int[] argb = new int[w * h];
        boolean anyAlpha = false;
        for (int y = 0; y < h; y++) {
            int row = colours + (h - 1 - y) * colourStride;
            for (int x = 0; x < w; x++) {
                int p = row + x * bits / 8;
                int blue = data[p] & 0xFF;
                int green = data[p + 1] & 0xFF;
                int red = data[p + 2] & 0xFF;
                int alpha = bits == 32 ? data[p + 3] & 0xFF : 0xFF;
                anyAlpha |= bits == 32 && alpha != 0;
                argb[y * w + x] = alpha << 24 | red << 16 | green << 8 | blue;
            }
        }
        // A 32-bit entry carries its own alpha, and its mask is vestigial. One whose alpha is all zero was written
        // by a tool that did not know about the alpha byte, and its mask is the only transparency it has.
        if (bits == 24 || !anyAlpha) {
            for (int y = 0; y < h; y++) {
                int row = mask + (h - 1 - y) * maskStride;
                for (int x = 0; x < w; x++) {
                    boolean transparent = (data[row + x / 8] >> (7 - x % 8) & 1) != 0;
                    int rgb = argb[y * w + x] & 0x00FFFFFF;
                    argb[y * w + x] = transparent ? rgb : 0xFF000000 | rgb;
                }
            }
        }
        return new Icon.Image(w, h, argb);
    }

    // ---- .png ----------------------------------------------------------------------------------------------

    static boolean isPng(byte[] data) {
        if (data.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (data[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    /** A PNG of eight bits a channel, not interlaced, in any of the five colour types; straight alpha out. */
    static Icon.Image png(byte[] data) {
        need(isPng(data), "not a PNG: the signature is wrong");
        ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int w = 0;
        int h = 0;
        int colourType = -1;
        byte[] palette = null;
        byte[] transparency = null;
        ByteArrayOutputStream idat = new ByteArrayOutputStream(data.length);
        int at = PNG_SIGNATURE.length;
        boolean ended = false;
        while (!ended) {
            need(at + 8 <= data.length, "a PNG that ends before its IEND");
            int length = b.getInt(at);
            String type = new String(data, at + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            int body = at + 8;
            need(length >= 0 && (long) body + length + 4 <= data.length, "a PNG chunk runs past the end: " + type);
            if (type.equals("IHDR")) {
                need(length >= 13, "a short IHDR");
                w = b.getInt(body);
                h = b.getInt(body + 4);
                int depth = data[body + 8] & 0xFF;
                colourType = data[body + 9] & 0xFF;
                int interlace = data[body + 12] & 0xFF;
                need(depth == 8, "a " + depth + "-bit PNG; only 8 bits a channel are read");
                need(interlace == 0, "an interlaced PNG");
                need(channels(colourType) > 0, "PNG colour type " + colourType);
                checkSize(w, h);
            } else if (type.equals("PLTE")) {
                palette = slice(data, body, length);
            } else if (type.equals("tRNS")) {
                transparency = slice(data, body, length);
            } else if (type.equals("IDAT")) {
                idat.write(data, body, length);
            } else if (type.equals("IEND")) {
                ended = true;
            }
            at = body + length + 4;   // the CRC is not checked: a mark is read from the application's own jar
        }
        need(colourType >= 0, "a PNG with no IHDR");
        need(colourType != 3 || palette != null, "a palette PNG with no PLTE");

        int channels = channels(colourType);
        int stride = w * channels;
        byte[] raw = inflate(idat.toByteArray(), (stride + 1) * h);
        byte[] pixels = unfilter(raw, w, h, channels);
        return new Icon.Image(w, h, toArgb(pixels, w, h, colourType, palette, transparency));
    }

    /** Samples per pixel for a colour type, or 0 for one PNG does not define. */
    private static int channels(int colourType) {
        if (colourType == 0 || colourType == 3) {
            return 1;
        }
        if (colourType == 4) {
            return 2;
        }
        if (colourType == 2) {
            return 3;
        }
        if (colourType == 6) {
            return 4;
        }
        return 0;
    }

    private static byte[] inflate(byte[] compressed, int expected) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[expected];
            int n = 0;
            while (n < expected && !inflater.finished()) {
                int got = inflater.inflate(out, n, expected - n);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    break;
                }
                n += got;
            }
            need(n == expected, "a PNG whose image data is " + n + " bytes where " + expected + " were expected");
            return out;
        } catch (DataFormatException e) {
            throw new IllegalArgumentException("a PNG whose image data does not inflate: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    /** Undo the five per-row filters in place of a copy; {@code bpp} is a whole number of bytes at 8 bits. */
    private static byte[] unfilter(byte[] raw, int w, int h, int bpp) {
        int stride = w * bpp;
        byte[] out = new byte[stride * h];
        for (int y = 0; y < h; y++) {
            int filter = raw[y * (stride + 1)] & 0xFF;
            int in = y * (stride + 1) + 1;
            int row = y * stride;
            int up = row - stride;
            for (int i = 0; i < stride; i++) {
                int a = i >= bpp ? out[row + i - bpp] & 0xFF : 0;
                int bAbove = y > 0 ? out[up + i] & 0xFF : 0;
                int c = i >= bpp && y > 0 ? out[up + i - bpp] & 0xFF : 0;
                int x = raw[in + i] & 0xFF;
                out[row + i] = (byte) (x + predictor(filter, a, bAbove, c));
            }
        }
        return out;
    }

    private static int predictor(int filter, int a, int b, int c) {
        if (filter == 0) {
            return 0;
        }
        if (filter == 1) {
            return a;
        }
        if (filter == 2) {
            return b;
        }
        if (filter == 3) {
            return (a + b) >>> 1;
        }
        need(filter == 4, "PNG row filter " + filter);
        int p = a + b - c;
        int pa = Math.abs(p - a);
        int pb = Math.abs(p - b);
        int pc = Math.abs(p - c);
        return pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
    }

    private static int[] toArgb(byte[] px, int w, int h, int colourType, byte[] palette, byte[] trns) {
        int[] argb = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            argb[i] = pixel(px, i, colourType, palette, trns);
        }
        return argb;
    }

    private static int pixel(byte[] px, int i, int colourType, byte[] palette, byte[] trns) {
        if (colourType == 6) {
            int p = i * 4;
            return (px[p + 3] & 0xFF) << 24 | (px[p] & 0xFF) << 16 | (px[p + 1] & 0xFF) << 8 | px[p + 2] & 0xFF;
        }
        if (colourType == 2) {
            int p = i * 3;
            int r = px[p] & 0xFF;
            int g = px[p + 1] & 0xFF;
            int bl = px[p + 2] & 0xFF;
            boolean keyed = trns != null && trns.length >= 6
                    && sample16(trns, 0) == r && sample16(trns, 2) == g && sample16(trns, 4) == bl;
            return (keyed ? 0 : 0xFF000000) | r << 16 | g << 8 | bl;
        }
        if (colourType == 4) {
            int v = px[i * 2] & 0xFF;
            return (px[i * 2 + 1] & 0xFF) << 24 | v << 16 | v << 8 | v;
        }
        if (colourType == 3) {
            int index = px[i] & 0xFF;
            need(index * 3 + 2 < palette.length, "a PNG pixel names palette entry " + index + " past its end");
            int alpha = trns != null && index < trns.length ? trns[index] & 0xFF : 0xFF;
            return alpha << 24 | (palette[index * 3] & 0xFF) << 16 | (palette[index * 3 + 1] & 0xFF) << 8
                    | palette[index * 3 + 2] & 0xFF;
        }
        int v = px[i] & 0xFF;
        boolean keyed = trns != null && trns.length >= 2 && sample16(trns, 0) == v;
        return (keyed ? 0 : 0xFF000000) | v << 16 | v << 8 | v;
    }

    /** A {@code tRNS} key sample: two bytes, of which an 8-bit image uses the low one. */
    private static int sample16(byte[] trns, int at) {
        return (trns[at] & 0xFF) << 8 | trns[at + 1] & 0xFF;
    }

    // ---- shared ----------------------------------------------------------------------------------------------

    private static byte[] slice(byte[] data, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, from, out, 0, length);
        return out;
    }

    private static void checkSize(int w, int h) {
        need(w > 0 && h > 0 && (long) w * h <= MAX_PIXELS, "an image of " + w + "x" + h);
    }

    private static void need(boolean condition, String otherwise) {
        if (!condition) {
            throw new IllegalArgumentException(otherwise);
        }
    }
}
