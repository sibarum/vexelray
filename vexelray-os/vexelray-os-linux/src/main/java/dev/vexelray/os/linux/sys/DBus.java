package dev.vexelray.os.linux.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Binding for {@code libdbus-1.so.3}: the session bus, method calls built and read with message iterators, and
 * signals read off the connection. One library, one class (see {@code docs/native-bindings.md} §4).
 *
 * <p>Only the iterator API is bound, never the variadic {@code dbus_message_append_args}: every argument goes in and
 * comes out one typed call at a time. {@code DBusError} and {@code DBusMessageIter} are public structs whose sizes the
 * library fixes (32 and 72 bytes on 64-bit); they are allocated generously and never read field by field.
 */
public final class DBus {

    private static final SymbolLookup LIB = Ffi.libraryFile("libdbus-1.so.3");

    public static final int BUS_SESSION = 0;
    public static final int TYPE_STRING = 's';
    public static final int TYPE_UINT32 = 'u';
    public static final int TYPE_INT32 = 'i';
    public static final int TYPE_BOOLEAN = 'b';
    public static final int TYPE_ARRAY = 'a';
    public static final int TYPE_VARIANT = 'v';
    public static final int TYPE_DICT_ENTRY = 'e';
    public static final int TYPE_STRUCT = 'r';
    public static final int TYPE_OBJECT_PATH = 'o';
    public static final int TYPE_BYTE = 'y';
    public static final int TYPE_INVALID = 0;

    /** {@code dbus_message_get_type}: a method call, and a signal. */
    public static final int MESSAGE_METHOD_CALL = 1;
    public static final int MESSAGE_SIGNAL = 4;

    /** Room for a {@code DBusError} or a {@code DBusMessageIter}, with margin. */
    public static final long STRUCT_SIZE = 128;

    private static final MethodHandle dbus_error_init = Ffi.downcall(LIB, "dbus_error_init",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle dbus_error_is_set = Ffi.downcall(LIB, "dbus_error_is_set",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle dbus_error_free = Ffi.downcall(LIB, "dbus_error_free",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle dbus_bus_get = Ffi.downcall(LIB, "dbus_bus_get",
            FunctionDescriptor.of(ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle dbus_bus_add_match = Ffi.downcall(LIB, "dbus_bus_add_match",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_new_method_call = Ffi.downcall(LIB,
            "dbus_message_new_method_call", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_unref = Ffi.downcall(LIB, "dbus_message_unref",
            FunctionDescriptor.ofVoid(ADDRESS));
    private static final MethodHandle dbus_message_iter_init_append = Ffi.downcall(LIB,
            "dbus_message_iter_init_append", FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_iter_append_basic = Ffi.downcall(LIB,
            "dbus_message_iter_append_basic", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle dbus_message_iter_open_container = Ffi.downcall(LIB,
            "dbus_message_iter_open_container", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_iter_close_container = Ffi.downcall(LIB,
            "dbus_message_iter_close_container", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_iter_init = Ffi.downcall(LIB, "dbus_message_iter_init",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_iter_get_arg_type = Ffi.downcall(LIB,
            "dbus_message_iter_get_arg_type", FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle dbus_message_iter_get_basic = Ffi.downcall(LIB, "dbus_message_iter_get_basic",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_iter_next = Ffi.downcall(LIB, "dbus_message_iter_next",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle dbus_message_is_signal = Ffi.downcall(LIB, "dbus_message_is_signal",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_connection_send_with_reply_and_block = Ffi.downcall(LIB,
            "dbus_connection_send_with_reply_and_block",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle dbus_connection_read_write = Ffi.downcall(LIB, "dbus_connection_read_write",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle dbus_connection_pop_message = Ffi.downcall(LIB, "dbus_connection_pop_message",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_new_method_return = Ffi.downcall(LIB,
            "dbus_message_new_method_return", FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_new_error = Ffi.downcall(LIB, "dbus_message_new_error",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_get_type = Ffi.downcall(LIB, "dbus_message_get_type",
            FunctionDescriptor.of(JAVA_INT, ADDRESS));
    private static final MethodHandle dbus_message_get_member = Ffi.downcall(LIB, "dbus_message_get_member",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_get_interface = Ffi.downcall(LIB, "dbus_message_get_interface",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_get_path = Ffi.downcall(LIB, "dbus_message_get_path",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_iter_recurse = Ffi.downcall(LIB, "dbus_message_iter_recurse",
            FunctionDescriptor.ofVoid(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_message_iter_append_fixed_array = Ffi.downcall(LIB,
            "dbus_message_iter_append_fixed_array", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    private static final MethodHandle dbus_bus_get_unique_name = Ffi.downcall(LIB, "dbus_bus_get_unique_name",
            FunctionDescriptor.of(ADDRESS, ADDRESS));
    private static final MethodHandle dbus_bus_name_has_owner = Ffi.downcall(LIB, "dbus_bus_name_has_owner",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_connection_send = Ffi.downcall(LIB, "dbus_connection_send",
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS));
    private static final MethodHandle dbus_connection_flush = Ffi.downcall(LIB, "dbus_connection_flush",
            FunctionDescriptor.ofVoid(ADDRESS));

    private DBus() {
    }

    /** A fresh, initialised {@code DBusError} in {@code arena}. */
    public static MemorySegment newError(Arena arena) {
        MemorySegment err = arena.allocate(STRUCT_SIZE, 8);
        try {
            dbus_error_init.invokeExact(err);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_error_init", t);
        }
        return err;
    }

    /** Throw with the error's message if {@code err} is set, freeing it either way. */
    public static void check(MemorySegment err, String what) {
        try {
            boolean set = (int) dbus_error_is_set.invokeExact(err) != 0;
            if (set) {
                // DBusError starts with name and message pointers.
                MemorySegment message = err.get(ADDRESS, ADDRESS.byteSize()).reinterpret(4096);
                String text = message.equals(MemorySegment.NULL) ? "(no message)" : message.getString(0);
                dbus_error_free.invokeExact(err);
                throw new NativeException(what + ": " + text);
            }
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow(what, t);
        }
    }

    /** The session bus, shared within the process; never {@code NULL} (it throws instead). */
    public static MemorySegment sessionBus(Arena arena) {
        MemorySegment err = newError(arena);
        try {
            MemorySegment conn = (MemorySegment) dbus_bus_get.invokeExact(BUS_SESSION, err);
            check(err, "dbus_bus_get(session)");
            if (conn.equals(MemorySegment.NULL)) {
                throw new NativeException("dbus_bus_get(session) returned NULL");
            }
            return conn;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_bus_get", t);
        }
    }

    public static void addMatch(MemorySegment conn, String rule, Arena arena) {
        MemorySegment err = newError(arena);
        try {
            dbus_bus_add_match.invokeExact(conn, arena.allocateFrom(rule), err);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_bus_add_match", t);
        }
        check(err, "dbus_bus_add_match");
    }

    public static MemorySegment newMethodCall(String destination, String path, String iface, String method,
                                              Arena arena) {
        try {
            MemorySegment msg = (MemorySegment) dbus_message_new_method_call.invokeExact(
                    arena.allocateFrom(destination), arena.allocateFrom(path), arena.allocateFrom(iface),
                    arena.allocateFrom(method));
            if (msg.equals(MemorySegment.NULL)) {
                throw new NativeException("dbus_message_new_method_call returned NULL");
            }
            return msg;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_new_method_call", t);
        }
    }

    public static void unref(MemorySegment msg) {
        try {
            dbus_message_unref.invokeExact(msg);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_unref", t);
        }
    }

    /** An iterator that appends to {@code msg}'s arguments. */
    public static MemorySegment appendIter(MemorySegment msg, Arena arena) {
        MemorySegment it = arena.allocate(STRUCT_SIZE, 8);
        try {
            dbus_message_iter_init_append.invokeExact(msg, it);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_init_append", t);
        }
        return it;
    }

    public static void appendString(MemorySegment it, String value, Arena arena) {
        MemorySegment slot = arena.allocate(ADDRESS);
        slot.set(ADDRESS, 0, arena.allocateFrom(value));
        appendBasic(it, TYPE_STRING, slot);
    }

    /** An object path ({@code o}): a string on the wire, under its own type. */
    public static void appendObjectPath(MemorySegment it, String path, Arena arena) {
        MemorySegment slot = arena.allocate(ADDRESS);
        slot.set(ADDRESS, 0, arena.allocateFrom(path));
        appendBasic(it, TYPE_OBJECT_PATH, slot);
    }

    /** A {@code u}, {@code i} or {@code b} (D-Bus booleans are 32-bit). */
    public static void appendInt(MemorySegment it, int type, int value, Arena arena) {
        appendBasic(it, type, arena.allocateFrom(JAVA_INT, value));
    }

    private static void appendBasic(MemorySegment it, int type, MemorySegment value) {
        try {
            if ((int) dbus_message_iter_append_basic.invokeExact(it, type, value) == 0) {
                throw new NativeException("dbus_message_iter_append_basic: out of memory");
            }
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_append_basic", t);
        }
    }

    /** Open a container ({@code a}, {@code v}, {@code e}) of {@code signature} (null for a dict entry) under {@code it}. */
    public static MemorySegment open(MemorySegment it, int type, String signature, Arena arena) {
        MemorySegment sub = arena.allocate(STRUCT_SIZE, 8);
        try {
            MemorySegment sig = signature == null ? MemorySegment.NULL : arena.allocateFrom(signature);
            if ((int) dbus_message_iter_open_container.invokeExact(it, type, sig, sub) == 0) {
                throw new NativeException("dbus_message_iter_open_container: out of memory");
            }
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_open_container", t);
        }
        return sub;
    }

    public static void close(MemorySegment it, MemorySegment sub) {
        try {
            if ((int) dbus_message_iter_close_container.invokeExact(it, sub) == 0) {
                throw new NativeException("dbus_message_iter_close_container: out of memory");
            }
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_close_container", t);
        }
    }

    /** Send {@code msg} and wait up to {@code timeoutMillis} for the reply, which the caller unrefs. */
    public static MemorySegment call(MemorySegment conn, MemorySegment msg, int timeoutMillis, Arena arena) {
        MemorySegment err = newError(arena);
        try {
            MemorySegment reply = (MemorySegment) dbus_connection_send_with_reply_and_block.invokeExact(conn, msg,
                    timeoutMillis, err);
            check(err, "D-Bus call");
            return reply;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_connection_send_with_reply_and_block", t);
        }
    }

    /** An iterator over {@code msg}'s arguments, or null when it has none. */
    public static MemorySegment readIter(MemorySegment msg, Arena arena) {
        MemorySegment it = arena.allocate(STRUCT_SIZE, 8);
        try {
            return (int) dbus_message_iter_init.invokeExact(msg, it) == 0 ? null : it;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_init", t);
        }
    }

    public static int argType(MemorySegment it) {
        try {
            return (int) dbus_message_iter_get_arg_type.invokeExact(it);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_get_arg_type", t);
        }
    }

    /** The current argument as a 32-bit integer ({@code u}, {@code i}). */
    public static int getInt(MemorySegment it, Arena arena) {
        MemorySegment slot = arena.allocate(8, 8);
        try {
            dbus_message_iter_get_basic.invokeExact(it, slot);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_get_basic", t);
        }
        return slot.get(JAVA_INT, 0);
    }

    /** The current argument as a string ({@code s}). */
    public static String getString(MemorySegment it, Arena arena) {
        MemorySegment slot = arena.allocate(ADDRESS);
        try {
            dbus_message_iter_get_basic.invokeExact(it, slot);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_get_basic", t);
        }
        return slot.get(ADDRESS, 0).reinterpret(Integer.MAX_VALUE).getString(0);
    }

    public static boolean next(MemorySegment it) {
        try {
            return (int) dbus_message_iter_next.invokeExact(it) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_next", t);
        }
    }

    public static boolean isSignal(MemorySegment msg, String iface, String name, Arena arena) {
        try {
            return (int) dbus_message_is_signal.invokeExact(msg, arena.allocateFrom(iface), arena.allocateFrom(name))
                    != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_is_signal", t);
        }
    }

    /** Move bytes in and out for up to {@code timeoutMillis}; false once the connection is gone. */
    public static boolean readWrite(MemorySegment conn, int timeoutMillis) {
        try {
            return (int) dbus_connection_read_write.invokeExact(conn, timeoutMillis) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_connection_read_write", t);
        }
    }

    /** Queue {@code msg} without waiting for a reply, and write it out now. */
    public static void sendAndFlush(MemorySegment conn, MemorySegment msg) {
        try {
            if ((int) dbus_connection_send.invokeExact(conn, msg, MemorySegment.NULL) == 0) {
                throw new NativeException("dbus_connection_send: out of memory");
            }
            dbus_connection_flush.invokeExact(conn);
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_connection_send", t);
        }
    }

    // ---- Answering calls -------------------------------------------------------------------------------------------

    /** {@link #MESSAGE_METHOD_CALL}, {@link #MESSAGE_SIGNAL}, ... */
    public static int type(MemorySegment msg) {
        try {
            return (int) dbus_message_get_type.invokeExact(msg);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_get_type", t);
        }
    }

    public static String member(MemorySegment msg) {
        try {
            return string((MemorySegment) dbus_message_get_member.invokeExact(msg));
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_get_member", t);
        }
    }

    public static String iface(MemorySegment msg) {
        try {
            return string((MemorySegment) dbus_message_get_interface.invokeExact(msg));
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_get_interface", t);
        }
    }

    public static String path(MemorySegment msg) {
        try {
            return string((MemorySegment) dbus_message_get_path.invokeExact(msg));
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_get_path", t);
        }
    }

    private static String string(MemorySegment p) {
        return p.equals(MemorySegment.NULL) ? "" : p.reinterpret(Integer.MAX_VALUE).getString(0);
    }

    /** The reply to {@code call}, to fill and send; the caller unrefs it. */
    public static MemorySegment newReturn(MemorySegment call) {
        try {
            MemorySegment reply = (MemorySegment) dbus_message_new_method_return.invokeExact(call);
            if (reply.equals(MemorySegment.NULL)) {
                throw new NativeException("dbus_message_new_method_return returned NULL");
            }
            return reply;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_new_method_return", t);
        }
    }

    /** An error reply to {@code call}; the caller sends and unrefs it. */
    public static MemorySegment newError(MemorySegment call, String name, String message, Arena arena) {
        try {
            return (MemorySegment) dbus_message_new_error.invokeExact(call, arena.allocateFrom(name),
                    arena.allocateFrom(message));
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_new_error", t);
        }
    }

    /** An iterator into the container {@code it} is on. */
    public static MemorySegment recurse(MemorySegment it, Arena arena) {
        MemorySegment sub = arena.allocate(STRUCT_SIZE, 8);
        try {
            dbus_message_iter_recurse.invokeExact(it, sub);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_recurse", t);
        }
        return sub;
    }

    /** An {@code ay} of {@code bytes}, in one call, into the array container {@code it}. */
    public static void appendBytes(MemorySegment it, byte[] bytes, Arena arena) {
        MemorySegment data = arena.allocateFrom(java.lang.foreign.ValueLayout.JAVA_BYTE, bytes);
        MemorySegment slot = arena.allocate(ADDRESS);
        slot.set(ADDRESS, 0, data);
        try {
            if ((int) dbus_message_iter_append_fixed_array.invokeExact(it, TYPE_BYTE, slot, bytes.length) == 0) {
                throw new NativeException("dbus_message_iter_append_fixed_array: out of memory");
            }
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_message_iter_append_fixed_array", t);
        }
    }

    /** This connection's unique bus name ({@code :1.42}). */
    public static String uniqueName(MemorySegment conn) {
        try {
            return string((MemorySegment) dbus_bus_get_unique_name.invokeExact(conn));
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_bus_get_unique_name", t);
        }
    }

    /** Whether some connection owns {@code name} right now. */
    public static boolean nameHasOwner(MemorySegment conn, String name, Arena arena) {
        MemorySegment err = newError(arena);
        try {
            boolean owned = (int) dbus_bus_name_has_owner.invokeExact(conn, arena.allocateFrom(name), err) != 0;
            check(err, "dbus_bus_name_has_owner");
            return owned;
        } catch (NativeException e) {
            throw e;
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_bus_name_has_owner", t);
        }
    }

    /** The next message read, or {@link MemorySegment#NULL}; the caller unrefs it. */
    public static MemorySegment pop(MemorySegment conn) {
        try {
            return (MemorySegment) dbus_connection_pop_message.invokeExact(conn);
        } catch (Throwable t) {
            throw NativeException.rethrow("dbus_connection_pop_message", t);
        }
    }
}
