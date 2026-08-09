package dev.vexelray.vulkan.present;

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
import static dev.vexelray.vulkan.vk.Ffm.sa;
import static dev.vexelray.vulkan.vk.Ffm.si;
import static dev.vexelray.vulkan.vk.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * An offscreen colour image that is <em>both</em> a render target and a sampled texture — the "draw 2D onto a
 * surface" path. It owns a {@code COLOR_ATTACHMENT | SAMPLED} image + view + linear sampler, a
 * {@link VulkanRenderPass} whose final layout is {@code SHADER_READ_ONLY_OPTIMAL}, a framebuffer, and a one-binding
 * descriptor set (combined image sampler) pointing at itself. Call {@link #renderInto} to draw a pipeline's
 * geometry into it (e.g. a {@link dev.vexelray.canvas} batch); afterward the image sits in {@code SHADER_READ_ONLY}
 * and {@link #descriptorSet()} can be bound by any later pipeline to sample it — an overlay composited over the
 * swapchain, or a 2D panel mapped onto geometry inside the 3D scene.
 */
public final class SampledColorTarget implements AutoCloseable {

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

    private static final GroupLayout SUBMIT_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("waitSemaphoreCount"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pWaitSemaphores"),
            ADDRESS.withName("pWaitDstStageMask"), JAVA_INT.withName("commandBufferCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pCommandBuffers"), JAVA_INT.withName("signalSemaphoreCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSignalSemaphores")).withName("VkSubmitInfo");

    private final VulkanDevice device;
    private final int width;
    private final int height;
    private final VulkanRenderPass renderPass;
    private final long image;
    private final long imageMemory;
    private final long imageView;
    private final long sampler;
    private final long framebuffer;
    private final long descriptorSetLayout;
    private final long descriptorPool;
    private final long descriptorSet;

    private final MethodHandle vkDestroyImage;
    private final MethodHandle vkFreeMemory;
    private final MethodHandle vkDestroyImageView;
    private final MethodHandle vkDestroySampler;
    private final MethodHandle vkDestroyFramebuffer;
    private final MethodHandle vkDestroyDescriptorSetLayout;
    private final MethodHandle vkDestroyDescriptorPool;

    public SampledColorTarget(VulkanDevice device, int width, int height) {
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
        MethodHandle vkCreateSampler = device.command("vkCreateSampler", C4);
        this.vkDestroySampler = device.command("vkDestroySampler", D_LONG);
        MethodHandle vkCreateFramebuffer = device.command("vkCreateFramebuffer", C4);
        this.vkDestroyFramebuffer = device.command("vkDestroyFramebuffer", D_LONG);
        MethodHandle vkCreateDescriptorSetLayout = device.command("vkCreateDescriptorSetLayout", C4);
        this.vkDestroyDescriptorSetLayout = device.command("vkDestroyDescriptorSetLayout", D_LONG);
        MethodHandle vkCreateDescriptorPool = device.command("vkCreateDescriptorPool", C4);
        this.vkDestroyDescriptorPool = device.command("vkDestroyDescriptorPool", D_LONG);
        MethodHandle vkAllocateDescriptorSets = device.command("vkAllocateDescriptorSets",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkUpdateDescriptorSets = device.command("vkUpdateDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));

        this.renderPass = new VulkanRenderPass(device, Vk.FORMAT_R8G8B8A8_UNORM,
                Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

        try (Arena arena = Arena.ofConfined()) {
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
            si(imgInfo, IMAGE_CREATE_INFO, "usage", Vk.IMAGE_USAGE_COLOR_ATTACHMENT_BIT | Vk.IMAGE_USAGE_SAMPLED_BIT);
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
            this.imageView = pView.get(JAVA_LONG, 0);

            MemorySegment sampInfo = arena.allocate(SAMPLER_CREATE_INFO);
            si(sampInfo, SAMPLER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            si(sampInfo, SAMPLER_CREATE_INFO, "magFilter", Vk.FILTER_LINEAR);
            si(sampInfo, SAMPLER_CREATE_INFO, "minFilter", Vk.FILTER_LINEAR);
            si(sampInfo, SAMPLER_CREATE_INFO, "mipmapMode", Vk.SAMPLER_MIPMAP_MODE_NEAREST);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeU", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeV", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "addressModeW", Vk.SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            si(sampInfo, SAMPLER_CREATE_INFO, "borderColor", Vk.BORDER_COLOR_FLOAT_OPAQUE_BLACK);
            MemorySegment pSampler = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateSampler, dev, sampInfo, MemorySegment.NULL, pSampler), "vkCreateSampler");
            this.sampler = pSampler.get(JAVA_LONG, 0);

            MemorySegment pAttach = arena.allocate(JAVA_LONG);
            pAttach.set(JAVA_LONG, 0, imageView);
            MemorySegment fbInfo = arena.allocate(FRAMEBUFFER_CREATE_INFO);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
            sl(fbInfo, FRAMEBUFFER_CREATE_INFO, "renderPass", renderPass.handle());
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "attachmentCount", 1);
            sa(fbInfo, FRAMEBUFFER_CREATE_INFO, "pAttachments", pAttach);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "width", width);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "height", height);
            si(fbInfo, FRAMEBUFFER_CREATE_INFO, "layers", 1);
            MemorySegment pFb = arena.allocate(JAVA_LONG);
            check(invoke(vkCreateFramebuffer, dev, fbInfo, MemorySegment.NULL, pFb), "vkCreateFramebuffer");
            this.framebuffer = pFb.get(JAVA_LONG, 0);

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

    /** The render pass to build the drawing pipeline against (single colour attachment, final SHADER_READ_ONLY). */
    public long renderPass() {
        return renderPass.handle();
    }

    /** The {@code VkDescriptorSetLayout} for a downstream pipeline that samples this target (set 0, binding 0). */
    public long descriptorSetLayout() {
        return descriptorSetLayout;
    }

    /** The {@code VkDescriptorSet} a downstream pipeline binds to sample this target. */
    public long descriptorSet() {
        return descriptorSet;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /**
     * Draw {@code vertexCount} vertices from {@code vertexBuffer} (with {@code descriptorSet} bound at set 0, e.g.
     * a font atlas) into this target, clearing to {@code (cr,cg,cb,ca)} first. One-time command buffer; after it
     * completes the image is in {@code SHADER_READ_ONLY} and can be sampled via {@link #descriptorSet()}.
     */
    public void renderInto(GraphicsPipeline pipeline, long vertexBuffer, long descriptorSet, int vertexCount,
                           float cr, float cg, float cb, float ca) {
        MemorySegment dev = device.handle();
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
        MethodHandle vkCmdDraw = device.command("vkCmdDraw",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT));
        MethodHandle vkQueueSubmit = device.command("vkQueueSubmit",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_LONG));

        try (Arena arena = Arena.ofConfined()) {
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

            MemorySegment begin = arena.allocate(COMMAND_BUFFER_BEGIN_INFO);
            si(begin, COMMAND_BUFFER_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            si(begin, COMMAND_BUFFER_BEGIN_INFO, "flags", Vk.COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            check(invoke(vkBeginCommandBuffer, cmd, begin), "vkBeginCommandBuffer");

            MemorySegment clear = arena.allocate(JAVA_FLOAT, 4);
            clear.setAtIndex(JAVA_FLOAT, 0, cr);
            clear.setAtIndex(JAVA_FLOAT, 1, cg);
            clear.setAtIndex(JAVA_FLOAT, 2, cb);
            clear.setAtIndex(JAVA_FLOAT, 3, ca);
            MemorySegment rpBegin = arena.allocate(RENDER_PASS_BEGIN_INFO);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "sType", Vk.STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            sl(rpBegin, RENDER_PASS_BEGIN_INFO, "renderPass", renderPass.handle());
            sl(rpBegin, RENDER_PASS_BEGIN_INFO, "framebuffer", framebuffer);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_extent_width", width);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "area_extent_height", height);
            si(rpBegin, RENDER_PASS_BEGIN_INFO, "clearValueCount", 1);
            sa(rpBegin, RENDER_PASS_BEGIN_INFO, "pClearValues", clear);

            invokeVoid(vkCmdBeginRenderPass, cmd, rpBegin, Vk.SUBPASS_CONTENTS_INLINE);
            invokeVoid(vkCmdBindPipeline, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipeline());
            if (descriptorSet != 0) {
                MemorySegment pSet = arena.allocate(JAVA_LONG);
                pSet.set(JAVA_LONG, 0, descriptorSet);
                invokeVoid(vkCmdBindDescriptorSets, cmd, Vk.PIPELINE_BIND_POINT_GRAPHICS, pipeline.pipelineLayout(),
                        0, 1, pSet, 0, MemorySegment.NULL);
            }
            if (vertexBuffer != 0 && vertexCount > 0) {
                MemorySegment pVb = arena.allocate(JAVA_LONG);
                pVb.set(JAVA_LONG, 0, vertexBuffer);
                MemorySegment pOff = arena.allocate(JAVA_LONG);
                pOff.set(JAVA_LONG, 0, 0L);
                invokeVoid(vkCmdBindVertexBuffers, cmd, 0, 1, pVb, pOff);
                invokeVoid(vkCmdDraw, cmd, vertexCount, 1, 0, 0);
            }
            invokeVoid(vkCmdEndRenderPass, cmd);
            check(invoke(vkEndCommandBuffer, cmd), "vkEndCommandBuffer");

            MemorySegment pCmdArray = arena.allocate(ADDRESS);
            pCmdArray.set(ADDRESS, 0, cmd);
            MemorySegment submit = arena.allocate(SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "sType", Vk.STRUCTURE_TYPE_SUBMIT_INFO);
            si(submit, SUBMIT_INFO, "commandBufferCount", 1);
            sa(submit, SUBMIT_INFO, "pCommandBuffers", pCmdArray);
            check(invoke(vkQueueSubmit, device.queue(), 1, submit, 0L), "vkQueueSubmit");
            device.waitIdle();
            invokeVoid(vkDestroyCommandPool, dev, pool, MemorySegment.NULL);
        }
    }

    private long allocate(Arena arena, MethodHandle vkAllocateMemory, MemorySegment dev, long size, int typeIndex) {
        MemorySegment info = arena.allocate(MEMORY_ALLOCATE_INFO);
        si(info, MEMORY_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO);
        sl(info, MEMORY_ALLOCATE_INFO, "allocationSize", size);
        si(info, MEMORY_ALLOCATE_INFO, "memoryTypeIndex", typeIndex);
        MemorySegment pMem = arena.allocate(JAVA_LONG);
        check(invoke(vkAllocateMemory, dev, info, MemorySegment.NULL, pMem), "vkAllocateMemory");
        return pMem.get(JAVA_LONG, 0);
    }

    @Override
    public void close() {
        MemorySegment dev = device.handle();
        invokeVoid(vkDestroyDescriptorPool, dev, descriptorPool, MemorySegment.NULL);
        invokeVoid(vkDestroyDescriptorSetLayout, dev, descriptorSetLayout, MemorySegment.NULL);
        invokeVoid(vkDestroyFramebuffer, dev, framebuffer, MemorySegment.NULL);
        invokeVoid(vkDestroySampler, dev, sampler, MemorySegment.NULL);
        invokeVoid(vkDestroyImageView, dev, imageView, MemorySegment.NULL);
        invokeVoid(vkDestroyImage, dev, image, MemorySegment.NULL);
        invokeVoid(vkFreeMemory, dev, imageMemory, MemorySegment.NULL);
        renderPass.close();
    }
}
