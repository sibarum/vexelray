package dev.vexelray.text;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

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
        byte[] rgba = pixels.rgba();
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
    void somethingElseIsRefused() {
        assertThrows(IOException.class, () -> AtlasPixels.read(new ByteArrayInputStream(new byte[] {
                (byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 1, 0, 0, 0, 1})));
    }

    @Test
    void aShortFileIsRefused() {
        byte[] header = {'V', 'X', 'P', 'X', 0, 0, 0, 2, 0, 0, 0, 2};
        assertThrows(IOException.class, () -> AtlasPixels.read(new ByteArrayInputStream(header)));
    }
}
