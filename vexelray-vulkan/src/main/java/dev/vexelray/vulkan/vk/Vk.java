package dev.vexelray.vulkan.vk;

/**
 * Vulkan enum/flag/structure-type constants used by the bindings. Grouped here so magic numbers never appear in
 * struct-filling code (the values are from the Vulkan spec; kept minimal — only what VexelRay uses so far).
 */
public final class Vk {

    private Vk() {
    }

    public static final int VK_SUCCESS = 0;
    public static final int VK_TRUE = 1;

    // Structure types
    public static final int STRUCTURE_TYPE_SUBMIT_INFO = 4;
    public static final int STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO = 5;
    public static final int STRUCTURE_TYPE_FENCE_CREATE_INFO = 8;
    public static final int STRUCTURE_TYPE_BUFFER_CREATE_INFO = 12;
    public static final int STRUCTURE_TYPE_IMAGE_CREATE_INFO = 14;
    public static final int STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO = 39;
    public static final int STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO = 40;
    public static final int STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO = 42;
    public static final int STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER = 45;

    // Formats
    public static final int FORMAT_R8G8B8A8_UNORM = 37;

    // Image
    public static final int IMAGE_TYPE_2D = 1;
    public static final int IMAGE_TILING_OPTIMAL = 0;
    public static final int SAMPLE_COUNT_1_BIT = 1;
    public static final int IMAGE_USAGE_TRANSFER_SRC_BIT = 0x0001;
    public static final int IMAGE_USAGE_TRANSFER_DST_BIT = 0x0002;
    public static final int IMAGE_USAGE_COLOR_ATTACHMENT_BIT = 0x0010;
    public static final int IMAGE_ASPECT_COLOR_BIT = 0x0001;

    // Layouts
    public static final int IMAGE_LAYOUT_UNDEFINED = 0;
    public static final int IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL = 6;
    public static final int IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL = 7;

    // Buffer
    public static final int BUFFER_USAGE_TRANSFER_DST_BIT = 0x0002;

    // Sharing
    public static final int SHARING_MODE_EXCLUSIVE = 0;

    // Memory properties
    public static final int MEMORY_PROPERTY_DEVICE_LOCAL_BIT = 0x0001;
    public static final int MEMORY_PROPERTY_HOST_VISIBLE_BIT = 0x0002;
    public static final int MEMORY_PROPERTY_HOST_COHERENT_BIT = 0x0004;

    // Command buffer
    public static final int COMMAND_BUFFER_LEVEL_PRIMARY = 0;
    public static final int COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT = 0x0001;

    // Pipeline stages / access
    public static final int PIPELINE_STAGE_TOP_OF_PIPE_BIT = 0x0001;
    public static final int PIPELINE_STAGE_TRANSFER_BIT = 0x1000;
    public static final int ACCESS_TRANSFER_READ_BIT = 0x0800;
    public static final int ACCESS_TRANSFER_WRITE_BIT = 0x1000;

    // Sentinels
    public static final int QUEUE_FAMILY_IGNORED = ~0;
    public static final long WHOLE_SIZE = ~0L;
}
