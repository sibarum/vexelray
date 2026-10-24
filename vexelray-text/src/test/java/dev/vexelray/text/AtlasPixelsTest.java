package dev.vexelray.text;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtlasPixelsTest {

    /**
     * The committed {@code primary.rgba} holds exactly the committed PNG's pixels. The build writes it from the PNG,
     * but both files are committed, so one can be changed without the other; this is what notices.
     */
    @Test
    void thePixelsAreThePngs() throws IOException {
        AtlasPixels pixels = AtlasPixels.primary();
        BufferedImage png;
        try (InputStream in = AtlasPixels.class.getResourceAsStream("/dev/vexelray/text/atlas/primary.png")) {
            png = ImageIO.read(in);
        }
        assertEquals(png.getWidth(), pixels.width(), "width");
        assertEquals(png.getHeight(), pixels.height(), "height");
        assertEquals(4, pixels.channels(), "channels");
        byte[] rgba = pixels.texels();
        for (int y = 0; y < png.getHeight(); y++) {
            for (int x = 0; x < png.getWidth(); x++) {
                int argb = png.getRGB(x, y);
                int i = (y * png.getWidth() + x) * 4;
                int got = (rgba[i + 3] & 0xFF) << 24 | (rgba[i] & 0xFF) << 16 | (rgba[i + 1] & 0xFF) << 8
                        | (rgba[i + 2] & 0xFF);
                if (got != argb) {
                    assertEquals(Integer.toHexString(argb), Integer.toHexString(got), "pixel " + x + ", " + y);
                }
            }
        }
    }

    @Test
    void oneChannelIsReadAsWritten() throws IOException {
        AtlasPixels p = AtlasPixels.read(new ByteArrayInputStream(oneChannel(3, 2, new byte[] {0, 1, 2, 3, 4, 5})));
        assertEquals(3, p.width());
        assertEquals(2, p.height());
        assertEquals(1, p.channels());
        assertArrayEquals(new byte[] {0, 1, 2, 3, 4, 5}, p.texels());
    }

    @Test
    void somethingElseIsRefused() {
        assertThrows(IOException.class, () -> AtlasPixels.read(new ByteArrayInputStream(new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 1, 0, 0, 0, 1})));
    }

    @Test
    void aShortFileIsRefused() {
        byte[] header = {'V', 'X', 'P', 'X', 0, 0, 0, 2, 0, 0, 0, 2};
        assertThrows(IOException.class, () -> AtlasPixels.read(new ByteArrayInputStream(header)));
    }

    @Test
    void aChannelCountThisDoesNotReadIsRefused() {
        byte[] header = {'V', 'X', 'P', 'C', 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 3};
        assertThrows(IOException.class, () -> AtlasPixels.read(new ByteArrayInputStream(header)));
    }

    /** A {@code VXPC} file of one channel, as vexelray-msdf-maven-plugin writes for an SDF face. */
    static byte[] oneChannel(int width, int height, byte[] texels) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(bytes);
        header.writeInt(AtlasPixels.MAGIC_CHANNELS);
        header.writeInt(width);
        header.writeInt(height);
        header.writeInt(1);
        header.flush();
        try (DeflaterOutputStream body = new DeflaterOutputStream(bytes)) {
            body.write(texels);
        }
        return bytes.toByteArray();
    }
}
