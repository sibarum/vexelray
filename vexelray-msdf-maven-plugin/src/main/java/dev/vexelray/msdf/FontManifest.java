package dev.vexelray.msdf;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code fonts.json}: every family, every face, where its files are and what its texture costs — readable without
 * loading a pixel.
 *
 * <p>The runtime loads every face it lists, at startup. The manifest exists so that what the application will hold
 * is a fact of the build, printed in the build log, rather than something discovered in a profiler. It is also the
 * one place a later residency policy would need to read (docs/plans/font-families.md §2).
 */
final class FontManifest {

    /** One baked face, as the manifest describes it. Paths are relative to the manifest's directory. */
    record Entry(FamilyPlan.Face face, String metrics, String pixels, int width, int height, int glyphs) {

        /** The texture's size on the GPU: RGBA8, one mip level. */
        long bytes() {
            return (long) width * height * 4;
        }
    }

    /** One family's faces, in manifest order. */
    record Family(String name, String fontFamily, List<Entry> faces) {
    }

    private static final Pattern WIDTH = Pattern.compile("\"width\":(\\d+)");
    private static final Pattern HEIGHT = Pattern.compile("\"height\":(\\d+)");
    private static final Pattern UNICODE = Pattern.compile("\"unicode\":");

    private FontManifest() {
    }

    /** Reads one face's produced atlas JSON for the numbers the manifest records. */
    static Entry entry(FamilyPlan.Face face, String metrics, String pixels, File json) throws MojoExecutionException {
        String text;
        try {
            text = Files.readString(json.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed reading atlas JSON " + json, e);
        }
        Matcher w = WIDTH.matcher(text);
        Matcher h = HEIGHT.matcher(text);
        if (!w.find() || !h.find()) {
            throw new MojoExecutionException("Atlas JSON " + json + " has no atlas width/height.");
        }
        int glyphs = 0;
        Matcher u = UNICODE.matcher(text);
        while (u.find()) {
            glyphs++;
        }
        return new Entry(face, metrics, pixels, Integer.parseInt(w.group(1)), Integer.parseInt(h.group(1)), glyphs);
    }

    static long bytes(List<Family> families) {
        long total = 0;
        for (Family f : families) {
            for (Entry e : f.faces()) {
                total += e.bytes();
            }
        }
        return total;
    }

    static String json(List<Family> families) {
        StringBuilder out = new StringBuilder("{\n  \"version\": 1,\n  \"bytes\": ").append(bytes(families))
                .append(",\n  \"families\": [");
        for (int i = 0; i < families.size(); i++) {
            Family f = families.get(i);
            out.append(i == 0 ? "\n" : ",\n")
                    .append("    {\"name\": ").append(quote(f.name()))
                    .append(", \"fontFamily\": ").append(quote(f.fontFamily()))
                    .append(", \"faces\": [");
            for (int k = 0; k < f.faces().size(); k++) {
                Entry e = f.faces().get(k);
                FaceStyle s = e.face().style();
                out.append(k == 0 ? "\n" : ",\n")
                        .append("      {\"style\": ").append(quote(e.face().key()))
                        .append(", \"weight\": ").append(s.weight())
                        .append(", \"stretch\": ").append(s.stretch())
                        .append(", \"slope\": ").append(quote(s.slope().name().toLowerCase(Locale.ROOT)))
                        .append(", \"source\": ").append(quote(e.face().source()))
                        .append(", \"metrics\": ").append(quote(e.metrics()))
                        .append(", \"pixels\": ").append(quote(e.pixels()))
                        .append(", \"width\": ").append(e.width())
                        .append(", \"height\": ").append(e.height())
                        .append(", \"glyphs\": ").append(e.glyphs())
                        .append(", \"bytes\": ").append(e.bytes())
                        .append('}');
            }
            out.append("\n    ]}");
        }
        return out.append("\n  ]\n}\n").toString();
    }

    /** The build-log table: one line per face, and the total. */
    static List<String> summary(List<Family> families) {
        List<String> out = new ArrayList<>();
        for (Family f : families) {
            for (Entry e : f.faces()) {
                out.add(String.format(Locale.ROOT, "  %-28s %5d x %-5d %6d glyphs %8s",
                        f.name() + "/" + e.face().key(), e.width(), e.height(), e.glyphs(), megabytes(e.bytes())));
            }
        }
        out.add(String.format(Locale.ROOT, "  %-28s %37s", "total", megabytes(bytes(families))));
        return out;
    }

    static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
