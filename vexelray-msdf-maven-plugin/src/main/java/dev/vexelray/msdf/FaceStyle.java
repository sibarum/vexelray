package dev.vexelray.msdf;

import java.util.ArrayList;
import java.util.List;

/**
 * Where a face sits in its family: weight, stretch and slope, the three axes CSS names a face by. Its
 * {@link #key()} is what an application asks for and what the atlas files are called — {@code bold-italic},
 * {@code condensed-light} — so it is derived from what the font says about itself, never from its file name.
 *
 * @param weight  100–1000, as {@code OS/2.usWeightClass} or a {@code wght} axis position
 * @param stretch 1–9, as {@code OS/2.usWidthClass}; 5 is normal
 */
record FaceStyle(int weight, int stretch, Slope slope) {

    enum Slope {
        NORMAL(null), ITALIC("italic"), OBLIQUE("oblique");

        private final String word;

        Slope(String word) {
            this.word = word;
        }
    }

    private static final String[] STRETCH = {
            "ultracondensed", "extracondensed", "condensed", "semicondensed", null,
            "semiexpanded", "expanded", "extraexpanded", "ultraexpanded"};

    /** The {@code wdth} axis percentage at which each stretch class is centred (OpenType {@code usWidthClass}). */
    private static final float[] STRETCH_PERCENT = {50f, 62.5f, 75f, 87.5f, 100f, 112.5f, 125f, 150f, 200f};

    /**
     * The face's name within its family. The defaults (normal stretch, weight 400, upright) are left out, and a
     * face that is all defaults is {@code regular}.
     */
    String key() {
        List<String> parts = new ArrayList<>(3);
        String s = STRETCH[Math.max(1, Math.min(9, stretch)) - 1];
        if (s != null) {
            parts.add(s);
        }
        if (weight != 400) {
            parts.add(weightWord(weight));
        }
        if (slope.word != null) {
            parts.add(slope.word);
        }
        return parts.isEmpty() ? "regular" : String.join("-", parts);
    }

    /**
     * The conventional name for a weight, or the number itself when it is not one of them. A variable font can
     * name an instance at 350 or 450; rounding either to 400 would give it the regular face's key and collide.
     */
    static String weightWord(int weight) {
        return switch (weight) {
            case 100 -> "thin";
            case 200 -> "extralight";
            case 300 -> "light";
            case 350 -> "semilight";
            case 400 -> "regular";
            case 500 -> "medium";
            case 600 -> "semibold";
            case 700 -> "bold";
            case 800 -> "extrabold";
            case 900 -> "black";
            case 950 -> "extrablack";
            default -> Integer.toString(weight);
        };
    }

    /** The stretch class nearest a {@code wdth} axis percentage. */
    static int stretchOf(float percent) {
        int best = 0;
        for (int i = 1; i < STRETCH_PERCENT.length; i++) {
            if (Math.abs(STRETCH_PERCENT[i] - percent) < Math.abs(STRETCH_PERCENT[best] - percent)) {
                best = i;
            }
        }
        return best + 1;
    }

    /** A static face: what its {@code OS/2} table says. */
    static FaceStyle of(SfntFont font) {
        return new FaceStyle(font.weightClass, font.widthClass, slopeOf(font));
    }

    /**
     * One named instance of a variable font. Each of weight, stretch and slope comes from its axis when the font
     * has one, and from the file's own {@code OS/2} otherwise — an italic variable font usually has no {@code ital}
     * axis, only an italic bit, and every instance in it is italic.
     */
    static FaceStyle of(SfntFont font, SfntFont.Instance instance) {
        int weight = font.weightClass;
        int stretch = font.widthClass;
        Slope slope = slopeOf(font);
        for (int i = 0; i < font.axes.size(); i++) {
            float v = instance.coordinates()[i];
            switch (font.axes.get(i).tag()) {
                case "wght" -> weight = Math.round(v);
                case "wdth" -> stretch = stretchOf(v);
                case "ital" -> slope = v >= 0.5f ? Slope.ITALIC : slope;
                case "slnt" -> slope = v != 0f && slope == Slope.NORMAL ? Slope.OBLIQUE : slope;
                default -> {
                    // Other axes (opsz, GRAD, ...) do not name a face. Instances that differ only along one of
                    // them land on the same key, and the planner reports that as a collision rather than
                    // silently keeping one.
                }
            }
        }
        return new FaceStyle(weight, stretch, slope);
    }

    private static Slope slopeOf(SfntFont font) {
        return font.italic ? Slope.ITALIC : font.oblique ? Slope.OBLIQUE : Slope.NORMAL;
    }
}
