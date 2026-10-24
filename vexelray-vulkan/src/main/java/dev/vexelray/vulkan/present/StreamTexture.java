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
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A texture whose pixels change: frames streamed to the GPU one after another, each shown as it lands, without making a
 * texture per frame. For a picture that plays.
 *
 * <p><b>A ring of slots, not one image rewritten.</b> {@link AtlasTexture} is never rewritten because a texture that
 * changed under a frame in flight would tear. This keeps that promise by never writing the image a node names: it holds
 * {@code slots} images of one size, each a {@link Slot} with its own descriptor set, made once. A frame is written into
 * a slot nothing shows, copied to the GPU, and then {@link #show}n. Three slots is enough: one shown, one landing, one
 * being written.
 *
 * <p><b>A node names the stream, not a slot.</b> {@link #live} is one {@link SampledImage} for the stream's whole life,
 * whose descriptor set is whichever slot was shown last, read as each frame is recorded. A node is pointed at it once,
 * and a new frame is a slot shown and a frame asked for ({@link #onShown}), never an edit to the tree: a video that
 * plays under still controls changes nothing a tree holds. A {@link Slot} is a {@link SampledImage} too, for a node
 * that must hold one frame.
 *
 * <p><b>The life of a slot</b> is {@link #take} (any thread: a free slot, its staging memory now the caller's), writing
 * its levels into {@link Slot#level}, {@link #submit} (the GUI thread: the copy is recorded and queued), and
 * {@link #show} (the GUI thread, between frames: it is what {@link #live} draws from now, and the slot shown before it
 * is free again). {@link #drop} gives back a slot taken or submitted and then not wanted.
 *
 * <p><b>Why no wait.</b> Each slot has its own region of one persistently mapped staging buffer, its own command
 * buffer and its own fence. A copy is ordered after any frame that sampled the slot before it by its own barrier
 * (fragment reads before transfer writes), and before any frame that samples it after by the same queue's submission
 * order; the device is never waited for. The fence only guards the slot's staging region: {@link #take} waits on it,
 * and by the time a slot has been shown and replaced its copy has long finished.
 *
 * <p>With {@code picture}, as {@code GuiApp.picture}: sRGB texels the sampler decodes to linear light, and each slot
 * carries a mip chain ({@link MipChain}), so a frame drawn small reads the level near its size. A frame's levels are
 * the writer's to make: {@link MipChain#of} on its own thread, never the GUI's.
 *
 * <p>{@link #submit}, {@link #show} and {@link #close} on the thread that submits frames, since a queue submission is
 * not shared; {@link #take}, {@link Slot#level} and {@link #drop} from any thread.
 */
public final class StreamTexture implements AutoCloseable {

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor MEMREQ = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);
    private static final FunctionDescriptor BIND = FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_LONG);

    /** {@code VK_FENCE_CREATE_SIGNALED_BIT}: a slot's fence starts signalled, so its first {@link #take} need not wait. */
    private static final int FENCE_CREATE_SIGNALED = 0x1;

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

    private static final GroupLayout FENCE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4)).withName("VkFenceCreateInfo");

    /** Where a slot is in its life. */
    private enum State {
        /** Nothing shows it and nobody is writing it. */
        FREE,
        /** Taken: its staging region is a writer's. */
        WRITING,
        /** Its copy is queued; a node may now be pointed at it. */
        SUBMITTED,
        /** A node names it. */
        SHOWN
    }

    private final VulkanDevice device;
    private final int width;
    private final int height;
    private final int levels;
    private final boolean picture;
    /** One slot's staging bytes: every level, end to end. */
    private final long slotBytes;
    private final long[] levelOffsets;

    private final long sampler;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long staging;
    private final long stagingMemory;
    private final MemorySegment mapped;
    private final long commandPool;
    private final Slot[] slots;

    private final MethodHandle vkDestroyImage;
    private final MethodHandle vkFreeMemory;
    private final MethodHandle vkDestroyImageView;
    private final MethodHandle vkDestroySampler;
    private final MethodHandle vkDestroyDescriptorSetLayout;
    private final MethodHandle vkDestroyDescriptorPool;
    private final MethodHandle vkDestroyBuffer;
    private final MethodHandle vkUnmapMemory;
    private final MethodHandle vkDestroyCommandPool;
    private final MethodHandle vkDestroyFence;
    private final MethodHandle vkWaitForFences;
    private final MethodHandle vkResetFences;
    private final MethodHandle vkResetCommandBuffer;
    private final MethodHandle vkBeginCommandBuffer;
    private final MethodHandle vkEndCommandBuffer;
    private final MethodHandle vkCmdPipelineBarrier;
    private final MethodHandle vkCmdCopyBufferToImage;
    private final MethodHandle vkQueueSubmit;

    /** What a node is pointed at to show whichever slot is shown ({@link #live}). */
    private final Live live = new Live();
    /** Told after every {@link #show}, outside the lock. */
    private volatile Runnable onShown = () -> { };

    // Guarded by this.
    private Slot shown;
    private boolean closed;

    /**
     * {@code slots} images of {@code width x height}, made once. With {@code picture}, sRGB with a mip chain, as
     * {@code GuiApp.picture} uploads; without, plain RGBA8 at one level, as {@code GuiApp.texture} does.
     */
    public StreamTexture(VulkanDevice device, int width, int height, int slots, boolean picture) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("a stream needs a positive size: " + width + "x" + height);
        }
        if (slots < 2) {
            throw new IllegalArgumentException("a stream needs a slot to show and one to write: " + slots);
        }
        Probe.opened(Lane.GPU, "StreamTexture", this);
        this.device = device;
        this.width = width;
        this.height = height;
        this.picture = picture;
        this.levels = picture ? MipChain.count(width, height) : 1;
        this.levelOffsets = new long[levels];
        long at = 0;
        for (int l = 0; l < levels; l++) {
            levelOffsets[l] = at;
            at += (long) MipChain.size(width, l) * MipChain.size(height, l) * 4;
        }
        this.slotBytes = at;
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
        MethodHandle vkCreateCommandPool = device.command("vkCreateCommandPool", C4);
        MethodHandle vkAllocateCommandBuffers = device.command("vkAllocateCommandBuffers",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkCreateFence = device.command("vkCreateFence", C4);
        this.vkDestroyImage = device.command("vkDestroyImage", D_LONG);
        this.vkFreeMemory = device.command("vkFreeMemory", D_LONG);
        this.vkDestroyImageView = device.command("vkDestroyImageView", D_LONG);
        this.vkDestroySampler = device.command("vkDestroySampler", D_LONG);
        this.vkDestroyDescriptorSetLayout = device.command("vkDestroyDescriptorSetLayout", D_LONG);
        this.vkDestroyDescriptorPool = device.command("vkDestroyDescriptorPool", D_LONG);
        this.vkDestroyBuffer = device.command("vkDestroyBuffer", D_LONG);
        this.vkUnmapMemory = device.command("vkUnmapMemory", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG));
        this.vkDestroyCommandPool = device.command("vkDestroyCommandPool", D_LONG);
        this.vkDestroyFence = device.command("vkDestroyFence", D_LONG);
        this.vkWaitForFences = device.command("vkWaitForFences",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG));
        this.vkResetFences = device.command("vkResetFences", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.vkResetCommandBuffer = device.command("vkResetCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
        this.vkBeginCommandBuffer = device.command("vkBeginCommandBuffer",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        this.vkEndCommandBuffer = device.command("vkEndCommandBuffer", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.vkCmdPipelineBarrier = device.command("vkCmdPipelineBarrier",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS,
                        JAVA_INT, ADDRESS));
        this.vkCmdCopyBufferToImage = device.command("vkCmdCopyBufferToImage",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
        this.vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));

        try (Arena arena = Arena.ofConfined()) {
            // --- one staging buffer for every slot, mapped for as long as the stream lives ---
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

            // --- a command pool whose buffers are each re-recorded, one per slot ---
            MemorySegment cpInfo = arena.allocate(COMMAND_POOL_CREATE_INFO);
            si(cpInfo, COMMAND_POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            si(cpInfo, COMMAND_POOL_CREATE_INFO, "flags", Vk.COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);
            si(cpInfo, COMMAND_POOL_CREATE_INFO, "queueFamilyIndex", device.queueFamilyIndex());
            MemorySegment pCp = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateCommandPool, dev, cpInfo, MemorySegment.NULL, pCp), "vkCreateCommandPool");
            this.commandPool = pCp.get(JAVA_LONG, 0);

            // --- the slots ---
            this.slots = new Slot[slots];
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
                si(imgInfo, IMAGE_CREATE_INFO, "usage", Vk.IMAGE_USAGE_TRANSFER_DST_BIT | Vk.IMAGE_USAGE_SAMPLED_BIT);
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

                MemorySegment cbInfo = arena.allocate(COMMAND_BUFFER_ALLOCATE_INFO);
                si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
                sl(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "commandPool", commandPool);
                si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "level", Vk.COMMAND_BUFFER_LEVEL_PRIMARY);
                si(cbInfo, COMMAND_BUFFER_ALLOCATE_INFO, "commandBufferCount", 1);
                MemorySegment pCmd = arena.allocate(ADDRESS);
                check(invoke(vkAllocateCommandBuffers, dev, cbInfo, pCmd), "vkAllocateCommandBuffers");
                MemorySegment cmd = pCmd.get(ADDRESS, 0);

                MemorySegment fenceInfo = arena.allocate(FENCE_CREATE_INFO);
                si(fenceInfo, FENCE_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FENCE_CREATE_INFO);
                si(fenceInfo, FENCE_CREATE_INFO, "flags", FENCE_CREATE_SIGNALED);
                MemorySegment pFence = arena.allocate(JAVA_LONG);
                check(invoke(vkCreateFence, dev, fenceInfo, MemorySegment.NULL, pFence), "vkCreateFence");

                this.slots[s] = new Slot(s, image, memory, view, set, cmd, pFence.get(JAVA_LONG, 0),
                        mapped.asSlice(slotBytes * s, slotBytes), slotBytes * s);
            }
        }
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** Mip levels in each slot: the whole chain for a picture, else 1. */
    public int levels() {
        return levels;
    }

    /** Whether the texels are sRGB with a mip chain, as a picture's are. */
    public boolean picture() {
        return picture;
    }

    public int slots() {
        return slots.length;
    }

    /** Slot {@code i}, whatever its state: for an owner that must know whether a tree still names any of them. */
    public Slot slot(int i) {
        return slots[i];
    }

    /** The bytes each slot holds on each side of the copy: every level of one frame. */
    public long frameBytes() {
        return slotBytes;
    }

    /** The {@code VkDescriptorSetLayout} every slot's set is allocated from. */
    public long descriptorSetLayout() {
        return descriptorSetLayout;
    }

    /**
     * A slot to write the next frame into, or null if every slot is shown, landing or being written. Any thread. The
     * slot's staging memory is the caller's until it hands the slot to {@link #submit} or {@link #drop}; this waits, if
     * it must, for the last copy out of that memory to finish.
     */
    public Slot take() {
        Slot s;
        synchronized (this) {
            if (closed) {
                return null;
            }
            s = null;
            for (Slot candidate : slots) {
                if (candidate.state == State.FREE) {
                    s = candidate;
                    break;
                }
            }
            if (s == null) {
                return null;
            }
            s.state = State.WRITING;
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pFence = arena.allocate(JAVA_LONG);
            pFence.set(JAVA_LONG, 0, s.fence);
            check(invoke(vkWaitForFences, device.handle(), 1, pFence, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        }
        return s;
    }

    /**
     * Queue the copy of a slot written since {@link #take} to the GPU. On the thread that submits frames, which then
     * points a node at the slot and calls {@link #show}; any frame submitted after this one samples the new pixels.
     */
    public void submit(Slot s) {
        synchronized (this) {
            requireMine(s);
            if (closed) {
                return;
            }
            if (s.state != State.WRITING) {
                throw new IllegalStateException("only a slot taken and written can be submitted; " + s + " is " + s.state);
            }
            s.state = State.SUBMITTED;
        }
        MemorySegment dev = device.handle();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pFence = arena.allocate(JAVA_LONG);
            pFence.set(JAVA_LONG, 0, s.fence);
            check(invoke(vkResetFences, dev, 1, pFence), "vkResetFences");
            check(invoke(vkResetCommandBuffer, s.cmd, 0), "vkResetCommandBuffer");
            MemorySegment begin = arena.allocate(COMMAND_BUFFER_BEGIN_INFO);
            si(begin, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(begin, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(invoke(vkBeginCommandBuffer, s.cmd, begin), "vkBeginCommandBuffer");

            // Earlier frames' reads of this slot, in submission order, finish before the copy writes it. The old
            // contents are not kept: every level is about to be overwritten.
            barrier(arena, s.cmd, s.image, Vk.IMAGE_LAYOUT_UNDEFINED, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0, Vk.ACCESS_TRANSFER_WRITE_BIT,
                    Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT, Vk.PIPELINE_STAGE_TRANSFER_BIT);
            MemorySegment regions = arena.allocate(BUFFER_IMAGE_COPY, levels);
            for (int l = 0; l < levels; l++) {
                MemorySegment region = regions.asSlice(l * BUFFER_IMAGE_COPY.byteSize(), BUFFER_IMAGE_COPY.byteSize());
                sl(region, BUFFER_IMAGE_COPY, "bufferOffset", s.stagingOffset + levelOffsets[l]);
                si(region, BUFFER_IMAGE_COPY, "is_aspectMask", Vk.IMAGE_ASPECT_COLOR_BIT);
                si(region, BUFFER_IMAGE_COPY, "is_mipLevel", l);
                si(region, BUFFER_IMAGE_COPY, "is_layerCount", 1);
                si(region, BUFFER_IMAGE_COPY, "ext_width", MipChain.size(width, l));
                si(region, BUFFER_IMAGE_COPY, "ext_height", MipChain.size(height, l));
                si(region, BUFFER_IMAGE_COPY, "ext_depth", 1);
            }
            invokeVoid(vkCmdCopyBufferToImage, s.cmd, staging, s.image, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, levels,
                    regions);
            barrier(arena, s.cmd, s.image, Vk.IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    Vk.ACCESS_TRANSFER_WRITE_BIT, Vk.ACCESS_SHADER_READ_BIT,
                    Vk.PIPELINE_STAGE_TRANSFER_BIT, Vk.PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
            check(invoke(vkEndCommandBuffer, s.cmd), "vkEndCommandBuffer");

            MemorySegment pCmd = arena.allocate(ADDRESS);
            pCmd.set(ADDRESS, 0, s.cmd);
            MemorySegment submit = arena.allocate(SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "commandBufferCount", 1);
            sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmd);
            check(invoke(vkQueueSubmit, device.queue(), 1, submit, s.fence), "vkQueueSubmit");
        }
    }

    /**
     * {@code s} is what {@link #live} draws from now, and the slot shown before it is free to be written again; then
     * {@link #onShown} is told. On the thread that submits frames, between frames, so every frame that sampled the old
     * slot has already been submitted and any copy into it is ordered after them. A node pointed at {@code s} itself
     * is pointed there before this, for the same reason.
     */
    public void show(Slot s) {
        synchronized (this) {
            requireMine(s);
            if (s.state != State.SUBMITTED && s.state != State.SHOWN) {
                throw new IllegalStateException("only a submitted slot can be shown; " + s + " is " + s.state);
            }
            if (shown != null && shown != s) {
                shown.state = State.FREE;
            }
            s.state = State.SHOWN;
            shown = s;
        }
        onShown.run();
    }

    /**
     * The stream as one image: whichever slot was {@link #show}n last, read as each frame is recorded. The same object
     * for the stream's life, so a node pointed at it once shows every frame after without being written to again. Point
     * a node at it only once a slot has been shown: before that there is nothing to draw.
     */
    public SampledImage live() {
        return live;
    }

    /**
     * Run {@code r} after every {@link #show}, on the thread that shows: the pixels under {@link #live} changed with no
     * edit to any tree, so whoever draws must be asked for a frame. Its owner's to set ({@code GuiApp.stream}).
     */
    public void onShown(Runnable r) {
        onShown = r == null ? () -> { } : r;
    }

    /**
     * Give back a slot taken or submitted and then not shown, such as a frame made for a position since left. Any
     * thread. Dropping the slot that is shown does nothing; it is freed by the next {@link #show}.
     */
    public synchronized void drop(Slot s) {
        requireMine(s);
        if (s.state == State.WRITING || s.state == State.SUBMITTED) {
            s.state = State.FREE;
        }
    }

    /** The slot last {@link #show}n, which {@link #live} draws from; or null. */
    public synchronized Slot shown() {
        return shown;
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    private void requireMine(Slot s) {
        if (s == null || s.owner() != this) {
            throw new IllegalArgumentException("not a slot of this stream: " + s);
        }
    }

    /**
     * Release every slot and the staging memory, once no frame samples any of them: waits for this stream's own
     * copies, not for the device. Idempotent, as {@link AtlasTexture#close} is and for the same reason.
     */
    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        Probe.closed(Lane.GPU, "StreamTexture", this);
        MemorySegment dev = device.handle();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment fences = arena.allocate(JAVA_LONG, slots.length);
            for (int s = 0; s < slots.length; s++) {
                fences.setAtIndex(JAVA_LONG, s, slots[s].fence);
            }
            check(invoke(vkWaitForFences, dev, slots.length, fences, Vk.VK_TRUE, Long.MAX_VALUE), "vkWaitForFences");
        }
        for (Slot s : slots) {
            invokeVoid(vkDestroyFence, dev, s.fence, MemorySegment.NULL);
            invokeVoid(vkDestroyImageView, dev, s.view, MemorySegment.NULL);
            invokeVoid(vkDestroyImage, dev, s.image, MemorySegment.NULL);
            invokeVoid(vkFreeMemory, dev, s.memory, MemorySegment.NULL);
        }
        invokeVoid(vkDestroyCommandPool, dev, commandPool, MemorySegment.NULL);
        invokeVoid(vkDestroyDescriptorPool, dev, descriptorPool, MemorySegment.NULL);
        invokeVoid(vkDestroyDescriptorSetLayout, dev, descriptorSetLayout, MemorySegment.NULL);
        invokeVoid(vkDestroySampler, dev, sampler, MemorySegment.NULL);
        invokeVoid(vkUnmapMemory, dev, stagingMemory);
        invokeVoid(vkDestroyBuffer, dev, staging, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, stagingMemory, MemorySegment.NULL);
    }

    @Override
    public String toString() {
        return "StreamTexture[" + width + "x" + height + ", " + slots.length + " slots"
                + (picture ? ", picture" : "") + (closed ? ", closed" : "") + "]";
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

    private void barrier(Arena arena, MemorySegment cmd, long image, int oldLayout, int newLayout, int srcAccess,
                         int dstAccess, int srcStage, int dstStage) {
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

    /**
     * One image of the ring, and what a node is pointed at: a {@link SampledImage} with a descriptor set of its own.
     * Its {@link #level} memory is the writer's between {@link StreamTexture#take} and {@link StreamTexture#submit}.
     */
    public final class Slot implements SampledImage {

        private final int index;
        private final long image;
        private final long memory;
        private final long view;
        private final long set;
        private final MemorySegment cmd;
        private final long fence;
        private final MemorySegment stagingRegion;
        private final long stagingOffset;
        // Guarded by the stream.
        private State state = State.FREE;

        private Slot(int index, long image, long memory, long view, long set, MemorySegment cmd, long fence,
                     MemorySegment stagingRegion, long stagingOffset) {
            this.index = index;
            this.image = image;
            this.memory = memory;
            this.view = view;
            this.set = set;
            this.cmd = cmd;
            this.fence = fence;
            this.stagingRegion = stagingRegion;
            this.stagingOffset = stagingOffset;
        }

        /** Level {@code l}'s staging memory: {@code size(width, l) x size(height, l)} RGBA8 texels to write. */
        public MemorySegment level(int l) {
            if (l < 0 || l >= levels) {
                throw new IndexOutOfBoundsException("level " + l + " of " + levels);
            }
            long end = l + 1 < levels ? levelOffsets[l + 1] : slotBytes;
            return stagingRegion.asSlice(levelOffsets[l], end - levelOffsets[l]);
        }

        /** Write every level at once, {@code levels[0]} the frame and each next one half the last ({@link MipChain}). */
        public void write(byte[][] chain) {
            if (chain.length != levels) {
                throw new IllegalArgumentException("a slot has " + levels + " levels; was given " + chain.length);
            }
            for (int l = 0; l < levels; l++) {
                MemorySegment dst = level(l);
                if (chain[l].length != dst.byteSize()) {
                    throw new IllegalArgumentException("level " + l + " is " + dst.byteSize() + " bytes; was given "
                            + chain[l].length);
                }
                MemorySegment.copy(MemorySegment.ofArray(chain[l]), 0, dst, 0, dst.byteSize());
            }
        }

        public StreamTexture owner() {
            return StreamTexture.this;
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
            return "Slot[" + index + " of " + StreamTexture.this + "]";
        }
    }

    /** {@link #live}: the slot shown, asked for each time a frame binds it. */
    private final class Live implements SampledImage {

        @Override
        public long descriptorSet() {
            Slot s = shown();
            if (s == null) {
                throw new IllegalStateException(StreamTexture.this + " was drawn before any frame was shown");
            }
            return s.set;
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
            return "Live[" + StreamTexture.this + "]";
        }
    }
}
