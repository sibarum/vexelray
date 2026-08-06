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
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * A live {@code VkInstance}, created through the hand-rolled Vulkan loader ({@link VkLoader}). Owns the instance
 * handle and the instance-level commands VexelRay uses, and tears the instance down on {@link #close()}.
 *
 * <p>This is the first concrete Vulkan object in the engine — proof that the direct-Panama path reaches real
 * Vulkan. Device, queues, and swapchain build on top of it in subsequent steps.
 */
public final class VulkanInstance implements AutoCloseable {

    private static final int VK_STRUCTURE_TYPE_APPLICATION_INFO = 0;
    private static final int VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO = 1;
    private static final int VK_SUCCESS = 0;
    private static final int VK_API_VERSION_1_3 = (1 << 22) | (3 << 12);
    private static final int VK_MAX_PHYSICAL_DEVICE_NAME_SIZE = 256;

    private static final GroupLayout APPLICATION_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            ADDRESS.withName("pApplicationName"),
            JAVA_INT.withName("applicationVersion"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pEngineName"),
            JAVA_INT.withName("engineVersion"),
            JAVA_INT.withName("apiVersion")
    ).withName("VkApplicationInfo");

    private static final GroupLayout INSTANCE_CREATE_INFO = MemoryLayout.structLayout(
            JAVA_INT.withName("sType"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pNext"),
            JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("pApplicationInfo"),
            JAVA_INT.withName("enabledLayerCount"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("ppEnabledLayerNames"),
            JAVA_INT.withName("enabledExtensionCount"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("ppEnabledExtensionNames")
    ).withName("VkInstanceCreateInfo");

    /** Leading fields of VkPhysicalDeviceProperties, padded past the full struct so Vulkan never writes OOB. */
    private static final GroupLayout PHYSICAL_DEVICE_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("apiVersion"),
            JAVA_INT.withName("driverVersion"),
            JAVA_INT.withName("vendorID"),
            JAVA_INT.withName("deviceID"),
            JAVA_INT.withName("deviceType"),
            MemoryLayout.sequenceLayout(VK_MAX_PHYSICAL_DEVICE_NAME_SIZE, JAVA_BYTE).withName("deviceName"),
            MemoryLayout.paddingLayout(1024 - 20 - VK_MAX_PHYSICAL_DEVICE_NAME_SIZE)
    ).withName("VkPhysicalDeviceProperties");

    private static final long DEVICE_NAME_OFFSET =
            PHYSICAL_DEVICE_PROPERTIES.byteOffset(MemoryLayout.PathElement.groupElement("deviceName"));

    private static final VarHandle AI_sType = Ffi.field(APPLICATION_INFO, "sType");
    private static final VarHandle AI_pApplicationName = Ffi.field(APPLICATION_INFO, "pApplicationName");
    private static final VarHandle AI_apiVersion = Ffi.field(APPLICATION_INFO, "apiVersion");
    private static final VarHandle AI_pEngineName = Ffi.field(APPLICATION_INFO, "pEngineName");

    private static final VarHandle CI_sType = Ffi.field(INSTANCE_CREATE_INFO, "sType");
    private static final VarHandle CI_pApplicationInfo = Ffi.field(INSTANCE_CREATE_INFO, "pApplicationInfo");
    private static final VarHandle CI_enabledExtensionCount = Ffi.field(INSTANCE_CREATE_INFO, "enabledExtensionCount");
    private static final VarHandle CI_ppEnabledExtensionNames = Ffi.field(INSTANCE_CREATE_INFO, "ppEnabledExtensionNames");

    private final MemorySegment handle;
    private final MethodHandle vkEnumeratePhysicalDevices;
    private final MethodHandle vkGetPhysicalDeviceProperties;
    private final MethodHandle vkDestroyInstance;

    public VulkanInstance(String applicationName, List<String> extensions) {
        MethodHandle vkCreateInstance = VkLoader.globalCommand("vkCreateInstance",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));

        try (Arena temp = Arena.ofConfined()) {
            MemorySegment appInfo = temp.allocate(APPLICATION_INFO);
            AI_sType.set(appInfo, VK_STRUCTURE_TYPE_APPLICATION_INFO);
            AI_pApplicationName.set(appInfo, temp.allocateFrom(applicationName));
            AI_pEngineName.set(appInfo, temp.allocateFrom("VexelRay"));
            AI_apiVersion.set(appInfo, VK_API_VERSION_1_3);

            MemorySegment extArray = temp.allocate(ADDRESS, extensions.size());
            for (int i = 0; i < extensions.size(); i++) {
                extArray.setAtIndex(ADDRESS, i, temp.allocateFrom(extensions.get(i)));
            }

            MemorySegment createInfo = temp.allocate(INSTANCE_CREATE_INFO);
            CI_sType.set(createInfo, VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            CI_pApplicationInfo.set(createInfo, appInfo);
            CI_enabledExtensionCount.set(createInfo, extensions.size());
            CI_ppEnabledExtensionNames.set(createInfo, extArray);

            MemorySegment pInstance = temp.allocate(ADDRESS);
            int result;
            try {
                result = (int) vkCreateInstance.invokeExact(createInfo, MemorySegment.NULL, pInstance);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkCreateInstance", t);
            }
            if (result != VK_SUCCESS) {
                throw new NativeException("vkCreateInstance failed: VkResult " + result);
            }
            this.handle = pInstance.get(ADDRESS, 0);
        }

        this.vkEnumeratePhysicalDevices = VkLoader.instanceCommand(handle, "vkEnumeratePhysicalDevices",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
        this.vkGetPhysicalDeviceProperties = VkLoader.instanceCommand(handle, "vkGetPhysicalDeviceProperties",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        this.vkDestroyInstance = VkLoader.instanceCommand(handle, "vkDestroyInstance",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    }

    /** The {@code VkInstance} handle. */
    public MemorySegment handle() {
        return handle;
    }

    /** The raw {@code VkInstance} address — for passing to {@code NativeWindow.createVulkanSurface}. */
    public long handleAddress() {
        return handle.address();
    }

    /** The reported name of every physical device visible to this instance. */
    public List<String> physicalDeviceNames() {
        List<String> names = new ArrayList<>();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pCount = temp.allocate(JAVA_INT);
            check(enumerate(pCount, MemorySegment.NULL), "vkEnumeratePhysicalDevices(count)");
            int count = pCount.get(JAVA_INT, 0);
            if (count == 0) {
                return names;
            }

            MemorySegment pDevices = temp.allocate(ADDRESS, count);
            check(enumerate(pCount, pDevices), "vkEnumeratePhysicalDevices(list)");

            MemorySegment props = temp.allocate(PHYSICAL_DEVICE_PROPERTIES);
            for (int i = 0; i < count; i++) {
                MemorySegment device = pDevices.getAtIndex(ADDRESS, i);
                try {
                    vkGetPhysicalDeviceProperties.invokeExact(device, props);
                } catch (Throwable t) {
                    throw NativeException.rethrow("vkGetPhysicalDeviceProperties", t);
                }
                names.add(props.getString(DEVICE_NAME_OFFSET));
            }
        }
        return names;
    }

    private int enumerate(MemorySegment pCount, MemorySegment pDevices) {
        try {
            return (int) vkEnumeratePhysicalDevices.invokeExact(handle, pCount, pDevices);
        } catch (Throwable t) {
            throw NativeException.rethrow("vkEnumeratePhysicalDevices", t);
        }
    }

    private static void check(int result, String call) {
        if (result != VK_SUCCESS) {
            throw new NativeException(call + " failed: VkResult " + result);
        }
    }

    @Override
    public void close() {
        try {
            vkDestroyInstance.invokeExact(handle, MemorySegment.NULL);
        } catch (Throwable t) {
            throw NativeException.rethrow("vkDestroyInstance", t);
        }
    }
}
