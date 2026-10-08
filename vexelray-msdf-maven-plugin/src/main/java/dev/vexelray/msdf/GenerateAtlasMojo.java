package dev.vexelray.msdf;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Generates MSDF (multi-channel signed distance field) atlases from TTF/OTF fonts via a bundled
 * {@code msdf-atlas-gen} binary, then bakes a font-independent missing-glyph box. A focused adaptation of Dasum's
 * {@code dasum-msdf-maven-plugin}: no icon-font codegen. One primary font per atlas, plus optional
 * {@code <extraFonts>} packed into the same image via msdf-atlas-gen's {@code -and} inputs (the JSON output then
 * uses the multi-font {@code variants} shape, one variant per face in declaration order).
 *
 * <p>{@code <families>} is the high-level form ({@link FamilyConfig}): name a family and its font files, and every
 * face it has is baked into its own atlas, with every glyph each face maps, plus a {@code fonts.json} manifest and
 * a printed account of the texture memory they will take. See docs/plans/font-families.md in vexelray-gui.
 *
 * <p>Two modes via {@code msdf.mode}:
 * <ul>
 *   <li>{@code primary} (default): invoke {@code msdf-atlas-gen} to regenerate outputs; fails loudly if the
 *       bundled binary is unavailable for the current OS.</li>
 *   <li>{@code prebuilt}: skip the binary; verify pre-generated atlas files exist. Used on non-Windows builds.</li>
 * </ul>
 * Primary mode is incremental: if all outputs are newer than the input font, the atlas is left alone (the
 * missing-glyph box is still baked idempotently).
 */
@Mojo(name = "generate-atlas", defaultPhase = LifecyclePhase.GENERATE_RESOURCES, threadSafe = true)
public class GenerateAtlasMojo extends AbstractMojo {

    @Parameter(property = "msdf.mode", defaultValue = "primary")
    private String mode;

    /** Base output directory; each atlas writes {@code <name>.png}, {@code <name>.json} and {@code <name>.rgba} here. */
    @Parameter(required = true)
    private File outputDir;

    /** Atlases from one font file and an explicit code-point list each. */
    @Parameter
    private List<AtlasConfig> atlases;

    /**
     * Font families: one atlas per face, every glyph by default, faces found by reading the fonts. Written under
     * {@code <outputDir>/<family>/<style>.*}, with {@code <outputDir>/fonts.json} listing them all.
     */
    @Parameter
    private List<FamilyConfig> families;

    /**
     * Fail the build when the families' textures together exceed this many megabytes. Unset, the total is printed
     * and nothing fails. Every face is loaded at startup, so this is the application's font VRAM, known here.
     */
    @Parameter(property = "msdf.fontBudgetMegabytes")
    private Integer fontBudgetMegabytes;

    /** Working directory for the extracted binary and intermediate charset files. */
    @Parameter(defaultValue = "${project.build.directory}/vexelray-msdf")
    private File workDir;

    /**
     * Fail the build when a charset asks for codepoints the font does not have, rather than warning.
     *
     * <p>Off by default because a partially covered range is usually a deliberate trade — asking for all of
     * Latin Extended-A and accepting whatever the face carries is reasonable. On, it is the guard for an atlas
     * whose exact coverage something depends on: an icon vocabulary cannot be specified against a font that
     * silently declines half of it.
     */
    @Parameter(property = "msdf.failOnMissingGlyphs", defaultValue = "false")
    private boolean failOnMissingGlyphs;

    @Override
    public void execute() throws MojoExecutionException {
        boolean noAtlases = atlases == null || atlases.isEmpty();
        boolean noFamilies = families == null || families.isEmpty();
        if (noAtlases && noFamilies) {
            getLog().warn("No <atlases> or <families> configured; nothing to do.");
            return;
        }
        boolean prebuilt = "prebuilt".equalsIgnoreCase(mode);
        if (!prebuilt && !"primary".equalsIgnoreCase(mode)) {
            throw new MojoExecutionException("Invalid mode '" + mode + "' — expected 'primary' or 'prebuilt'.");
        }
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new MojoExecutionException("Failed to create output dir: " + outputDir);
        }
        File binary = prebuilt ? null : extractBinary();
        for (AtlasConfig cfg : noAtlases ? List.<AtlasConfig>of() : atlases) {
            processAtlas(cfg, binary, prebuilt);
        }
        if (!noFamilies) {
            processFamilies(binary, prebuilt);
        }
    }

    // --- families --------------------------------------------------------------------------------------------

    private void processFamilies(File binary, boolean prebuilt) throws MojoExecutionException {
        List<FamilyPlan> plans = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (FamilyConfig cfg : families) {
            FamilyPlan plan = FamilyPlan.of(cfg);
            if (!names.add(plan.family.toLowerCase(Locale.ROOT))) {
                throw new MojoExecutionException("Two families are both named '" + plan.family + "'.");
            }
            plans.add(plan);
        }

        List<FontManifest.Family> manifest = new ArrayList<>();
        for (int i = 0; i < plans.size(); i++) {
            FamilyPlan plan = plans.get(i);
            FamilyConfig cfg = families.get(i);
            File dir = new File(outputDir, plan.family);
            if (!dir.exists() && !dir.mkdirs()) {
                throw new MojoExecutionException("Failed to create family output dir: " + dir);
            }
            List<FontManifest.Entry> entries = new ArrayList<>();
            for (FamilyPlan.Face face : plan.faces) {
                entries.add(processFace(plan, cfg, face, dir, binary, prebuilt));
            }
            reportStale(plan, dir);
            manifest.add(new FontManifest.Family(plan.family, plan.fontFamily, entries));
        }

        File manifestFile = new File(outputDir, "fonts.json");
        try {
            Files.writeString(manifestFile.toPath(), FontManifest.json(manifest), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed writing font manifest " + manifestFile, e);
        }
        getLog().info("Font families (every face is loaded at startup):");
        for (String line : FontManifest.summary(manifest)) {
            getLog().info(line);
        }
        for (FontManifest.Family f : manifest) {
            for (FontManifest.Entry e : f.faces()) {
                if (e.width() > VULKAN_GUARANTEED_DIMENSION || e.height() > VULKAN_GUARANTEED_DIMENSION) {
                    getLog().warn("Face " + f.name() + "/" + e.face().key() + " is " + e.width() + " x " + e.height()
                            + ", past the " + VULKAN_GUARANTEED_DIMENSION + " px every Vulkan device must support."
                            + " Narrow it with <charset>, or lower <fontSize>.");
                }
            }
        }
        long total = FontManifest.bytes(manifest);
        if (fontBudgetMegabytes != null && total > fontBudgetMegabytes * 1024L * 1024L) {
            throw new MojoExecutionException("Font textures total " + FontManifest.megabytes(total)
                    + ", over the budget of " + fontBudgetMegabytes + " MB. Narrow a family's <styles> or"
                    + " <charset>, or raise msdf.fontBudgetMegabytes.");
        }
    }

    /** {@code maxImageDimension2D}'s required minimum in the Vulkan spec. */
    private static final int VULKAN_GUARANTEED_DIMENSION = 4096;

    private FontManifest.Entry processFace(FamilyPlan plan, FamilyConfig cfg, FamilyPlan.Face face, File dir,
                                           File binary, boolean prebuilt) throws MojoExecutionException {
        String label = plan.family + "/" + face.key();
        File pngOut = new File(dir, face.key() + ".png");
        File jsonOut = new File(dir, face.key() + ".json");
        File rgbaOut = new File(dir, face.key() + ".rgba");
        File stampOut = new File(dir, face.key() + ".stamp");

        if (prebuilt) {
            if (!pngOut.isFile() || !jsonOut.isFile()) {
                throw new MojoExecutionException("Prebuilt face '" + label + "' missing at " + dir
                        + ". Switch to the default primary mode to regenerate, or commit the atlas.");
            }
        } else {
            String charset = FamilyPlan.charsetOf(face, cfg.charset);
            List<String> generate = new ArrayList<>(face.fontArguments());
            generate.addAll(List.of("-type", "msdf", "-format", "png",
                    "-size", Integer.toString(cfg.fontSize), "-pxrange", Integer.toString(cfg.pxRange), "-square4"));
            String stamp = stamp(face, generate, charset);
            if (pngOut.isFile() && jsonOut.isFile() && stamp.equals(readStamp(stampOut))) {
                getLog().info("Face '" + label + "' is up to date — skipping.");
            } else {
                File charsetFile = writeCharsetFile(plan.family + "-" + face.key(), charset);
                List<String> cmd = new ArrayList<>();
                cmd.add(binary.getAbsolutePath());
                cmd.add(generate.get(0));
                cmd.add(generate.get(1));
                cmd.add("-charset");
                cmd.add(charsetFile.getAbsolutePath());
                cmd.addAll(generate.subList(2, generate.size()));
                cmd.addAll(List.of("-imageout", pngOut.getAbsolutePath(), "-json", jsonOut.getAbsolutePath()));
                // A stale stamp must not outlive a failed run, or the next build would skip a half-written face.
                stampOut.delete();
                run(cmd, label);
                if (!pngOut.isFile() || !jsonOut.isFile()) {
                    throw new MojoExecutionException("msdf-atlas-gen completed but face '" + label
                            + "' outputs are missing: " + pngOut + " / " + jsonOut);
                }
                if (NotdefGlyph.ensure(pngOut, jsonOut)) {
                    getLog().info("Face '" + label + "': baked missing-glyph box (U+FFFD).");
                }
                writeStamp(stampOut, stamp);
            }
            if (cfg.charset != null && !cfg.charset.isBlank()) {
                reportFaceCoverage(label, cfg, jsonOut);
            }
        }
        AtlasPixels.ensure(pngOut, rgbaOut);
        return FontManifest.entry(face, plan.family + "/" + jsonOut.getName(), plan.family + "/" + rgbaOut.getName(),
                jsonOut);
    }

    /**
     * Files in a family's directory that no face of it produced — a style dropped from {@code <styles>}, a font
     * removed from the sources. Said rather than deleted: the directory is usually under {@code src/main/resources},
     * and what is in it is the author's to remove. They are not in the manifest, so nothing loads them; they only
     * make the jar bigger.
     */
    private void reportStale(FamilyPlan plan, File dir) {
        Set<String> keys = new HashSet<>();
        for (FamilyPlan.Face face : plan.faces) {
            keys.add(face.key());
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            String ext = dot < 0 ? "" : n.substring(dot + 1);
            if (dot > 0 && List.of("png", "json", "rgba", "stamp").contains(ext) && !keys.contains(n.substring(0, dot))) {
                getLog().warn("Family '" + plan.family + "': " + f + " belongs to no face it bakes now. It is not in"
                        + " fonts.json and nothing loads it; delete it to keep it out of the jar.");
            }
        }
    }

    private void reportFaceCoverage(String label, FamilyConfig cfg, File jsonOut) throws MojoExecutionException {
        String json;
        try {
            json = Files.readString(jsonOut.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLog().warn("Face '" + label + "': could not read " + jsonOut.getName() + " to check glyph coverage ("
                    + e.getMessage() + ") — coverage is unverified.");
            return;
        }
        CharsetCoverage.Coverage coverage = CharsetCoverage.measure(resolveCharsetContent(cfg.charset), json);
        List<String> ranges = coverage.missingRanges();
        if (ranges.isEmpty()) {
            return;
        }
        getLog().warn("Face '" + label + "': " + coverage.missing().size() + " of " + coverage.requested().size()
                + " requested codepoints are not in the font, and text using them will draw the missing-glyph box:");
        for (String range : ranges) {
            getLog().warn("    " + range);
        }
        if (failOnMissingGlyphs) {
            throw new MojoExecutionException("Face '" + label + "' is missing requested glyphs and"
                    + " failOnMissingGlyphs is set.");
        }
    }

    /**
     * What decides a face's output: its generator arguments, its charset, and the font's contents. A changed
     * size, range, instance or charset regenerates it, where comparing file times would not.
     *
     * <p>Contents, not path or time: the outputs are committed, and a fresh clone has a different directory and
     * every file dated by the checkout. Either in the stamp would regenerate every face on every new machine.
     */
    private static String stamp(FamilyPlan.Face face, List<String> generate, String charset)
            throws MojoExecutionException {
        String path = face.font().path.toAbsolutePath().toString();
        String args = String.join("\u0000", generate).replace(path, face.font().path.getFileName().toString());
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(Files.readAllBytes(face.font().path));
            sha.update((args + "\u0000" + charset).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java platform", e);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed reading " + face.font().path, e);
        }
    }

    private static String readStamp(File f) {
        try {
            return f.isFile() ? Files.readString(f.toPath(), StandardCharsets.UTF_8).trim() : "";
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeStamp(File f, String stamp) throws MojoExecutionException {
        try {
            Files.writeString(f.toPath(), stamp + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed writing " + f, e);
        }
    }

    private File extractBinary() throws MojoExecutionException {
        String resource = "/bin/windows-x64/msdf-atlas-gen.exe";
        if (!System.getProperty("os.name", "").toLowerCase().contains("windows")) {
            throw new MojoExecutionException(
                    "msdf-atlas-gen.exe is bundled only for Windows in this plugin. "
                            + "Use -Dmsdf.mode=prebuilt to consume pre-generated atlases on other platforms, "
                            + "or supply a binary for the current OS.");
        }
        File binDir = new File(workDir, "bin");
        if (!binDir.exists() && !binDir.mkdirs()) {
            throw new MojoExecutionException("Failed to create binary work dir: " + binDir);
        }
        File target = new File(binDir, "msdf-atlas-gen.exe");
        if (!target.exists()) {
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in == null) {
                    throw new MojoExecutionException("Bundled binary not found at classpath resource " + resource
                            + " — the plugin JAR may be corrupted.");
                }
                Files.copy(in, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new MojoExecutionException("Failed to extract msdf-atlas-gen.exe", e);
            }
            target.setExecutable(true, false);
        }
        return target;
    }

    private void processAtlas(AtlasConfig cfg, File binary, boolean prebuilt) throws MojoExecutionException {
        if (cfg.name == null || cfg.name.isBlank()) {
            throw new MojoExecutionException("Atlas missing required <name>.");
        }
        if (cfg.font == null) {
            throw new MojoExecutionException("Atlas '" + cfg.name + "' missing required <font>.");
        }
        File pngOut = new File(outputDir, cfg.name + ".png");
        File jsonOut = new File(outputDir, cfg.name + ".json");

        if (prebuilt) {
            if (!pngOut.isFile() || !jsonOut.isFile()) {
                throw new MojoExecutionException("Prebuilt atlas '" + cfg.name + "' missing at " + outputDir
                        + " (expected " + pngOut.getName() + " and " + jsonOut.getName()
                        + "). Switch to the default primary mode to regenerate, or commit the atlas.");
            }
            getLog().info("Atlas '" + cfg.name + "' (prebuilt): " + pngOut.getName() + " + " + jsonOut.getName());
            writePixels(cfg, pngOut);
            return;
        }

        if (!cfg.font.isFile()) {
            throw new MojoExecutionException("Atlas '" + cfg.name + "' font not found: " + cfg.font);
        }
        for (AtlasConfig.ExtraFont extra : extraFonts(cfg)) {
            if (extra.font == null || !extra.font.isFile()) {
                throw new MojoExecutionException("Atlas '" + cfg.name + "' extra font not found: " + extra.font);
            }
        }
        if (isUpToDate(cfg, pngOut, jsonOut)) {
            getLog().info("Atlas '" + cfg.name + "' is up to date — skipping.");
            bakeNotdef(cfg, pngOut, jsonOut);
            writePixels(cfg, pngOut);
            reportCoverage(cfg, jsonOut);
            return;
        }

        File charsetFile = writeCharsetFile(cfg.name, resolveCharsetContent(cfg.charset));
        runMsdfAtlasGen(binary, cfg, charsetFile, pngOut, jsonOut);
        if (!pngOut.isFile() || !jsonOut.isFile()) {
            throw new MojoExecutionException("msdf-atlas-gen completed but expected outputs are missing: "
                    + pngOut + " / " + jsonOut);
        }
        getLog().info("Atlas '" + cfg.name + "' generated: " + pngOut.length() + " bytes png + "
                + jsonOut.length() + " bytes json");
        bakeNotdef(cfg, pngOut, jsonOut);
        writePixels(cfg, pngOut);
        reportCoverage(cfg, jsonOut);
    }

    /**
     * Diff every charset this atlas requested against the glyphs it actually produced, and say what the font
     * did not cover.
     *
     * <p>Deliberately also on the up-to-date path above. A warning that appears only on the build that
     * regenerates the atlas is a warning nobody sees: the atlas is regenerated once, months ago, and every
     * build anyone is actually watching takes the skip. Reading the JSON is cheap and the report is the same
     * either way, so there is no reason for the two paths to disagree about what they say.
     */
    private void reportCoverage(AtlasConfig cfg, File jsonOut) throws MojoExecutionException {
        String json;
        try {
            json = Files.readString(jsonOut.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Said, not swallowed: without the JSON this check answers "nothing missing" about an atlas it
            // never read, which is exactly the confident wrong answer it exists to prevent.
            getLog().warn("Atlas '" + cfg.name + "': could not read " + jsonOut.getName()
                    + " to check glyph coverage (" + e.getMessage() + ") — coverage is unverified.");
            return;
        }

        boolean anyMissing = false;
        List<String> charsets = new ArrayList<>();
        charsets.add(cfg.charset);
        for (AtlasConfig.ExtraFont extra : extraFonts(cfg)) {
            charsets.add(extra.charset);
        }
        for (int i = 0; i < charsets.size(); i++) {
            String face = i == 0 ? cfg.font.getName() : extraFonts(cfg).get(i - 1).font.getName();
            CharsetCoverage.Coverage coverage =
                    CharsetCoverage.measure(resolveCharsetContent(charsets.get(i)), json);
            for (String token : coverage.unparsed()) {
                getLog().warn("Atlas '" + cfg.name + "' (" + face + "): charset token '" + token
                        + "' was not understood, so those codepoints are unchecked.");
            }
            List<String> ranges = coverage.missingRanges();
            if (ranges.isEmpty()) {
                getLog().info("Atlas '" + cfg.name + "' (" + face + "): all "
                        + coverage.requested().size() + " requested codepoints are present.");
                continue;
            }
            anyMissing = true;
            getLog().warn("Atlas '" + cfg.name + "' (" + face + "): " + coverage.missing().size()
                    + " of " + coverage.requested().size()
                    + " requested codepoints are NOT in the atlas — the font does not have them, and text"
                    + " using them will draw the missing-glyph box:");
            for (String range : ranges) {
                getLog().warn("    " + range);
            }
        }
        if (anyMissing && failOnMissingGlyphs) {
            throw new MojoExecutionException("Atlas '" + cfg.name
                    + "' is missing requested glyphs and failOnMissingGlyphs is set.");
        }
    }

    private void bakeNotdef(AtlasConfig cfg, File pngOut, File jsonOut) throws MojoExecutionException {
        if (NotdefGlyph.ensure(pngOut, jsonOut)) {
            getLog().info("Atlas '" + cfg.name + "': baked missing-glyph box (U+FFFD).");
        }
    }

    /**
     * The atlas's pixels as {@code <name>.rgba} beside the PNG, for a runtime that must not decode a PNG (see
     * {@link AtlasPixels}). After the missing-glyph box is baked, so both hold it. Prebuilt mode writes it too:
     * it needs only the committed PNG, and no binary.
     */
    private void writePixels(AtlasConfig cfg, File pngOut) throws MojoExecutionException {
        File rgbaOut = new File(outputDir, cfg.name + ".rgba");
        if (AtlasPixels.ensure(pngOut, rgbaOut)) {
            getLog().info("Atlas '" + cfg.name + "': wrote " + rgbaOut.getName() + ", " + rgbaOut.length()
                    + " bytes of deflated RGBA.");
        }
    }

    private boolean isUpToDate(AtlasConfig cfg, File pngOut, File jsonOut) {
        if (!pngOut.isFile() || !jsonOut.isFile()) {
            return false;
        }
        long outputMtime = Math.min(pngOut.lastModified(), jsonOut.lastModified());
        long inputMtime = cfg.font.lastModified();
        for (AtlasConfig.ExtraFont extra : extraFonts(cfg)) {
            inputMtime = Math.max(inputMtime, extra.font.lastModified());
        }
        return inputMtime <= outputMtime;
    }

    private static List<AtlasConfig.ExtraFont> extraFonts(AtlasConfig cfg) {
        return cfg.extraFonts == null ? List.of() : cfg.extraFonts;
    }

    static String resolveCharsetContent(String charset) {
        String preset = charset == null ? "ascii" : charset.trim();
        return switch (preset.toLowerCase()) {
            case "ascii" -> "[0x20, 0x7E]\n";
            case "latin-1", "latin1" -> "[0x20, 0x7E]\n[0xA0, 0xFF]\n";
            default -> preset.endsWith("\n") ? preset : preset + "\n";
        };
    }

    private File writeCharsetFile(String atlasName, String content) throws MojoExecutionException {
        File charsetsDir = new File(workDir, "charsets");
        if (!charsetsDir.exists() && !charsetsDir.mkdirs()) {
            throw new MojoExecutionException("Failed to create charsets work dir: " + charsetsDir);
        }
        File f = new File(charsetsDir, atlasName + ".txt");
        try {
            Files.writeString(f.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to write charset file " + f, e);
        }
        return f;
    }

    private void runMsdfAtlasGen(File binary, AtlasConfig cfg, File charsetFile, File pngOut, File jsonOut)
            throws MojoExecutionException {
        List<String> cmd = new ArrayList<>();
        cmd.add(binary.getAbsolutePath());
        cmd.add("-font");
        cmd.add(cfg.font.getAbsolutePath());
        cmd.add("-charset");
        cmd.add(charsetFile.getAbsolutePath());
        // Additional faces share the one atlas image; -and switches the JSON to the "variants" shape,
        // one variant per face in this order (the runtime resolves face indices from that order).
        int extraIndex = 0;
        for (AtlasConfig.ExtraFont extra : extraFonts(cfg)) {
            File extraCharset = writeCharsetFile(cfg.name + "-extra" + extraIndex++,
                    resolveCharsetContent(extra.charset));
            cmd.add("-and");
            cmd.add("-font");
            cmd.add(extra.font.getAbsolutePath());
            cmd.add("-charset");
            cmd.add(extraCharset.getAbsolutePath());
        }
        cmd.add("-type");
        cmd.add("msdf");
        cmd.add("-format");
        cmd.add("png");
        cmd.add("-size");
        cmd.add(Integer.toString(cfg.fontSize));
        cmd.add("-pxrange");
        cmd.add(Integer.toString(cfg.pxRange));
        cmd.add("-dimensions");
        cmd.add(Integer.toString(cfg.atlasSize));
        cmd.add(Integer.toString(cfg.atlasSize));
        cmd.add("-imageout");
        cmd.add(pngOut.getAbsolutePath());
        cmd.add("-json");
        cmd.add(jsonOut.getAbsolutePath());

        run(cmd, cfg.name);
    }

    private void run(List<String> cmd, String label) throws MojoExecutionException {
        getLog().info("msdf-atlas-gen: " + String.join(" ", cmd));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        try {
            Process p = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    getLog().info("[msdf-atlas-gen] " + line);
                }
            }
            int exit = p.waitFor();
            if (exit != 0) {
                throw new MojoExecutionException("msdf-atlas-gen exited with code " + exit + " for '" + label + "'.");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to invoke msdf-atlas-gen", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while waiting for msdf-atlas-gen", e);
        }
    }
}
