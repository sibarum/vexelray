package dev.vexelray.canvas;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A distance field: the edge at one half, the distance true inside and out, and the margin round the shape. */
class DistanceFieldTest {

    @Test
    void aLineOfPixelsKnowsHowFarTheEdgeIs() {
        // Eight pixels, the middle four inside: a pixel's distance is to the edge between pixel centres.
        boolean[] inside = new boolean[8 * 8];
        for (int y = 0; y < 8; y++) {
            for (int x = 2; x < 6; x++) {
                inside[y * 8 + x] = true;
            }
        }
        float[] d = DistanceField.signed(inside, 8);
        assertEquals(-1.5f, d[4 * 8], 1e-5f, "two pixels out");
        assertEquals(-0.5f, d[4 * 8 + 1], 1e-5f);
        assertEquals(0.5f, d[4 * 8 + 2], 1e-5f, "just inside");
        assertEquals(1.5f, d[4 * 8 + 3], 1e-5f);
    }

    @Test
    void aDiagonalDistanceIsEuclidean() {
        // One pixel inside, at (5, 5): the pixel at (2, 1) is five away, and half a pixel of that is the edge.
        boolean[] inside = new boolean[10 * 10];
        inside[5 * 10 + 5] = true;
        float[] d = DistanceField.signed(inside, 10);
        assertEquals(0.5f - 5f, d[1 * 10 + 2], 1e-5f);
    }

    @Test
    void aSquareMaskHasItsEdgeAtOneHalf() {
        // A 32-texel field of a square from a quarter in to three quarters in, with 8 texels round it.
        int fine = DistanceField.maskSide(32);
        byte[] mask = new byte[fine * fine * 4];
        for (int y = fine / 4; y < fine * 3 / 4; y++) {
            for (int x = fine / 4; x < fine * 3 / 4; x++) {
                mask[(y * fine + x) * 4 + 3] = (byte) 255;
            }
        }
        DistanceField f = DistanceField.of(mask, 32, 8);
        assertEquals(48, f.side());
        assertEquals(0.25f, f.margin(), 1e-6f);
        int centre = value(f.rgba(), 48, 24, 24);
        int outside = value(f.rgba(), 48, 4, 24);
        // The square's left edge is at texel 8 + 8 = 16: the texels either side straddle one half.
        int left = value(f.rgba(), 48, 15, 24);
        int right = value(f.rgba(), 48, 16, 24);
        assertTrue(centre > 200, "deep inside reads high: " + centre);
        assertTrue(left < 128 && right > 128, "the edge falls between texels 15 and 16: " + left + ", " + right);
        assertEquals(255, left + right, 2, "symmetric about one half");
        assertTrue(outside < 60, "well outside reads low: " + outside);
    }

    @Test
    void aMaskOfTheWrongSizeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> DistanceField.of(new byte[16], 32, 8));
    }

    private static int value(byte[] rgba, int side, int x, int y) {
        return rgba[(y * side + x) * 4] & 0xFF;
    }
}
