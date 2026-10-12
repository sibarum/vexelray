package dev.vexelray.canvas;

/**
 * A shape as a signed distance field: for every texel, how far it is from the shape's edge, inside or out. Drawn by
 * {@link Canvas#field}, which cuts at the edge, one small texture is a shape at any size and in any colour, sharp at
 * both, and the distance beyond the edge is what a glow or a soft shadow is made from.
 *
 * <p>Made from a mask's coverage: the shape rasterised at {@value #OVERSAMPLE} times the field's size, by whoever can
 * rasterise it (an SVG through imagelib in {@code vexelray-codec}, or the icon set's baking tool). Working from pixels
 * rather than paths means the rasteriser's own antialiasing and every feature it knows (knockouts, clips, strokes) come
 * out as it means them, and nothing here parses a shape. The exact Euclidean distance of every fine pixel to the
 * nearest pixel across the edge is found in two separable passes (Felzenszwalb and Huttenlocher), and averaged down to
 * the field's texels, which puts the edge within a small fraction of a texel.
 *
 * <p>The texture is {@code inner + 2 * spread} texels a side: the shape's square in the middle, and {@code spread}
 * texels of margin round it, so the distance runs on past the edge for effects to use. Each texel holds
 * {@code 0.5 + d / (2 * spread)} in every channel, {@code d} in texels and positive inside, clamped to {@code 0..1}: the
 * edge is at one half. Alpha is opaque.
 *
 * @param rgba   the field, {@code side() * side()} RGBA8 texels, top row first
 * @param inner  the shape's square, in texels
 * @param spread the margin round it, and the farthest distance the field holds, in texels
 */
public record DistanceField(byte[] rgba, int inner, int spread) {

    /** How many fine pixels a texel is made from, each way. */
    public static final int OVERSAMPLE = 8;

    private static final float FAR = 1e20f;

    public DistanceField {
        if (inner <= 0 || spread < 0) {
            throw new IllegalArgumentException("a field of " + inner + " texels with " + spread + " round it");
        }
        int side = inner + 2 * spread;
        if (rgba == null || rgba.length != side * side * 4) {
            throw new IllegalArgumentException("a field " + side + " texels across is " + side * side * 4
                    + " bytes; was given " + (rgba == null ? "null" : rgba.length));
        }
    }

    /** The texels across the whole texture. */
    public int side() {
        return inner + 2 * spread;
    }

    /** The margin as a share of the shape's square: how far past its box the field's quad reaches, each side. */
    public float margin() {
        return (float) spread / inner;
    }

    /** The fine pixels across the mask {@link #of} wants: {@code inner} texels, {@value #OVERSAMPLE} to a texel. */
    public static int maskSide(int inner) {
        return inner * OVERSAMPLE;
    }

    /**
     * The field of a mask: {@code mask} is {@link #maskSide(int) maskSide(inner)} pixels square of straight RGBA8, top
     * row first, the shape wherever alpha is at least one half. Its square is {@code inner} texels across and
     * {@code spread} texels of margin are added round it.
     */
    public static DistanceField of(byte[] mask, int inner, int spread) {
        if (inner <= 0 || spread < 0) {
            throw new IllegalArgumentException("a field of " + inner + " texels with " + spread + " round it");
        }
        int fine = maskSide(inner);
        if (mask == null || mask.length != fine * fine * 4) {
            throw new IllegalArgumentException("a field " + inner + " texels across is made from a mask " + fine
                    + " px square, " + fine * fine * 4 + " bytes; was given "
                    + (mask == null ? "null" : mask.length));
        }
        int pad = spread * OVERSAMPLE;
        int n = fine + 2 * pad;
        boolean[] inside = new boolean[n * n];
        for (int y = 0; y < fine; y++) {
            for (int x = 0; x < fine; x++) {
                inside[(y + pad) * n + x + pad] = (mask[(y * fine + x) * 4 + 3] & 0xFF) >= 128;
            }
        }
        return new DistanceField(encode(signed(inside, n), n, inner + 2 * spread, spread), inner, spread);
    }

    /**
     * The signed distance of every pixel of an {@code n x n} grid to the edge of {@code inside}, in pixels: from a
     * pixel's centre to the nearest pixel centre across the edge, less half a pixel, so the edge falls between them.
     * Positive inside.
     */
    static float[] signed(boolean[] inside, int n) {
        float[] toInside = transform(inside, n, true);
        float[] toOutside = transform(inside, n, false);
        float[] d = new float[n * n];
        for (int i = 0; i < d.length; i++) {
            d[i] = inside[i]
                    ? (float) Math.sqrt(toOutside[i]) - 0.5f
                    : 0.5f - (float) Math.sqrt(toInside[i]);
        }
        return d;
    }

    /** The squared distance of every pixel to the nearest pixel that is {@code inside} as asked, in two passes. */
    private static float[] transform(boolean[] inside, int n, boolean toInside) {
        float[] g = new float[n * n];
        for (int i = 0; i < g.length; i++) {
            g[i] = inside[i] == toInside ? 0f : FAR;
        }
        float[] f = new float[n];
        float[] out = new float[n];
        int[] v = new int[n];
        double[] z = new double[n + 1];
        for (int x = 0; x < n; x++) {   // down each column
            for (int y = 0; y < n; y++) {
                f[y] = g[y * n + x];
            }
            line(f, n, out, v, z);
            for (int y = 0; y < n; y++) {
                g[y * n + x] = out[y];
            }
        }
        for (int y = 0; y < n; y++) {   // then across each row
            System.arraycopy(g, y * n, f, 0, n);
            line(f, n, out, v, z);
            System.arraycopy(out, 0, g, y * n, n);
        }
        return g;
    }

    /**
     * The one-dimensional squared distance transform of {@code f}: the lower envelope of the parabolas rooted at each
     * {@code (q, f[q])}. In doubles, so a far value's parabola sits harmlessly high rather than losing the near ones.
     */
    private static void line(float[] f, int n, float[] out, int[] v, double[] z) {
        int k = 0;
        v[0] = 0;
        z[0] = Double.NEGATIVE_INFINITY;
        z[1] = Double.POSITIVE_INFINITY;
        for (int q = 1; q < n; q++) {
            double s = cross(f, q, v[k]);
            while (s <= z[k]) {
                k--;
                s = cross(f, q, v[k]);
            }
            k++;
            v[k] = q;
            z[k] = s;
            z[k + 1] = Double.POSITIVE_INFINITY;
        }
        k = 0;
        for (int q = 0; q < n; q++) {
            while (z[k + 1] < q) {
                k++;
            }
            double d = q - v[k];
            out[q] = (float) Math.min(FAR, d * d + f[v[k]]);
        }
    }

    /** Where the parabolas rooted at {@code q} and {@code p} cross. */
    private static double cross(float[] f, int q, int p) {
        return ((f[q] + (double) q * q) - (f[p] + (double) p * p)) / (2.0 * q - 2.0 * p);
    }

    /** Average each texel's fine pixels' distances, in texels, and store {@code 0.5 + d / (2 * spread)}. */
    private static byte[] encode(float[] fine, int n, int side, int spread) {
        byte[] rgba = new byte[side * side * 4];
        float scale = 1f / OVERSAMPLE;
        float range = 2f * Math.max(1, spread);
        for (int ty = 0; ty < side; ty++) {
            for (int tx = 0; tx < side; tx++) {
                float sum = 0f;
                for (int y = ty * OVERSAMPLE; y < (ty + 1) * OVERSAMPLE; y++) {
                    for (int x = tx * OVERSAMPLE; x < (tx + 1) * OVERSAMPLE; x++) {
                        sum += fine[y * n + x];
                    }
                }
                float d = sum / (OVERSAMPLE * OVERSAMPLE) * scale;
                int v = Math.round(Math.max(0f, Math.min(1f, 0.5f + d / range)) * 255f);
                int o = (ty * side + tx) * 4;
                rgba[o] = (byte) v;
                rgba[o + 1] = (byte) v;
                rgba[o + 2] = (byte) v;
                rgba[o + 3] = (byte) 255;
            }
        }
        return rgba;
    }
}
