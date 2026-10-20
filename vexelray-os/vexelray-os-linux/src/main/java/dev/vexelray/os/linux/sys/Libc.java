package dev.vexelray.os.linux.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;
import static java.lang.foreign.ValueLayout.JAVA_SHORT;

/**
 * Binding for {@code libc.so.6}: the few POSIX calls a window's event wait needs — {@code poll} on the X
 * connection and a self-pipe that another thread writes to end the wait. One library, one class
 * (see {@code docs/native-bindings.md} §4).
 */
public final class Libc {

    private static final SymbolLookup LIB = Ffi.libraryFile("libc.so.6");

    /** {@code POLLIN}: there is data to read. */
    public static final short POLLIN = 0x001;

    /** {@code O_NONBLOCK} and {@code O_CLOEXEC}, for {@code pipe2}. */
    public static final int O_NONBLOCK = 0x800;
    public static final int O_CLOEXEC = 0x80000;

    /** {@code struct pollfd}. */
    public static final GroupLayout POLLFD = MemoryLayout.structLayout(
            JAVA_INT.withName("fd"),
            JAVA_SHORT.withName("events"),
            JAVA_SHORT.withName("revents")
    ).withName("pollfd");

    private static final VarHandle POLLFD_fd = MemoryLayout.sequenceLayout(2, POLLFD)
            .varHandle(MemoryLayout.PathElement.sequenceElement(), MemoryLayout.PathElement.groupElement("fd"));
    private static final VarHandle POLLFD_events = MemoryLayout.sequenceLayout(2, POLLFD)
            .varHandle(MemoryLayout.PathElement.sequenceElement(), MemoryLayout.PathElement.groupElement("events"));

    private static final MethodHandle poll = Ffi.downcall(LIB, "poll",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT));
    private static final MethodHandle pipe2 = Ffi.downcall(LIB, "pipe2",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle read = Ffi.downcall(LIB, "read",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle write = Ffi.downcall(LIB, "write",
            FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG));
    private static final MethodHandle close = Ffi.downcall(LIB, "close",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT));

    private Libc() {
    }

    /** A non-blocking, close-on-exec pipe: {@code [readEnd, writeEnd]}. */
    public static int[] pipe() {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment fds = temp.allocate(JAVA_INT, 2);
            int rc = (int) pipe2.invokeExact(fds, O_NONBLOCK | O_CLOEXEC);
            if (rc != 0) {
                throw new NativeException("pipe2 failed");
            }
            return new int[]{fds.getAtIndex(JAVA_INT, 0), fds.getAtIndex(JAVA_INT, 1)};
        } catch (Throwable t) {
            throw NativeException.rethrow("pipe2", t);
        }
    }

    /**
     * Wait until either descriptor is readable or {@code timeoutMillis} passes ({@code -1}: no limit). Returns
     * whether anything is readable; an interrupted wait counts as nothing, which callers treat as a spurious wake.
     */
    public static boolean pollReadable(int fd1, int fd2, int timeoutMillis) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment fds = temp.allocate(POLLFD, 2);
            POLLFD_fd.set(fds, 0L, 0L, fd1);
            POLLFD_events.set(fds, 0L, 0L, POLLIN);
            POLLFD_fd.set(fds, 0L, 1L, fd2);
            POLLFD_events.set(fds, 0L, 1L, POLLIN);
            return (int) poll.invokeExact(fds, 2L, timeoutMillis) > 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("poll", t);
        }
    }

    /** Read and discard whatever is in a non-blocking descriptor. */
    public static void drain(int fd) {
        try (Arena temp = Arena.ofConfined()) {
            MemorySegment buf = temp.allocate(64);
            while ((long) read.invokeExact(fd, buf, 64L) > 0) {
                // keep draining
            }
        } catch (Throwable t) {
            throw NativeException.rethrow("read", t);
        }
    }

    /** Write one byte; a full pipe already holds a wake, so a failed write loses nothing. */
    public static void writeByte(int fd) {
        try (Arena temp = Arena.ofConfined()) {
            long ignored = (long) write.invokeExact(fd, temp.allocate(1), 1L);
        } catch (Throwable t) {
            throw NativeException.rethrow("write", t);
        }
    }

    public static void close(int fd) {
        try {
            int ignored = (int) close.invokeExact(fd);
        } catch (Throwable t) {
            throw NativeException.rethrow("close", t);
        }
    }
}
