package dev.vexelray.text;

import java.util.Locale;

/**
 * What a face's atlas holds per texel, as {@code fonts.json} records it ({@code "type"}); vexelray-msdf-maven-plugin's
 * {@code FieldType} is the build's side of the same list.
 *
 * <p>Each type has its own text source in the canvas shader (vexelray-canvas's {@code CoreShadelets.text}), and a
 * shader holds one: an application's fonts are all of one type, and its shader is composed for that type.
 */
public enum FieldType {

    /** Three distances whose median is the edge: sharp corners at any magnification. RGBA, alpha unused. */
    MSDF(4),
    /** One true distance: a quarter of the texture, corners that round off when drawn well above the atlas size. */
    SDF(1);

    private final int channels;

    FieldType(int channels) {
        this.channels = channels;
    }

    /** Bytes per texel, in the pixels file and on the GPU. */
    public int channels() {
        return channels;
    }

    /** The type {@code fonts.json} names; {@link #MSDF} when it names none, as every manifest did before there were others. */
    public static FieldType of(String key) {
        if (key == null) {
            return MSDF;
        }
        for (FieldType t : values()) {
            if (t.name().equals(key.toUpperCase(Locale.ROOT))) {
                return t;
            }
        }
        throw new IllegalStateException("font manifest names atlas type '" + key + "', which this does not read");
    }
}
