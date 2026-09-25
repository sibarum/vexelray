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
 * Writes an atlas's pixels a second time, as {@code <name>.rgba}: straight RGBA8, top row first, deflated. The PNG
 * stays the atlas's source of truth and what people look at. The {@code .rgba} is what a runtime loads, so that
 * nothing at runtime has to decode an image format. Decoding a PNG in Java means ImageIO, and so AWT and its native
 * libraries, which a GraalVM native image on Windows can only ship as DLLs beside the executable.
 *
 * <p>The layout, which {@code dev.vexelray.text.AtlasPixels} reads:
 * <pre>
 *   4 bytes   magic "VXPX"
 *   int32     width, big-endian
 *   int32     height, big-endian
 *   rest      a zlib stream of width · height · 4 bytes, R G B A per pixel, rows top to bottom
 * </pre>
 * Derived from the PNG and written after the missing-glyph box is baked into it, so the two always hold the same
 * pixels. Rewritten whenever the PNG is newer, and otherwise left alone.
 */
final class AtlasPixels {

    /** "VXPX". */
    static final int MAGIC = 0x56585058;

    private AtlasPixels() {
    }

    /** Writes {@code rgba} from {@code png} if it is missing or older; returns whether it wrote. */
    static boolean ensure(File png, File rgba) throws MojoExecutionException {
        if (rgba.isFile() && rgba.lastModified() >= png.lastModified()) {
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
        byte[] row = new byte[w * 4];
        // Beside the target and moved into place, so a build that dies half-way never leaves a truncated atlas.
        File tmp = new File(rgba.getPath() + ".part");
        try (OutputStream file = new BufferedOutputStream(Files.newOutputStream(tmp.toPath()))) {
            DataOutputStream header = new DataOutputStream(file);
            header.writeInt(MAGIC);
            header.writeInt(w);
            header.writeInt(h);
            header.flush();
            Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
            try (DeflaterOutputStream body = new DeflaterOutputStream(file, deflater, 1 << 16)) {
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int argb = img.getRGB(x, y);
                        int i = x * 4;
                        row[i] = (byte) (argb >> 16);
                        row[i + 1] = (byte) (argb >> 8);
                        row[i + 2] = (byte) argb;
                        row[i + 3] = (byte) (argb >>> 24);
                    }
                    body.write(row);
                }
            } finally {
                deflater.end();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed writing atlas pixels " + rgba, e);
        }
        try {
            Files.move(tmp.toPath(), rgba.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed moving atlas pixels into place at " + rgba, e);
        }
        return true;
    }
}
