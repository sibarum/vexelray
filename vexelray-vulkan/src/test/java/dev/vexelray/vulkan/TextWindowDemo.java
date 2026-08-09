package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.text.AtlasData;
import dev.vexelray.text.GlyphLayout;
import dev.vexelray.text.GlyphQuad;
import dev.vexelray.text.MsdfShader;
import dev.vexelray.text.TextLayout;
import dev.vexelray.text.TextMesh;
import dev.vexelray.vulkan.present.AtlasTexture;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.VertexBuffer;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.VulkanSwapchain;
import dev.vexelray.vulkan.present.WindowedPresenter;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VkLoader;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static dev.vexelray.vulkan.vk.Ffm.check;
import static dev.vexelray.vulkan.vk.Ffm.gi;
import static dev.vexelray.vulkan.vk.Ffm.gl;
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.invokeVoid;
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Manual smoke check (not a unit test): MSDF text in a live window — VexelRay's first font rendering. It loads the
 * committed Noto Sans MSDF atlas ({@code /dev/vexelray/text/atlas/primary.*}, bootstrapped from Dasum), lays a
 * string out into glyph quads ({@link GlyphLayout} + {@link TextMesh}), uploads them to a {@link VertexBuffer},
 * uploads the atlas to an {@link AtlasTexture} (sampled image + sampler + descriptor set), builds a blended,
 * vertex-buffer {@link GraphicsPipeline} with the MSDF shader ({@link MsdfShader}), and draws via
 * {@link WindowedPresenter}. Colour + {@code screenPxRange} travel in a 16-byte fragment push constant.
 *
 * <p>Run: {@code TextWindowDemo} (windowed until closed), {@code TextWindowDemo <frames>} (capped), or
 * {@code TextWindowDemo --capture <out.png>} (headless one-frame grab). Needs {@code --enable-native-access=ALL-UNNAMED}.
 */
public final class TextWindowDemo {

    private static final String ATLAS_JSON = "/dev/vexelray/text/atlas/primary.json";
    private static final String ATLAS_PNG = "/dev/vexelray/text/atlas/primary.png";
    private static final String TEXT = "VexelRay MSDF text 2026!";
    private static final float FONT_PX = 72f;
    private static final float[] TEXT_COLOR = {0.95f, 0.96f, 0.98f};

    public static void main(String[] args) throws IOException {
        ComposedShader vs = MsdfShader.vertex();
        ComposedShader fs = MsdfShader.fragment();
        AtlasData atlas = AtlasData.loadFromResource(ATLAS_JSON);
        int[] atlasSize = new int[2];
        byte[] atlasRgba = loadAtlasRgba(atlasSize);
        System.out.println("TextWindowDemo — msdf shaders v=" + vs.spirv().length + " f=" + fs.spirv().length
                + " bytes; atlas " + atlasSize[0] + "x" + atlasSize[1] + ", " + atlas.glyphs().size() + " glyphs");

        NativePlatform platform = NativePlatform.current();
        if (args.length >= 1 && args[0].equals("--capture")) {
            captureFrame(platform, vs, fs, atlas, atlasSize, atlasRgba, args.length >= 2 ? args[1] : "text.png");
            return;
        }
        if (args.length >= 1 && args[0].equals("--layout")) {
            captureLayout(platform, vs, fs, atlas, atlasSize, atlasRgba, args.length >= 2 ? args[1] : "text-layout.png");
            return;
        }
        int maxFrames = args.length > 0 ? Integer.parseInt(args[0]) : 0;

        int w = 900;
        int h = 260;
        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay text (MSDF)", w, h, true));
             VulkanInstance instance = new VulkanInstance("VexelRay text",
                     platform.requiredVulkanInstanceExtensions())) {
            long surface = window.createVulkanSurface(instance.handleAddress(), VkLoader.getInstanceProcAddrPointer());
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsPresentDevice(surface)
                    .orElseThrow(() -> new IllegalStateException("no graphics+present device"));
            System.out.println("device: " + selection.deviceName());

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection);
                 VulkanSwapchain swapchain = new VulkanSwapchain(instance.handle(), device, surface,
                         window.width(), window.height());
                 VulkanRenderPass renderPass = new VulkanRenderPass(device, swapchain.format(),
                         Vk.IMAGE_LAYOUT_PRESENT_SRC_KHR);
                 AtlasTexture atlasTex = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba)) {

                GlyphLayout layout = new GlyphLayout(atlas);
                float textWidth = layout.measure(TEXT, FONT_PX);
                float baselineX = (swapchain.width() - textWidth) / 2f;
                float baselineY = swapchain.height() / 2f + layout.ascent(FONT_PX) / 2f;
                List<GlyphQuad> quads = layout.layout(TEXT, baselineX, baselineY, FONT_PX);
                float[] vertices = TextMesh.toVertices(quads, swapchain.width(), swapchain.height());
                float screenPxRange = layout.screenPxRange(FONT_PX);

                try (VertexBuffer vb = new VertexBuffer(device, vertices);
                     GraphicsPipeline pipeline = new GraphicsPipeline(device, renderPass.handle(),
                             swapchain.width(), swapchain.height(), vs.spirv(), "main", fs.spirv(), "main",
                             textConfig(atlasTex));
                     WindowedPresenter presenter = new WindowedPresenter(device, swapchain, renderPass.handle(),
                             pipeline, window)) {
                    presenter.configureDraw(vb.handle(), atlasTex.descriptorSet(), TextMesh.vertexCount(vertices));
                    presenter.run(maxFrames, MsdfShader.PUSH_CONSTANT_BYTES,
                            (dt, pc) -> writePush(pc, TEXT_COLOR, screenPxRange));
                }
            }
            instance.destroySurface(surface);
        }
        System.out.println("clean shutdown");
    }

    /** The MSDF pipeline config: interleaved pos+uv, the atlas descriptor set layout, alpha blend, fragment push. */
    private static GraphicsPipeline.Config textConfig(AtlasTexture atlasTex) {
        return new GraphicsPipeline.Config(
                TextMesh.VERTEX_STRIDE_BYTES,
                List.of(new GraphicsPipeline.VertexAttribute(MsdfShader.POS_LOCATION, Vk.FORMAT_R32G32_SFLOAT, 0),
                        new GraphicsPipeline.VertexAttribute(MsdfShader.UV_LOCATION, Vk.FORMAT_R32G32_SFLOAT, 8)),
                new long[]{atlasTex.descriptorSetLayout()},
                true, Vk.SHADER_STAGE_FRAGMENT_BIT, MsdfShader.PUSH_CONSTANT_BYTES);
    }

    private static void writePush(MemorySegment pc, float[] color, float screenPxRange) {
        pc.set(JAVA_FLOAT, 0, color[0]);
        pc.set(JAVA_FLOAT, 4, color[1]);
        pc.set(JAVA_FLOAT, 8, color[2]);
        pc.set(JAVA_FLOAT, 12, screenPxRange);
    }

    /** Decode the atlas PNG (classpath) to tightly-packed RGBA, row 0 = top; fills {@code sizeOut} = [w, h]. */
    private static byte[] loadAtlasRgba(int[] sizeOut) throws IOException {
        try (InputStream in = TextWindowDemo.class.getResourceAsStream(ATLAS_PNG)) {
            if (in == null) {
                throw new IllegalStateException("atlas PNG not found: " + ATLAS_PNG);
            }
            BufferedImage img = ImageIO.read(in);
            int w = img.getWidth();
            int h = img.getHeight();
            sizeOut[0] = w;
            sizeOut[1] = h;
            byte[] rgba = new byte[w * h * 4];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = img.getRGB(x, y);
                    int i = (y * w + x) * 4;
                    rgba[i] = (byte) ((argb >> 16) & 0xFF);
                    rgba[i + 1] = (byte) ((argb >> 8) & 0xFF);
                    rgba[i + 2] = (byte) (argb & 0xFF);
                    rgba[i + 3] = (byte) ((argb >> 24) & 0xFF);
                }
            }
            return rgba;
        }
    }

    // ----------------------------------------------------------------------------------------------------------
    // Offscreen one-frame capture: reuses VulkanRenderPass + GraphicsPipeline + AtlasTexture + VertexBuffer, and
    // inlines the offscreen colour image + framebuffer + record + readback (patterned on OffscreenRenderer).
    // ----------------------------------------------------------------------------------------------------------

    private static void captureFrame(NativePlatform platform, ComposedShader vs, ComposedShader fs,
                                     AtlasData atlas, int[] atlasSize, byte[] atlasRgba, String path)
            throws IOException {
        int w = 900;
        int h = 260;
        try (VulkanInstance instance = new VulkanInstance("VexelRay text",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            System.out.println("device: " + sel.deviceName());
            try (VulkanDevice device = new VulkanDevice(instance.handle(), sel);
                 VulkanRenderPass renderPass = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                         Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                 AtlasTexture atlasTex = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba)) {

                GlyphLayout layout = new GlyphLayout(atlas);
                float textWidth = layout.measure(TEXT, FONT_PX);
                float baselineX = (w - textWidth) / 2f;
                float baselineY = h / 2f + layout.ascent(FONT_PX) / 2f;
                List<GlyphQuad> quads = layout.layout(TEXT, baselineX, baselineY, FONT_PX);
                float[] vertices = TextMesh.toVertices(quads, w, h);
                float screenPxRange = layout.screenPxRange(FONT_PX);

                try (VertexBuffer vb = new VertexBuffer(device, vertices);
                     GraphicsPipeline pipeline = new GraphicsPipeline(device, renderPass.handle(), w, h,
                             vs.spirv(), "main", fs.spirv(), "main", textConfig(atlasTex))) {
                    byte[] rgba = renderOffscreen(device, renderPass.handle(), pipeline, w, h, vb.handle(),
                            atlasTex.descriptorSet(), TextMesh.vertexCount(vertices), TEXT_COLOR, screenPxRange);
                    ImageIO.write(toImage(rgba, w, h), "PNG", new File(path));
                    System.out.println("captured " + new File(path).getAbsolutePath());
                }
            }
        }
    }

    /**
     * Offscreen capture of the {@link TextLayout} engine: a justified, word+char-wrapped paragraph in a box,
     * a centered heading placed by anchor, and a right-aligned footer — one draw, three alignment modes.
     */
    private static void captureLayout(NativePlatform platform, ComposedShader vs, ComposedShader fs,
                                      AtlasData atlas, int[] atlasSize, byte[] atlasRgba, String path)
            throws IOException {
        int w = 760;
        int h = 460;
        TextLayout tl = new TextLayout(atlas);
        String heading = "VexelRay Text Layout";
        String body = "The layout engine breaks and wraps text to a box, then aligns it: left, centre, right, or "
                + "justified. Over-long words like Supercalifragilisticexpialidocious are broken across lines when "
                + "word wrapping alone cannot fit them. It also measures blocks and answers fit questions.";
        String footer = "measure - wrap - align - place - fit";

        java.util.List<dev.vexelray.text.GlyphQuad> quads = new java.util.ArrayList<>();
        // Heading: centred on a point near the top.
        TextLayout.PlacedText head = tl.placeAt(heading, w / 2f, 44f, TextLayout.Anchor.TOP_CENTER,
                TextLayout.TextStyle.of(40f).withWrap(TextLayout.WrapMode.NONE));
        quads.addAll(head.quads());
        // Body: justified, word+char wrapped inside a box.
        TextLayout.TextBox box = new TextLayout.TextBox(40f, 110f, w - 80f, 260f);
        TextLayout.PlacedText para = tl.place(body, box,
                TextLayout.TextStyle.of(28f).withWrap(TextLayout.WrapMode.WORD_CHAR)
                        .withAlign(TextLayout.HAlign.JUSTIFY, TextLayout.VAlign.TOP));
        quads.addAll(para.quads());
        // Footer: right-aligned on the bottom edge.
        TextLayout.PlacedText foot = tl.placeAt(footer, w - 40f, h - 30f, TextLayout.Anchor.BOTTOM_RIGHT,
                TextLayout.TextStyle.of(22f).withWrap(TextLayout.WrapMode.NONE));
        quads.addAll(foot.quads());

        float[] vertices = TextMesh.toVertices(quads, w, h);
        float screenPxRange = tl.screenPxRange(28f);   // all blocks share the atlas; body size drives AA range

        try (VulkanInstance instance = new VulkanInstance("VexelRay text",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection sel = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics device"));
            System.out.println("device: " + sel.deviceName());
            try (VulkanDevice device = new VulkanDevice(instance.handle(), sel);
                 VulkanRenderPass renderPass = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                         Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                 AtlasTexture atlasTex = new AtlasTexture(device, atlasSize[0], atlasSize[1], atlasRgba);
                 VertexBuffer vb = new VertexBuffer(device, vertices);
                 GraphicsPipeline pipeline = new GraphicsPipeline(device, renderPass.handle(), w, h,
                         vs.spirv(), "main", fs.spirv(), "main", textConfig(atlasTex))) {
                byte[] rgba = renderOffscreen(device, renderPass.handle(), pipeline, w, h, vb.handle(),
                        atlasTex.descriptorSet(), TextMesh.vertexCount(vertices), TEXT_COLOR, screenPxRange);
                ImageIO.write(toImage(rgba, w, h), "PNG", new File(path));
                System.out.println("captured " + new File(path).getAbsolutePath());
            }
        }
    }

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor MEMREQ = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    private static final GroupLayout IMAGE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("imageType"), JAVA_INT.withName("format"),
            JAVA_INT.withName("extent_width"), JAVA_INT.withName("extent_height"), JAVA_INT.withName("extent_depth"),
            JAVA_INT.withName("mipLevels"), JAVA_INT.withName("arrayLayers"), JAVA_INT.withName("samples"),
            JAVA_INT.withName("tiling"), JAVA_INT.withName("usage"), JAVA_INT.withName("sharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices"), JAVA_INT.withName("initialLayout"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageCreateInfo");

    private static final GroupLayout MEMORY_REQUIREMENTS = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"), JAVA_LONG.withName("alignment"),
            JAVA_INT.withName("memoryTypeBits"), MemoryLayout.paddingLayout(4)).withName("VkMemoryRequirements");

    private static final GroupLayout MEMORY_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("allocationSize"), JAVA_INT.withName("memoryTypeIndex"), MemoryLayout.paddingLayout(4)
    ).withName("VkMemoryAllocateInfo");

    private static final GroupLayout BUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("size"),
            JAVA_INT.withName("usage"), JAVA_INT.withName("sharingMode"),
            JAVA_INT.withName("queueFamilyIndexCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueueFamilyIndices")).withName("VkBufferCreateInfo");

    private static final GroupLayout IMAGE_VIEW_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("image"),
            JAVA_INT.withName("viewType"), JAVA_INT.withName("format"),
            JAVA_INT.withName("c_r"), JAVA_INT.withName("c_g"), JAVA_INT.withName("c_b"), JAVA_INT.withName("c_a"),
            JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"), JAVA_INT.withName("sr_levelCount"),
            JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageViewCreateInfo");

    private static final GroupLayout FRAMEBUFFER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("renderPass"),
            JAVA_INT.withName("attachmentCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pAttachments"),
            JAVA_INT.withName("width"), JAVA_INT.withName("height"), JAVA_INT.withName("layers"),
            MemoryLayout.paddingLayout(4)).withName("VkFramebufferCreateInfo");

    private static final GroupLayout COMMAND_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("queueFamilyIndex")).withName("VkCommandPoolCreateInfo");

    private static final GroupLayout COMMAND_BUFFER_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("commandPool"), JAVA_INT.withName("level"), JAVA_INT.withName("commandBufferCount")
    ).withName("VkCommandBufferAllocateInfo");

    private static final GroupLayout COMMAND_BUFFER_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pInheritanceInfo")
    ).withName("VkCommandBufferBeginInfo");

    private static final GroupLayout RENDER_PASS_BEGIN_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("renderPass"), JAVA_LONG.withName("framebuffer"),
            JAVA_INT.withName("area_offset_x"), JAVA_INT.withName("area_offset_y"),
            JAVA_INT.withName("area_extent_width"), JAVA_INT.withName("area_extent_height"),
            JAVA_INT.withName("clearValueCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pClearValues")
    ).withName("VkRenderPassBeginInfo");

    private static final GroupLayout BUFFER_IMAGE_COPY = MemoryLayout.structLayout(
            JAVA_LONG.withName("bufferOffset"), JAVA_INT.withName("bufferRowLength"), JAVA_INT.withName("bufferImageHeight"),
            JAVA_INT.withName("is_aspectMask"), JAVA_INT.withName("is_mipLevel"),
            JAVA_INT.withName("is_baseArrayLayer"), JAVA_INT.withName("is_layerCount"),
            JAVA_INT.withName("off_x"), JAVA_INT.withName("off_y"), JAVA_INT.withName("off_z"),
            JAVA_INT.withName("ext_width"), JAVA_INT.withName("ext_height"), JAVA_INT.withName("ext_depth")
    ).withName("VkBufferImageCopy");

    private static final GroupLayout SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private static byte[] renderOffscreen(VulkanDevice device, long renderPass, GraphicsPipeline pipeline,
                                          int width, int height, long vertexBuffer, long descriptorSet,
                                          int vertexCount, float[] color, float screenPxRange) {
        MemorySegment dev = device.handle();
        long pixelBytes = (long) width * height * 4;

        MethodHandle vkCreateImage = device.command("vkCreateImage", C4);
        MethodHandle vkDestroyImage = device.command("vkDestroyImage", D_LONG);
        MethodHandle vkGetImageMemoryRequirements = device.command("vkGetImageMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        MethodHandle vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        MethodHandle vkBindImageMemory = device.command("vkBindImageMemory", BIND);
        MethodHandle vkCreateImageView = device.command("vkCreateImageView", C4);
        MethodHandle vkDestroyImageView = device.command("vkDestroyImageView", D_LONG);
        MethodHandle vkCreateFramebuffer = device.command("vkCreateFramebuffer", C4);
        MethodHandle vkDestroyFramebuffer = device.command("vkDestroyFramebuffer", D_LONG);
        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkUnmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool", C4);
        MethodHandle vkDestroyCommandPool = device.command("vkDestroyCommandPool", D_LONG);
        MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle vkEndCommandBuffer = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle vkCmdBeginRenderPass = device.command("vkCmdBeginRenderPass",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, JAVA_INT));
        MethodHandle vkCmdEndRenderPass = device.command("vkCmdEndRenderPass", FunctionDescriptor.ofVoid(ADDRESS));
        MethodHandle vkCmdBindPipeline = device.command("vkCmdBindPipeline",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG));
        MethodHandle vkCmdBindVertexBuffers = device.command("vkCmdBindVertexBuffers",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle vkCmdBindDescriptorSets = device.command("vkCmdBindDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        MethodHandle vkCmdPushConstants = device.command("vkCmdPushConstants",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS));
        MethodHandle vkCmdDraw = device.command("vkCmdDraw",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        MethodHandle vkCmdCopyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));

        try (Arena arena = Arena.ofConfined()) {
            // colour image (COLOR_ATTACHMENT | TRANSFER_SRC), device-local
            MemorySegment imgInfo = arena.allocate(IMAGE_CREATE_INFO);
            si(imgInfo, IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            si(imgInfo, IMAGE_CREATE_INFO, "imageType", Vk.IMAGE_TYPE_2D);
            si(imgInfo, IMAGE_CREATE_INFO, "format", Vk.FORMAT_R8G8B8A8_UNORM);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_width", width);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_height", height);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_depth", 1);
            si(imgInfo, IMAGE_CREATE_INFO, "mipLevels", 1);
            si(imgInfo, IMAGE_CREATE_INFO, "arrayLayers", 1);
            si(imgInfo, IMAGE_CREATE_INFO, "samples", Vk.SAMPLE_COUNT_1_BIT);
            si(imgInfo, IMAGE_CREATE_INFO, "tiling", Vk.IMAGE_TILING_OPTIMAL);
            si(imgInfo, IMAGE_CREATE_INFO, "usage", Vk.IMAGE_USAGE_COLOR_ATTACHMENT_BIT | Vk.IMAGE_USAGE_TRANSFER_SRC_BIT);
            si(imgInfo, IMAGE_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            si(imgInfo, IMAGE_CREATE_INFO, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
            MemorySegment pImage = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImage, dev, imgInfo, MemorySegment.NULL, pImage), "vkCreateImage");
            long image = pImage.get(JAVA_LONG, 0);
            MemorySegment imgReq = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetImageMemoryRequirements, dev, image, imgReq);
            long imageMemory = allocate(arena, vkAllocateMemory, dev, gl(imgReq, MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(imgReq, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(invoke(vkBindImageMemory, dev, image, imageMemory, 0L), "vkBindImageMemory");

            MemorySegment viewInfo = arena.allocate(IMAGE_VIEW_CREATE_INFO);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            sl(viewInfo, IMAGE_VIEW_CREATE_INFO, "image", image);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "format", Vk.FORMAT_R8G8B8A8_UNORM);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_levelCount", 1);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
            MemorySegment pView = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImageView, dev, viewInfo, MemorySegment.NULL, pView), "vkCreateImageView");
            long view = pView.get(JAVA_LONG, 0);

            MemorySegment pAttachViews = arena.allocate(JAVA_LONG);
            pAttachViews.set(JAVA_LONG, 0, view);
            MemorySegment fbInfo = arena.allocate(FRAMEBUFFER_CREATE_INFO);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
            sl(fbInfo, FRAMEBUFFER_CREATE_INFO, "renderPass", renderPass);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "attachmentCount", 1);
            sa(fbInfo, FRAMEBUFFER_CREATE_INFO, "pAttachments", pAttachViews);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "width", width);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "height", height);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "layers", 1);
            MemorySegment pFramebuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateFramebuffer, dev, fbInfo, MemorySegment.NULL, pFramebuffer), "vkCreateFramebuffer");
            long framebuffer = pFramebuffer.get(JAVA_LONG, 0);

            // readback buffer (host visible)
            MemorySegment bufferInfo = arena.allocate(BUFFER_CREATE_INFO);
            si(bufferInfo, BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            sl(bufferInfo, BUFFER_CREATE_INFO, "size", pixelBytes);
            si(bufferInfo, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
            si(bufferInfo, BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            MemorySegment pBuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateBuffer, dev, bufferInfo, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(JAVA_LONG, 0);
            MemorySegment bufReq = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetBufferMemoryRequirements, dev, buffer, bufReq);
            long bufferMemory = allocate(arena, vkAllocateMemory, dev, gl(bufReq, MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(bufReq, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(invoke(vkBindBufferMemory, dev, buffer, bufferMemory, 0L), "vkBindBufferMemory");

            // command buffer
            MemorySegment poolInfo = arena.allocate(COMMAND_POOL_CREATE_INFO);
            si(poolInfo, COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            si(poolInfo, COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
            MemorySegment pPool = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateCommandPool, dev, poolInfo, MemorySegment.NULL, pPool), "vkCreateCommandPool");
            long pool = pPool.get(JAVA_LONG, 0);
            MemorySegment cbAlloc = arena.allocate(COMMAND_BUFFER_ALLOCATE_INFO);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            sl(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
            si(cbAlloc, COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
            MemorySegment pCmd = arena.allocate(ADDRESS);
            check(invoke(vkAllocateCommandBuffers, dev, cbAlloc, pCmd), "vkAllocateCommandBuffers");
            MemorySegment cmd = pCmd.get(ADDRESS, 0);

            MemorySegment beginInfo = arena.allocate(COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(beginInfo, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(invoke(vkBeginCommandBuffer, cmd, beginInfo), "vkBeginCommandBuffer");

            MemorySegment clearValue = arena.allocate(JAVA_FLOAT, 4);
            clearValue.setAtIndex(JAVA_FLOAT, 0, 0.07f);
            clearValue.setAtIndex(JAVA_FLOAT, 1, 0.08f);
            clearValue.setAtIndex(JAVA_FLOAT, 2, 0.11f);
            clearValue.setAtIndex(JAVA_FLOAT, 3, 1.0f);
            MemorySegment rpBegin = arena.allocate(RENDER_PASS_BEGIN_INFO);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            sl(rpBegin, RENDER_PASS_BEGIN_INFO, "renderPass", renderPass);
            sl(rpBegin, RENDER_PASS_BEGIN_INFO, "framebuffer", framebuffer);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_extent_width", width);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_extent_height", height);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "clearValueCount", 1);
            sa(rpBegin, RENDER_PASS_BEGIN_INFO, "pClearValues", clearValue);

            invokeVoid(vkCmdBeginRenderPass, cmd, rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
            invokeVoid(vkCmdBindPipeline, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
            MemorySegment pSet = arena.allocate(JAVA_LONG);
            pSet.set(JAVA_LONG, 0, descriptorSet);
            invokeVoid(vkCmdBindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(),
                    0, 1, pSet, 0, MemorySegment.NULL);
            MemorySegment pVb = arena.allocate(JAVA_LONG);
            pVb.set(JAVA_LONG, 0, vertexBuffer);
            MemorySegment pOff = arena.allocate(JAVA_LONG);
            pOff.set(JAVA_LONG, 0, 0L);
            invokeVoid(vkCmdBindVertexBuffers, cmd, 0, 1, pVb, pOff);
            MemorySegment pc = arena.allocate(MsdfShader.PUSH_CONSTANT_BYTES);
            pc.set(JAVA_FLOAT, 0, color[0]);
            pc.set(JAVA_FLOAT, 4, color[1]);
            pc.set(JAVA_FLOAT, 8, color[2]);
            pc.set(JAVA_FLOAT, 12, screenPxRange);
            invokeVoid(vkCmdPushConstants, cmd, pipeline.pipelineLayout(), Vk.SHADER_STAGE_FRAGMENT_BIT, 0,
                    MsdfShader.PUSH_CONSTANT_BYTES, pc);
            invokeVoid(vkCmdDraw, cmd, vertexCount, 1, 0, 0);
            invokeVoid(vkCmdEndRenderPass, cmd);

            MemorySegment region = arena.allocate(BUFFER_IMAGE_COPY);
            si(region, BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(region, BUFFER_IMAGE_COPY, "is_layerCount", 1);
            si(region, BUFFER_IMAGE_COPY, "ext_width", width);
            si(region, BUFFER_IMAGE_COPY, "ext_height", height);
            si(region, BUFFER_IMAGE_COPY, "ext_depth", 1);
            invokeVoid(vkCmdCopyImageToBuffer, cmd, image, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1, region);
            check(invoke(vkEndCommandBuffer, cmd), "vkEndCommandBuffer");

            MemorySegment pCmdArray = arena.allocate(ADDRESS);
            pCmdArray.set(ADDRESS, 0, cmd);
            MemorySegment submit = arena.allocate(SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "commandBufferCount", 1);
            sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmdArray);
            check(invoke(vkQueueSubmit, device.queue(), 1, submit, 0L), "vkQueueSubmit");
            device.waitIdle();

            MemorySegment ppData = arena.allocate(ADDRESS);
            check(invoke(vkMapMemory, dev, bufferMemory, 0L, pixelBytes, 0, ppData), "vkMapMemory");
            byte[] pixels = ppData.get(ADDRESS, 0).reinterpret(pixelBytes).toArray(JAVA_BYTE);
            invokeVoid(vkUnmapMemory, dev, bufferMemory);

            invokeVoid(vkDestroyCommandPool, dev, pool, MemorySegment.NULL);
            invokeVoid(vkDestroyFramebuffer, dev, framebuffer, MemorySegment.NULL);
            invokeVoid(vkDestroyImageView, dev, view, MemorySegment.NULL);
            invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, bufferMemory, MemorySegment.NULL);
            invokeVoid(vkDestroyImage, dev, image, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, imageMemory, MemorySegment.NULL);
            return pixels;
        }
    }

    private static long allocate(Arena arena, MethodHandle vkAllocateMemory, MemorySegment dev, long size, int typeIndex) {
        MemorySegment info = arena.allocate(MEMORY_ALLOCATE_INFO);
        si(info, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
        sl(info, MEMORY_ALLOCATE_INFO, "allocationSize", size);
        si(info, MEMORY_ALLOCATE_INFO, "memoryTypeIndex", typeIndex);
        MemorySegment pMem = arena.allocate(JAVA_LONG);
        check(invoke(vkAllocateMemory, dev, info, MemorySegment.NULL, pMem), "vkAllocateMemory");
        return pMem.get(JAVA_LONG, 0);
    }

    private static BufferedImage toImage(byte[] rgba, int w, int h) {
        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = (y * w + x) * 4;
                image.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                        | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
            }
        }
        return image;
    }

    private TextWindowDemo() {
    }
}
