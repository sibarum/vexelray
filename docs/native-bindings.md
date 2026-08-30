# VexelRay Native Binding Convention

> **Status: normative.** This document defines *the only* sanctioned way to call native code in VexelRay.
> Every OS integration — windowing, the Vulkan loader, any future platform API — follows it exactly. If a
> binding does not match this document, the binding is wrong, not the document. Read this before writing or
> reviewing any native code.

---

## 1. Why this exists

VexelRay integrates **directly with the operating system** through its own [Panama](https://openjdk.org/jeps/454)
(Foreign Function & Memory API) bindings. There is **no LWJGL, no GLFW, no SDL, no jextract, no third-party
native library** — the only thing on the host beyond the JVM is the graphics driver and the OS's own libraries.

This buys us three things we care about:

1. **A single native-image binary.** No bundled `.dll`/`.so` to extract to a temp dir at startup — we call the
   system loaders directly.
2. **Total control of the surface.** An engine only needs a window, an event stream, and a `VkSurfaceKHR`. We
   own exactly that and nothing else.
3. **No dependency drift.** The native surface is ours; it cannot break because an upstream binding changed.

The cost is that **we write and maintain the bindings**, on **every platform**, forever. This document exists
to make that cost boringly uniform: one skeleton, copied, never improvised.

### Non-negotiable principles

| # | Principle | Consequence |
|---|-----------|-------------|
| P1 | **Multi-platform from day one** | No binding is written for one OS "for now." Every native concern has a platform-agnostic interface in `vexelray-os` and one implementation per supported platform. |
| P2 | **Native-image safe** | Every downcall/upcall is registered; libraries load at *runtime*, never at image-build time; no reflection. |
| P3 | **Build-time selection** | Maven detects the host OS and puts exactly one platform module on the classpath. The artifact never carries foreign platforms' code. |
| P4 | **One foolproof convention, no magic** | Every library binding is the same shape (§4). Selection is `ServiceLoader` + Maven profiles — standard, inspectable, no reflection tricks, no offset arithmetic. |
| P5 | **The OS layer knows nothing about Vulkan bindings** | It builds a surface from an opaque `VkInstance` handle + a `vkGetInstanceProcAddr` pointer. It never depends on the Vulkan module. |

---

## 2. Platform matrix

| Platform | `Platform` id | System libraries | Window handle | Vulkan surface extension | Surface entry point |
|----------|---------------|------------------|---------------|--------------------------|---------------------|
| Windows  | `WINDOWS` | `user32`, `kernel32` | `HWND` (+`HINSTANCE`) | `VK_KHR_win32_surface` | `vkCreateWin32SurfaceKHR` |
| Linux/X11 | `LINUX` | `libX11` | `Window` (+`Display*`) | `VK_KHR_xlib_surface` | `vkCreateXlibSurfaceKHR` |
| Linux/Wayland | `LINUX` | `libwayland-client` | `wl_surface*` (+`wl_display*`) | `VK_KHR_wayland_surface` | `vkCreateWaylandSurfaceKHR` |
| macOS | `MACOS` | `libobjc`, AppKit, QuartzCore | `NSWindow` → `CAMetalLayer` | `VK_EXT_metal_surface` (MoltenVK) | `vkCreateMetalSurfaceEXT` |

Every platform additionally requires the base `VK_KHR_surface` instance extension. A platform reports its own
required instance extensions (§5); the Vulkan module enables exactly what the active platform asks for.

> **Input lives in Tactroller, not here.** This document covers windowing and the Vulkan surface only.
> Pointer/keyboard input is delegated to [Tactroller](../../tactroller), a sibling first-party project that
> already applies this convention verbatim (per-OS Panama bindings to `user32`/`libX11`/CoreGraphics, one
> `InputBackend` via `ServiceLoader`, native-image-clean). `vexelray-os` never binds `GetAsyncKeyState`,
> `GetCursorPos`, or their peers — the engine depends on `tactroller-api` and polls it per frame. Adding a new
> platform (§7.2) therefore means window + surface only; input for that platform is Tactroller's concern.

> **Windows is the first implementation.** X11, Wayland, and macOS are stubs that throw
> `UnsupportedOperationException("<platform> not yet implemented")` until built — but their module, interface,
> and service registration exist from day one (P1), so adding one is filling in a skeleton, never inventing one.

---

## 3. Module layout & selection

```
vexelray-os                    aggregator (packaging=pom, no code) — groups the OS layer
├─ vexelray-os-api             shared, platform-agnostic API + the Ffi helper (§4.1). Depends on NOTHING.
├─ vexelray-os-windows         WindowsPlatform + user32/kernel32 bindings + reachability metadata
├─ vexelray-os-linux           LinuxPlatform (X11 first) + libX11 bindings + reachability metadata
└─ vexelray-os-macos           MacosPlatform + AppKit/QuartzCore bindings + reachability metadata

vexelray-vulkan  ──depends on──►  vexelray-os-api   (and, via a Maven profile, the one active platform module)
```

`vexelray-os` is a nested aggregator: the root reactor lists it once, and it lists the four modules above. The
folder nesting is organizational only — the reactor flattens it and orders modules by dependency
(`vexelray-os-api` before the platform modules). The shared code lives in `vexelray-os-api`; the `vexelray-os`
folder itself has no `src/`.

### 3.1 Build-time selection — Maven (P3)

The **runnable/consumer module** (the one that actually needs a live window — `vexelray-vulkan`, or an app/demo
module) declares one OS-activated profile per platform. Each adds *its* platform module as a dependency. Maven
activates a profile automatically from the build host's OS family — **no flag, no property, no magic.**

> **Not the parent POM.** A profile defined in the parent is inherited by *every* module, so it would leak a
> platform dependency into `vexelray-core` and `vexelray-shader` and break the layering. The selection lives with
> the consumer that assembles a runnable engine — the only module that should depend on a concrete platform.

```xml
<!-- vexelray-vulkan (or the app) pom.xml -->
<profiles>
  <profile>
    <id>platform-windows</id>
    <activation><os><family>windows</family></os></activation>
    <dependencies>
      <dependency><groupId>dev.vexelray</groupId><artifactId>vexelray-os-windows</artifactId></dependency>
    </dependencies>
  </profile>
  <profile>
    <id>platform-linux</id>
    <activation><os><family>unix</family><name>linux</name></os></activation>
    <dependencies>
      <dependency><groupId>dev.vexelray</groupId><artifactId>vexelray-os-linux</artifactId></dependency>
    </dependencies>
  </profile>
  <profile>
    <id>platform-macos</id>
    <activation><os><family>mac</family></os></activation>
    <dependencies>
      <dependency><groupId>dev.vexelray</groupId><artifactId>vexelray-os-macos</artifactId></dependency>
    </dependencies>
  </profile>
</profiles>
```

Only the matching platform module reaches the classpath and the native-image build. The other platforms'
code and reachability metadata never ship in this artifact.

> All platform modules still *compile* on any host — FFM bindings are pure Java (functions are named by string,
> resolved at runtime). We build every platform module in CI regardless of host; only *assembly/native-image*
> is host-selected. This keeps `vexelray-os-linux` from rotting while we develop on Windows.

### 3.2 Runtime selection — one provider (P4)

The active platform module publishes exactly one `NativePlatform` service. The engine resolves it once:

```java
NativePlatform platform = NativePlatform.current();   // ServiceLoader.load(...).findFirst()
```

Because Maven put exactly one platform module on the classpath, `ServiceLoader` finds exactly one provider.
Zero providers → a clear "no VexelRay platform module on the classpath" error. Two → a misconfigured build,
also caught. No `os.name` sniffing outside the `Platform` enum; no reflection.

Each platform module registers its provider the standard way:

```
# vexelray-os-windows/src/main/resources/META-INF/services/dev.vexelray.os.NativePlatform
dev.vexelray.os.windows.WindowsPlatform
```

---

## 4. The binding convention — "one library, one class"

Every native library is bound by **one `final` class**, named after the library, in package
`dev.vexelray.os.<platform>.sys`. The class has an invariant shape. Copy it; do not improvise.

### 4.1 The shared FFI helper (`vexelray-os-api`)

All the FFM ceremony lives in one helper so bindings never hand-roll it. This is the *only* place `Linker`,
`SymbolLookup`, and `upcallStub` are touched.

```java
package dev.vexelray.os.ffi;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

/** The one sanctioned entry to Panama. Every native binding routes through here — never call Linker directly. */
public final class Ffi {
    private static final Linker LINKER = Linker.nativeLinker();

    /** Process-lifetime arena for symbols and stubs that live as long as the engine. */
    public static final Arena GLOBAL = Arena.ofShared();

    private Ffi() {}

    /** Open a system library by bare name ("user32", "X11") — resolved at RUNTIME (native-image safe). */
    public static SymbolLookup library(String name) {
        return SymbolLookup.libraryLookup(System.mapLibraryName(name), GLOBAL);
    }

    /** A downcall handle for {@code symbol} in {@code lib}. Fails loudly if the symbol is absent. */
    public static MethodHandle downcall(SymbolLookup lib, String symbol, FunctionDescriptor descriptor) {
        MemorySegment address = lib.find(symbol)
                .orElseThrow(() -> new NativeException("symbol not found: " + symbol));
        return LINKER.downcallHandle(address, descriptor);
    }

    /** An upcall stub (a Java callback the OS can invoke, e.g. a WndProc), bound to {@code lifetime}. */
    public static MemorySegment upcall(MethodHandles.Lookup lookup, Class<?> owner, String method,
                                       FunctionDescriptor descriptor, Arena lifetime) {
        try {
            MethodHandle target = lookup.findStatic(owner, method, descriptor.toMethodType());
            return LINKER.upcallStub(target, descriptor, lifetime);
        } catch (ReflectiveOperationException e) {
            throw new NativeException("no upcall target " + owner.getSimpleName() + "#" + method, e);
        }
    }
}
```

### 4.2 The library-binding skeleton

```java
package dev.vexelray.os.windows.sys;

import dev.vexelray.os.ffi.Ffi;
import dev.vexelray.os.ffi.NativeException;
import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import static java.lang.foreign.ValueLayout.*;

/** Binding for user32.dll. One library, one class. All handles private; a typed Java wrapper per call. */
public final class User32 {

    // (1) The library — one lookup, opened once, at runtime.
    private static final SymbolLookup LIB = Ffi.library("user32");

    // (2) Struct layouts — named fields only. Offsets come from the layout, NEVER from a literal. (§4.3)
    public static final GroupLayout MSG = MemoryLayout.structLayout(
            ADDRESS.withName("hwnd"),
            JAVA_INT.withName("message"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("wParam"),
            JAVA_LONG.withName("lParam"),
            JAVA_INT.withName("time"),
            JAVA_INT.withName("pt_x"),
            JAVA_INT.withName("pt_y"),
            MemoryLayout.paddingLayout(4)
    ).withName("MSG");

    // (3) One downcall handle per C function. Named exactly like the C symbol.
    private static final MethodHandle CreateWindowExW = Ffi.downcall(LIB, "CreateWindowExW",
            FunctionDescriptor.of(ADDRESS,
                    JAVA_INT, ADDRESS, ADDRESS, JAVA_INT,
                    JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT,
                    ADDRESS, ADDRESS, ADDRESS, ADDRESS));

    private static final MethodHandle DefWindowProcW = Ffi.downcall(LIB, "DefWindowProcW",
            FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG));

    private User32() {}

    // (4) One public, Java-typed wrapper per call. Marshalling in; invokeExact; error check out.
    public static MemorySegment createWindowExW(int exStyle, MemorySegment className, MemorySegment title,
                                                int style, int x, int y, int w, int h,
                                                MemorySegment parent, MemorySegment menu,
                                                MemorySegment instance, MemorySegment param) {
        try {
            MemorySegment hwnd = (MemorySegment) CreateWindowExW.invokeExact(exStyle, className, title, style,
                    x, y, w, h, parent, menu, instance, param);
            if (hwnd.equals(MemorySegment.NULL)) {
                throw new NativeException("CreateWindowExW returned NULL (GetLastError=" + Kernel32.getLastError() + ")");
            }
            return hwnd;
        } catch (Throwable t) {
            throw NativeException.rethrow("CreateWindowExW", t);
        }
    }

    public static long defWindowProcW(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            return (long) DefWindowProcW.invokeExact(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            throw NativeException.rethrow("DefWindowProcW", t);
        }
    }
}
```

### 4.3 Rules that make it foolproof

1. **One library → one class**, named after the library, in `dev.vexelray.os.<platform>.sys`.
2. **Every downcall handle is `private static final`** and named **exactly** like the C symbol
   (`CreateWindowExW`, not `createWindow`). The public wrapper is the camelCase Java version.
3. **Struct fields are named layout elements.** Access is via a `VarHandle` from
   `PathElement.groupElement("field")` (`Ffi.field`), or — for write-heavy struct filling — via
   `segment.set(VALUE_LAYOUT, layout.byteOffset(groupElement("field")), v)`. Either way **the offset comes from
   the layout, never a literal** — a literal byte offset in a binding is a bug. Padding is explicit
   `paddingLayout`, matched to the C ABI.
4. **Every wrapper is Java-typed** (no raw `MemorySegment` leakage where a real type exists) and does
   `invokeExact` inside `try { … } catch (Throwable t) { throw NativeException.rethrow(name, t); }`.
5. **Return codes are checked at the wrapper boundary** and turned into a `NativeException` carrying the call
   name (and `GetLastError`/`errno` where the platform provides it). Native failures never return silently.
6. **Callbacks (upcalls) are `static` methods** bound through `Ffi.upcall(...)` to an explicit `Arena` whose
   lifetime ≥ the window's. Never bind an upcall to a confined arena you close early.
7. **Arenas encode ownership.** `Ffi.GLOBAL` for process-lifetime symbols/stubs; a per-window `Arena.ofShared()`
   (closed in `NativeWindow.close()`) for window-scoped allocations. Per-call scratch uses a confined arena in a
   try-with-resources. **No unmanaged `Arena.ofAuto()` for anything the GC lifetime matters for.**
8. **No `Linker`, `libraryLookup`, or `upcallStub` outside `Ffi`.** Bindings call the helper; the helper is the
   single audited surface.

### 4.4 Loader-based libraries (Vulkan)

Some libraries expose almost nothing by symbol name — you resolve one bootstrap symbol and load everything else
through it. **Vulkan** is the case that matters here: `vulkan-1` exports `vkGetInstanceProcAddr`, and every other
command is resolved through that (global commands with a `NULL` instance; instance/device commands with a live
handle). This is a *variant* of §4.2, not an exception to it — the same rules hold, with two additions:

1. **One loader class** (`VkLoader`) owns the bootstrap symbol and exposes typed resolvers —
   `globalCommand(name, descriptor)` and `instanceCommand(instance, name, descriptor)` — each returning a
   `MethodHandle` built from the resolved pointer via `Ffi.downcall(MemorySegment, …)`. Bindings never call
   `vkGetInstanceProcAddr` themselves.
2. **Command handles are resolved once and cached** (as `final` fields on the object that owns the scope — the
   instance caches its instance-level commands, the device its device-level commands), not re-resolved per call.

Everything else — named struct layouts, typed wrappers, `VkResult` checks turned into `NativeException`, arenas
for ownership, registration for native-image — is exactly as §4.2/§4.3/§6. The loader library's name is itself
platform-specific (`vulkan-1` on Windows, `libvulkan.so.1` on Linux, MoltenVK on macOS); source it from the
active platform once multiple platforms are implemented.

---

## 5. The platform-agnostic API (`vexelray-os-api`)

This is what the engine codes against. It never mentions Win32, X11, or Cocoa.

```java
package dev.vexelray.os;

public enum Platform { WINDOWS, LINUX, MACOS }

/** The one service each platform module provides. Resolved once via ServiceLoader. */
public interface NativePlatform {
    Platform platform();

    /** Vulkan INSTANCE extensions this platform needs for presentation, e.g. [VK_KHR_surface, VK_KHR_win32_surface]. */
    java.util.List<String> requiredVulkanInstanceExtensions();

    NativeWindow createWindow(WindowConfig config);

    static NativePlatform current() {
        return java.util.ServiceLoader.load(NativePlatform.class).findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "no dev.vexelray.os.NativePlatform on the classpath — is a vexelray-os-<platform> module present?"));
    }
}

public record WindowConfig(String title, int width, int height, boolean resizable) {}

/** A live OS window. The engine drives events and asks it to mint a Vulkan surface. */
public interface NativeWindow extends AutoCloseable {

    /** Framebuffer size in pixels (post-DPI). */
    int width();
    int height();

    /** Pump the OS event queue once; return false once the window has been asked to close. */
    boolean pumpEvents();

    /**
     * Create a VkSurfaceKHR for this window. The OS module owns the platform surface struct + entry point; the
     * Vulkan module stays platform-agnostic and just hands over the instance and its proc loader.
     *
     * @param vkInstance           the VkInstance handle (opaque long)
     * @param vkGetInstanceProcAddr a pointer to vkGetInstanceProcAddr, used to load vkCreate*SurfaceKHR
     * @return the VkSurfaceKHR handle (opaque long)
     */
    long createVulkanSurface(long vkInstance, java.lang.foreign.MemorySegment vkGetInstanceProcAddr);

    /** The raw OS handle (HWND / X11 Window / NSWindow) — for logging and validation only. */
    long osHandle();

    @Override
    void close();
}
```

**Why `createVulkanSurface` takes `vkGetInstanceProcAddr` (P5):** the surface-creation function
(`vkCreateWin32SurfaceKHR`, …) is a *Vulkan* function, but its argument struct is *platform* data (the `HWND` +
`HINSTANCE`). Keeping the call inside the OS module means all platform-specific knowledge stays there and the
Vulkan module never grows a `#ifdef`. The OS module loads the entry point through the passed proc-addr pointer —
it needs no Vulkan-binding dependency, only the loader pointer and the extension name it already reports.

---

## 6. Native-image rules (P2)

1. **Runtime flag.** Apps run with `--enable-native-access=ALL-UNNAMED` (module form once modularized). The
   native-image build and any `exec` runs set it; document it in each runnable module's README.
2. **Registered foreign calls.** Every downcall/upcall is registered in the platform module's
   `src/main/resources/META-INF/native-image/dev.vexelray/<artifact>/reachability-metadata.json` under
   `"foreign"`. Generate it with the tracing agent (`-agentlib:native-image-agent=config-output-dir=…`) against
   the module's tests, review it, and commit it. Never hand-edit blindly.
3. **Libraries load at runtime, not build time.** `Ffi.library(...)` runs when the binding class initializes;
   binding classes must **not** initialize during image build. Keep them off the build-time-init path (they are
   by default) and, if the analysis ever pulls one in, pin it with
   `--initialize-at-run-time=dev.vexelray.os.<platform>`. A binding must never call a native function in a static
   initializer.
4. **No reflection, no dynamic method handles.** Every handle is a `static final` created from a string literal
   symbol name — statically analyzable. `ServiceLoader` providers are registered (they are, via `META-INF/services`)
   so they survive image build.
5. **Arenas over finalizers.** Resource cleanup is explicit (`close()` + arena close), never `Cleaner`/finalizer
   dependent, so image-time reachability is trivial.

---

## 7. Checklists

### 7.1 Adding a native function to an existing binding
1. Add a `private static final MethodHandle <CName>` via `Ffi.downcall`, descriptor matching the C ABI exactly.
2. Add a Java-typed public wrapper with the `try/invokeExact/catch rethrow` shape and a return-code check.
3. If a struct is involved, add its `GroupLayout` with named fields + padding; access via `VarHandle`.
4. Regenerate & commit the module's `reachability-metadata.json`.

### 7.2 Adding a whole new platform
1. New module `vexelray-os-<platform>` under the `vexelray-os` aggregator; add a `platform-<os>` profile to the
   consumer/runnable module (§3.1).
2. Implement `NativePlatform` + `NativeWindow` using `dev.vexelray.os.<platform>.sys.*` bindings (§4).
3. Report the platform's `requiredVulkanInstanceExtensions()` and implement `createVulkanSurface` with that
   platform's `vkCreate*SurfaceKHR`.
4. Register the provider in `META-INF/services/dev.vexelray.os.NativePlatform`.
5. Add `reachability-metadata.json`; build in CI on that OS.

### 7.3 Window icons on a new platform

`Icon` is straight-alpha ARGB at one or more sizes and names no OS, so a platform's whole job is to realise it.
The application-facing side of the feature — what to supply, what it is not, and what Windows does with it — is
**[docs/window-icons.md](window-icons.md)**. Three things are worth knowing before writing the platform code:

- **The icon belongs to the window, not the process** — which is why `WindowConfig.icon` and
  `NativeWindow.setIcon` exist at all, and why `NativePlatform.setApplicationIcon` is a VexelRay-level default
  that a platform pushes down to each window rather than an OS call. On Windows it is two `WM_SETICON`s; on X11
  the `_NET_WM_ICON` property, which takes every size in one array; on macOS `NSWindow` has no icon of its own
  for a non-document window, so the honest implementation sets `NSApp.applicationIconImage` for the application
  icon and leaves the per-window call a no-op rather than pretending.
- **Pick a size, do not resample.** Ask the OS what size it wants (`GetSystemMetrics`, or the scale factor) and
  hand it `Icon.bestFor(that)`. Scaling in the engine throws away the reason an icon carries several sizes.
- **Straight alpha, and the platform converts.** Windows wants a 32-bit DIB with an explicit alpha mask (a
  device-dependent bitmap silently drops the channel and the icon gains a halo); a compositor that wants
  premultiplied pixels gets them multiplied on that platform's side of the seam, never in `Icon`.

Not implementing it is a supported state: every method defaults to a no-op, and a window keeps the OS default.

---

## 8. Anti-patterns — banned (the "no magic" list)

- ❌ A literal byte offset into a struct. Use a named layout + `VarHandle`.
- ❌ `Linker`, `SymbolLookup.libraryLookup`, or `upcallStub` anywhere but `Ffi`.
- ❌ `os.name`/`os.arch` string checks outside the `Platform` enum + Maven profiles.
- ❌ Reflection or `MethodHandles.lookup().findVirtual(...)` to reach native code.
- ❌ A binding that compiles for only one OS, or a platform interface with a single implementation "for now."
- ❌ Calling a native function from a static initializer, or opening a library at image-build time.
- ❌ Swallowing a native error code / returning a NULL handle without throwing.
- ❌ `Arena.ofAuto()` for a resource whose lifetime the engine must control.
- ❌ The Vulkan module importing anything from `dev.vexelray.os.<platform>` (it depends only on `dev.vexelray.os`).
```
