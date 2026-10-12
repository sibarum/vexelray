package dev.vexelray.vulkan.present;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The mip chain: halves down to one texel, averaged in light, and no colour from a transparent texel. */
class MipChainTest {

    @Test
    void levelsHalveDownToOneTexel() {
        assertEquals(11, MipChain.count(1024, 600));
        assertEquals(1, MipChain.count(1, 1));
        byte[][] levels = MipChain.of(new byte[5 * 3 * 4], 5, 3);
        assertEquals(3, levels.length);
        assertEquals(2 * 1 * 4, levels[1].length);
        assertEquals(1 * 1 * 4, levels[2].length);
    }

    @Test
    void blackAndWhiteAverageToHalfTheLight() {
        // A 2 x 2 checkerboard is one texel of half the light: sRGB 188, not the 128 of averaging the values.
        byte[] src = new byte[2 * 2 * 4];
        for (int i = 0; i < 4; i++) {
            int v = (i == 0 || i == 3) ? 255 : 0;
            src[i * 4] = src[i * 4 + 1] = src[i * 4 + 2] = (byte) v;
            src[i * 4 + 3] = (byte) 255;
        }
        byte[] top = MipChain.of(src, 2, 2)[1];
        assertEquals(188, top[0] & 0xFF, 2);
        assertEquals(255, top[3] & 0xFF);
    }

    @Test
    void aTransparentTexelsColourDoesNotBleed() {
        // Opaque red beside three transparent greens: red, at a quarter of the cover.
        byte[] src = new byte[2 * 2 * 4];
        src[0] = (byte) 255;
        src[3] = (byte) 255;
        for (int i = 1; i < 4; i++) {
            src[i * 4 + 1] = (byte) 255;
        }
        byte[] top = MipChain.of(src, 2, 2)[1];
        assertEquals(255, top[0] & 0xFF, 1);
        assertEquals(0, top[1] & 0xFF, 1);
        assertEquals(64, top[3] & 0xFF, 1);
    }
}
