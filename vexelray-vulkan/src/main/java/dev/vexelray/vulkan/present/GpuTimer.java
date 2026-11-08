package dev.vexelray.vulkan.present;

import dev.supirvast.vulkan.VkLoader;
import dev.supirvast.vulkan.VulkanDevice;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.invoke.MethodHandle;

import static dev.supirvast.vulkan.Ffm.check;
import static dev.supirvast.vulkan.Ffm.invoke;
import static dev.supirvast.vulkan.Ffm.invokeVoid;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * How long the GPU took over a stretch of one command buffer: a timestamp written at its start (when everything before
 * it has begun) and at its end (when everything in it has finished), read once the submission is done, in the device's
 * own ticks times its {@code timestampPeriod}. What the format comparison times the GPU's drawing by; the CPU's clock
 * cannot, since recording a frame and the GPU drawing it are apart.
 *
 * <p>Not thread-safe; one stretch at a time: {@link #begin}, {@link #end}, submit and wait, {@link #millis}.
 */
public final class GpuTimer implements AutoCloseable {

    // VkPhysicalDeviceProperties: limits at 296 (five uint32, 256 chars of name, 16 bytes of UUID, aligned to 8);
    // within VkPhysicalDeviceLimits, maxImageDimension2D at 4, timestampComputeAndGraphics at 420, timestampPeriod at
    // 424. Read and checked against what they must be, so a wrong offset fails here rather than timing nonsense.
    private static final long LIMITS = 296, MAX_IMAGE_2D = LIMITS + 4, TIMESTAMP_ALL = LIMITS + 420,
            TIMESTAMP_PERIOD = LIMITS + 424, PROPERTIES_BYTES = 1024;

    private static final int QUERY_POOL_CREATE_INFO = 11, QUERY_TYPE_TIMESTAMP = 2;
    private static final int RESULT_64 = 1, RESULT_WAIT = 2;
    private static final int TOP_OF_PIPE = 0x1, BOTTOM_OF_PIPE = 0x2000;

    private final VulkanDevice device;
    private final Arena arena = Arena.ofShared();
    private final MethodHandle reset, write, results, destroy;
    private final long pool;
    private final double nanosPerTick;
    private final MemorySegment ticks;

    /** A timer on {@code device}, of {@code instance}; refused if the device cannot time its graphics queue. */
    public GpuTimer(MemorySegment instance, VulkanDevice device) {
        this.device = device;
        MethodHandle props = VkLoader.instanceCommand(instance, "vkGetPhysicalDeviceProperties",
                FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
        MemorySegment p = arena.allocate(PROPERTIES_BYTES, 8);
        invokeVoid(props, device.physicalDevice(), p);
        int max2d = p.get(JAVA_INT, MAX_IMAGE_2D);
        if (max2d < 4096 || max2d > (1 << 20)) {
            throw new IllegalStateException("device properties read wrong: maxImageDimension2D " + max2d);
        }
        if (p.get(JAVA_INT, TIMESTAMP_ALL) == 0) {
            throw new UnsupportedOperationException("this device does not time its graphics and compute queues");
        }
        this.nanosPerTick = p.get(JAVA_FLOAT, TIMESTAMP_PERIOD);
        if (!(nanosPerTick > 0)) {
            throw new IllegalStateException("a timestamp period of " + nanosPerTick + " ns");
        }
        MethodHandle create = device.command("vkCreateQueryPool",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        this.destroy = device.command("vkDestroyQueryPool", FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, ADDRESS));
        this.reset = device.command("vkCmdResetQueryPool",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_LONG, JAVA_INT, JAVA_INT));
        this.write = device.command("vkCmdWriteTimestamp",
                FunctionDescriptor.ofVoid(ADDRESS, JAVA_INT, JAVA_LONG, JAVA_INT));
        this.results = device.command("vkGetQueryPoolResults", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG,
                JAVA_INT, JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, JAVA_INT));
        // VkQueryPoolCreateInfo: sType, pNext, flags, queryType, queryCount, pipelineStatistics
        MemorySegment info = arena.allocate(32, 8);
        info.set(JAVA_INT, 0, QUERY_POOL_CREATE_INFO);
        info.set(JAVA_INT, 20, QUERY_TYPE_TIMESTAMP);
        info.set(JAVA_INT, 24, 2);
        MemorySegment pPool = arena.allocate(JAVA_LONG);
        check(invoke(create, device.handle(), info, MemorySegment.NULL, pPool), "vkCreateQueryPool");
        this.pool = pPool.get(JAVA_LONG, 0);
        this.ticks = arena.allocate(16, 8);
    }

    /** The stretch starts here, in {@code cmd}, outside any render pass. */
    public void begin(MemorySegment cmd) {
        invokeVoid(reset, cmd, pool, 0, 2);
        invokeVoid(write, cmd, TOP_OF_PIPE, pool, 0);
    }

    /** The stretch ends here, in {@code cmd}, outside any render pass. */
    public void end(MemorySegment cmd) {
        invokeVoid(write, cmd, BOTTOM_OF_PIPE, pool, 1);
    }

    /** The GPU's milliseconds between {@link #begin} and {@link #end}, once their submission is done; waits if not. */
    public double millis() {
        check(invoke(results, device.handle(), pool, 0, 2, 16L, ticks, 8L, RESULT_64 | RESULT_WAIT),
                "vkGetQueryPoolResults");
        long t0 = ticks.get(JAVA_LONG, 0), t1 = ticks.get(JAVA_LONG, 8);
        return (t1 - t0) * nanosPerTick / 1e6;
    }

    /** Nanoseconds a tick, the device's {@code timestampPeriod}. */
    public double nanosPerTick() {
        return nanosPerTick;
    }

    @Override
    public void close() {
        invokeVoid(destroy, device.handle(), pool, MemorySegment.NULL);
        arena.close();
    }
}
