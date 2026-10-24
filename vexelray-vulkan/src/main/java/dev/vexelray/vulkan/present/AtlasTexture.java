package dev.vexelray.vulkan.present;

import sibarum.probe.Lane;
import sibarum.probe.Probe;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;

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
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A sampled R8G8B8A8 texture (or R8, for a one-channel distance field) with everything the fragment stage needs to read it: the {@code VkImage} + memory +
 * view, a linear-clamp {@code VkSampler}, and a one-binding descriptor set (combined image sampler at set 0,
 * binding 0). The RGBA pixels are uploaded once at construction via a staging buffer and a one-time command buffer
 * (barrier {@code UNDEFINED → TRANSFER_DST}, copy, barrier {@code TRANSFER_DST → SHADER_READ_ONLY}), queued on the
 * device's queue and not waited for: a frame that draws it is submitted after it, and the staging is freed once a
 * {@link QueueMark} says the copy is done.
 *
 * <p>This is the first sampled-image path in VexelRay's present layer — the MSDF font atlas is the initial client.
 * Expose {@link #descriptorSetLayout()} to build a {@link GraphicsPipeline} against, and {@link #descriptorSet()}
 * to bind before drawing. Linear filtering is required for correct SDF interpolation.
 */
public final class AtlasTexture implements SampledImage, AutoCloseable {

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
            JAVA_INT.withName("addressModeW"), ValueLayoutFloat("mipLodBias"), JAVA_INT.withName("anisotropyEnable"),
            ValueLayoutFloat("maxAnisotropy"), JAVA_INT.withName("compareEnable"), JAVA_INT.withName("compareOp"),
            ValueLayoutFloat("minLod"), ValueLayoutFloat("maxLod"), JAVA_INT.withName("borderColor"),
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

    private static java.lang.foreign.ValueLayout.OfFloat ValueLayoutFloat(String name) {
        return java.lang.foreign.ValueLayout.JAVA_FLOAT.withName(name);
    }

    private final VulkanDevice device;
    /** Whether the texels are sRGB, which the sampler decodes to linear light. */
    private final boolean srgb;
    private final long image;
    private final long imageMemory;
    private final long imageView;
    private final long sampler;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;

    private final MethodHandle vkDestroyImage;
    private final MethodHandle vkFreeMemory;
    private final MethodHandle vkDestroyImageView;
    private final MethodHandle vkDestroySampler;
    private final MethodHandle vkDestroyDescriptorSetLayout;
    private final MethodHandle vkDestroyDescriptorPool;
    private final MethodHandle vkDestroyBuffer;
    private final MethodHandle vkDestroyCommandPool;

    /** What the upload needs until its copy has finished: freed by {@link #settle}. */
    private final long staging;
    private final long stagingMemory;
    private final long uploadPool;
    /** Passed once the upload's copy has finished; null once its staging is freed. */
    private volatile QueueMark uploaded;

    /**
     * A 1x1 opaque-white texture — what gets bound at the image set for spans that draw no image.
     *
     * <p>A descriptor set layout is part of a pipeline layout, so it is not optional: something valid has to be
     * bound at every set the pipeline declares, whether or not this frame reads it. White with alpha 1 is the
     * identity for the image kind's multiply, so binding it is also harmless if a vertex ever does read it.
     */
    public static AtlasTexture placeholder(VulkanDevice device) {
        return new AtlasTexture(device, 1, 1, new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
    }

    public AtlasTexture(VulkanDevice device, int width, int height, byte[] rgba) {
        this(device, width, height, new byte[][] {rgba}, false, 4);
    }

    /**
     * A distance-field atlas of {@code channels} bytes per texel: 4 is RGBA, as {@link #AtlasTexture(VulkanDevice, int,
     * int, byte[])}; 1 is an {@code R8_UNORM} image whose view reads its one channel into all four, so a shader taking
     * {@code median(r, g, b)} of an MSDF gets the same distance from a one-channel SDF, and both share a pipeline.
     */
    public AtlasTexture(VulkanDevice device, int width, int height, int channels, byte[] texels) {
        this(device, width, height, new byte[][] {texels}, false, channels);
    }

    /**
     * A texture with its mip chain, {@code levels[0]} the full {@code width x height} image and each next one half the
     * last ({@link MipChain}), sampled trilinearly. With {@code srgb}, the texels are sRGB-encoded and the sampler
     * decodes them, so every blend it does, across texels and between levels, is in linear light; a shader reading
     * one gets linear light and encodes it again ({@link #linear()}).
     */
    public AtlasTexture(VulkanDevice device, int width, int height, byte[][] levels, boolean srgb) {
        this(device, width, height, levels, srgb, 4);
    }

    private AtlasTexture(VulkanDevice device, int width, int height, byte[][] levels, boolean srgb, int channels) {
        Probe.opened(Lane.GPU, "AtlasTexture", this);
        if (channels != 1 && channels != 4) {
            throw new IllegalArgumentException("an atlas texture is 1 or 4 channels, not " + channels);
        }
        if (channels == 1 && srgb) {
            throw new IllegalArgumentException("a one-channel atlas is a distance, not a colour; it has no sRGB form");
        }
        this.device = device;
        this.srgb = srgb;
        MemorySegment dev = device.handle();
        int mipLevels = levels.length;
        if (mipLevels < 1 || levels[0].length < (long) width * height * channels) {
            throw new IllegalArgumentException("texels too few for " + width + "x" + height + " at " + channels
                    + " channels");
        }
        long pixelBytes = 0;
        for (int l = 0; l < mipLevels; l++) {
            pixelBytes += (long) MipChain.size(width, l) * MipChain.size(height, l) * channels;
        }
        int format = channels == 1 ? Vk.FORMAT_R8_UNORM
                : srgb ? Vk.FORMAT_R8G8B8A8_SRGB : Vk.FORMAT_R8G8B8A8_UNORM;

        MethodHandle vkCreateBuffer = device.command("vkCreateBuffer", C4);
        MethodHandle vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        MethodHandle vkGetBufferMemoryRequirements = device.command("vkGetBufferMemoryRequirements", MEMREQ);
        MethodHandle vkAllocateMemory = device.command("vkAllocateMemory", C4);
        MethodHandle vkBindBufferMemory = device.command("vkBindBufferMemory", BIND);
        MethodHandle vkMapMemory = device.command("vkMapMemory",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG, JAVA_INT, ADDRESS));
        MethodHandle vkUnmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        MethodHandle vkCreateImage = device.command("vkCreateImage", C4);
        this.vkDestroyImage = device.command("vkDestroyImage", D_LONG);
        MethodHandle vkGetImageMemoryRequirements = device.command("vkGetImageMemoryRequirements", MEMREQ);
        this.vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        MethodHandle vkBindImageMemory = device.command("vkBindImageMemory", BIND);
        MethodHandle vkCreateImageView = device.command("vkCreateImageView", C4);
        this.vkDestroyImageView = device.command("vkDestroyImageView", D_LONG);
        MethodHandle vkCreateSampler = device.command("vkCreateSampler", C4);
        this.vkDestroySampler = device.command("vkDestroySampler", D_LONG);
        MethodHandle vkCreateDescriptorSetLayout = device.command("vkCreateDescriptorSetLayout", C4);
        this.vkDestroyDescriptorSetLayout = device.command("vkDestroyDescriptorSetLayout", D_LONG);
        MethodHandle vkCreateDescriptorPool = device.command("vkCreateDescriptorPool", C4);
        this.vkDestroyDescriptorPool = device.command("vkDestroyDescriptorPool", D_LONG);
        MethodHandle vkAllocateDescriptorSets = device.command("vkAllocateDescriptorSets",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkUpdateDescriptorSets = device.command("vkUpdateDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool", C4);
        MethodHandle vkDestroyCommandPool = device.command("vkDestroyCommandPool", D_LONG);
        MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        MethodHandle vkEndCommandBuffer = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        MethodHandle vkCmdPipelineBarrier = device.command("vkCmdPipelineBarrier",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT, ADDRESS));
        MethodHandle vkCmdCopyBufferToImage = device.command("vkCmdCopyBufferToImage",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));

        try (Arena arena = Arena.ofConfined()) {
            // --- staging buffer (host visible), filled with the RGBA pixels ---
            MemorySegment bufInfo = arena.allocate(BUFFER_CREATE_INFO);
            si(bufInfo, BUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_BUFFER_CREATE_INFO);
            sl(bufInfo, BUFFER_CREATE_INFO, "size", pixelBytes);
            si(bufInfo, BUFFER_CREATE_INFO, "usage", Vk.BUFFER_USAGE_TRANSFER_SRC_BIT);
            si(bufInfo, BUFFER_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            MemorySegment pBuffer = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateBuffer, dev, bufInfo, MemorySegment.NULL, pBuffer), "vkCreateBuffer");
            long staging = pBuffer.get(JAVA_LONG, 0);

            MemorySegment bufReq = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetBufferMemoryRequirements, dev, staging, bufReq);
            long stagingMemory = allocate(arena, vkAllocateMemory, dev, gl(bufReq, MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(bufReq, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_HOST_VISIBLE_BIT | Vk.MEMORY_PROPERTY_HOST_COHERENT_BIT));
            check(invoke(vkBindBufferMemory, dev, staging, stagingMemory, 0L), "vkBindBufferMemory");

            MemorySegment ppData = arena.allocate(ADDRESS);
            check(invoke(vkMapMemory, dev, stagingMemory, 0L, pixelBytes, 0, ppData), "vkMapMemory");
            MemorySegment mapped = ppData.get(ADDRESS, 0).reinterpret(pixelBytes);
            long[] offsets = new long[mipLevels];
            long at = 0;
            for (int l = 0; l < mipLevels; l++) {
                int bytes = MipChain.size(width, l) * MipChain.size(height, l) * channels;
                offsets[l] = at;
                MemorySegment.copy(levels[l], 0, mapped, JAVA_BYTE, at, bytes);
                at += bytes;
            }
            invokeVoid(vkUnmapMemory, dev, stagingMemory);

            // --- device-local sampled image ---
            MemorySegment imgInfo = arena.allocate(IMAGE_CREATE_INFO);
            si(imgInfo, IMAGE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_CREATE_INFO);
            si(imgInfo, IMAGE_CREATE_INFO, "imageType", Vk.IMAGE_TYPE_2D);
            si(imgInfo, IMAGE_CREATE_INFO, "format", format);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_width", width);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_height", height);
            si(imgInfo, IMAGE_CREATE_INFO, "extent_depth", 1);
            si(imgInfo, IMAGE_CREATE_INFO, "mipLevels", mipLevels);
            si(imgInfo, IMAGE_CREATE_INFO, "arrayLayers", 1);
            si(imgInfo, IMAGE_CREATE_INFO, "samples", Vk.SAMPLE_COUNT_1_BIT);
            si(imgInfo, IMAGE_CREATE_INFO, "tiling", Vk.IMAGE_TILING_OPTIMAL);
            si(imgInfo, IMAGE_CREATE_INFO, "usage", Vk.IMAGE_USAGE_TRANSFER_DST_BIT | Vk.IMAGE_USAGE_SAMPLED_BIT);
            si(imgInfo, IMAGE_CREATE_INFO, "sharingMode", Vk.SHARING_MODE_EXCLUSIVE);
            si(imgInfo, IMAGE_CREATE_INFO, "initialLayout", Vk.IMAGE_LAYOUT_UNDEFINED);
            MemorySegment pImage = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImage, dev, imgInfo, MemorySegment.NULL, pImage), "vkCreateImage");
            this.image = pImage.get(JAVA_LONG, 0);

            MemorySegment imgReq = arena.allocate(MEMORY_REQUIREMENTS);
            invokeVoid(vkGetImageMemoryRequirements, dev, image, imgReq);
            this.imageMemory = allocate(arena, vkAllocateMemory, dev, gl(imgReq, MEMORY_REQUIREMENTS, "size"),
                    device.findMemoryType(gi(imgReq, MEMORY_REQUIREMENTS, "memoryTypeBits"),
                            Vk.MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
            check(invoke(vkBindImageMemory, dev, image, imageMemory, 0L), "vkBindImageMemory");

            // --- one-time upload: barrier -> copy -> barrier ---
            long pool = createCommandPool(arena, vkCreateCommandPool, dev, device.queueFamilyIndex());
            MemorySegment cmd = allocateCommandBuffer(arena, vkAllocateCommandBuffers, dev, pool);
            MemorySegment begin = arena.allocate(COMMAND_BUFFER_BEGIN_INFO);
            si(begin, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(begin, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(invoke(vkBeginCommandBuffer, cmd, begin), "vkBeginCommandBuffer");

            barrier(arena, vkCmdPipelineBarrier, cmd, image, mipLevels,
                    Vk.IMAGE_LAYOUT_UNDEFINED, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, Vk.ACCESS_TRANSFER_WRITE_BIT,
                    Vk.PIPELINE_STAGE_TOP_OF_PIPE_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT);

            // One copy region per level, each from its own place in the staging buffer.
            MemorySegment regions = arena.allocate(BUFFER_IMAGE_COPY, mipLevels);
            for (int l = 0; l < mipLevels; l++) {
                MemorySegment region = regions.asSlice(l * BUFFER_IMAGE_COPY.byteSize(), BUFFER_IMAGE_COPY.byteSize());
                sl(region, BUFFER_IMAGE_COPY, "bufferOffset", offsets[l]);
                si(region, BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                si(region, BUFFER_IMAGE_COPY, "is_mipLevel", l);
                si(region, BUFFER_IMAGE_COPY, "is_layerCount", 1);
                si(region, BUFFER_IMAGE_COPY, "ext_width", MipChain.size(width, l));
                si(region, BUFFER_IMAGE_COPY, "ext_height", MipChain.size(height, l));
                si(region, BUFFER_IMAGE_COPY, "ext_depth", 1);
            }
            invokeVoid(vkCmdCopyBufferToImage, cmd, staging, image, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, mipLevels,
                    regions);

            barrier(arena, vkCmdPipelineBarrier, cmd, image, mipLevels,
                    Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    Vk.ACCESS_TRANSFER_WRITE_BIT, Vk.ACCESS_SHADER_READ_BIT,
                    Vk.PIPELINE_STAGE_TRANSFER_BIT, Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT);

            check(invoke(vkEndCommandBuffer, cmd), "vkEndCommandBuffer");
            submit(arena, vkQueueSubmit, device, cmd);
            // Not waited for. Every frame that can sample this is submitted after it on the same queue, and the
            // copy's last barrier orders its write before their fragment reads; all that has to outlive the copy is
            // the staging and the command buffer, which a mark taken now frees once the copy is done (settle).
            this.staging = staging;
            this.stagingMemory = stagingMemory;
            this.uploadPool = pool;
            this.vkDestroyBuffer = vkDestroyBuffer;
            this.vkDestroyCommandPool = vkDestroyCommandPool;
            this.uploaded = QueueMark.now(device);

            // --- image view ---
            MemorySegment viewInfo = arena.allocate(IMAGE_VIEW_CREATE_INFO);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            sl(viewInfo, IMAGE_VIEW_CREATE_INFO, "image", image);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "viewType", Vk.IMAGE_VIEW_TYPE_2D);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "format", format);
            if (channels == 1) {
                // R into every channel: median(r, g, b) of this is the one distance, and alpha is it too.
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "c_r", Vk.COMPONENT_SWIZZLE_R);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "c_g", Vk.COMPONENT_SWIZZLE_R);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "c_b", Vk.COMPONENT_SWIZZLE_R);
                si(viewInfo, IMAGE_VIEW_CREATE_INFO, "c_a", Vk.COMPONENT_SWIZZLE_R);
            }
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_levelCount", mipLevels);
            si(viewInfo, IMAGE_VIEW_CREATE_INFO, "sr_layerCount", 1);
            MemorySegment pView = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateImageView, dev, viewInfo, MemorySegment.NULL, pView), "vkCreateImageView");
            this.imageView = pView.get(JAVA_LONG, 0);

            // --- sampler (linear, clamp to edge) ---
            MemorySegment sampInfo = arena.allocate(SAMPLER_CREATE_INFO);
            si(sampInfo, SAMPLER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            si(sampInfo, SAMPLER_CREATE_INFO, "magFilter", Vk.FILTER_LINEAR);
            si(sampInfo, SAMPLER_CREATE_INFO, "minFilter", Vk.FILTER_LINEAR);
            // Trilinear with a chain: between levels as well as within one. One level reads level 0 only.
            si(sampInfo, SAMPLER_CREATE_INFO, "mipmapMode",
                    mipLevels > 1 ? Vk.SAMPLER_MIPMAP_MODE_LINEAR : Vk.SAMPLER_MIPMAP_MODE_NEAREST);
            sampInfo.set(java.lang.foreign.ValueLayout.JAVA_FLOAT,
                    SAMPLER_CREATE_INFO.byteOffset(MemoryLayout.PathElement.groupElement("maxLod")), (float) mipLevels);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeU", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeV", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeW", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "borderColor", Vk.BORDER_COLOR_FLOAT_OPAQUE_BLACK);
            MemorySegment pSampler = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateSampler, dev, sampInfo, MemorySegment.NULL, pSampler), "vkCreateSampler");
            this.sampler = pSampler.get(JAVA_LONG, 0);

            // --- descriptor set layout (binding 0: combined image sampler, fragment stage) ---
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

            // --- descriptor pool + set ---
            MemorySegment poolSize = arena.allocate(DESCRIPTOR_POOL_SIZE);
            si(poolSize, DESCRIPTOR_POOL_SIZE, "type", Vk.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            si(poolSize, DESCRIPTOR_POOL_SIZE, "descriptorCount", 1);
            MemorySegment poolInfo = arena.allocate(DESCRIPTOR_POOL_CREATE_INFO);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "maxSets", 1);
            si(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "poolSizeCount", 1);
            sa(poolInfo, DESCRIPTOR_POOL_CREATE_INFO, "pPoolSizes", poolSize);
            MemorySegment pPool = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateDescriptorPool, dev, poolInfo, MemorySegment.NULL, pPool), "vkCreateDescriptorPool");
            this.descriptorPool = pPool.get(JAVA_LONG, 0);

            MemorySegment pSetLayouts = arena.allocate(JAVA_LONG);
            pSetLayouts.set(JAVA_LONG, 0, descriptorSetLayout);
            MemorySegment allocInfo = arena.allocate(DESCRIPTOR_SET_ALLOCATE_INFO);
            si(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            sl(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "descriptorPool", descriptorPool);
            si(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "descriptorSetCount", 1);
            sa(allocInfo, DESCRIPTOR_SET_ALLOCATE_INFO, "pSetLayouts", pSetLayouts);
            MemorySegment pSet = arena.allocate(JAVA_LONG);
            check(invoke(vkAllocateDescriptorSets, dev, allocInfo, pSet), "vkAllocateDescriptorSets");
            this.descriptorSet = pSet.get(JAVA_LONG, 0);

            // --- point the set at (sampler, view) ---
            MemorySegment imageInfo = arena.allocate(DESCRIPTOR_IMAGE_INFO);
            sl(imageInfo, DESCRIPTOR_IMAGE_INFO, "sampler", sampler);
            sl(imageInfo, DESCRIPTOR_IMAGE_INFO, "imageView", imageView);
            si(imageInfo, DESCRIPTOR_IMAGE_INFO, "imageLayout", Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            MemorySegment write = arena.allocate(WRITE_DESCRIPTOR_SET);
            si(write, WRITE_DESCRIPTOR_SET, "sType", Vk.STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            sl(write, WRITE_DESCRIPTOR_SET, "dstSet", descriptorSet);
            si(write, WRITE_DESCRIPTOR_SET, "dstBinding", 0);
            si(write, WRITE_DESCRIPTOR_SET, "descriptorCount", 1);
            si(write, WRITE_DESCRIPTOR_SET, "descriptorType", Vk.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
            sa(write, WRITE_DESCRIPTOR_SET, "pImageInfo", imageInfo);
            invokeVoid(vkUpdateDescriptorSets, dev, 1, write, 0, MemorySegment.NULL);
        }
    }

    /** The {@code VkDescriptorSetLayout} to build the consuming {@link GraphicsPipeline} against. */
    public long descriptorSetLayout() {
        return descriptorSetLayout;
    }

    @Override
    public VulkanDevice device() {
        return device;
    }

    @Override
    public boolean linear() {
        return srgb;
    }

    /**
     * The {@code VkDescriptorSet} to bind before drawing (set 0). Asked for by every frame that draws this, so it is
     * also where the upload's staging is freed, by the first frame recorded after the copy has finished.
     */
    public long descriptorSet() {
        if (uploaded != null) {
            settle(false);
        }
        return descriptorSet;
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

    private void barrier(Arena arena, MethodHandle vkCmdPipelineBarrier, MemorySegment cmd, long image, int levels,
                         int oldLayout, int newLayout, int srcAccess, int dstAccess, int srcStage, int dstStage) {
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
        si(b, IMAGE_MEMORY_BARRIER, "sr_levelCount", levels);
        si(b, IMAGE_MEMORY_BARRIER, "sr_layerCount", 1);
        invokeVoid(vkCmdPipelineBarrier, cmd, srcStage, dstStage, 0, 0, MemorySegment.NULL, 0, MemorySegment.NULL,
                1, b);
    }

    private long createCommandPool(Arena arena, MethodHandle vkCreateCommandPool, MemorySegment dev, int queueFamily) {
        MemorySegment info = arena.allocate(COMMAND_POOL_CREATE_INFO);
        si(info, COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
        si(info, COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", queueFamily);
        MemorySegment p = arena.allocate(JAVA_LONG);
        check(invoke(vkCreateCommandPool, dev, info, MemorySegment.NULL, p), "vkCreateCommandPool");
        return p.get(JAVA_LONG, 0);
    }

    private MemorySegment allocateCommandBuffer(Arena arena, MethodHandle vkAllocateCommandBuffers, MemorySegment dev,
                                                long pool) {
        MemorySegment info = arena.allocate(COMMAND_BUFFER_ALLOCATE_INFO);
        si(info, COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
        sl(info, COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", pool);
        si(info, COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
        si(info, COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
        MemorySegment p = arena.allocate(ADDRESS);
        check(invoke(vkAllocateCommandBuffers, dev, info, p), "vkAllocateCommandBuffers");
        return p.get(ADDRESS, 0);
    }

    private void submit(Arena arena, MethodHandle vkQueueSubmit, VulkanDevice device, MemorySegment cmd) {
        MemorySegment pCmd = arena.allocate(ADDRESS);
        pCmd.set(ADDRESS, 0, cmd);
        MemorySegment submit = arena.allocate(SUBMIT_INFO);
        si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
        si(submit, SUBMIT_INFO, "commandBufferCount", 1);
        sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmd);
        check(invoke(vkQueueSubmit, device.queue(), 1, submit, 0L), "vkQueueSubmit");
    }

    /**
     * Free the upload's staging buffer and command pool once its copy has finished: without blocking when
     * {@code wait} is false (and then perhaps not yet), or after waiting for the copy alone when it is true.
     */
    private synchronized void settle(boolean wait) {
        QueueMark mark = uploaded;
        if (mark == null) {
            return;
        }
        if (wait) {
            mark.await();
        } else if (!mark.passed()) {
            return;
        }
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyCommandPool, dev, uploadPool, MemorySegment.NULL);
        invokeVoid(vkDestroyBuffer, dev, staging, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, stagingMemory, MemorySegment.NULL);
        mark.close();
        uploaded = null;
    }

    private boolean closed;

    /** Whether {@link #close} has run, so an owner keeping a list of these can drop the ones already released. */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Release everything. Idempotent, because a texture an application replaces is closed by the application and
     * then again by whatever owns the list it was made from; a second destroy of a Vulkan handle is not reported,
     * it corrupts the loader's heap.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Probe.closed(Lane.GPU, "AtlasTexture", this);
        settle(true);
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyDescriptorPool, dev, descriptorPool, MemorySegment.NULL);
        invokeVoid(vkDestroyDescriptorSetLayout, dev, descriptorSetLayout, MemorySegment.NULL);
        invokeVoid(vkDestroySampler, dev, sampler, MemorySegment.NULL);
        invokeVoid(vkDestroyImageView, dev, imageView, MemorySegment.NULL);
        invokeVoid(vkDestroyImage, dev, image, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, imageMemory, MemorySegment.NULL);
    }
}
