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
import java.util.ArrayList;
import java.util.List;

/**
 * Generates MSDF (multi-channel signed distance field) atlases from TTF/OTF fonts via a bundled
 * {@code msdf-atlas-gen} binary, then bakes a font-independent missing-glyph box. A focused adaptation of Dasum's
 * {@code dasum-msdf-maven-plugin}: no icon-font codegen. One primary font per atlas, plus optional
 * {@code <extraFonts>} packed into the same image via msdf-atlas-gen's {@code -and} inputs (the JSON output then
 * uses the multi-font {@code variants} shape, one variant per face in declaration order).
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

    /** Base output directory; each atlas writes {@code <name>.png} and {@code <name>.json} here. */
    @Parameter(required = true)
    private File outputDir;

    @Parameter(required = true)
    private List<AtlasConfig> atlases;

    /** Working directory for the extracted binary and intermediate charset files. */
    @Parameter(defaultValue = "${project.build.directory}/vexelray-msdf")
    private File workDir;

    @Override
    public void execute() throws MojoExecutionException {
        if (atlases == null || atlases.isEmpty()) {
            getLog().warn("No <atlases> configured; nothing to do.");
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
        for (AtlasConfig cfg : atlases) {
            processAtlas(cfg, binary, prebuilt);
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
    }

    private void bakeNotdef(AtlasConfig cfg, File pngOut, File jsonOut) throws MojoExecutionException {
        if (NotdefGlyph.ensure(pngOut, jsonOut)) {
            getLog().info("Atlas '" + cfg.name + "': baked missing-glyph box (U+FFFD).");
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

    private static String resolveCharsetContent(String charset) {
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
                throw new MojoExecutionException("msdf-atlas-gen exited with code " + exit + " for atlas '" + cfg.name + "'.");
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to invoke msdf-atlas-gen", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("Interrupted while waiting for msdf-atlas-gen", e);
        }
    }
}
