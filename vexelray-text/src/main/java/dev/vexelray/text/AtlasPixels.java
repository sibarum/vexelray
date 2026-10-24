package dev.vexelray.text;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.zip.InflaterInputStream;

/**
 * An atlas's pixels as the runtime loads them: {@code channels} bytes per texel, tightly packed, top row first, the
 * layout a {@code VK_FORMAT_R8G8B8A8_UNORM} (four) or {@code VK_FORMAT_R8_UNORM} (one) image is uploaded from. Read
 * from the {@code <name>.rgba} or {@code <name>.r8} that vexelray-msdf-maven-plugin writes beside {@code <name>.png}
 * from the same pixels.
 *
 * <p>The PNG is the atlas's source of truth, and what people look at. This exists so that nothing at runtime has
 * to decode an image format. In Java that means ImageIO, and so AWT and its native libraries, which a GraalVM native
 * image on Windows can only ship as DLLs beside the executable. Inflating the file below needs only
 * {@code java.util.zip}, which every native image links in.
 *
 * <pre>
 *   4 bytes   magic "VXPX"                      4 bytes   magic "VXPC"
 *   int32     width, big-endian                 int32     width, big-endian
 *   int32     height, big-endian                int32     height, big-endian
 *   rest      a zlib stream of                  int32     channels, 1 or 4, big-endian
 *             width · height · 4 bytes,         rest      a zlib stream of width · height · channels bytes,
 *             R G B A per pixel                           R first, rows top to bottom
 * </pre>
 */
public record AtlasPixels(int width, int height, int channels, byte[] texels) {

    /** "VXPX": four channels, no channel count. */
    static final int MAGIC = 0x56585058;
    /** "VXPC": a channel count after the height. */
    static final int MAGIC_CHANNELS = 0x56585043;

    /** Where the primary atlas's pixels are on the class path, beside its PNG and JSON. */
    public static final String PRIMARY = "/dev/vexelray/text/atlas/primary.rgba";

    public AtlasPixels {
        if (channels != 1 && channels != 4) {
            throw new IllegalArgumentException("atlas pixels are 1 or 4 channels, not " + channels);
        }
        if (width < 1 || height < 1 || texels.length != (long) width * height * channels) {
            throw new IllegalArgumentException("an atlas of " + width + " x " + height + " at " + channels
                    + " channels needs " + (long) width * height * channels + " bytes, got " + texels.length);
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

    /** Reads either layout above from {@code in}, and does not close it. */
    public static AtlasPixels read(InputStream in) throws IOException {
        DataInputStream header = new DataInputStream(in);
        int magic = header.readInt();
        if (magic != MAGIC && magic != MAGIC_CHANNELS) {
            throw new IOException(String.format("not atlas pixels: magic %08x, expected %08x or %08x", magic, MAGIC,
                    MAGIC_CHANNELS));
        }
        int width = header.readInt();
        int height = header.readInt();
        int channels = magic == MAGIC ? 4 : header.readInt();
        if (channels != 1 && channels != 4) {
            throw new IOException("atlas pixels of " + channels + " channels; this reads 1 or 4");
        }
        if (width < 1 || height < 1 || (long) width * height * channels > Integer.MAX_VALUE - 8) {
            throw new IOException("atlas pixels of an impossible size, " + width + " x " + height);
        }
        byte[] texels = new byte[width * height * channels];
        InflaterInputStream body = new InflaterInputStream(in);
        int at = 0;
        while (at < texels.length) {
            int n = body.read(texels, at, texels.length - at);
            if (n < 0) {
                throw new EOFException("atlas pixels end after " + at + " of " + texels.length + " bytes");
            }
            at += n;
        }
        return new AtlasPixels(width, height, channels, texels);
    }
}
