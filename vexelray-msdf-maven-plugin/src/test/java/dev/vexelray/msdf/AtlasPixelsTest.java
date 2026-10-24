package dev.vexelray.msdf;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class AtlasPixelsTest {

    /**
     * An SDF atlas is a grayscale PNG. Read through getRGB, every mid-gray comes back changed (linear gray to sRGB),
     * which moves every edge; the one-channel file has to hold the samples exactly as msdf-atlas-gen stored them.
     */
    @Test
    void aGrayscaleFieldKeepsItsSamplesExactly(@TempDir Path dir) throws Exception {
        BufferedImage img = new BufferedImage(16, 2, BufferedImage.TYPE_BYTE_GRAY);
        byte[] expected = new byte[16 * 2];
        for (int i = 0; i < expected.length; i++) {
            int v = i * 8;
            img.getRaster().setSample(i % 16, i / 16, 0, v);
            expected[i] = (byte) v;
        }
        File png = dir.resolve("regular.png").toFile();
        ImageIO.write(img, "png", png);
        File out = dir.resolve("regular.r8").toFile();

        AtlasPixels.ensure(png, out, FieldType.SDF.channels);

        try (InputStream in = Files.newInputStream(out.toPath())) {
            DataInputStream header = new DataInputStream(in);
            assertEquals(AtlasPixels.MAGIC_CHANNELS, header.readInt());
            assertEquals(16, header.readInt());
            assertEquals(2, header.readInt());
            assertEquals(1, header.readInt());
            assertArrayEquals(expected, new InflaterInputStream(in).readAllBytes());
        }
    }

    /** Four channels keep the original header, so an atlas written before there were others still reads the same. */
    @Test
    void fourChannelsKeepTheOriginalLayout(@TempDir Path dir) throws Exception {
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0x80102030);
        File png = dir.resolve("regular.png").toFile();
        ImageIO.write(img, "png", png);
        File out = dir.resolve("regular.rgba").toFile();

        AtlasPixels.ensure(png, out, FieldType.MSDF.channels);

        try (InputStream in = Files.newInputStream(out.toPath())) {
            DataInputStream header = new DataInputStream(in);
            assertEquals(AtlasPixels.MAGIC, header.readInt());
            assertEquals(1, header.readInt());
            assertEquals(1, header.readInt());
            assertArrayEquals(new byte[] {0x10, 0x20, 0x30, (byte) 0x80}, new InflaterInputStream(in).readAllBytes());
        }
    }
}
