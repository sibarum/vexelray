package dev.vexelray.text;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.InflaterInputStream;

/**
 * An atlas's pixels as the runtime loads them: straight RGBA8, tightly packed, top row first, the layout a
 * {@code VK_FORMAT_R8G8B8A8_UNORM} image is uploaded from. Read from {@code <name>.rgba}, which
 * vexelray-msdf-maven-plugin writes beside {@code <name>.png} from the same pixels.
 *
 * <p>The PNG is the atlas's source of truth, and what people look at. This exists so that nothing at runtime has
 * to decode an image format. In Java that means ImageIO, and so AWT and its native libraries, which a GraalVM native
 * image on Windows can only ship as DLLs beside the executable. Inflating the file below needs only
 * {@code java.util.zip}, which every native image links in.
 *
 * <pre>
 *   4 bytes   magic "VXPX"
 *   int32     width, big-endian
 *   int32     height, big-endian
 *   rest      a zlib stream of width · height · 4 bytes, R G B A per pixel, rows top to bottom
 * </pre>
 */
public record AtlasPixels(int width, int height, byte[] rgba) {

    /** "VXPX". */
    static final int MAGIC = 0x56585058;

    /** Where the primary atlas's pixels are on the class path, beside its PNG and JSON. */
    public static final String PRIMARY = "/dev/vexelray/text/atlas/primary.rgba";

    public AtlasPixels {
        if (width < 1 || height < 1 || rgba.length != (long) width * height * 4) {
            throw new IllegalArgumentException("an atlas of " + width + " x " + height + " needs "
                    + (long) width * height * 4 + " bytes of RGBA, got " + rgba.length);
        }
    }

    /** The primary atlas's pixels, from the class path. */
    public static AtlasPixels primary() {
        try (InputStream in = AtlasPixels.class.getResourceAsStream(PRIMARY)) {
            if (in == null) {
                throw new IllegalStateException("atlas pixels not found on the class path: " + PRIMARY);
            }
            return read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading atlas pixels " + PRIMARY, e);
        }
    }

    /** Reads the layout above from {@code in}, and does not close it. */
    public static AtlasPixels read(InputStream in) throws IOException {
        DataInputStream header = new DataInputStream(in);
        int magic = header.readInt();
        if (magic != MAGIC) {
            throw new IOException(String.format("not atlas pixels: magic %08x, expected %08x", magic, MAGIC));
        }
        int width = header.readInt();
        int height = header.readInt();
        if (width < 1 || height < 1 || (long) width * height * 4 > Integer.MAX_VALUE - 8) {
            throw new IOException("atlas pixels of an impossible size, " + width + " x " + height);
        }
        byte[] rgba = new byte[width * height * 4];
        InflaterInputStream body = new InflaterInputStream(in);
        int at = 0;
        while (at < rgba.length) {
            int n = body.read(rgba, at, rgba.length - at);
            if (n < 0) {
                throw new EOFException("atlas pixels end after " + at + " of " + rgba.length + " bytes");
            }
            at += n;
        }
        return new AtlasPixels(width, height, rgba);
    }
}
