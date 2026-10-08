package dev.vexelray.text;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Every font face an application has, as the build baked them: one atlas per face, listed in the {@code fonts.json}
 * manifest that vexelray-msdf-maven-plugin writes for its {@code <families>}. A registry — family and style in, a
 * {@link Face} out — and the per-code-point fallback that decides which face actually draws a glyph.
 *
 * <h2>Everything is decided at build time</h2>
 *
 * <p>The set is fixed when it is read: no face is added, generated or evicted afterwards, so what the application
 * holds does not depend on what it is shown (docs/plans/font-families.md §1 in vexelray-gui). Every face's metrics
 * are parsed by {@link #read}; its pixels are a separate file, read by {@link #pixels(Face)}, which an application
 * calls for every face at startup. Keeping the two apart costs nothing now and means measurement can never depend on
 * whether a texture is resident (§2).
 *
 * <h2>Asking for a face always gets one</h2>
 *
 * <p>A style the family was not built with resolves to its nearest face by the CSS font-matching order — stretch,
 * then slope, then weight — so asking for semibold in a family that has regular and bold gets bold, the same answer
 * a browser gives. A family that does not exist is a programming error and throws: family names are build-time
 * facts.
 *
 * <h2>Fallback is per code point</h2>
 *
 * <p>A face's {@link #chain(Face) chain} is every face of its own family, nearest style first, and then every face of
 * each {@link #withFallback fallback family} in the same order. The first face in the chain that has a glyph draws
 * it, so an italic that lacks a letter borrows the upright one from its own design before any other family's. Only a
 * code point no face in the chain has draws the missing-glyph box, and that box is the asking face's own.
 */
public final class FontSet {

    /** The default set: the faces vexelray-text bakes from the fonts it ships. */
    public static final String STANDARD = "/dev/vexelray/text/atlas/fonts.json";

    /** Upright, italic, or oblique — the third axis a face is named by. */
    public enum Slope {
        NORMAL, ITALIC, OBLIQUE;

        /**
         * CSS's order of preference among slopes: row is the slope asked for, column the face's, value the rank.
         * Upright prefers oblique to italic; each slanted slope prefers the other slant to upright.
         */
        private static final int[][] RANK = {
                {0, 2, 1},
                {2, 0, 1},
                {2, 1, 0}};

        private int rank(Slope actual) {
            return RANK[ordinal()][actual.ordinal()];
        }
    }

    /**
     * One baked face.
     *
     * @param id      its index in {@link #faces()}, stable for the life of the set — what a draw names
     * @param family  the key the application uses ({@code sans}), not the font's own family name
     * @param style   the style key the build named its files by ({@code bold-italic})
     * @param weight  100–1000
     * @param stretch 1–9, 5 is normal
     * @param pixels  where its pixels are, relative to the manifest
     */
    public record Face(int id, String family, String style, int weight, int stretch, Slope slope, AtlasData atlas,
                       String pixels) {
    }

    private final List<Face> faces;
    private final Map<String, List<Face>> byFamily;
    private final List<String> fallback;
    private final Function<String, InputStream> open;

    private FontSet(List<Face> faces, List<String> fallback, Function<String, InputStream> open) {
        this.faces = faces;
        this.fallback = fallback;
        this.open = open;
        Map<String, List<Face>> map = new LinkedHashMap<>();
        for (Face f : faces) {
            map.computeIfAbsent(f.family(), k -> new ArrayList<>()).add(f);
        }
        map.replaceAll((k, v) -> List.copyOf(v));
        this.byFamily = map;
    }

    /** The standard set, from the class path. */
    public static FontSet standard() {
        return fromClassPath(STANDARD);
    }

    /** A set from a manifest on the class path; its atlases are resolved beside it. */
    public static FontSet fromClassPath(String manifest) {
        String base = manifest.substring(0, manifest.lastIndexOf('/') + 1);
        return read(path -> FontSet.class.getResourceAsStream(path.equals("") ? manifest : base + path));
    }

    /**
     * A set from wherever {@code open} finds it: {@code open.apply("")} is the manifest, and any other argument is a
     * path relative to it. {@code open} is kept, to read pixels later; it returns null for a file that is not there.
     */
    @SuppressWarnings("unchecked")
    public static FontSet read(Function<String, InputStream> open) {
        Map<String, Object> root = Json.parseObject(text(open, ""));
        Object version = root.get("version");
        if (!(version instanceof Number n) || n.intValue() != 1) {
            throw new IllegalStateException("font manifest version " + version + " is not one this reads (1)");
        }
        List<Face> faces = new ArrayList<>();
        for (Object fo : (List<Object>) root.get("families")) {
            Map<String, Object> family = (Map<String, Object>) fo;
            String name = (String) family.get("name");
            for (Object o : (List<Object>) family.get("faces")) {
                Map<String, Object> face = (Map<String, Object>) o;
                String metrics = (String) face.get("metrics");
                faces.add(new Face(faces.size(), name, (String) face.get("style"),
                        ((Number) face.get("weight")).intValue(), ((Number) face.get("stretch")).intValue(),
                        Slope.valueOf(((String) face.get("slope")).toUpperCase(Locale.ROOT)),
                        AtlasData.parse(text(open, metrics)), (String) face.get("pixels")));
            }
        }
        if (faces.isEmpty()) {
            throw new IllegalStateException("font manifest lists no faces");
        }
        return new FontSet(List.copyOf(faces), List.of(), open);
    }

    /**
     * This set, with {@code families} consulted in order for any code point a face's own family lacks. The faces and
     * their ids are the same; only the chains differ.
     */
    public FontSet withFallback(String... families) {
        for (String f : families) {
            family(f);
        }
        return new FontSet(faces, List.of(families), open);
    }

    public List<Face> faces() {
        return faces;
    }

    public Face face(int id) {
        return faces.get(id);
    }

    /** The family keys, in manifest order. */
    public List<String> families() {
        return List.copyOf(byFamily.keySet());
    }

    /** The family's faces, in manifest order. Throws for a family the set does not have. */
    public List<Face> family(String family) {
        List<Face> found = byFamily.get(family);
        if (found == null) {
            throw new IllegalArgumentException("no font family '" + family + "'; the set has " + byFamily.keySet());
        }
        return found;
    }

    /** The family's regular face, or the nearest to it. */
    public Face face(String family) {
        return face(family, 400, 5, Slope.NORMAL);
    }

    /** The family's face nearest {@code weight} and {@code slope}, at normal stretch. */
    public Face face(String family, int weight, Slope slope) {
        return face(family, weight, 5, slope);
    }

    /** The family's face nearest the given style, by the CSS font-matching order. */
    public Face face(String family, int weight, int stretch, Slope slope) {
        return nearestFirst(family(family), weight, stretch, slope).get(0);
    }

    /** The faces that may draw a code point for {@code face}, in the order they are asked. */
    public List<Face> chain(Face face) {
        List<Face> out = new ArrayList<>(nearestFirst(family(face.family()), face.weight(), face.stretch(), face.slope()));
        for (String f : fallback) {
            if (!f.equals(face.family())) {
                out.addAll(nearestFirst(family(f), face.weight(), face.stretch(), face.slope()));
            }
        }
        return List.copyOf(out);
    }

    /** A layout that draws {@code face}, falling back along its {@link #chain}. */
    public GlyphLayout layout(Face face) {
        return new GlyphLayout(chain(face));
    }

    /** The face's pixels, read now. */
    public AtlasPixels pixels(Face face) {
        try (InputStream in = open.apply(face.pixels())) {
            if (in == null) {
                throw new IllegalStateException("atlas pixels not found: " + face.pixels());
            }
            return AtlasPixels.read(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading atlas pixels " + face.pixels(), e);
        }
    }

    /**
     * The family's faces ordered by how well each answers the asked-for style: CSS font matching (Fonts Level 4
     * §5.2) narrows by stretch, then slope, then weight, and the same three keys in that order sort the whole family,
     * so the first is the face CSS would choose and the rest are the next-best in turn.
     */
    private static List<Face> nearestFirst(List<Face> family, int weight, int stretch, Slope slope) {
        List<Face> sorted = new ArrayList<>(family);
        sorted.sort(Comparator.comparingInt((Face f) -> stretchRank(stretch, f.stretch()))
                .thenComparingInt(f -> slope.rank(f.slope()))
                .thenComparingInt(f -> weightRank(weight, f.weight()))
                .thenComparingInt(Face::id));
        return sorted;
    }

    /** At or below normal, narrower faces are preferred, nearest first; above it, wider ones. */
    private static int stretchRank(int desired, int actual) {
        if (desired <= 5) {
            return actual <= desired ? desired - actual : 100 + actual - desired;
        }
        return actual >= desired ? actual - desired : 100 + desired - actual;
    }

    /**
     * 400–500 asked for: up to 500 first, then lighter, then heavier. Below 400: lighter first, then heavier. Above
     * 500: heavier first, then lighter. Nearest first within each.
     */
    private static int weightRank(int desired, int actual) {
        if (desired >= 400 && desired <= 500) {
            if (actual >= desired && actual <= 500) {
                return actual - desired;
            }
            return actual < desired ? 1000 + desired - actual : 2000 + actual - desired;
        }
        if (desired < 400) {
            return actual <= desired ? desired - actual : 1000 + actual - desired;
        }
        return actual >= desired ? actual - desired : 1000 + desired - actual;
    }

    private static String text(Function<String, InputStream> open, String path) {
        try (InputStream in = open.apply(path)) {
            if (in == null) {
                throw new IllegalStateException("font file not found: " + (path.isEmpty() ? "the manifest" : path));
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("failed reading " + (path.isEmpty() ? "the font manifest" : path), e);
        }
    }

    @Override
    public String toString() {
        return "FontSet" + byFamily.keySet() + (fallback.isEmpty() ? "" : " fallback " + fallback)
                + ", " + faces.size() + " faces";
    }
}
