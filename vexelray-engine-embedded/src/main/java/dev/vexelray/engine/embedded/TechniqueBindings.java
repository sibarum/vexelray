package dev.vexelray.engine.embedded;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.engine.TechniqueBinding;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.util.List;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static dev.supirvast.vulkan.Ffm.sa;
import static dev.supirvast.vulkan.Ffm.si;
import static dev.supirvast.vulkan.Ffm.sl;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * What a host binds for one technique's {@link dev.vexelray.engine.RenderTechnique#bindings() bindings}: the descriptor
 * set layout made from the declaration, a ring of sets as deep as the frames that may be in flight, and what each name
 * is bound to now (vexelray-gui's {@code docs/plans/device-feed.md}, 4a). Every host that runs techniques binds through
 * this, so a technique's set is written the same way whoever records it.
 *
 * <p>Told what each name is bound to ({@link #buffer}, {@link #picture}) whenever that changes, and asked each frame
 * for the set to hand the technique ({@link #next}), which writes the next set of the ring with what is bound now. A
 * set is written again {@value #RING} frames later, by when the frame that read it has finished: the host's frames
 * in flight never outnumber the ring. Writing allocates nothing; the structs it writes from are made once.
 *
 * <p>On the thread that records frames, as the technique is.
 */
public final class TechniqueBindings implements AutoCloseable {

    /** Sets in the ring: more than a host has frames in flight. */
    public static final int RING = 3;
    /** {@code VK_WHOLE_SIZE}: a buffer bound to its end. */
    public static final long WHOLE = -1L;
    private static final int DESCRIPTOR_TYPE_STORAGE_BUFFER = 7;

    private static final FunctionDescriptor C4 = FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS);
    private static final FunctionDescriptor D_LONG = FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS);

    private static final GroupLayout LAYOUT_BINDING = MemoryLayout.structLayout(
            JAVA_INT.withName("binding"), JAVA_INT.withName("descriptorType"), JAVA_INT.withName("descriptorCount"),
            JAVA_INT.withName("stageFlags"), ADDRESS.withName("pImmutableSamplers")).withName("VkDescriptorSetLayoutBinding");
    private static final GroupLayout LAYOUT_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("bindingCount"), ADDRESS.withName("pBindings")
    ).withName("VkDescriptorSetLayoutCreateInfo");
    private static final GroupLayout POOL_SIZE = MemoryLayout.structLayout(
            JAVA_INT.withName("type"), JAVA_INT.withName("descriptorCount")).withName("VkDescriptorPoolSize");
    private static final GroupLayout POOL_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"), JAVA_INT.withName("maxSets"), JAVA_INT.withName("poolSizeCount"),
            MemoryLayout.paddingLayout(4), ADDRESS.withName("pPoolSizes")).withName("VkDescriptorPoolCreateInfo");
    private static final GroupLayout SET_ALLOCATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("descriptorPool"), JAVA_INT.withName("descriptorSetCount"), MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pSetLayouts")).withName("VkDescriptorSetAllocateInfo");
    private static final GroupLayout IMAGE_INFO = MemoryLayout.structLayout(
            JAVA_LONG.withName("sampler"), JAVA_LONG.withName("imageView"), JAVA_INT.withName("imageLayout"),
            MemoryLayout.paddingLayout(4)).withName("VkDescriptorImageInfo");
    private static final GroupLayout BUFFER_INFO = MemoryLayout.structLayout(
            JAVA_LONG.withName("buffer"), JAVA_LONG.withName("offset"), JAVA_LONG.withName("range"))
            .withName("VkDescriptorBufferInfo");
    private static final GroupLayout WRITE = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"), MemoryLayout.paddingLayout(4), ADDRESS.withName("pNext"),
            JAVA_LONG.withName("dstSet"), JAVA_INT.withName("dstBinding"), JAVA_INT.withName("dstArrayElement"),
            JAVA_INT.withName("descriptorCount"), JAVA_INT.withName("descriptorType"), ADDRESS.withName("pImageInfo"),
            ADDRESS.withName("pBufferInfo"), ADDRESS.withName("pTexelBufferView")).withName("VkWriteDescriptorSet");

    private final VulkanDevice device;
    private final List<TechniqueBinding> declared;
    private final long layout;
    private final long pool;
    private final long[] sets = new long[RING];
    private final Arena arena = Arena.ofShared();
    private final MemorySegment writes;
    private final MemorySegment images;
    private final MemorySegment buffers;
    private final boolean[] bound;
    private final MethodHandle vkUpdateDescriptorSets;
    private final MethodHandle vkDestroyDescriptorPool;
    private final MethodHandle vkDestroyDescriptorSetLayout;
    private int frame;
    private boolean closed;

    /** The layout and ring for {@code declared}, which is not empty, on {@code device}. */
    public TechniqueBindings(VulkanDevice device, List<TechniqueBinding> declared) {
        if (declared.isEmpty()) {
            throw new IllegalArgumentException("a technique that declares no bindings needs none bound");
        }
        this.device = device;
        this.declared = List.copyOf(declared);
        int n = declared.size();
        this.bound = new boolean[n];
        MemorySegment dev = device.handle();
        MethodHandle vkCreateDescriptorSetLayout = device.command("vkCreateDescriptorSetLayout", C4);
        MethodHandle vkCreateDescriptorPool = device.command("vkCreateDescriptorPool", C4);
        MethodHandle vkAllocateDescriptorSets = device.command("vkAllocateDescriptorSets",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        this.vkUpdateDescriptorSets = device.command("vkUpdateDescriptorSets",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
        this.vkDestroyDescriptorPool = device.command("vkDestroyDescriptorPool", D_LONG);
        this.vkDestroyDescriptorSetLayout = device.command("vkDestroyDescriptorSetLayout", D_LONG);
        int buffersDeclared = 0;
        try (Arena a = Arena.ofConfined()) {
            MemorySegment bindings = a.allocate(LAYOUT_BINDING, n);
            for (int i = 0; i < n; i++) {
                MemorySegment b = slice(bindings, LAYOUT_BINDING, i);
                boolean buffer = declared.get(i).kind() == TechniqueBinding.Kind.STORAGE_BUFFER;
                buffersDeclared += buffer ? 1 : 0;
                si(b, LAYOUT_BINDING, "binding", i);
                si(b, LAYOUT_BINDING, "descriptorType", type(declared.get(i)));
                si(b, LAYOUT_BINDING, "descriptorCount", 1);
                si(b, LAYOUT_BINDING, "stageFlags", buffer
                        ? Vk.SHADER_STAGE_VERTEX_BIT | Vk.SHADER_STAGE_FRAGMENT_BIT : Vk.SHADER_STAGE_FRAGMENT_BIT);
            }
            MemorySegment info = a.allocate(LAYOUT_CREATE_INFO);
            si(info, LAYOUT_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO);
            si(info, LAYOUT_CREATE_INFO, "bindingCount", n);
            sa(info, LAYOUT_CREATE_INFO, "pBindings", bindings);
            MemorySegment pLayout = a.allocate(JAVA_LONG);
            check(invoke(vkCreateDescriptorSetLayout, dev, info, MemorySegment.NULL, pLayout),
                    "vkCreateDescriptorSetLayout");
            this.layout = pLayout.get(JAVA_LONG, 0);

            int pictures = n - buffersDeclared;
            MemorySegment sizes = a.allocate(POOL_SIZE, 2);
            int kinds = 0;
            if (buffersDeclared > 0) {
                MemorySegment s = slice(sizes, POOL_SIZE, kinds++);
                si(s, POOL_SIZE, "type", DESCRIPTOR_TYPE_STORAGE_BUFFER);
                si(s, POOL_SIZE, "descriptorCount", buffersDeclared * RING);
            }
            if (pictures > 0) {
                MemorySegment s = slice(sizes, POOL_SIZE, kinds++);
                si(s, POOL_SIZE, "type", Vk.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);
                si(s, POOL_SIZE, "descriptorCount", pictures * RING);
            }
            MemorySegment poolInfo = a.allocate(POOL_CREATE_INFO);
            si(poolInfo, POOL_CREATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO);
            si(poolInfo, POOL_CREATE_INFO, "maxSets", RING);
            si(poolInfo, POOL_CREATE_INFO, "poolSizeCount", kinds);
            sa(poolInfo, POOL_CREATE_INFO, "pPoolSizes", sizes);
            MemorySegment pPool = a.allocate(JAVA_LONG);
            check(invoke(vkCreateDescriptorPool, dev, poolInfo, MemorySegment.NULL, pPool), "vkCreateDescriptorPool");
            this.pool = pPool.get(JAVA_LONG, 0);

            MemorySegment layouts = a.allocate(JAVA_LONG, RING);
            for (int i = 0; i < RING; i++) {
                layouts.setAtIndex(JAVA_LONG, i, layout);
            }
            MemorySegment alloc = a.allocate(SET_ALLOCATE_INFO);
            si(alloc, SET_ALLOCATE_INFO, "sType", Vk.STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO);
            sl(alloc, SET_ALLOCATE_INFO, "descriptorPool", pool);
            si(alloc, SET_ALLOCATE_INFO, "descriptorSetCount", RING);
            sa(alloc, SET_ALLOCATE_INFO, "pSetLayouts", layouts);
            MemorySegment pSets = a.allocate(JAVA_LONG, RING);
            check(invoke(vkAllocateDescriptorSets, dev, alloc, pSets), "vkAllocateDescriptorSets");
            for (int i = 0; i < RING; i++) {
                sets[i] = pSets.getAtIndex(JAVA_LONG, i);
            }
        }
        this.writes = arena.allocate(WRITE, n);
        this.images = arena.allocate(IMAGE_INFO, n);
        this.buffers = arena.allocate(BUFFER_INFO, n);
        for (int i = 0; i < n; i++) {
            MemorySegment w = slice(writes, WRITE, i);
            si(w, WRITE, "sType", Vk.STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            si(w, WRITE, "dstBinding", i);
            si(w, WRITE, "descriptorCount", 1);
            si(w, WRITE, "descriptorType", type(declared.get(i)));
            if (declared.get(i).kind() == TechniqueBinding.Kind.STORAGE_BUFFER) {
                sa(w, WRITE, "pBufferInfo", slice(buffers, BUFFER_INFO, i));
            } else {
                sa(w, WRITE, "pImageInfo", slice(images, IMAGE_INFO, i));
                si(slice(images, IMAGE_INFO, i), IMAGE_INFO, "imageLayout", Vk.IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
        }
    }

    /** The set's layout: {@code TechniqueContext.bindingLayout()}, which the technique builds its pipeline with. */
    public long layout() {
        return layout;
    }

    /** Bind the buffer {@code name} declared to {@code buffer}, from {@code offset} for {@code range} bytes ({@link #WHOLE}). */
    public void buffer(String name, long buffer, long offset, long range) {
        int i = index(name, TechniqueBinding.Kind.STORAGE_BUFFER);
        MemorySegment b = slice(buffers, BUFFER_INFO, i);
        sl(b, BUFFER_INFO, "buffer", buffer);
        sl(b, BUFFER_INFO, "offset", offset);
        sl(b, BUFFER_INFO, "range", range);
        bound[i] = true;
    }

    /** Bind the picture {@code name} declared to {@code imageView}, sampled with {@code sampler}, in shader-read layout. */
    public void picture(String name, long imageView, long sampler) {
        int i = index(name, TechniqueBinding.Kind.SAMPLED_IMAGE);
        MemorySegment b = slice(images, IMAGE_INFO, i);
        sl(b, IMAGE_INFO, "sampler", sampler);
        sl(b, IMAGE_INFO, "imageView", imageView);
        bound[i] = true;
    }

    /**
     * The set for this frame: the next of the ring, written with what each name is bound to now. Every declared name
     * must have been bound once; one that never was is refused by name, rather than drawn from nothing. Allocates
     * nothing.
     */
    public long next() {
        for (int i = 0; i < bound.length; i++) {
            if (!bound[i]) {
                throw new IllegalStateException("the technique's binding '" + declared.get(i).name()
                        + "' was never bound; the host binds every name the technique declares before it records");
            }
        }
        long set = sets[frame];
        frame = (frame + 1) % RING;
        for (int i = 0; i < bound.length; i++) {
            sl(slice(writes, WRITE, i), WRITE, "dstSet", set);
        }
        invokeVoid(vkUpdateDescriptorSets, device.handle(), bound.length, writes, 0, MemorySegment.NULL);
        return set;
    }

    /** Free the sets and the layout; after the frames that used them, as everything the host frees. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        invokeVoid(vkDestroyDescriptorPool, device.handle(), pool, MemorySegment.NULL);
        invokeVoid(vkDestroyDescriptorSetLayout, device.handle(), layout, MemorySegment.NULL);
        arena.close();
    }

    private int index(String name, TechniqueBinding.Kind kind) {
        for (int i = 0; i < declared.size(); i++) {
            TechniqueBinding b = declared.get(i);
            if (b.name().equals(name)) {
                if (b.kind() != kind) {
                    throw new IllegalArgumentException("'" + name + "' is declared a " + b.kind() + ", not a " + kind);
                }
                return i;
            }
        }
        throw new IllegalArgumentException("the technique declares no binding '" + name + "'; it declares "
                + declared.stream().map(TechniqueBinding::name).toList());
    }

    private static int type(TechniqueBinding b) {
        return b.kind() == TechniqueBinding.Kind.STORAGE_BUFFER
                ? DESCRIPTOR_TYPE_STORAGE_BUFFER : Vk.DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    }

    private static MemorySegment slice(MemorySegment array, GroupLayout layout, int i) {
        return array.asSlice(i * layout.byteSize(), layout.byteSize());
    }
}
