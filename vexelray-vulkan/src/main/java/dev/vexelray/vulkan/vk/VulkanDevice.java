package dev.vexelray.vulkan.vk;

import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A logical {@code VkDevice} over a chosen {@link VulkanInstance.DeviceSelection}, with one graphics+present
 * queue and the {@code VK_KHR_swapchain} device extension enabled (the swapchain step builds on it). Device-level
 * commands are resolved once through {@code vkGetDeviceProcAddr} and cached (see {@code docs/native-bindings.md}
 * §4.4). Destroys the device on {@link #close()}.
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

    private static final VarHandle QCI_sType = Ffi.field(DEVICE_QUEUE_CREATE_INFO, "sType");
    private static final VarHandle QCI_queueFamilyIndex = Ffi.field(DEVICE_QUEUE_CREATE_INFO, "queueFamilyIndex");
    private static final VarHandle QCI_queueCount = Ffi.field(DEVICE_QUEUE_CREATE_INFO, "queueCount");
    private static final VarHandle QCI_pQueuePriorities = Ffi.field(DEVICE_QUEUE_CREATE_INFO, "pQueuePriorities");

    private static final VarHandle DCI_sType = Ffi.field(DEVICE_CREATE_INFO, "sType");
    private static final VarHandle DCI_queueCreateInfoCount = Ffi.field(DEVICE_CREATE_INFO, "queueCreateInfoCount");
    private static final VarHandle DCI_pQueueCreateInfos = Ffi.field(DEVICE_CREATE_INFO, "pQueueCreateInfos");
    private static final VarHandle DCI_enabledExtensionCount = Ffi.field(DEVICE_CREATE_INFO, "enabledExtensionCount");
    private static final VarHandle DCI_ppEnabledExtensionNames = Ffi.field(DEVICE_CREATE_INFO, "ppEnabledExtensionNames");

    private final MemorySegment handle;
    private final MemorySegment queue;
    private final int queueFamilyIndex;
    private final MethodHandle vkDeviceWaitIdle;
    private final MethodHandle vkDestroyDevice;

    public VulkanDevice(MemorySegment instance, VulkanInstance.DeviceSelection selection) {
        this.queueFamilyIndex = selection.queueFamilyIndex();

        MethodHandle vkCreateDevice = VkLoader.instanceCommand(instance, "vkCreateDevice",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        MethodHandle vkGetDeviceProcAddr = VkLoader.instanceCommand(instance, "vkGetDeviceProcAddr",
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

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

        MethodHandle vkGetDeviceQueue = deviceCommand(vkGetDeviceProcAddr, handle, "vkGetDeviceQueue",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_INT, ADDRESS));
        this.vkDeviceWaitIdle = deviceCommand(vkGetDeviceProcAddr, handle, "vkDeviceWaitIdle",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        this.vkDestroyDevice = deviceCommand(vkGetDeviceProcAddr, handle, "vkDestroyDevice",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));

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

    /** Resolve a device-level command through {@code vkGetDeviceProcAddr} and bind it (cached by the caller). */
    private static MethodHandle deviceCommand(MethodHandle getDeviceProcAddr, MemorySegment device, String name,
                                              FunctionDescriptor descriptor) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment cName = temp.allocateFrom(name);
            MemorySegment fn = (MemorySegment) getDeviceProcAddr.invokeExact(device, cName);
            if (fn.equals(MemorySegment.NULL)) {
                throw new NativeException("vkGetDeviceProcAddr returned NULL for " + name);
            }
            return Ffi.downcall(fn, descriptor);
        } catch (Throwable t) {
            throw NativeException.rethrow("vkGetDeviceProcAddr(" + name + ")", t);
        }
    }

    public MemorySegment handle() {
        return handle;
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
