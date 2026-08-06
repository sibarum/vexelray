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
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A live {@code VkInstance}, created through the hand-rolled Vulkan loader ({@link VkLoader}). Owns the instance
 * handle and the instance-level commands VexelRay uses — physical-device enumeration, queue-family and
 * surface-support queries used to pick a device, and surface teardown — and destroys the instance on
 * {@link #close()}.
 */
public final class VulkanInstance implements AutoCloseable {

    private static final int VK_STRUCTURE_TYPE_APPLICATION_INFO = 0;
    private static final int VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO = 1;
    private static final int VK_SUCCESS = 0;
    private static final int VK_TRUE = 1;
    private static final int VK_API_VERSION_1_3 = (1 << 22) | (3 << 12);
    private static final int VK_MAX_PHYSICAL_DEVICE_NAME_SIZE = 256;
    private static final int VK_QUEUE_GRAPHICS_BIT = 0x0001;
    private static final int VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU = 2;

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

    /** VkQueueFamilyProperties — 24 bytes; only queueFlags is read here. */
    private static final GroupLayout QUEUE_FAMILY_PROPERTIES = MemoryLayout.structLayout(
            JAVA_INT.withName("queueFlags"),
            JAVA_INT.withName("queueCount"),
            JAVA_INT.withName("timestampValidBits"),
            JAVA_INT.withName("granularity_width"),
            JAVA_INT.withName("granularity_height"),
            JAVA_INT.withName("granularity_depth")
    ).withName("VkQueueFamilyProperties");

    private static final long DEVICE_NAME_OFFSET =
            PHYSICAL_DEVICE_PROPERTIES.byteOffset(MemoryLayout.PathElement.groupElement("deviceName"));
    private static final long QFP_STRIDE = QUEUE_FAMILY_PROPERTIES.byteSize();

    private static final VarHandle AI_sType = Ffi.field(APPLICATION_INFO, "sType");
    private static final VarHandle AI_pApplicationName = Ffi.field(APPLICATION_INFO, "pApplicationName");
    private static final VarHandle AI_apiVersion = Ffi.field(APPLICATION_INFO, "apiVersion");
    private static final VarHandle AI_pEngineName = Ffi.field(APPLICATION_INFO, "pEngineName");

    private static final VarHandle CI_sType = Ffi.field(INSTANCE_CREATE_INFO, "sType");
    private static final VarHandle CI_pApplicationInfo = Ffi.field(INSTANCE_CREATE_INFO, "pApplicationInfo");
    private static final VarHandle CI_enabledExtensionCount = Ffi.field(INSTANCE_CREATE_INFO, "enabledExtensionCount");
    private static final VarHandle CI_ppEnabledExtensionNames = Ffi.field(INSTANCE_CREATE_INFO, "ppEnabledExtensionNames");

    private static final VarHandle PDP_deviceType = Ffi.field(PHYSICAL_DEVICE_PROPERTIES, "deviceType");
    private static final VarHandle QFP_queueFlags = Ffi.field(QUEUE_FAMILY_PROPERTIES, "queueFlags");

    /** A chosen physical device plus a queue family that supports both graphics and presentation to a surface. */
    public record DeviceSelection(MemorySegment physicalDevice, int queueFamilyIndex, String deviceName) {
    }

    private final MemorySegment handle;
    private final MethodHandle vkEnumeratePhysicalDevices;
    private final MethodHandle vkGetPhysicalDeviceProperties;
    private final MethodHandle vkGetPhysicalDeviceQueueFamilyProperties;
    private final MethodHandle vkGetPhysicalDeviceSurfaceSupportKHR;
    private final MethodHandle vkDestroySurfaceKHR;
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
        this.vkGetPhysicalDeviceQueueFamilyProperties = VkLoader.instanceCommand(handle,
                "vkGetPhysicalDeviceQueueFamilyProperties", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
        this.vkGetPhysicalDeviceSurfaceSupportKHR = VkLoader.instanceCommand(handle,
                "vkGetPhysicalDeviceSurfaceSupportKHR",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_LONG, ADDRESS));
        this.vkDestroySurfaceKHR = VkLoader.instanceCommand(handle, "vkDestroySurfaceKHR",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
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

    /** Handles of every physical device visible to this instance. */
    public List<MemorySegment> physicalDevices() {
        List<MemorySegment> devices = new ArrayList<>();
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pCount = temp.allocate(JAVA_INT);
            check(enumerate(pCount, MemorySegment.NULL), "vkEnumeratePhysicalDevices(count)");
            int count = pCount.get(JAVA_INT, 0);
            if (count == 0) {
                return devices;
            }
            MemorySegment pDevices = temp.allocate(ADDRESS, count);
            check(enumerate(pCount, pDevices), "vkEnumeratePhysicalDevices(list)");
            for (int i = 0; i < count; i++) {
                devices.add(pDevices.getAtIndex(ADDRESS, i));
            }
        }
        return devices;
    }

    /** The reported name of every physical device (convenience over {@link #physicalDevices()}). */
    public List<String> physicalDeviceNames() {
        List<String> names = new ArrayList<>();
        for (MemorySegment device : physicalDevices()) {
            names.add(deviceName(device));
        }
        return names;
    }

    /**
     * Pick a device and a queue family that supports both graphics and presentation to {@code surface}. Prefers a
     * discrete GPU when one qualifies; otherwise the first that does. Empty if none can present to the surface.
     */
    public Optional<DeviceSelection> selectGraphicsPresentDevice(long surface) {
        DeviceSelection firstMatch = null;
        for (MemorySegment device : physicalDevices()) {
            int family = graphicsPresentQueueFamily(device, surface);
            if (family < 0) {
                continue;
            }
            DeviceSelection selection = new DeviceSelection(device, family, deviceName(device));
            if (deviceType(device) == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
                return Optional.of(selection);
            }
            if (firstMatch == null) {
                firstMatch = selection;
            }
        }
        return Optional.ofNullable(firstMatch);
    }

    /** Destroy a surface created for this instance. */
    public void destroySurface(long surface) {
        try {
            vkDestroySurfaceKHR.invokeExact(handle, surface, MemorySegment.NULL);
        } catch (Throwable t) {
            throw NativeException.rethrow("vkDestroySurfaceKHR", t);
        }
    }

    private int graphicsPresentQueueFamily(MemorySegment device, long surface) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment pCount = temp.allocate(JAVA_INT);
            try {
                vkGetPhysicalDeviceQueueFamilyProperties.invokeExact(device, pCount, MemorySegment.NULL);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkGetPhysicalDeviceQueueFamilyProperties", t);
            }
            int count = pCount.get(JAVA_INT, 0);
            MemorySegment props = temp.allocate(QUEUE_FAMILY_PROPERTIES, count);
            try {
                vkGetPhysicalDeviceQueueFamilyProperties.invokeExact(device, pCount, props);
            } catch (Throwable t) {
                throw NativeException.rethrow("vkGetPhysicalDeviceQueueFamilyProperties", t);
            }
            MemorySegment pSupported = temp.allocate(JAVA_INT);
            for (int f = 0; f < count; f++) {
                int flags = (int) QFP_queueFlags.get(props.asSlice(f * QFP_STRIDE, QFP_STRIDE));
                boolean graphics = (flags & VK_QUEUE_GRAPHICS_BIT) != 0;
                if (graphics && surfaceSupported(device, f, surface, pSupported)) {
                    return f;
                }
            }
            return -1;
        }
    }

    private boolean surfaceSupported(MemorySegment device, int family, long surface, MemorySegment pSupported) {
        try {
            int r = (int) vkGetPhysicalDeviceSurfaceSupportKHR.invokeExact(device, family, surface, pSupported);
            check(r, "vkGetPhysicalDeviceSurfaceSupportKHR");
            return pSupported.get(JAVA_INT, 0) == VK_TRUE;
        } catch (Throwable t) {
            throw NativeException.rethrow("vkGetPhysicalDeviceSurfaceSupportKHR", t);
        }
    }

    private String deviceName(MemorySegment device) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment props = temp.allocate(PHYSICAL_DEVICE_PROPERTIES);
            properties(device, props);
            return props.getString(DEVICE_NAME_OFFSET);
        }
    }

    private int deviceType(MemorySegment device) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment props = temp.allocate(PHYSICAL_DEVICE_PROPERTIES);
            properties(device, props);
            return (int) PDP_deviceType.get(props);
        }
    }

    private void properties(MemorySegment device, MemorySegment props) {
        try {
            vkGetPhysicalDeviceProperties.invokeExact(device, props);
        } catch (Throwable t) {
            throw NativeException.rethrow("vkGetPhysicalDeviceProperties", t);
        }
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
