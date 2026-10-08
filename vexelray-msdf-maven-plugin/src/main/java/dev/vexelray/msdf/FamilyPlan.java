package dev.vexelray.msdf;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Which faces a {@link FamilyConfig} bakes, decided from the fonts alone — before, and without, the binary. Kept
 * apart from the mojo so that every way a family can be misconfigured is answerable in a test.
 *
 * <p>Every refusal names what it found, because each one is a question the pom's author has to answer: which of
 * these families did you mean, which of these two files is the bold one, which of these styles did you want.
 */
final class FamilyPlan {

    /**
     * One face to bake.
     *
     * @param instance the named instance of a variable font, or null for a static face
     */
    record Face(String key, FaceStyle style, SfntFont font, SfntFont.Instance instance) {

        /** The input arguments for msdf-atlas-gen: {@code -font file}, or {@code -varfont file?wght=700&...}. */
        List<String> fontArguments() {
            String path = font.path.toAbsolutePath().toString();
            if (instance == null) {
                return List.of("-font", path);
            }
            StringBuilder spec = new StringBuilder(path).append('?');
            for (int i = 0; i < font.axes.size(); i++) {
                spec.append(i == 0 ? "" : "&").append(font.axes.get(i).tag()).append('=')
                        .append(number(instance.coordinates()[i]));
            }
            return List.of("-varfont", spec.toString());
        }

        /** Where the face came from, for messages: the file, and the instance if it is one. */
        String source() {
            return font.path.getFileName() + (instance == null ? "" : " (instance \"" + instance.name() + "\")");
        }
    }

    final String family;
    final String fontFamily;
    final List<Face> faces;

    private FamilyPlan(String family, String fontFamily, List<Face> faces) {
        this.family = family;
        this.fontFamily = fontFamily;
        this.faces = faces;
    }

    static FamilyPlan of(FamilyConfig cfg) throws MojoExecutionException {
        if (cfg.name == null || cfg.name.isBlank() || !cfg.name.matches("[A-Za-z0-9._-]+")) {
            throw new MojoExecutionException("Family needs a <name> of letters, digits, '.', '_' or '-' — it is"
                    + " both the key the application asks for and a directory name. Got: " + cfg.name);
        }
        List<SfntFont> fonts = read(cfg);

        Map<String, List<SfntFont>> byFamily = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (SfntFont f : fonts) {
            byFamily.computeIfAbsent(f.family, k -> new ArrayList<>()).add(f);
        }
        List<SfntFont> chosen;
        if (cfg.match != null && !cfg.match.isBlank()) {
            chosen = byFamily.get(cfg.match.trim());
            if (chosen == null) {
                throw new MojoExecutionException("Family '" + cfg.name + "': no font in its sources is family '"
                        + cfg.match.trim() + "'. Found: " + describe(byFamily) + ".");
            }
        } else if (byFamily.size() > 1) {
            throw new MojoExecutionException("Family '" + cfg.name + "': its sources hold " + byFamily.size()
                    + " families — " + describe(byFamily) + ". Say which with <match>.");
        } else {
            chosen = byFamily.values().iterator().next();
        }

        Map<String, Face> byKey = new LinkedHashMap<>();
        for (SfntFont font : chosen) {
            List<Face> found = new ArrayList<>();
            if (font.isVariable() && !font.instances.isEmpty()) {
                for (SfntFont.Instance instance : font.instances) {
                    FaceStyle style = FaceStyle.of(font, instance);
                    found.add(new Face(style.key(), style, font, instance));
                }
            } else {
                // A static face — or a variable font that names no instances, baked at its defaults.
                FaceStyle style = FaceStyle.of(font);
                found.add(new Face(style.key(), style, font, null));
            }
            for (Face face : found) {
                Face clash = byKey.putIfAbsent(face.key(), face);
                if (clash != null) {
                    throw new MojoExecutionException("Family '" + cfg.name + "': two faces are both '" + face.key()
                            + "' — " + clash.source() + " and " + face.source() + ". They differ in something"
                            + " that does not name a face (an optical size, say); keep one in <sources>.");
                }
            }
        }

        List<Face> faces = new ArrayList<>(byKey.values());
        Set<String> wanted = styles(cfg.styles);
        if (!wanted.isEmpty()) {
            List<String> unknown = new ArrayList<>();
            for (String key : wanted) {
                if (!byKey.containsKey(key)) {
                    unknown.add(key);
                }
            }
            if (!unknown.isEmpty()) {
                throw new MojoExecutionException("Family '" + cfg.name + "': <styles> asks for " + unknown
                        + ", which " + (unknown.size() == 1 ? "is" : "are") + " not in its sources. Available: "
                        + byKey.keySet() + ".");
            }
            faces.removeIf(face -> !wanted.contains(face.key()));
        }
        // Manifest order: stretch, then weight, then slope, so the file reads the way a style picker would.
        faces.sort(Comparator.comparingInt((Face f) -> Math.abs(f.style().stretch() - 5))
                .thenComparingInt(f -> f.style().stretch())
                .thenComparingInt(f -> f.style().weight())
                .thenComparing(f -> f.style().slope()));
        return new FamilyPlan(cfg.name, chosen.get(0).family, List.copyOf(faces));
    }

    /**
     * The charset file content for one face: {@code charset} when the family narrows, else every code point the
     * face maps, as ranges — less controls (they never draw) and U+FFFD (the synthesized box, the same in every
     * face).
     */
    static String charsetOf(Face face, String charset) {
        if (charset != null && !charset.isBlank()) {
            return GenerateAtlasMojo.resolveCharsetContent(charset);
        }
        StringBuilder out = new StringBuilder();
        int start = -1;
        int previous = -1;
        for (int cp : face.font().codepoints()) {
            if (cp < 0x20 || (cp >= 0x7F && cp < 0xA0) || cp == NotdefGlyph.NOTDEF_CODEPOINT) {
                continue;
            }
            if (start < 0) {
                start = cp;
            } else if (cp != previous + 1) {
                range(out, start, previous);
                start = cp;
            }
            previous = cp;
        }
        if (start >= 0) {
            range(out, start, previous);
        }
        return out.toString();
    }

    private static void range(StringBuilder out, int from, int to) {
        out.append(String.format(Locale.ROOT, "[0x%X, 0x%X]\n", from, to));
    }

    private static List<SfntFont> read(FamilyConfig cfg) throws MojoExecutionException {
        if (cfg.sources == null || cfg.sources.isEmpty()) {
            throw new MojoExecutionException("Family '" + cfg.name + "' has no <sources>.");
        }
        List<File> files = new ArrayList<>();
        for (File source : cfg.sources) {
            if (source.isDirectory()) {
                File[] listed = source.listFiles((dir, n) -> {
                    String lower = n.toLowerCase(Locale.ROOT);
                    return lower.endsWith(".ttf") || lower.endsWith(".otf");
                });
                if (listed != null) {
                    Arrays.sort(listed);
                    files.addAll(Arrays.asList(listed));
                }
            } else if (source.isFile()) {
                files.add(source);
            } else {
                throw new MojoExecutionException("Family '" + cfg.name + "': source not found: " + source);
            }
        }
        if (files.isEmpty()) {
            throw new MojoExecutionException("Family '" + cfg.name + "': no .ttf or .otf files in " + cfg.sources);
        }
        List<SfntFont> fonts = new ArrayList<>();
        for (File f : files) {
            try {
                fonts.add(SfntFont.read(f.toPath()));
            } catch (IOException | RuntimeException e) {
                throw new MojoExecutionException("Family '" + cfg.name + "': could not read " + f + ": "
                        + e.getMessage(), e);
            }
        }
        return fonts;
    }

    private static Set<String> styles(String styles) {
        Set<String> out = new LinkedHashSet<>();
        if (styles != null) {
            for (String s : styles.trim().split("[\\s,]+")) {
                if (!s.isEmpty()) {
                    out.add(s.toLowerCase(Locale.ROOT));
                }
            }
        }
        return out;
    }

    private static String describe(Map<String, List<SfntFont>> byFamily) {
        List<String> out = new ArrayList<>();
        byFamily.forEach((family, fonts) -> out.add("'" + family + "' (" + fonts.size()
                + (fonts.size() == 1 ? " file" : " files") + ")"));
        return String.join(", ", out);
    }

    private static String number(float v) {
        return v == Math.rint(v) ? Integer.toString((int) v) : Float.toString(v);
    }
}
