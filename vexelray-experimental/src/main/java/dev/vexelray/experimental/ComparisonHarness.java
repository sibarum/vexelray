package dev.vexelray.experimental;

import com.oracle.truffle.api.CallTarget;
import dev.supirvast.vast.CoreToTruffle;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs a set of {@link ShapeField}s through the shared {@link Raymarcher} on one Vulkan device and produces a
 * comparison: a metrics table (markdown + stdout), a per-field capture PNG, and a labelled side-by-side montage.
 * Everything is headless (offscreen), so it runs without a window and is reproducible for CI or a benchmark log.
 *
 * <p>Measured per field: shader-compose time, SPIR-V size, median cold render time over several repeats, CPU
 * (Truffle) evaluation cost of the same field, and a fidelity RMSE of the candidate render against a high-step
 * reference render of that field. Fixed camera and resolution across all fields so the comparison is fair.
 *
 * <p><b>Render-time caveat:</b> {@link OffscreenRenderer} builds a fresh pipeline + shader modules per call, so
 * "render time" is <em>cold</em> — it includes driver shader compilation, which for large SPIR-V dominates. It is
 * a consistent comparative cost signal, not a pure GPU frame time; timestamp-query GPU timing is a future refinement.
 */
public final class ComparisonHarness {

    private static final float[] CAMERA = {0.0f, 1.2f, -3.0f};

    private final int width;
    private final int height;
    private final int candidateSteps;
    private final int referenceSteps;
    private final int renderRepeats;

    public ComparisonHarness(int width, int height, int candidateSteps, int referenceSteps, int renderRepeats) {
        this.width = width;
        this.height = height;
        this.candidateSteps = candidateSteps;
        this.referenceSteps = referenceSteps;
        this.renderRepeats = renderRepeats;
    }

    /** A sensible default: 512², 160-step candidates vs a 512-step reference, 5 render repeats. */
    public static ComparisonHarness standard() {
        return new ComparisonHarness(512, 512, 160, 512, 5);
    }

    /** Run every field, writing captures + montage + report under {@code outDir}; returns the metrics rows. */
    public List<Metrics> run(List<ShapeField> fields, Path outDir) {
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] vert = Fullscreen.triangleVertexWithUvSpirv();
        byte[] cam = camBytes(CAMERA[0], CAMERA[1], CAMERA[2]);
        NativePlatform platform = NativePlatform.current();

        List<Metrics> results = new ArrayList<>();
        List<byte[]> captures = new ArrayList<>();
        try (VulkanInstance instance = new VulkanInstance("vexelray-experimental",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            System.out.println("device: " + sel.deviceName());
            try (VulkanDevice device = new VulkanDevice(instance.handle(), sel)) {
                for (ShapeField field : fields) {
                    System.out.println("--- " + field.name() + " ---");

                    long t0 = System.nanoTime();
                    byte[] frag = Raymarcher.fragmentSpirv(field, candidateSteps);
                    double composeMs = (System.nanoTime() - t0) / 1e6;

                    byte[] rgba = null;
                    double[] times = new double[renderRepeats];
                    for (int k = 0; k < renderRepeats; k++) {
                        long r0 = System.nanoTime();
                        rgba = OffscreenRenderer.render(device, width, height, vert, "main", frag, "main", 3,
                                0.10f, 0.12f, 0.16f, 1.0f, cam);
                        times[k] = (System.nanoTime() - r0) / 1e6;
                    }
                    double renderMed = median(times);

                    byte[] refFrag = Raymarcher.fragmentSpirv(field, referenceSteps);
                    byte[] refRgba = OffscreenRenderer.render(device, width, height, vert, "main", refFrag, "main", 3,
                            0.10f, 0.12f, 0.16f, 1.0f, cam);
                    double rmse = rmse(rgba, refRgba);

                    double cpuNs = benchmarkCpu(field);

                    writePng(rgba, outDir.resolve(field.name() + ".png"));
                    captures.add(rgba);
                    results.add(new Metrics(field.name(), composeMs, frag.length, renderMed, cpuNs, rmse,
                            field.applicability()));
                    System.out.printf("    compose=%.1fms  spirv=%dKB  render=%.1fms  cpu=%.0fns  fidelityRMSE=%.2f%n",
                            composeMs, frag.length / 1024, renderMed, cpuNs, rmse);
                }
            }
        }
        writeMontage(fields, captures, outDir.resolve("montage.png"));
        writeReport(results, outDir.resolve("report.md"));
        return results;
    }

    /** Time one CPU evaluation of the field via the Truffle backend (warm up first), in ns/call. */
    private double benchmarkCpu(ShapeField field) {
        CallTarget cpu = new CoreToTruffle().lower(Raymarcher.sdfFunction(field));
        float[][] pts = samplePoints();
        // warm up (JIT + Truffle)
        for (int w = 0; w < 20_000; w++) {
            cpu.call((Object) pts[w % pts.length]);
        }
        int calls = 300_000;
        long t0 = System.nanoTime();
        float sink = 0f;
        for (int c = 0; c < calls; c++) {
            sink += (Float) cpu.call((Object) pts[c % pts.length]);
        }
        long elapsed = System.nanoTime() - t0;
        if (Float.isNaN(sink)) {
            System.out.print("");   // keep the JIT from eliminating the loop
        }
        return (double) elapsed / calls;
    }

    /** A deterministic spread of sample points across the field's domain (no RNG, so runs are reproducible). */
    private static float[][] samplePoints() {
        List<float[]> pts = new ArrayList<>();
        for (int ix = -10; ix <= 10; ix++) {
            for (int iz = -10; iz <= 10; iz++) {
                pts.add(new float[] {ix * 0.9f, 1.0f + (ix + iz) * 0.05f, iz * 0.9f});
            }
        }
        return pts.toArray(new float[0][]);
    }

    private static double median(double[] xs) {
        double[] s = xs.clone();
        Arrays.sort(s);
        return s[s.length / 2];
    }

    /** Root-mean-square error between two RGBA buffers, on the 0..255 scale (RGB channels). */
    private static double rmse(byte[] a, byte[] b) {
        long sum = 0;
        int n = 0;
        for (int i = 0; i < a.length; i += 4) {
            for (int c = 0; c < 3; c++) {
                int d = (a[i + c] & 0xFF) - (b[i + c] & 0xFF);
                sum += (long) d * d;
                n++;
            }
        }
        return Math.sqrt((double) sum / n);
    }

    private BufferedImage toImage(byte[] rgba) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int i = (y * width + x) * 4;
                image.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                        | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
        }
        return image;
    }

    private void writePng(byte[] rgba, Path path) {
        try {
            ImageIO.write(toImage(rgba), "PNG", path.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeMontage(List<ShapeField> fields, List<byte[]> captures, Path path) {
        int bar = 24;
        BufferedImage montage = new BufferedImage(width * captures.size(), height + bar, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = montage.createGraphics();
        g.setColor(new Color(0x101216));
        g.fillRect(0, 0, montage.getWidth(), montage.getHeight());
        for (int i = 0; i < captures.size(); i++) {
            g.drawImage(toImage(captures.get(i)), i * width, bar, null);
            g.setColor(Color.WHITE);
            g.drawString(fields.get(i).name(), i * width + 8, 16);
        }
        g.dispose();
        try {
            ImageIO.write(montage, "PNG", path.toFile());
            System.out.println("montage: " + path.toAbsolutePath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void writeReport(List<Metrics> results, Path path) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Shape-technique comparison\n\n");
        sb.append(String.format("Camera (%.1f, %.1f, %.1f), %d×%d, candidate %d steps vs reference %d steps, "
                        + "median of %d renders.%n%n",
                CAMERA[0], CAMERA[1], CAMERA[2], width, height, candidateSteps, referenceSteps, renderRepeats));
        sb.append("| technique | compose (ms) | SPIR-V (KB) | render (ms, cold) | CPU eval (ns) | fidelity RMSE |\n");
        sb.append("|---|--:|--:|--:|--:|--:|\n");
        for (Metrics m : results) {
            sb.append(String.format("| %s | %.1f | %d | %.1f | %.0f | %.2f |%n",
                    m.name(), m.composeMillis(), m.spirvBytes() / 1024, m.renderMedianMillis(),
                    m.cpuNanosPerCall(), m.fidelityRmse()));
        }
        sb.append("\n## Applicability\n\n");
        for (Metrics m : results) {
            sb.append("- **").append(m.name()).append("** — ").append(m.applicability()).append('\n');
        }
        sb.append("\n> Render time is *cold* (includes pipeline + shader-module build, which dominates for large "
                + "SPIR-V). It is a comparative signal, not a pure GPU frame time.\n");
        try {
            Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
            System.out.println("report: " + path.toAbsolutePath());
            System.out.println();
            System.out.println(sb);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] camBytes(float x, float y, float z) {
        return ByteBuffer.allocate(Raymarcher.CAM_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putFloat(x).putFloat(y).putFloat(z).array();
    }
}
