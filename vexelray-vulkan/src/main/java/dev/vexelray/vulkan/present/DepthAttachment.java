package dev.vexelray.vulkan.present;

import sibarum.probe.Lane;
import sibarum.probe.Probe;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.vexelray.vulkan.vk.Ffm.check;
import static dev.vexelray.vulkan.vk.Ffm.gi;
import static dev.vexelray.vulkan.vk.Ffm.gl;
import static dev.vexelray.vulkan.vk.Ffm.invoke;
import static dev.vexelray.vulkan.vk.Ffm.invokeVoid;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A device-local depth image, its memory, and its view — the attachment every technique in a pipeline shares so
 * that they occlude one another rather than merely painting in order.
 *
 * <h2>Why this is the piece that mattered</h2>
 *
 * <p>Without a shared depth buffer, "a pipeline of techniques" is strictly weaker than what
 * {@link SampledColorTarget} already does: N techniques writing colour in sequence is compositing, and
 * compositing can be done by rendering each to its own image and sampling them. What compositing cannot do is
 * let a marched SDF surface and a rasterised mesh interleave <em>per pixel</em> — for that they must test and
 * write one depth attachment inside one render pass. That is the whole argument for the technique model, and
 * until this class existed the argument had no implementation behind it.
 *
 * <h2>Not owned by the swapchain, and rebuilt with it</h2>
 *
 * <p>One depth image serves every swapchain image rather than one per frame. That is safe only because a single
 * frame is in flight: the fence in {@link WindowedPresenter} means frame N+1 does not begin until frame N has
 * finished reading and writing depth. Raising frames-in-flight makes this a hazard — two frames would share one
 * depth buffer — so it is called out here rather than discovered later, and
 * {@code EngineConfig.framesInFlight} is deliberately capped at what the runtime honours.
 *
 * <p>The image is sized to an extent and so must be recreated when the window resizes, exactly as the
 * framebuffers over the swapchain images are. The render pass need not be: it depends on the depth
 * <em>format</em>, which does not change.
 *
 * @see VulkanRenderPass#VulkanRenderPass(VulkanDevice, int, int, int)
 */
public final class DepthAttachment implements AutoCloseable {

    /**
     * The depth format this class uses, and the one a target declaring depth resolves to.
     *
     * <p>{@code D32_SFLOAT} rather than {@code D24_UNORM_S8_UINT} for two reasons. It is the format Vulkan
     * requires every implementation to support as a depth-stencil attachment, so there is no fallback path to
     * write and no device query to get wrong; and it carries no stencil, which is honest — nothing in this
     * engine tests stencil, and a format with eight unused bits invites a reader to think something does.
     */
    public static final int FORMAT = Vk.FORMAT_D32_SFLOAT;

    /**
     * The value depth is cleared to at the start of a frame, and therefore the far plane in normalized device
     * coordinates. 1.0 with {@code COMPARE_OP_LESS} means "nothing has been drawn here yet, and anything is
     * nearer than nothing" — the pairing a reversed-Z scheme would invert on both sides at once, never one.
     */
    public static final float CLEAR_DEPTH = 1.0f;

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor MEMREQ = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND =
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);

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

    private static final GroupLayout IMAGE_VIEW_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4), JAVA_LONG.withName("image"),
            JAVA_INT.withName("viewType"), JAVA_INT.withName("format"),
            JAVA_INT.withName("c_r"), JAVA_INT.withName("c_g"), JAVA_INT.withName("c_b"), JAVA_INT.withName("c_a"),
            JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"), JAVA_INT.withName("sr_levelCount"),
            JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"), MemoryLayout.paddingLayout(4)
    ).withName("VkImageViewCreateInfo");

    private final VulkanDevice device;
    private final int width;
    private final int height;
    private final long image;
    private final long memory;
    private final long view;

    private final MethodHandle vkDestroyImage;
    private final MethodHandle vkFreeMemory;
    private final MethodHandle vkDestroyImageView;

    private boolean closed;

    public DepthAttachment(VulkanDevice device, int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("depth extent must be positive, got " + width + "x" + height);
        }
        Probe.opened(Lane.GPU, "DepthAttachment", this);
        this.device = device;
        this.width = width;
        this.height = height;
        MemorySegment dev = device.handle();

        MethodHandle vkCreateImage = device.command("vkCreateImage", C4);
        this.vkDestroyImage = device.command("vkDestroyImage", D_LONG);
        MethodHandle vkGetImageMemoryRequirements = device.command("vkGetImageMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        this.vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        MethodHandle vkBindImageMemory = device.command("vkBindImageMemory", BIND);
        MethodHandle vkCreateImageView = device.command("vkCreateImageView", C4);
        this.vkDestroyImageView = device.command("vkDestroyImageView", D_LONG);

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment imgInfo = arena.allocate(IMAGE_CREATE_INFO);
            si(imgInfo, IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            si(imgInfo, IMAGE_CREATE_INFO, "imageType", Vk.IMAGE_TYPE_2D);
            si(imgInfo, IMAGE_CREATE_INFO, "format", FORMAT);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_width", width);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_height", height);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_depth", 1);
            si(imgInfo, IMAGE_CREATE_INFO, "mipLevels", 1);
            si(imgInfo, IMAGE_CREATE_INFO, "arrayLayers", 1);
            si(imgInfo, IMAGE_CREATE_INFO, "samples", Vk.SAMPLE_COUNT_1_BIT);
            si(imgInfo, IMAGE_CREATE_INFO, "tiling", Vk.IMAGE_TILING_OPTIMAL);
            si(imgInfo, IMAGE_CREATE_INFO, "usage", Vk.IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT);
            si(imgInfo, IMAGE_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            // UNDEFINED, not DEPTH_STENCIL_ATTACHMENT_OPTIMAL. The render pass's first use of this attachment
            // is a CLEAR, which does not read the previous contents, so it transitions from UNDEFINED itself and
            // no explicit barrier or one-off transition command is needed here.
            si(imgInfo, IMAGE_CREATE_INFO, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
            MemorySegment pImage = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImage, dev, imgInfo, MemorySegment.NULL, pImage), "vkCreateImage");
            this.image = pImage.get(JAVA_LONG, 0);

            MemorySegment req = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetImageMemoryRequirements, dev, image, req);
            MemorySegment allocInfo = arena.allocate(MEMORY_ALLOCATE_INFO);
            si(allocInfo, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
            sl(allocInfo, MEMORY_ALLOCATE_INFO, "allocationSize", gl(req, MEMORY_REQUIREMENTS, "size"));
            si(allocInfo, MEMORY_ALLOCATE_INFO, "memoryTypeIndex",
                    device.findMemoryType(gi(req, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            MemorySegment pMemory = arena.allocate(JAVA_LONG);
            check(invoke(vkAllocateMemory, dev, allocInfo, MemorySegment.NULL, pMemory), "vkAllocateMemory");
            this.memory = pMemory.get(JAVA_LONG, 0);
            check(invoke(vkBindImageMemory, dev, image, memory, 0L), "vkBindImageMemory");

            MemorySegment viewInfo = arena.allocate(IMAGE_VIEW_CREATE_INFO);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            sl(viewInfo, IMAGE_VIEW_CREATE_INFO, "image", image);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "format", FORMAT);
            // DEPTH only. A view carrying the stencil aspect of a format that has no stencil is invalid usage,
            // which is why FORMAT being stencil-free above is a simplification here rather than a lost feature.
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_DEPTH_BIT);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_levelCount", 1);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
            MemorySegment pView = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImageView, dev, viewInfo, MemorySegment.NULL, pView), "vkCreateImageView");
            this.view = pView.get(JAVA_LONG, 0);
        }
    }

    /** The {@code VkImageView} a framebuffer attaches at the render pass's depth slot. */
    public long view() {
        return view;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Whether this attachment still matches an extent, or the window has outgrown it and it must be rebuilt. */
    public boolean matches(int width, int height) {
        return this.width == width && this.height == height;
    }

    /**
     * Destroys the view, image, and memory. Idempotent for the reason {@link WindowedPresenter} spells out at
     * length: destroying an already-destroyed handle corrupts the loader's own heap, and the symptom surfaces
     * later as a call through an entry point that is no longer code.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Probe.closed(Lane.GPU, "DepthAttachment", this);
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyImageView, dev, view, MemorySegment.NULL);
        invokeVoid(vkDestroyImage, dev, image, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, memory, MemorySegment.NULL);
    }
}
