package dev.vexelray.msdf;

import org.apache.maven.plugin.MojoExecutionException;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * What a face's atlas holds per texel, as a family's {@code <type>} names it: msdf-atlas-gen's {@code -type}, how many
 * channels the runtime file keeps, and that file's extension. {@code dev.vexelray.text.FieldType} is the runtime's
 * side of the same list, read back from {@code fonts.json}.
 *
 * <ul>
 *   <li>{@link #MSDF}: three distances whose median is the edge, so corners stay sharp at any magnification. Kept as
 *       RGBA, alpha unused, which is the layout the GPU uploads.</li>
 *   <li>{@link #SDF}: one true distance. A quarter of the texels and about a quarter of the file; corners round off
 *       once text is drawn well above the atlas's {@code fontSize}.</li>
 * </ul>
 */
enum FieldType {

    MSDF("msdf", 4, "rgba"),
    SDF("sdf", 1, "r8");

    /** The {@code <type>} value, msdf-atlas-gen's {@code -type}, and what {@code fonts.json} records. */
    final String key;
    /** Bytes per texel in the runtime file and on the GPU. */
    final int channels;
    /** The runtime file's extension. */
    final String extension;

    FieldType(String key, int channels, String extension) {
        this.key = key;
        this.channels = channels;
        this.extension = extension;
    }

    /** The type {@code value} names; {@link #MSDF} for null or blank. */
    static FieldType of(String value) throws MojoExecutionException {
        if (value == null || value.isBlank()) {
            return MSDF;
        }
        for (FieldType t : values()) {
            if (t.key.equals(value.trim().toLowerCase(Locale.ROOT))) {
                return t;
            }
        }
        throw new MojoExecutionException("Unknown atlas type '" + value + "': expected one of "
                + Arrays.stream(values()).map(t -> t.key).collect(Collectors.joining(", ")) + ".");
    }
}
