package dev.vexelray.shadelets;

import dev.supirvast.vastir.lower.CoreToSpirv;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.canvas.Canvas;
import dev.vexelray.canvas.CanvasShader;
import dev.vexelray.canvas.CanvasVertex;
import dev.vexelray.canvas.shadelet.Composition;
import dev.vexelray.canvas.shadelet.LookTable;
import dev.vexelray.target.ImageHandle;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.OffscreenDraw;
import dev.vexelray.vulkan.present.StorageBuffer;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.WindowedPresenter;

import java.util.ArrayList;
import java.util.List;

/**
 * A canvas drawn offscreen with a composed fragment shader and read back as RGBA: the GPU half of a shadelet's
 * tests. One device, render pass, pipeline and table buffer for its life; each {@link #draw} uploads the look table
 * at 0 and the canvas's element records at {@link Composition#RECORDS_BASE}, as a host does every frame.
 *
 * <p>The pipeline is the canvas's own: the fat vertex format, alpha blending, and its three sets (a placeholder atlas
 * at set 0, the image or field at set 1, the tables at set 2). The fragment stage is lowered in-process from the
 * composition, so the test draws exactly what the composition says.
 */
final class OffscreenCanvas implements AutoCloseable {

    /** A texture a canvas's image or field quads name by {@code handle}, bound at set 1 for their runs. */
    record Texture(ImageHandle handle, int width, int height, byte[] rgba) {
    }

    /** An image handle with nothing behind it: the harness maps it to a {@link Texture}'s upload. */
    static final class Handle implements ImageHandle {
    }

    final int width;
    final int height;
    private final VulkanInstance instance;
    private final VulkanDevice device;
    private final VulkanRenderPass renderPass;
    private final AtlasTexture placeholder;
    private final StorageBuffer tables;
    private final GraphicsPipeline pipeline;

    /** Whether there is a Vulkan graphics device to draw with; the GPU tests skip without one. */
    static boolean available() {
        try (VulkanInstance probe = new VulkanInstance("vexelray-shadelets probe", List.of())) {
            return probe.selectGraphicsDevice().isPresent();
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    OffscreenCanvas(Composition composition, int width, int height) {
        this.width = width;
        this.height = height;
        byte[] vertex = new CoreToSpirv().lower(new CanvasShader.Vertex().module()).toByteArray();
        byte[] fragment = new CoreToSpirv().lower(CanvasShader.fragmentModule(composition)).toByteArray();
        instance = new VulkanInstance("vexelray-shadelets test", List.of());
        VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                .orElseThrow(() -> new IllegalStateException("no graphics device"));
        device = new VulkanDevice(instance.handle(), selection, false);
        renderPass = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        placeholder = AtlasTexture.placeholder(device);
        tables = new StorageBuffer(device, Composition.TABLES_FLOATS, Composition.TABLES_BINDING);
        List<GraphicsPipeline.VertexAttribute> attributes = new ArrayList<>();
        for (CanvasVertex.Attr a : CanvasVertex.ATTRIBUTES) {
            attributes.add(GraphicsPipeline.VertexAttribute.floats(a.location(), a.components(), a.offset()));
        }
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(CanvasVertex.STRIDE_BYTES, attributes,
                new long[]{placeholder.descriptorSetLayout(), placeholder.descriptorSetLayout(),
                        tables.descriptorSetLayout()}, true, Vk.SHADER_STAGE_FRAGMENT_BIT, 0);
        pipeline = new GraphicsPipeline(device, renderPass.handle(), width, height, vertex, "main", fragment, "main",
                config);
    }

    /** {@code canvas} drawn over opaque black, as tightly packed RGBA rows, top to bottom. */
    byte[] draw(LookTable table, Canvas canvas) {
        return draw(table, canvas, null);
    }

    /** As {@link #draw(LookTable, Canvas)}, with {@code texture} bound for the runs that name its handle. */
    byte[] draw(LookTable table, Canvas canvas, Texture texture) {
        float[] looks = table.floats();
        tables.update(0, looks, looks.length);
        tables.update(Composition.RECORDS_BASE, canvas.records(), canvas.recordCount());
        AtlasTexture image = texture == null ? null
                : new AtlasTexture(device, texture.width(), texture.height(), texture.rgba());
        try (VertexBuffer vertices = new VertexBuffer(device, canvas.toVertexArray())) {
            List<WindowedPresenter.Run> runs = new ArrayList<>();
            for (Canvas.Run run : canvas.runs()) {
                long set1 = placeholder.descriptorSet();
                if (run.image() != null) {
                    if (texture == null || run.image() != texture.handle()) {
                        throw new IllegalArgumentException("the canvas names an image the harness was not given");
                    }
                    set1 = image.descriptorSet();
                }
                runs.add(new WindowedPresenter.Run(set1, run.firstVertex(), run.vertexCount()));
            }
            return OffscreenDraw.toRgba(device, renderPass.handle(), pipeline, width, height, vertices.handle(),
                    placeholder.descriptorSet(), tables.descriptorSet(), runs, 0f, 0f, 0f, 1f);
        } finally {
            if (image != null) {
                image.close();
            }
        }
    }

    @Override
    public void close() {
        pipeline.close();
        tables.close();
        placeholder.close();
        renderPass.close();
        device.close();
        instance.close();
    }

    // --- reading the pixels ---

    /** Channel {@code c} (0 red .. 3 alpha) of pixel {@code (x, y)}, 0..255. */
    static int channel(byte[] rgba, int width, int x, int y, int c) {
        return rgba[(y * width + x) * 4 + c] & 0xFF;
    }

    /**
     * Where along row {@code y} channel {@code c} crosses half of {@code full}, searching from {@code from} toward
     * {@code to}, linearly interpolated between the pixel centres either side (pixel {@code x}'s centre is
     * {@code x + 0.5}). NaN if it never crosses.
     */
    static double crossing(byte[] rgba, int width, int y, int c, int from, int to, int full) {
        double half = full / 2.0;
        int step = to > from ? 1 : -1;
        for (int x = from; x != to; x += step) {
            double a = channel(rgba, width, x, y, c);
            double b = channel(rgba, width, x + step, y, c);
            if ((a - half) * (b - half) <= 0 && a != b) {
                double t = (half - a) / (b - a);
                return x + 0.5 + step * t;
            }
        }
        return Double.NaN;
    }

    /** The largest per-channel difference between two images, inside the rectangle {@code [x0, x1) x [y0, y1)}. */
    static int maxDifference(byte[] a, byte[] b, int width, int x0, int y0, int x1, int y1) {
        int worst = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                for (int c = 0; c < 4; c++) {
                    worst = Math.max(worst, Math.abs(channel(a, width, x, y, c) - channel(b, width, x, y, c)));
                }
            }
        }
        return worst;
    }

    /** Pixels whose channels differ by more than {@code tolerance} anywhere. */
    static int countDifferent(byte[] a, byte[] b, int width, int height, int tolerance) {
        int n = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int c = 0; c < 4; c++) {
                    if (Math.abs(channel(a, width, x, y, c) - channel(b, width, x, y, c)) > tolerance) {
                        n++;
                        break;
                    }
                }
            }
        }
        return n;
    }

    /** Pixels with any channel above zero: how much was drawn over the black background. */
    static int litPixels(byte[] rgba, int width, int height) {
        int n = 0;
        for (int i = 0; i < width * height; i++) {
            if ((rgba[i * 4] & 0xFF) + (rgba[i * 4 + 1] & 0xFF) + (rgba[i * 4 + 2] & 0xFF) > 0) {
                n++;
            }
        }
        return n;
    }
}
