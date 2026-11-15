package dev.vexelray.vulkan.present;

/**
 * How a decoder's frame is laid out before it is converted to RGB: the planes a device feed's slot is staged as and
 * converted from on the GPU (vexelray-gui's {@code docs/plans/device-feed.md}, B). Fixed for a feed's life, so the
 * conversion's pipeline and its constants are made once.
 *
 * <p>Every format here is 4:2:0: a luma plane of the frame's size and chroma at half each way, rounded up. Staged
 * planes are tightly packed, row after row with no padding, one plane after another in {@link #planes()} order.
 *
 * @param format    which planes, and how many bits a sample
 * @param matrix    the YUV matrix: 601, 709 or 2020 (anything else reads as 709, the HD default)
 * @param fullRange whether the values span the whole range, rather than video's limited one
 */
public record PlaneLayout(Format format, int matrix, boolean fullRange) {

    /** The layouts the GPU converts. Anything else is converted to RGBA on the CPU and staged as that. */
    public enum Format {
        /** FFmpeg's {@code yuv420p}: three 8-bit planes. */
        YUV420P(3, 1, false, false),
        /** {@code nv12}: an 8-bit luma plane and one of interleaved Cb, Cr. */
        NV12(2, 1, true, false),
        /** {@code yuv420p10le}: three planes of 16-bit samples holding 10 bits in their low bits. */
        YUV420P10(3, 2, false, false),
        /** {@code p010le}: a 16-bit luma plane and one of interleaved chroma, 10 bits in each sample's high bits. */
        P010(2, 2, true, true);

        final int planes;
        final int bytesPerSample;
        final boolean interleaved;
        final boolean highBits;

        Format(int planes, int bytesPerSample, boolean interleaved, boolean highBits) {
            this.planes = planes;
            this.bytesPerSample = bytesPerSample;
            this.interleaved = interleaved;
            this.highBits = highBits;
        }

        /** The format by FFmpeg's name, or null when the GPU does not convert it. */
        public static Format named(String ffmpegName) {
            return switch (ffmpegName) {
                case "yuv420p", "yuvj420p" -> YUV420P;
                case "nv12" -> NV12;
                case "yuv420p10le" -> YUV420P10;
                case "p010le" -> P010;
                default -> null;
            };
        }
    }

    public PlaneLayout {
        if (format == null) {
            throw new IllegalArgumentException("a plane layout names its format");
        }
    }

    /** How many planes: three, or two with chroma interleaved. */
    public int planes() {
        return format.planes;
    }

    /** Plane {@code p}'s width in samples, for a frame {@code width} wide. */
    public int planeWidth(int p, int width) {
        return p == 0 ? width : (width + 1) / 2;
    }

    /** Plane {@code p}'s height in rows, for a frame {@code height} high. */
    public int planeHeight(int p, int height) {
        return p == 0 ? height : (height + 1) / 2;
    }

    /** The bytes one sample of plane {@code p} takes: one or two per channel, two channels where chroma interleaves. */
    public int sampleBytes(int p) {
        return format.bytesPerSample * (p > 0 && format.interleaved ? 2 : 1);
    }

    /** One row of plane {@code p}, packed. */
    public int rowBytes(int p, int width) {
        return planeWidth(p, width) * sampleBytes(p);
    }

    /** Plane {@code p}'s packed bytes. */
    public long planeBytes(int p, int width, int height) {
        return (long) rowBytes(p, width) * planeHeight(p, height);
    }

    /** Where plane {@code p} starts in a slot's staging. */
    public long planeOffset(int p, int width, int height) {
        long at = 0;
        for (int i = 0; i < p; i++) {
            at += planeBytes(i, width, height);
        }
        return at;
    }

    /** Every plane's packed bytes: one slot's staging. */
    public long frameBytes(int width, int height) {
        return planeOffset(planes(), width, height);
    }

    /**
     * The conversion's constants, as the shader reads them: the scale that brings a sample to {@code value / max}, the
     * luma offset and gain, the chroma centre and gain, the matrix's four coefficients, and whether chroma interleaves.
     */
    float[] constants() {
        float scale;
        float max;
        if (format.bytesPerSample == 1) {
            scale = 1f;
            max = 255f;
        } else {
            max = 1023f;
            // A 16-bit UNORM reads v / 65535; ten bits low make that x / 65535, ten bits high x * 64 / 65535.
            scale = format.highBits ? 65535f / (64f * 1023f) : 65535f / 1023f;
        }
        float steps = max + 1f;   // 256 or 1024: the limited range's offsets are 16 and 128 of every 256
        float yOff = fullRange ? 0f : 16f * steps / 256f / max;
        float yMul = fullRange ? 1f : max / (219f * steps / 256f);
        float cOff = 128f * steps / 256f / max;
        float cMul = fullRange ? 1f : max / (224f * steps / 256f);
        float kr;
        float kb;
        float kgb;
        float kgr;
        switch (matrix) {
            case 601 -> {
                kr = 1.402f;
                kb = 1.772f;
                kgb = 0.344136f;
                kgr = 0.714136f;
            }
            case 2020 -> {
                kr = 1.4746f;
                kb = 1.8814f;
                kgb = 0.164553f;
                kgr = 0.571353f;
            }
            default -> {
                kr = 1.5748f;
                kb = 1.8556f;
                kgb = 0.187324f;
                kgr = 0.468124f;
            }
        }
        return new float[] {scale, yOff, yMul, cOff, cMul, kr, kb, kgb, kgr, format.interleaved ? 1f : 0f};
    }
}
