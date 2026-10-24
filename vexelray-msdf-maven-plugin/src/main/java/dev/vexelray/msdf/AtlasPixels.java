package dev.vexelray.msdf;

import org.apache.maven.plugin.MojoExecutionException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Writes an atlas's pixels a second time, as the file a runtime loads: {@code <name>.rgba} for an MSDF face,
 * {@code <name>.r8} for an SDF one ({@link FieldType#extension}), tightly packed, top row first, deflated. The PNG
 * stays the atlas's source of truth and what people look at. The runtime file exists so that nothing at runtime has to
 * decode an image format. Decoding a PNG in Java means ImageIO, and so AWT and its native libraries, which a GraalVM
 * native image on Windows can only ship as DLLs beside the executable.
 *
 * <p>The layouts, which {@code dev.vexelray.text.AtlasPixels} reads. Four channels keep the original layout, so every
 * atlas written before there were others still reads:
 * <pre>
 *   4 bytes   magic "VXPX"                      4 bytes   magic "VXPC"
 *   int32     width, big-endian                 int32     width, big-endian
 *   int32     height, big-endian                int32     height, big-endian
 *   rest      a zlib stream of                  int32     channels, big-endian
 *             width · height · 4 bytes,         rest      a zlib stream of width · height · channels bytes,
 *             R G B A per pixel                           R first, rows top to bottom
 * </pre>
 * Derived from the PNG and written after the missing-glyph box is baked into it, so the two always hold the same
 * pixels. Rewritten whenever the PNG is newer, and otherwise left alone.
 */
final class AtlasPixels {

    /** "VXPX": four channels, no channel count. */
    static final int MAGIC = 0x56585058;
    /** "VXPC": a channel count after the height. */
    static final int MAGIC_CHANNELS = 0x56585043;

    private AtlasPixels() {
    }

    /** Writes {@code out} from {@code png} if it is missing or older, keeping {@code channels}; returns whether it wrote. */
    static boolean ensure(File png, File out, int channels) throws MojoExecutionException {
        if (channels != 1 && channels != 4) {
            throw new IllegalArgumentException("atlas pixels are 1 or 4 channels, not " + channels);
        }
        if (out.isFile() && out.lastModified() >= png.lastModified()) {
            return false;
        }
        BufferedImage img;
        try {
            img = ImageIO.read(png);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed reading atlas PNG " + png, e);
        }
        if (img == null) {
            throw new MojoExecutionException("Unrecognised atlas PNG (ImageIO returned null): " + png);
        }
        int w = img.getWidth();
        int h = img.getHeight();
        // A one-channel field is a grayscale PNG, and getRGB on one converts linear gray to sRGB: every distance but
        // 0 and 255 would come back changed. Its samples are read as stored.
        boolean gray = img.getRaster().getNumBands() == 1;
        byte[] row = new byte[w * channels];
        // Beside the target and moved into place, so a build that dies half-way never leaves a truncated atlas.
        File tmp = new File(out.getPath() + ".part");
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(tmp.toPath()))) {
            DataOutputStream header = new DataOutputStream(file);
            header.writeInt(channels == 4 ? MAGIC : MAGIC_CHANNELS);
            header.writeInt(w);
            header.writeInt(h);
            if (channels != 4) {
                header.writeInt(channels);
            }
            header.flush();
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            try (DeflaterOutputStream body = new DeflaterOutputStream(file, deflater, 1 << 16)) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int argb = gray ? 0 : img.getRGB(x, y);
                        int i = x * channels;
                        row[i] = (byte) (gray ? img.getRaster().getSample(x, y, 0) : argb >> 16);
                        if (channels == 4) {
                            row[i + 1] = (byte) (argb >> 8);
                            row[i + 2] = (byte) argb;
                            row[i + 3] = (byte) (argb >>> 24);
                        }
                    }
                    body.write(row);
                }
            } finally {
                deflater.end();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed writing atlas pixels " + out, e);
        }
        try {
            Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed moving atlas pixels into place at " + out, e);
        }
        return true;
    }
}
