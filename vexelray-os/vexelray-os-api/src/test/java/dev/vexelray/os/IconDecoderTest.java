package dev.vexelray.os;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Icon#fromIco} and {@link Icon#fromPng} against ImageIO, which the test may use and the decoder may not: every
 * PNG here is written by ImageIO and read back both ways, so the reference is a decoder nobody here wrote.
 */
class IconDecoderTest {

    /** Noise, so every row filter ImageIO's encoder chooses is exercised rather than only "none". */
    private static BufferedImage noise(int type, int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // Mostly smooth with some noise: smooth rows push the encoder to Sub/Up/Average/Paeth.
                int a = type == BufferedImage.TYPE_INT_ARGB ? (x * 255 / Math.max(1, w - 1)) : 0xFF;
                int v = (x * 7 + y * 13 + r.nextInt(8)) & 0xFF;
                img.setRGB(x, y, a << 24 | v << 16 | (255 - v) << 8 | (v ^ y) & 0xFF);
            }
        }
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, "png", out));
        return out.toByteArray();
    }

    /** What ImageIO reads the same bytes as: the reference. */
    private static int[] reference(byte[] png) throws IOException {
        BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(png));
        int w = img.getWidth();
        int h = img.getHeight();
        return img.getRGB(0, 0, w, h, new int[w * h], 0, w);
    }

    private static void agrees(BufferedImage img) throws IOException {
        byte[] png = png(img);
        Icon.Image decoded = Icon.fromPng(png).images().get(0);
        assertEquals(img.getWidth(), decoded.width());
        assertEquals(img.getHeight(), decoded.height());
        assertArrayEquals(reference(png), decoded.argb());
    }

    @Test
    void rgbaAgreesWithImageIo() throws IOException {
        agrees(noise(BufferedImage.TYPE_INT_ARGB, 37, 29, 1));
    }

    @Test
    void rgbAgreesWithImageIo() throws IOException {
        agrees(noise(BufferedImage.TYPE_INT_RGB, 48, 48, 2));
    }

    /**
     * Against the stored samples, not {@code getRGB}: ImageIO reads a grey PNG into a linear grey colour space and
     * gamma-converts on the way out, though a PNG's samples are already display-encoded. A grey mark should show the
     * grey that was drawn.
     */
    @Test
    void greyIsTheSampleThatWasStored() throws IOException {
        BufferedImage img = noise(BufferedImage.TYPE_BYTE_GRAY, 16, 20, 3);
        Icon.Image decoded = Icon.fromPng(png(img)).images().get(0);
        int[] want = new int[16 * 20];
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 16; x++) {
                int v = img.getRaster().getSample(x, y, 0);
                want[y * 16 + x] = 0xFF000000 | v << 16 | v << 8 | v;
            }
        }
        assertArrayEquals(want, decoded.argb());
    }

    @Test
    void paletteWithTransparencyAgreesWithImageIo() throws IOException {
        byte[] r = {0, (byte) 255, 0, 10};
        byte[] g = {0, 0, (byte) 255, 20};
        byte[] b = {0, 0, 0, (byte) 200};
        byte[] a = {0, (byte) 255, (byte) 128, (byte) 255};
        BufferedImage img = new BufferedImage(9, 7, BufferedImage.TYPE_BYTE_INDEXED,
                new IndexColorModel(8, 4, r, g, b, a));
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 9; x++) {
                img.getRaster().setSample(x, y, 0, (x + y) % 4);
            }
        }
        agrees(img);
    }

    @Test
    void anIcoOfPngsGivesEverySize() throws IOException {
        byte[] p16 = png(noise(BufferedImage.TYPE_INT_ARGB, 16, 16, 4));
        byte[] p32 = png(noise(BufferedImage.TYPE_INT_ARGB, 32, 32, 5));
        byte[] p256 = png(noise(BufferedImage.TYPE_INT_ARGB, 256, 256, 6));
        Icon icon = Icon.fromIco(ico(List.of(p16, p32, p256), List.of(16, 32, 256), List.of(32, 32, 32)));
        assertEquals(3, icon.images().size());
        assertArrayEquals(reference(p16), icon.bestFor(16).argb());
        assertArrayEquals(reference(p32), icon.bestFor(32).argb());
        assertArrayEquals(reference(p256), icon.bestFor(256).argb());
        assertEquals(256, icon.bestFor(256).width(), "a directory width of 0 means 256");
    }

    @Test
    void aBitmapEntryIsReadBottomUpWithItsAlpha() {
        // 2x2, 32-bit: top-left opaque red, top-right half-transparent green, bottom row blue and clear.
        int[] topDown = {0xFFFF0000, 0x8000FF00, 0xFF0000FF, 0x00000000};
        byte[] entry = bitmap32(2, 2, topDown, false);
        Icon icon = Icon.fromIco(ico(List.of(entry), List.of(2), List.of(32)));
        assertArrayEquals(topDown, icon.images().get(0).argb());
    }

    @Test
    void aBitmapWithNoAlphaFallsBackToItsMask() {
        // Alpha all zero, as a tool unaware of the alpha byte writes it: the AND mask says top-right is clear.
        int[] colours = {0x00112233, 0x00445566, 0x00778899, 0x00AABBCC};
        byte[] entry = bitmap32(2, 2, colours, true);
        int[] got = Icon.fromIco(ico(List.of(entry), List.of(2), List.of(32))).images().get(0).argb();
        assertArrayEquals(new int[] {0xFF112233, 0x00445566, 0xFF778899, 0xFFAABBCC}, got);
    }

    @Test
    void twoEntriesOfOneSizeKeepTheDeeper() throws IOException {
        byte[] deep = png(noise(BufferedImage.TYPE_INT_ARGB, 16, 16, 7));
        byte[] shallow = png(noise(BufferedImage.TYPE_INT_RGB, 16, 16, 8));
        Icon icon = Icon.fromIco(ico(List.of(shallow, deep), List.of(16, 16), List.of(24, 32)));
        assertEquals(1, icon.images().size());
        assertArrayEquals(reference(deep), icon.images().get(0).argb());
    }

    @Test
    void whatIsNotAnIconSaysSo() throws IOException {
        IllegalArgumentException notIco = assertThrows(IllegalArgumentException.class,
                () -> Icon.fromIco(png(noise(BufferedImage.TYPE_INT_ARGB, 4, 4, 9))));
        assertTrue(notIco.getMessage().contains(".ico"), notIco.getMessage());
        IllegalArgumentException notPng = assertThrows(IllegalArgumentException.class,
                () -> Icon.fromPng(new byte[] {1, 2, 3}));
        assertTrue(notPng.getMessage().contains("PNG 0"), notPng.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Icon.fromIco(new byte[] {0, 0, 1, 0, 1, 0}),
                "a directory that runs past the end of the file");
    }

    // ---- writing the containers, for the tests ---------------------------------------------------------------

    private static byte[] ico(List<byte[]> images, List<Integer> sizes, List<Integer> depths) {
        int header = 6 + 16 * images.size();
        int total = header + images.stream().mapToInt(i -> i.length).sum();
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
        int offset = header;
        for (int i = 0; i < images.size(); i++) {
            int side = sizes.get(i);
            b.put((byte) (side >= 256 ? 0 : side)).put((byte) (side >= 256 ? 0 : side)).put((byte) 0).put((byte) 0);
            b.putShort((short) 1).putShort((short) (int) depths.get(i));
            b.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        for (byte[] image : images) {
            b.put(image);
        }
        return b.array();
    }

    /** A 32-bit {@code .ico} bitmap entry; {@code mask} sets the AND bit for the top-right pixel only. */
    private static byte[] bitmap32(int w, int h, int[] topDown, boolean mask) {
        int colourStride = w * 4;
        int maskStride = ((w + 31) / 32) * 4;
        ByteBuffer b = ByteBuffer.allocate(40 + colourStride * h + maskStride * h).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(40).putInt(w).putInt(h * 2).putShort((short) 1).putShort((short) 32).putInt(0)
                .putInt(0).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                int p = topDown[y * w + x];
                b.put((byte) p).put((byte) (p >> 8)).put((byte) (p >> 16)).put((byte) (p >>> 24));
            }
        }
        for (int y = h - 1; y >= 0; y--) {
            byte bits = (byte) (mask && y == 0 ? 0x40 : 0);   // x == 1 is bit 6 of the row's first byte
            b.put(bits);
            for (int i = 1; i < maskStride; i++) {
                b.put((byte) 0);
            }
        }
        return b.array();
    }
}
