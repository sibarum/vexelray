package dev.vexelray.vulkan.present;

/**
 * An image's mip chain, made on the CPU: each level half the last (rounded down, never below one texel), down to a
 * single texel, each texel the average of the two by two under it. Uploaded with the image ({@link AtlasTexture}), so a
 * picture drawn smaller than its texture reads a level near its size and nothing is skipped.
 *
 * <p>Averaged as the eye would: in linear light, with each texel weighed by its alpha, so a transparent texel's colour
 * does not bleed into its neighbours and fine bright detail on dark keeps its brightness. Stored as sRGB again.
 */
public final class MipChain {

    /** sRGB byte to linear light. */
    private static final float[] DECODE = new float[256];
    /** Linear light, in {@code ENCODE.length - 1} steps, to an sRGB byte. */
    private static final byte[] ENCODE = new byte[4096];

    static {
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            DECODE[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        for (int i = 0; i < ENCODE.length; i++) {
            double l = i / (double) (ENCODE.length - 1);
            double c = l <= 0.0031308 ? l * 12.92 : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
            ENCODE[i] = (byte) Math.round(Math.max(0, Math.min(1, c)) * 255);
        }
    }

    private MipChain() {
    }

    /** How many levels a {@code width x height} image has, its own size included. */
    public static int count(int width, int height) {
        return 32 - Integer.numberOfLeadingZeros(Math.max(1, Math.max(width, height)));
    }

    /** The width or height of level {@code level} of a side {@code size} long. */
    public static int size(int size, int level) {
        return Math.max(1, size >> level);
    }

    /** Every level of the straight RGBA8 image {@code rgba}, itself first. */
    public static byte[][] of(byte[] rgba, int width, int height) {
        int n = count(width, height);
        byte[][] levels = new byte[n][];
        levels[0] = rgba;
        for (int l = 1; l < n; l++) {
            levels[l] = half(levels[l - 1], size(width, l - 1), size(height, l - 1), size(width, l), size(height, l));
        }
        return levels;
    }

    /** One level from the one above it: each texel the two by two it covers, the last row or column repeated. */
    private static byte[] half(byte[] src, int sw, int sh, int dw, int dh) {
        byte[] dst = new byte[dw * dh * 4];
        int last = ENCODE.length - 1;
        for (int y = 0; y < dh; y++) {
            int y0 = Math.min(2 * y, sh - 1);
            int y1 = Math.min(2 * y + 1, sh - 1);
            for (int x = 0; x < dw; x++) {
                int x0 = Math.min(2 * x, sw - 1);
                int x1 = Math.min(2 * x + 1, sw - 1);
                float r = 0, g = 0, b = 0, a = 0;
                for (int p : new int[] {(y0 * sw + x0) * 4, (y0 * sw + x1) * 4, (y1 * sw + x0) * 4, (y1 * sw + x1) * 4}) {
                    float wa = (src[p + 3] & 0xFF) / 255f;
                    r += DECODE[src[p] & 0xFF] * wa;
                    g += DECODE[src[p + 1] & 0xFF] * wa;
                    b += DECODE[src[p + 2] & 0xFF] * wa;
                    a += wa;
                }
                int o = (y * dw + x) * 4;
                if (a > 0f) {
                    dst[o] = ENCODE[(int) (Math.min(1f, r / a) * last + 0.5f)];
                    dst[o + 1] = ENCODE[(int) (Math.min(1f, g / a) * last + 0.5f)];
                    dst[o + 2] = ENCODE[(int) (Math.min(1f, b / a) * last + 0.5f)];
                }
                dst[o + 3] = (byte) Math.round(a / 4f * 255f);
            }
        }
        return dst;
    }
}
