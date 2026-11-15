package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import sibarum.probe.Lane;
import sibarum.probe.Probe;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.gi;
import static dev.supirvast.vulkan.Ffm.gl;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.sa;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The device half of a device feed (vexelray-gui's {@code docs/plans/device-feed.md}, A and B): a fixed ring of slots,
 * each a picture on the device and the staging it is written through, and nothing else. Which slot is free, handed over
 * or shown is the feed's to know; this holds the memory and records the GPU's part.
 *
 * <p><b>Written with no queue.</b> Each slot's staging is host-visible memory of the device, mapped for the ring's
 * life: a producer writes a frame into {@link #staging} on its own thread, with no call into Vulkan and no lock, and
 * that is the whole of its side. Only level 0: the levels below it are the GPU's to make.
 *
 * <p><b>Landed by the frame that first shows it.</b> {@link #land} records, into a frame's command buffer and before its
 * render pass begins, the copy from a slot's staging into its image and, for a picture, a blit chain for its mips, ending
 * with the image ready to be sampled. The frame's own queue then orders the landing before the draw, so there is no
 * timeline, no fence and no submission of this class's own: the GUI thread records a few commands and copies no byte.
 * A landing waits, inside the GPU, for every earlier read of the slot's image in submission order, so landing a slot
 * that frames still in flight sample would tear; the feed's free rule is what keeps that from happening.
 *
 * <p>With {@code picture}, sRGB texels the sampler decodes to linear light and a whole mip chain, blitted in linear
 * light; without, plain RGBA8 at one level. {@link #land}, {@link #image} and {@link #close} on the thread that records
 * frames; {@link #staging} from any thread, by whoever the feed has given the slot to.
 */
public final class FeedSlots implements AutoCloseable {

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

    private static final GroupLayout SAMPLER_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("magFilter"), JAVA_INT.withName("minFilter"),
            JAVA_INT.withName("mipmapMode"), JAVA_INT.withName("addressModeU"), JAVA_INT.withName("addressModeV"),
            JAVA_INT.withName("addressModeW"), JAVA_FLOAT.withName("mipLodBias"), JAVA_INT.withName("anisotropyEnable"),
            JAVA_FLOAT.withName("maxAnisotropy"), JAVA_INT.withName("compareEnable"), JAVA_INT.withName("compareOp"),
            JAVA_FLOAT.withName("minLod"), JAVA_FLOAT.withName("maxLod"), JAVA_INT.withName("borderColor"),
            JAVA_INT.withName("unnormalizedCoordinates")).withName("VkSamplerCreateInfo");

    private static final GroupLayout DESCRIPTOR_SET_LAYOUT_BINDING = MemoryLayout.structLayout(
            JAVA_INT.withName("binding"), JAVA_INT.withName("descriptorType"), JAVA_INT.withName("descriptorCount"),
            JAVA_INT.withName("stageFlags"), ADDRESS.withName("pImmutableSamplers")
    ).withName("VkDescriptorSetLayoutBinding");

    private static final GroupLayout DESCRIPTOR_SET_LAYOUT_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("bindingCount"), ADDRESS.withName("pBindings")
    ).withName("VkDescriptorSetLayoutCreateInfo");

    private static final GroupLayout DESCRIPTOR_POOL_SIZE = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), JAVA_INT.withName("descriptorCount")).withName("VkDescriptorPoolSize");

    private static final GroupLayout DESCRIPTOR_POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("maxSets"), JAVA_INT.withName("poolSizeCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pPoolSizes")).withName("VkDescriptorPoolCreateInfo");

    private static final GroupLayout DESCRIPTOR_SET_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("descriptorPool"), JAVA_INT.withName("descriptorSetCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSetLayouts")).withName("VkDescriptorSetAllocateInfo");

    private static final GroupLayout DESCRIPTOR_IMAGE_INFO = MemoryLayout.structLayout(
            JAVA_LONG.withName("sampler"), JAVA_LONG.withName("imageView"), JAVA_INT.withName("imageLayout"),
            MemoryLayout.paddingLayout(4)).withName("VkDescriptorImageInfo");

    private static final GroupLayout WRITE_DESCRIPTOR_SET = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("dstSet"), JAVA_INT.withName("dstBinding"), JAVA_INT.withName("dstArrayElement"),
            JAVA_INT.withName("descriptorCount"), JAVA_INT.withName("descriptorType"), ADDRESS.withName("pImageInfo"),
            ADDRESS.withName("pBufferInfo"), ADDRESS.withName("pTexelBufferView")).withName("VkWriteDescriptorSet");

    private static final GroupLayout IMAGE_MEMORY_BARRIER = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("srcAccessMask"), JAVA_INT.withName("dstAccessMask"),
            JAVA_INT.withName("oldLayout"), JAVA_INT.withName("newLayout"),
            JAVA_INT.withName("srcQueueFamilyIndex"), JAVA_INT.withName("dstQueueFamilyIndex"),
            JAVA_LONG.withName("image"), JAVA_INT.withName("sr_aspectMask"), JAVA_INT.withName("sr_baseMipLevel"),
            JAVA_INT.withName("sr_levelCount"), JAVA_INT.withName("sr_baseArrayLayer"), JAVA_INT.withName("sr_layerCount"),
            MemoryLayout.paddingLayout(4)).withName("VkImageMemoryBarrier");

    private static final GroupLayout BUFFER_IMAGE_COPY = MemoryLayout.structLayout(
            JAVA_LONG.withName("bufferOffset"), JAVA_INT.withName("bufferRowLength"), JAVA_INT.withName("bufferImageHeight"),
            JAVA_INT.withName("is_aspectMask"), JAVA_INT.withName("is_mipLevel"),
            JAVA_INT.withName("is_baseArrayLayer"), JAVA_INT.withName("is_layerCount"),
            JAVA_INT.withName("off_x"), JAVA_INT.withName("off_y"), JAVA_INT.withName("off_z"),
            JAVA_INT.withName("ext_width"), JAVA_INT.withName("ext_height"), JAVA_INT.withName("ext_depth")
    ).withName("VkBufferImageCopy");

    /** {@code VkImageBlit}: two subresources and two corner pairs. */
    private static final GroupLayout IMAGE_BLIT = MemoryLayout.structLayout(
            JAVA_INT.withName("s_aspectMask"), JAVA_INT.withName("s_mipLevel"),
            JAVA_INT.withName("s_baseArrayLayer"), JAVA_INT.withName("s_layerCount"),
            JAVA_INT.withName("s0_x"), JAVA_INT.withName("s0_y"), JAVA_INT.withName("s0_z"),
            JAVA_INT.withName("s1_x"), JAVA_INT.withName("s1_y"), JAVA_INT.withName("s1_z"),
            JAVA_INT.withName("d_aspectMask"), JAVA_INT.withName("d_mipLevel"),
            JAVA_INT.withName("d_baseArrayLayer"), JAVA_INT.withName("d_layerCount"),
            JAVA_INT.withName("d0_x"), JAVA_INT.withName("d0_y"), JAVA_INT.withName("d0_z"),
            JAVA_INT.withName("d1_x"), JAVA_INT.withName("d1_y"), JAVA_INT.withName("d1_z")
    ).withName("VkImageBlit");

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

    private static final GroupLayout SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private final VulkanDevice device;
    private final int width;
    private final int height;
    private final int levels;
    private final boolean picture;
    private final long slotBytes;

    private final long sampler;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long staging;
    private final long stagingMemory;
    private final MemorySegment mapped;
    private final Slot[] slots;

    private final MethodHandle vkDestroyImage;
    private final MethodHandle vkFreeMemory;
    private final MethodHandle vkDestroyImageView;
    private final MethodHandle vkDestroySampler;
    private final MethodHandle vkDestroyDescriptorSetLayout;
    private final MethodHandle vkDestroyDescriptorPool;
    private final MethodHandle vkDestroyBuffer;
    private final MethodHandle vkUnmapMemory;
    private final MethodHandle vkCmdPipelineBarrier;
    private final MethodHandle vkCmdCopyBufferToImage;
    private final MethodHandle vkCmdBlitImage;

    private boolean closed;

    /**
     * {@code slots} pictures of {@code width x height} and their staging, made once. With {@code picture}, sRGB with a
     * mip chain the landing makes; without, plain RGBA8 at one level.
     */
    public FeedSlots(VulkanDevice device, int width, int height, int slots, boolean picture) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a feed's slots need a positive size: " + width + "x" + height);
        }
        if (slots < 1) {
            throw new IllegalArgumentException("a feed has a slot at least: " + slots);
        }
        Probe.opened(Lane.GPU, "FeedSlots", this);
        this.device = device;
        this.width = width;
        this.height = height;
        this.picture = picture;
        this.levels = picture ? MipChain.count(width, height) : 1;
        this.slotBytes = (long) width * height * 4;
        int format = picture ? Vk.FORMAT_R8G8B8A8_SRGB : Vk.FORMAT_R8G8B8A8_UNORM;
        MemorySegment dev = device.handle();

        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkCreateImage = device.command("vkCreateImage", C4);
        MethodHandle vkGetImageMemoryRequirements = device.command("vkGetImageMemoryRequirements", MEMREQ);
        MethodHandle vkBindImageMemory = device.command("vkBindImageMemory", BIND);
        MethodHandle vkCreateImageView = device.command("vkCreateImageView", C4);
        MethodHandle vkCreateSampler = device.command("vkCreateSampler", C4);
        MethodHandle vkCreateDescriptorSetLayout = device.command("vkCreateDescriptorSetLayout", C4);
        MethodHandle vkCreateDescriptorPool = device.command("vkCreateDescriptorPool", C4);
        MethodHandle vkAllocateDescriptorSets = device.command("vkAllocateDescriptorSets",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkUpdateDescriptorSets = device.command("vkUpdateDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.vkDestroyImage = device.command("vkDestroyImage", D_LONG);
        this.vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        this.vkDestroyImageView = device.command("vkDestroyImageView", D_LONG);
        this.vkDestroySampler = device.command("vkDestroySampler", D_LONG);
        this.vkDestroyDescriptorSetLayout = device.command("vkDestroyDescriptorSetLayout", D_LONG);
        this.vkDestroyDescriptorPool = device.command("vkDestroyDescriptorPool", D_LONG);
        this.vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        this.vkUnmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        this.vkCmdPipelineBarrier = device.command("vkCmdPipelineBarrier",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT, ADDRESS));
        this.vkCmdCopyBufferToImage = device.command("vkCmdCopyBufferToImage",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        this.vkCmdBlitImage = device.command("vkCmdBlitImage",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS,
                        JAVA_INT));

        try (Arena arena = Arena.ofConfined()) {
            // --- one staging buffer for every slot's level 0, mapped for as long as the ring lives ---
            long stagingBytes = slotBytes * slots;
            MemorySegment bufInfo = arena.allocate(BUFFER_CREATE_INFO);
            si(bufInfo, BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            sl(bufInfo, BUFFER_CREATE_INFO, "size", stagingBytes);
            si(bufInfo, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_SRC_BIT);
            si(bufInfo, BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            MemorySegment pBuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateBuffer, dev, bufInfo, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            this.staging = pBuffer.get(JAVA_LONG, 0);
            MemorySegment bufReq = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetBufferMemoryRequirements, dev, staging, bufReq);
            this.stagingMemory = allocate(arena, vkAllocateMemory, dev, gl(bufReq, MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(bufReq, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(invoke(vkBindBufferMemory, dev, staging, stagingMemory, 0L), "vkBindBufferMemory");
            MemorySegment ppData = arena.allocate(ADDRESS);
            check(invoke(vkMapMemory, dev, stagingMemory, 0L, stagingBytes, 0, ppData), "vkMapMemory");
            this.mapped = ppData.get(ADDRESS, 0).reinterpret(stagingBytes);

            // --- one sampler and one layout, shared by every slot ---
            MemorySegment sampInfo = arena.allocate(SAMPLER_CREATE_INFO);
            si(sampInfo, SAMPLER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            si(sampInfo, SAMPLER_CREATE_INFO, "magFilter", Vk.FILTER_LINEAR);
            si(sampInfo, SAMPLER_CREATE_INFO, "minFilter", Vk.FILTER_LINEAR);
            si(sampInfo, SAMPLER_CREATE_INFO, "mipmapMode",
                    levels > 1 ? Vk.SAMPLER_MIPMAP_MODE_LINEAR : Vk.SAMPLER_MIPMAP_MODE_NEAREST);
            sampInfo.set(JAVA_FLOAT, SAMPLER_CREATE_INFO.byteOffset(MemoryLayout.PathElement.groupElement("maxLod")),
                    (float) levels);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeU", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeV", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeW", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "borderColor", Vk.BORDER_COLOR_FLOAT_OPAQUE_BLACK);
            MemorySegment pSampler = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateSampler, dev, sampInfo, MemorySegment.NULL, pSampler), "vkCreateSampler");
            this.sampler = pSampler.get(JAVA_LONG, 0);

            MemorySegment binding = arena.allocate(DESCRIPTOR_SET_LAYOUT_BINDING);
            si(binding, DESCRIPTOR_SET_LAYOUT_BINDING, "binding", 0);
            si(binding, DESCRIPTOR_SET_LAYOUT_BINDING, "descriptorType", Vk.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            si(binding, DESCRIPTOR_SET_LAYOUT_BINDING, "descriptorCount", 1);
            si(binding, DESCRIPTOR_SET_LAYOUT_BINDING, "stageFlags", Vk.SHADER_STAGE_FRAGMENT_BIT);
            MemorySegment dslInfo = arena.allocate(DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            si(dslInfo, DESCRIPTOR_SET_LAYOUT_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            si(dslInfo, DESCRIPTOR_SET_LAYOUT_CREATE_INFO, "bindingCount", 1);
            sa(dslInfo, DESCRIPTOR_SET_LAYOUT_CREATE_INFO, "pBindings", binding);
            MemorySegment pDsl = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateDescriptorSetLayout, dev, dslInfo, MemorySegment.NULL, pDsl),
                    "vkCreateDescriptorSetLayout");
            this.descriptorSetLayout = pDsl.get(JAVA_LONG, 0);

            MemorySegment poolSize = arena.allocate(DESCRIPTOR_POOL_SIZE);
            si(poolSize, DESCRIPTOR_POOL_SIZE, "type", Vk.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            si(poolSize, DESCRIPTOR_POOL_SIZE, "descriptorCount", slots);
            MemorySegment poolInfo = arena.allocate(DESCRIPTOR_POOL_CREATE_INFO);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "maxSets", slots);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "poolSizeCount", 1);
            sa(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "pPoolSizes", poolSize);
            MemorySegment pPool = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateDescriptorPool, dev, poolInfo, MemorySegment.NULL, pPool), "vkCreateDescriptorPool");
            this.descriptorPool = pPool.get(JAVA_LONG, 0);

            // --- the slots: an image each, sampled, copied into, and blitted from and into for its mips ---
            this.slots = new Slot[slots];
            int usage = Vk.IMAGE_USAGE_TRANSFER_DST_BIT | Vk.IMAGE_USAGE_SAMPLED_BIT
                    | (levels > 1 ? Vk.IMAGE_USAGE_TRANSFER_SRC_BIT : 0);
            for (int s = 0; s < slots; s++) {
                MemorySegment imgInfo = arena.allocate(IMAGE_CREATE_INFO);
                si(imgInfo, IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
                si(imgInfo, IMAGE_CREATE_INFO, "imageType", Vk.IMAGE_TYPE_2D);
                si(imgInfo, IMAGE_CREATE_INFO, "format", format);
                si(imgInfo, IMAGE_CREATE_INFO, "extent_width", width);
                si(imgInfo, IMAGE_CREATE_INFO, "extent_height", height);
                si(imgInfo, IMAGE_CREATE_INFO, "extent_depth", 1);
                si(imgInfo, IMAGE_CREATE_INFO, "mipLevels", levels);
                si(imgInfo, IMAGE_CREATE_INFO, "arrayLayers", 1);
                si(imgInfo, IMAGE_CREATE_INFO, "samples", Vk.SAMPLE_COUNT_1_BIT);
                si(imgInfo, IMAGE_CREATE_INFO, "tiling", Vk.IMAGE_TILING_OPTIMAL);
                si(imgInfo, IMAGE_CREATE_INFO, "usage", usage);
                si(imgInfo, IMAGE_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
                si(imgInfo, IMAGE_CREATE_INFO, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
                MemorySegment pImage = arena.allocate(JAVA_LONG);
                check(invoke(vkCreateImage, dev, imgInfo, MemorySegment.NULL, pImage), "vkCreateImage");
                long image = pImage.get(JAVA_LONG, 0);
                MemorySegment imgReq = arena.allocate(MEMORY_REQUIREMENTS);
                invokeVoid(vkGetImageMemoryRequirements, dev, image, imgReq);
                long memory = allocate(arena, vkAllocateMemory, dev, gl(imgReq, MEMORY_REQUIREMENTS, "size"),
                        device.findMemoryType(gi(imgReq, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                                Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
                check(invoke(vkBindImageMemory, dev, image, memory, 0L), "vkBindImageMemory");

                MemorySegment viewInfo = arena.allocate(IMAGE_VIEW_CREATE_INFO);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
                sl(viewInfo, IMAGE_VIEW_CREATE_INFO, "image", image);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "format", format);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_levelCount", levels);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
                MemorySegment pView = arena.allocate(JAVA_LONG);
                check(invoke(vkCreateImageView, dev, viewInfo, MemorySegment.NULL, pView), "vkCreateImageView");
                long view = pView.get(JAVA_LONG, 0);

                MemorySegment pSetLayouts = arena.allocate(JAVA_LONG);
                pSetLayouts.set(JAVA_LONG, 0, descriptorSetLayout);
                MemorySegment allocInfo = arena.allocate(DESCRIPTOR_SET_ALLOCATE_INFO);
                si(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
                sl(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "descriptorPool", descriptorPool);
                si(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "descriptorSetCount", 1);
                sa(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "pSetLayouts", pSetLayouts);
                MemorySegment pSet = arena.allocate(JAVA_LONG);
                check(invoke(vkAllocateDescriptorSets, dev, allocInfo, pSet), "vkAllocateDescriptorSets");
                long set = pSet.get(JAVA_LONG, 0);
                MemorySegment imageInfo = arena.allocate(DESCRIPTOR_IMAGE_INFO);
                sl(imageInfo, DESCRIPTOR_IMAGE_INFO, "sampler", sampler);
                sl(imageInfo, DESCRIPTOR_IMAGE_INFO, "imageView", view);
                si(imageInfo, DESCRIPTOR_IMAGE_INFO, "imageLayout", Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                MemorySegment write = arena.allocate(WRITE_DESCRIPTOR_SET);
                si(write, WRITE_DESCRIPTOR_SET, "sType", Vk.STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
                sl(write, WRITE_DESCRIPTOR_SET, "dstSet", set);
                si(write, WRITE_DESCRIPTOR_SET, "dstBinding", 0);
                si(write, WRITE_DESCRIPTOR_SET, "descriptorCount", 1);
                si(write, WRITE_DESCRIPTOR_SET, "descriptorType", Vk.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                sa(write, WRITE_DESCRIPTOR_SET, "pImageInfo", imageInfo);
                invokeVoid(vkUpdateDescriptorSets, dev, 1, write, 0, MemorySegment.NULL);

                this.slots[s] = new Slot(s, image, memory, view, set, mapped.asSlice(slotBytes * s, slotBytes),
                        slotBytes * s);
            }
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Mip levels in each slot's image: the whole chain for a picture, else 1. Only level 0 is staged. */
    public int levels() {
        return levels;
    }

    public boolean picture() {
        return picture;
    }

    public int slots() {
        return slots.length;
    }

    /** The bytes of one slot's staging: level 0, RGBA8, rows of {@code width} texels with no padding. */
    public long stagingBytes() {
        return slotBytes;
    }

    /**
     * Slot {@code slot}'s staging: {@code width x height} RGBA8 texels, row after row, to write. Any thread, by whoever
     * the feed has given the slot to, and only between the feed giving it and handing it over.
     */
    public MemorySegment staging(int slot) {
        return slot(slot).stagingRegion;
    }

    /** Slot {@code slot}'s picture, as a node's run binds it: valid to sample once the slot has been {@link #land}ed. */
    public SampledImage image(int slot) {
        return slot(slot);
    }

    /**
     * Record slot {@code slot}'s landing into {@code commandBuffer}, outside any render pass, before the pass that
     * samples it: its staging copied into level 0, each level below blitted from the one above, and the whole image
     * left ready for fragment shaders to sample. Every earlier read of the image, in submission order, finishes first;
     * every later sample sees the new texels. The thread that records frames.
     */
    public void land(MemorySegment commandBuffer, int slot) {
        Slot s = slot(slot);
        if (closed) {
            throw new IllegalStateException(this + " is closed");
        }
        try (Arena arena = Arena.ofConfined()) {
            // Whatever the image held is not kept: level 0 is about to be copied whole and the rest made from it.
            barrier(arena, commandBuffer, s.image, 0, levels, Vk.IMAGE_LAYOUT_UNDEFINED,
                    Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, Vk.ACCESS_TRANSFER_WRITE_BIT,
                    Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT);
            MemorySegment region = arena.allocate(BUFFER_IMAGE_COPY);
            sl(region, BUFFER_IMAGE_COPY, "bufferOffset", s.stagingOffset);
            si(region, BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(region, BUFFER_IMAGE_COPY, "is_mipLevel", 0);
            si(region, BUFFER_IMAGE_COPY, "is_layerCount", 1);
            si(region, BUFFER_IMAGE_COPY, "ext_width", width);
            si(region, BUFFER_IMAGE_COPY, "ext_height", height);
            si(region, BUFFER_IMAGE_COPY, "ext_depth", 1);
            invokeVoid(vkCmdCopyBufferToImage, commandBuffer, staging, s.image, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    1, region);

            MemorySegment blit = levels > 1 ? arena.allocate(IMAGE_BLIT) : null;
            for (int l = 1; l < levels; l++) {
                // The level above is written; it becomes the source of this one.
                barrier(arena, commandBuffer, s.image, l - 1, 1, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, Vk.ACCESS_TRANSFER_WRITE_BIT, Vk.ACCESS_TRANSFER_READ_BIT,
                        Vk.PIPELINE_STAGE_TRANSFER_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT);
                blit.fill((byte) 0);
                si(blit, IMAGE_BLIT, "s_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                si(blit, IMAGE_BLIT, "s_mipLevel", l - 1);
                si(blit, IMAGE_BLIT, "s_layerCount", 1);
                si(blit, IMAGE_BLIT, "s1_x", MipChain.size(width, l - 1));
                si(blit, IMAGE_BLIT, "s1_y", MipChain.size(height, l - 1));
                si(blit, IMAGE_BLIT, "s1_z", 1);
                si(blit, IMAGE_BLIT, "d_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                si(blit, IMAGE_BLIT, "d_mipLevel", l);
                si(blit, IMAGE_BLIT, "d_layerCount", 1);
                si(blit, IMAGE_BLIT, "d1_x", MipChain.size(width, l));
                si(blit, IMAGE_BLIT, "d1_y", MipChain.size(height, l));
                si(blit, IMAGE_BLIT, "d1_z", 1);
                invokeVoid(vkCmdBlitImage, commandBuffer, s.image, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        s.image, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, blit, Vk.FILTER_LINEAR);
            }
            // Every level but the last was a blit's source; the last was only written.
            if (levels > 1) {
                barrier(arena, commandBuffer, s.image, 0, levels - 1, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                        Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, Vk.ACCESS_TRANSFER_READ_BIT,
                        Vk.ACCESS_SHADER_READ_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT,
                        Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
            }
            barrier(arena, commandBuffer, s.image, levels - 1, 1, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, Vk.ACCESS_TRANSFER_WRITE_BIT, Vk.ACCESS_SHADER_READ_BIT,
                    Vk.PIPELINE_STAGE_TRANSFER_BIT, Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
        }
    }

    /**
     * Level {@code level} of a landed slot, read back: RGBA8, row after row. Submits a command buffer of its own and
     * waits for the device, so for a test, never a frame. The thread that records frames.
     */
    byte[] read(int slot, int level) {
        Slot s = slot(slot);
        int w = MipChain.size(width, level);
        int h = MipChain.size(height, level);
        long bytes = (long) w * h * 4;
        MemorySegment dev = device.handle();
        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkCmdCopyImageToBuffer = device.command("vkCmdCopyImageToBuffer",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_INT, ADDRESS));
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bufInfo = arena.allocate(BUFFER_CREATE_INFO);
            si(bufInfo, BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            sl(bufInfo, BUFFER_CREATE_INFO, "size", bytes);
            si(bufInfo, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_DST_BIT);
            si(bufInfo, BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            MemorySegment pBuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateBuffer, dev, bufInfo, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            long buffer = pBuffer.get(JAVA_LONG, 0);
            MemorySegment req = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetBufferMemoryRequirements, dev, buffer, req);
            long memory = allocate(arena, vkAllocateMemory, dev, gl(req, MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(req, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(invoke(vkBindBufferMemory, dev, buffer, memory, 0L), "vkBindBufferMemory");
            try {
                OneShot.run(device, cmd -> {
                    barrier(arena, cmd, s.image, level, 1, Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                            Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, Vk.ACCESS_SHADER_READ_BIT, Vk.ACCESS_TRANSFER_READ_BIT,
                            Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT);
                    MemorySegment region = arena.allocate(BUFFER_IMAGE_COPY);
                    si(region, BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                    si(region, BUFFER_IMAGE_COPY, "is_mipLevel", level);
                    si(region, BUFFER_IMAGE_COPY, "is_layerCount", 1);
                    si(region, BUFFER_IMAGE_COPY, "ext_width", w);
                    si(region, BUFFER_IMAGE_COPY, "ext_height", h);
                    si(region, BUFFER_IMAGE_COPY, "ext_depth", 1);
                    invokeVoid(vkCmdCopyImageToBuffer, cmd, s.image, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, buffer, 1,
                            region);
                    barrier(arena, cmd, s.image, level, 1, Vk.IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                            Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, Vk.ACCESS_TRANSFER_READ_BIT,
                            Vk.ACCESS_SHADER_READ_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT,
                            Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
                });
                MemorySegment ppData = arena.allocate(ADDRESS);
                check(invoke(vkMapMemory, dev, memory, 0L, bytes, 0, ppData), "vkMapMemory");
                byte[] out = ppData.get(ADDRESS, 0).reinterpret(bytes).toArray(JAVA_BYTE);
                invokeVoid(vkUnmapMemory, dev, memory);
                return out;
            } finally {
                invokeVoid(vkDestroyBuffer, dev, buffer, MemorySegment.NULL);
                invokeVoid(vkFreeMemory, dev, memory, MemorySegment.NULL);
            }
        }
    }

    /**
     * Release every slot and the staging. The caller has made sure no frame in flight samples any of them, as it does for
     * a texture it releases. Idempotent.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Probe.closed(Lane.GPU, "FeedSlots", this);
        MemorySegment dev = device.handle();
        for (Slot s : slots) {
            invokeVoid(vkDestroyImageView, dev, s.view, MemorySegment.NULL);
            invokeVoid(vkDestroyImage, dev, s.image, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, s.memory, MemorySegment.NULL);
        }
        invokeVoid(vkDestroyDescriptorPool, dev, descriptorPool, MemorySegment.NULL);
        invokeVoid(vkDestroyDescriptorSetLayout, dev, descriptorSetLayout, MemorySegment.NULL);
        invokeVoid(vkDestroySampler, dev, sampler, MemorySegment.NULL);
        invokeVoid(vkUnmapMemory, dev, stagingMemory);
        invokeVoid(vkDestroyBuffer, dev, staging, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, stagingMemory, MemorySegment.NULL);
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public String toString() {
        return "FeedSlots[" + width + "x" + height + ", " + slots.length + (slots.length == 1 ? " slot" : " slots")
                + (picture ? ", picture" : "") + (closed ? ", closed" : "") + "]";
    }

    private Slot slot(int slot) {
        if (slot < 0 || slot >= slots.length) {
            throw new IndexOutOfBoundsException("no slot " + slot + " in " + this);
        }
        return slots[slot];
    }

    private long allocate(Arena arena, MethodHandle vkAllocateMemory, MemorySegment dev, long size, int memoryTypeIndex) {
        MemorySegment info = arena.allocate(MEMORY_ALLOCATE_INFO);
        si(info, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
        sl(info, MEMORY_ALLOCATE_INFO, "allocationSize", size);
        si(info, MEMORY_ALLOCATE_INFO, "memoryTypeIndex", memoryTypeIndex);
        MemorySegment pMem = arena.allocate(JAVA_LONG);
        check(invoke(vkAllocateMemory, dev, info, MemorySegment.NULL, pMem), "vkAllocateMemory");
        return pMem.get(JAVA_LONG, 0);
    }

    private void barrier(Arena arena, MemorySegment cmd, long image, int baseLevel, int levelCount, int oldLayout,
                         int newLayout, int srcAccess, int dstAccess, int srcStage, int dstStage) {
        MemorySegment b = arena.allocate(IMAGE_MEMORY_BARRIER);
        si(b, IMAGE_MEMORY_BARRIER, "sType", Vk.STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        si(b, IMAGE_MEMORY_BARRIER, "srcAccessMask", srcAccess);
        si(b, IMAGE_MEMORY_BARRIER, "dstAccessMask", dstAccess);
        si(b, IMAGE_MEMORY_BARRIER, "oldLayout", oldLayout);
        si(b, IMAGE_MEMORY_BARRIER, "newLayout", newLayout);
        si(b, IMAGE_MEMORY_BARRIER, "srcQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
        si(b, IMAGE_MEMORY_BARRIER, "dstQueueFamilyIndex", Vk.QUEUE_FAMILY_IGNORED);
        sl(b, IMAGE_MEMORY_BARRIER, "image", image);
        si(b, IMAGE_MEMORY_BARRIER, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
        si(b, IMAGE_MEMORY_BARRIER, "sr_baseMipLevel", baseLevel);
        si(b, IMAGE_MEMORY_BARRIER, "sr_levelCount", levelCount);
        si(b, IMAGE_MEMORY_BARRIER, "sr_layerCount", 1);
        invokeVoid(vkCmdPipelineBarrier, cmd, srcStage, dstStage, 0, 0, MemorySegment.NULL, 0, MemorySegment.NULL,
                1, b);
    }

    /** One image of the ring, as a node's run binds it. */
    private final class Slot implements SampledImage {

        private final int index;
        private final long image;
        private final long memory;
        private final long view;
        private final long set;
        private final MemorySegment stagingRegion;
        private final long stagingOffset;

        private Slot(int index, long image, long memory, long view, long set, MemorySegment stagingRegion,
                     long stagingOffset) {
            this.index = index;
            this.image = image;
            this.memory = memory;
            this.view = view;
            this.set = set;
            this.stagingRegion = stagingRegion;
            this.stagingOffset = stagingOffset;
        }

        @Override
        public long descriptorSet() {
            return set;
        }

        @Override
        public long descriptorSetLayout() {
            return descriptorSetLayout;
        }

        @Override
        public VulkanDevice device() {
            return device;
        }

        @Override
        public boolean linear() {
            return picture;
        }

        @Override
        public String toString() {
            return "Slot[" + index + " of " + FeedSlots.this + "]";
        }
    }

    /** A command buffer recorded, submitted and waited for: {@link #read}'s, never a frame's. */
    private static final class OneShot {

        interface Body {
            void record(MemorySegment cmd);
        }

        static void run(VulkanDevice device, Body body) {
            MemorySegment dev = device.handle();
            MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool", C4);
            MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
            MethodHandle vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            MethodHandle vkEndCommandBuffer = device.command("vkEndCommandBuffer",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
            MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));
            MethodHandle vkQueueWaitIdle = device.command("vkQueueWaitIdle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            MethodHandle vkDestroyCommandPool = device.command("vkDestroyCommandPool", D_LONG);
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment cpInfo = arena.allocate(COMMAND_POOL_CREATE_INFO);
                si(cpInfo, COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
                si(cpInfo, COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
                MemorySegment pCp = arena.allocate(JAVA_LONG);
                check(invoke(vkCreateCommandPool, dev, cpInfo, MemorySegment.NULL, pCp), "vkCreateCommandPool");
                long pool = pCp.get(JAVA_LONG, 0);
                try {
                    MemorySegment cbInfo = arena.allocate(COMMAND_BUFFER_ALLOCATE_INFO);
                    si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
                    sl(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
                    si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
                    si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
                    MemorySegment pCmd = arena.allocate(ADDRESS);
                    check(invoke(vkAllocateCommandBuffers, dev, cbInfo, pCmd), "vkAllocateCommandBuffers");
                    MemorySegment cmd = pCmd.get(ADDRESS, 0);
                    MemorySegment begin = arena.allocate(COMMAND_BUFFER_BEGIN_INFO);
                    si(begin, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
                    si(begin, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                    check(invoke(vkBeginCommandBuffer, cmd, begin), "vkBeginCommandBuffer");
                    body.record(cmd);
                    check(invoke(vkEndCommandBuffer, cmd), "vkEndCommandBuffer");
                    MemorySegment submit = arena.allocate(SUBMIT_INFO);
                    si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
                    si(submit, SUBMIT_INFO, "commandBufferCount", 1);
                    sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmd);
                    check(invoke(vkQueueSubmit, device.queue(), 1, submit, 0L), "vkQueueSubmit");
                    check(invoke(vkQueueWaitIdle, device.queue()), "vkQueueWaitIdle");
                } finally {
                    invokeVoid(vkDestroyCommandPool, dev, pool, MemorySegment.NULL);
                }
            }
        }
    }

    /** For a test: record {@code body} into a command buffer of its own, submit it and wait for the queue. */
    static void oneShot(VulkanDevice device, java.util.function.Consumer<MemorySegment> body) {
        OneShot.run(device, body::accept);
    }
}
