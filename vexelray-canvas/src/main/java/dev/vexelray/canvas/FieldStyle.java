package dev.vexelray.canvas;

/**
 * How a distance field is drawn ({@link Canvas#field}): the shape in {@code tint}, and the effects its distance makes
 * cheap, all in the shape's own colour or in shadow.
 *
 * @param tint   the shape's colour, and the glow's
 * @param glow   how far a halo of the tint reaches past the edge, in px at the field's drawn size; 0 for none
 * @param shadow how soft a shadow under the shape is, and how far down it falls, in px; 0 for none
 */
public record FieldStyle(Color tint, float glow, float shadow) {

    public FieldStyle {
        if (tint == null) {
            throw new IllegalArgumentException("a field is drawn in some colour");
        }
        glow = Math.max(0f, glow);
        shadow = Math.max(0f, shadow);
    }

    /** The shape alone, in {@code tint}. */
    public static FieldStyle plain(Color tint) {
        return new FieldStyle(tint, 0f, 0f);
    }
}
