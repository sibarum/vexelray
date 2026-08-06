package dev.vexelray.vulkan.vk;

import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A logical {@code VkDevice} over a chosen {@link VulkanInstance.DeviceSelection}, with one graphics+present
 * queue and the {@code VK_KHR_swapchain} device extension enabled. Device-level commands are resolved through
 * {@code vkGetDeviceProcAddr} — {@link #command} exposes that resolver so higher-level Vulkan objects (images,
 * command buffers, the swapchain) bind exactly the commands they need. Destroys the device on {@link #close()}.
 */
public final class VulkanDevice implements AutoCloseable {

    private static final int VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO = 2;
    private static final int VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO = 3;
    private static final int VK_SUCCESS = 0;

    private static final GroupLayout DEVICE_QUEUE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("queueFamilyIndex"),
            JAVA_INT.withName("queueCount"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pQueuePriorities")
    ).withName("VkDeviceQueueCreateInfo");

    private static final GroupLayout DEVICE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            JAVA_INT.withName("queueCreateInfoCount"),
            ADDRESS.withName("pQueueCreateInfos"),
            JAVA_INT.withName("enabledLayerCount"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("ppEnabledLayerNames"),
            JAVA_INT.withName("enabledExtensionCount"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("ppEnabledExtensionNames"),
            ADDRESS.withName("pEnabledFeatures")
    ).withName("VkDeviceCreateInfo");

    private static final GroupLayout MEMORY_TYPE = MemoryLayout.structLayout(
            JAVA_INT.withName("propertyFlags"), JAVA_INT.withName("heapIndex"));
    private static final GroupLayout MEMORY_HEAP = MemoryLayout.structLayout(
            JAVA_LONG.withName("size"), JAVA_INT.withName("flags"), MemoryLayout.paddingLayout(4));
    private static final GroupLayout MEMORY_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("memoryTypeCount"),
            MemoryLayout.sequenceLayout(32, MEMORY_TYPE).withName("memoryTypes"),
            JAVA_INT.withName("memoryHeapCount"),
            MemoryLayout.sequenceLayout(16, MEMORY_HEAP).withName("memoryHeaps")
    ).withName("VkPhysicalDeviceMemoryProperties");
    private static final long MEMORY_TYPES_OFFSET =
            MEMORY_PROPERTIES.byteOffset(PathElement.groupElement("memoryTypes"));
    private static final long MEMORY_TYPE_STRIDE = MEMORY_TYPE.byteSize();

    private static final VarHandle QCI_sType = Ffi.field(DEVICE_QUEUE_CREATE_INFO, "sType");
    private static final VarHandle QCI_queueFamilyIndex = Ffi.field(DEVICE_QUEUE_CREATE_INFO, "queueFamilyIndex");
    private static final VarHandle QCI_queueCount = Ffi.field(DEVICE_QUEUE_CREATE_INFO, "queueCount");
    private static final VarHandle QCI_pQueuePriorities = Ffi.field(DEVICE_QUEUE_CREATE_INFO, "pQueuePriorities");

    private static final VarHandle DCI_sType = Ffi.field(DEVICE_CREATE_INFO, "sType");
    private static final VarHandle DCI_queueCreateInfoCount = Ffi.field(DEVICE_CREATE_INFO, "queueCreateInfoCount");
    private static final VarHandle DCI_pQueueCreateInfos = Ffi.field(DEVICE_CREATE_INFO, "pQueueCreateInfos");
    private static final VarHandle DCI_enabledExtensionCount = Ffi.field(DEVICE_CREATE_INFO, "enabledExtensionCount");
    private static final VarHandle DCI_ppEnabledExtensionNames = Ffi.field(DEVICE_CREATE_INFO, "ppEnabledExtensionNames");

    private static final VarHandle MP_memoryTypeCount = Ffi.field(MEMORY_PROPERTIES, "memoryTypeCount");

    private final MemorySegment handle;
    private final MemorySegment physicalDevice;
    private final MemorySegment queue;
    private final int queueFamilyIndex;
    private final MethodHandle vkGetDeviceProcAddr;
    private final MethodHandle vkGetPhysicalDeviceMemoryProperties;
    private final MethodHandle vkDeviceWaitIdle;
    private final MethodHandle vkDestroyDevice;

    public VulkanDevice(MemorySegment instance, VulkanInstance.DeviceSelection selection) {
        this.physicalDevice = selection.physicalDevice();
        this.queueFamilyIndex = selection.queueFamilyIndex();

        MethodHandle vkCreateDevice = VkLoader.instanceCommand(instance, "vkCreateDevice",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.vkGetDeviceProcAddr = VkLoader.instanceCommand(instance, "vkGetDeviceProcAddr",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));
        this.vkGetPhysicalDeviceMemoryProperties = VkLoader.instanceCommand(instance,
                "vkGetPhysicalDeviceMemoryProperties", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment queueInfo = temp.allocate(DEVICE_QUEUE_CREATE_INFO);
            QCI_sType.set(queueInfo, VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
            QCI_queueFamilyIndex.set(queueInfo, queueFamilyIndex);
            QCI_queueCount.set(queueInfo, 1);
            QCI_pQueuePriorities.set(queueInfo, temp.allocateFrom(JAVA_FLOAT, 1.0f));

            MemorySegment extArray = temp.allocate(ADDRESS, 1);
            extArray.setAtIndex(ADDRESS, 0, temp.allocateFrom("VK_KHR_swapchain"));

            MemorySegment createInfo = temp.allocate(DEVICE_CREATE_INFO);
            DCI_sType.set(createInfo, VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            DCI_queueCreateInfoCount.set(createInfo, 1);
            DCI_pQueueCreateInfos.set(createInfo, queueInfo);
            DCI_enabledExtensionCount.set(createInfo, 1);
            DCI_ppEnabledExtensionNames.set(createInfo, extArray);

            MemorySegment pDevice = temp.allocate(ADDRESS);
            int result;
            try {
                result = (int) vkCreateDevice.invokeExact(selection.physicalDevice(), createInfo,
                        MemorySegment.NULL, pDevice);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkCreateDevice", t);
            }
            if (result != VK_SUCCESS) {
                throw new NativeException("vkCreateDevice failed: VkResult " + result);
            }
            this.handle = pDevice.get(ADDRESS, 0);
        }

        MethodHandle vkGetDeviceQueue = command("vkGetDeviceQueue",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        this.vkDeviceWaitIdle = command("vkDeviceWaitIdle", FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.vkDestroyDevice = command("vkDestroyDevice", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pQueue = temp.allocate(ADDRESS);
            try {
                vkGetDeviceQueue.invokeExact(handle, queueFamilyIndex, 0, pQueue);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkGetDeviceQueue", t);
            }
            this.queue = pQueue.get(ADDRESS, 0);
        }
    }

    /** Resolve and bind a device-level command through {@code vkGetDeviceProcAddr}. Callers cache the result. */
    public MethodHandle command(String name, FunctionDescriptor descriptor) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cName = temp.allocateFrom(name);
            MemorySegment fn = (MemorySegment) vkGetDeviceProcAddr.invokeExact(handle, cName);
            if (fn.equals(MemorySegment.NULL)) {
                throw new NativeException("vkGetDeviceProcAddr returned NULL for " + name);
            }
            return Ffi.downcall(fn, descriptor);
        } catch (Throwable t) {
            throw NativeException.rethrow("vkGetDeviceProcAddr(" + name + ")", t);
        }
    }

    /**
     * The index of a memory type present in {@code typeBits} (from a {@code VkMemoryRequirements}) that has all of
     * {@code requiredProperties} (VK_MEMORY_PROPERTY_* flags). Throws if none qualifies.
     */
    public int findMemoryType(int typeBits, int requiredProperties) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment props = temp.allocate(MEMORY_PROPERTIES);
            try {
                vkGetPhysicalDeviceMemoryProperties.invokeExact(physicalDevice, props);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkGetPhysicalDeviceMemoryProperties", t);
            }
            int count = (int) MP_memoryTypeCount.get(props);
            for (int i = 0; i < count; i++) {
                int flags = props.get(JAVA_INT, MEMORY_TYPES_OFFSET + (long) i * MEMORY_TYPE_STRIDE);
                if ((typeBits & (1 << i)) != 0 && (flags & requiredProperties) == requiredProperties) {
                    return i;
                }
            }
        }
        throw new NativeException("no memory type for typeBits=" + typeBits + " properties=" + requiredProperties);
    }

    public MemorySegment handle() {
        return handle;
    }

    public MemorySegment physicalDevice() {
        return physicalDevice;
    }

    public MemorySegment queue() {
        return queue;
    }

    public int queueFamilyIndex() {
        return queueFamilyIndex;
    }

    /** Block until the device has finished all submitted work. */
    public void waitIdle() {
        try {
            int r = (int) vkDeviceWaitIdle.invokeExact(handle);
            if (r != VK_SUCCESS) {
                throw new NativeException("vkDeviceWaitIdle failed: VkResult " + r);
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("vkDeviceWaitIdle", t);
        }
    }

    @Override
    public void close() {
        try {
            vkDestroyDevice.invokeExact(handle, MemorySegment.NULL);
        } catch (Throwable t) {
            throw NativeException.rethrow("vkDestroyDevice", t);
        }
    }
}
