package dev.vexelray.os.windows.sys;

import dev.supirvast.ffi.Ffi;
import dev.supirvast.ffi.NativeException;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_CHAR;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/**
 * Binding for {@code shell32.dll}. One library, one class (see {@code docs/native-bindings.md} §4).
 */
public final class Shell32 {

    private static final SymbolLookup LIB = Ffi.library("shell32");

    private static final MethodHandle CommandLineToArgvW = Ffi.downcall(LIB, "CommandLineToArgvW",
            FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS));

    private Shell32() {
    }

    /**
     * {@code CommandLineToArgvW}: split a UTF-16 command line by the C runtime's rules. Returns an array of
     * {@code argc} string pointers (written to {@code argc}), to be released with {@link Kernel32#localFree}, or
     * {@code NULL} on failure.
     */
    public static MemorySegment commandLineToArgvW(MemorySegment commandLine, MemorySegment argc) {
        try {
            return (MemorySegment) CommandLineToArgvW.invokeExact(commandLine, argc);
        } catch (Throwable t) {
            throw NativeException.rethrow("CommandLineToArgvW", t);
        }
    }

    // ---- The notification area -------------------------------------------------------------------------------

    public static final int NIM_ADD = 0x0;
    public static final int NIM_MODIFY = 0x1;
    public static final int NIM_DELETE = 0x2;
    public static final int NIM_SETVERSION = 0x4;
    public static final int NIF_MESSAGE = 0x1;
    public static final int NIF_ICON = 0x2;
    public static final int NIF_TIP = 0x4;
    public static final int NIF_SHOWTIP = 0x80;
    /** {@code NOTIFYICON_VERSION_4}: the callback's low word of lParam is the event, and WM_CONTEXTMENU is sent. */
    public static final int NOTIFYICON_VERSION_4 = 4;
    /** {@code szTip}, in UTF-16 units including the terminator. */
    public static final int TIP_CHARS = 128;

    /** {@code NOTIFYICONDATAW}, the whole current form: 976 bytes on x64, padding spelled out. */
    public static final GroupLayout NOTIFYICONDATAW = MemoryLayout.structLayout(
            JAVA_INT.withName("cbSize"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hWnd"),
            JAVA_INT.withName("uID"),
            JAVA_INT.withName("uFlags"),
            JAVA_INT.withName("uCallbackMessage"),
            MemoryLayout.paddingLayout(4),
            ADDRESS.withName("hIcon"),
            MemoryLayout.sequenceLayout(TIP_CHARS, JAVA_CHAR).withName("szTip"),
            JAVA_INT.withName("dwState"),
            JAVA_INT.withName("dwStateMask"),
            MemoryLayout.sequenceLayout(256, JAVA_CHAR).withName("szInfo"),
            JAVA_INT.withName("uVersion"),
            MemoryLayout.sequenceLayout(64, JAVA_CHAR).withName("szInfoTitle"),
            JAVA_INT.withName("dwInfoFlags"),
            MemoryLayout.sequenceLayout(16, JAVA_BYTE).withName("guidItem"),
            ADDRESS.withName("hBalloonIcon")
    ).withName("NOTIFYICONDATAW");

    private static final long NID_TIP = NOTIFYICONDATAW.byteOffset(MemoryLayout.PathElement.groupElement("szTip"));

    private static final MethodHandle Shell_NotifyIconW = Ffi.downcall(LIB, "Shell_NotifyIconW",
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS));

    /**
     * A filled {@code NOTIFYICONDATAW} for icon {@code id} of {@code hwnd}: callback {@code message}, {@code icon}
     * and the tooltip {@code tip} (cut to fit). The version is set to 4, for {@link #NIM_SETVERSION}.
     */
    public static MemorySegment allocNotifyIconData(SegmentAllocator allocator, MemorySegment hwnd, int id,
                                                    int message, MemorySegment icon, String tip) {
        MemorySegment nid = allocator.allocate(NOTIFYICONDATAW);
        nid.set(JAVA_INT, NOTIFYICONDATAW.byteOffset(MemoryLayout.PathElement.groupElement("cbSize")),
                (int) NOTIFYICONDATAW.byteSize());
        nid.set(ADDRESS, NOTIFYICONDATAW.byteOffset(MemoryLayout.PathElement.groupElement("hWnd")), hwnd);
        nid.set(JAVA_INT, NOTIFYICONDATAW.byteOffset(MemoryLayout.PathElement.groupElement("uID")), id);
        nid.set(JAVA_INT, NOTIFYICONDATAW.byteOffset(MemoryLayout.PathElement.groupElement("uFlags")),
                NIF_MESSAGE | NIF_ICON | NIF_TIP | NIF_SHOWTIP);
        nid.set(JAVA_INT, NOTIFYICONDATAW.byteOffset(MemoryLayout.PathElement.groupElement("uCallbackMessage")),
                message);
        nid.set(ADDRESS, NOTIFYICONDATAW.byteOffset(MemoryLayout.PathElement.groupElement("hIcon")), icon);
        String cut = tip.length() >= TIP_CHARS ? tip.substring(0, TIP_CHARS - 1) : tip;
        for (int i = 0; i < cut.length(); i++) {
            nid.set(JAVA_CHAR, NID_TIP + 2L * i, cut.charAt(i));
        }
        nid.set(JAVA_INT, NOTIFYICONDATAW.byteOffset(MemoryLayout.PathElement.groupElement("uVersion")),
                NOTIFYICON_VERSION_4);
        return nid;
    }

    /** {@code Shell_NotifyIconW}; returns whether the shell accepted it (it refuses while Explorer is not running). */
    public static boolean notifyIcon(int message, MemorySegment data) {
        try {
            return (int) Shell_NotifyIconW.invokeExact(message, data) != 0;
        } catch (Throwable t) {
            throw NativeException.rethrow("Shell_NotifyIconW", t);
        }
    }
}
