# Window icons

> How an application says what it looks like. Two levels — the application's mark, and any window's own —
> because a process is routinely several things at once. Shipped and working on Windows; a no-op elsewhere.
>
> Implementation notes for porting this to a new platform are in
> [native-bindings.md §7.3](native-bindings.md#73-window-icons-on-a-new-platform). This doc is for the
> application side.

---

## 1. The short version

```java
NativePlatform platform = NativePlatform.current();

Icon appIcon = Icon.fromFiles(
        Path.of("icon-16.png"), Path.of("icon-32.png"), Path.of("icon-256.png"));

platform.setApplicationIcon(appIcon);                        // the default for every window

// A window that says nothing wears the application's mark.
NativeWindow main = platform.createWindow(WindowConfig.of("Fathom", 1280, 720));

// A window that should not look like the application says so at creation.
NativeWindow palette = platform.createWindow(
        WindowConfig.of("Palette", 320, 640).icon(toolIcon));

// And any window may change its mind later.
main.setIcon(unsavedChangesIcon);
```

Three rules cover the whole API:

1. A window's own icon always wins over the application's.
2. `setApplicationIcon` reaches windows already open *and* windows opened later — but only the ones that never
   chose for themselves. A default that overwrote a choice would not be a default.
3. `null` anywhere means "inherit": `WindowConfig.icon(null)` and `setIcon(null)` both go back to the
   application's mark, and a `setApplicationIcon(null)` goes back to whatever the OS gives an application that
   names none.

---

## 2. Why two levels

Nearly every window should look like the application. The few that should not — a tool palette, a preferences
window, a console watching a document, a second document of a different type — should be able to say so without
every *other* window having to repeat the default.

The alternative designs both fail on a real case. One level per process cannot tell two windows apart, which is
the case that motivated this. One level per window and no default makes every `createWindow` call carry the same
argument, and makes "change the application's mark" a loop the application has to write.

On Windows this is not a compromise imposed by the API: an icon there genuinely belongs to the `HWND` rather
than to the process, so per-window is the shape the OS already has. `setApplicationIcon` is the part VexelRay
adds — there is no process-level icon to set, so it is a VexelRay-level fact pushed down to each window.

---

## 3. An icon is a set of sizes

`Icon` holds one image *per size*, and that is the whole reason it is a type rather than a
`width, height, pixels` triple on `WindowConfig`:

```java
Icon icon = Icon.of(
        new Icon.Image(16, 16, pixels16),
        new Icon.Image(32, 32, pixels32),
        new Icon.Image(256, 256, pixels256));
```

The window manager asks for several sizes of the same mark at moments the application never sees: a small one
for the caption, a larger one for Alt-Tab, a larger one again on a 200%-scaled display. Supply the sizes that
were **drawn** at those sizes and each request is answered with artwork meant for it. Supply one and every other
size is a resample of it — and a mark legible at 256 pixels is rarely legible reduced to 16, because the detail
that survives the reduction is not the detail a designer would have kept.

`Icon.bestFor(n)` is what the platform calls: nearest size wins, ties go to the larger image (scaling down loses
less than scaling up), and a single-size icon answers every request rather than failing. Nothing in VexelRay
resamples — picking is the whole strategy.

**A useful set is 16, 32, 48, 256.** One size is fine to start with; prefer 32 if you only have one, since it is
what most requests land nearest to.

### Pixel format

Straight-alpha ARGB, one `int` per pixel as `0xAARRGGBB`, row-major from the top-left.

Straight rather than premultiplied because that is what an authoring tool exports and what every icon container
stores. A compositor that wants premultiplied pixels gets them multiplied on its own platform's side of the
seam, never in `Icon`.

`Icon` and `Icon.Image` are immutable and copy pixels both in and out, so one icon can be shared by every window
in a process and a caller's later write to its own array cannot reach a window.

### Loading from files

```java
Icon.fromFiles(Path.of("icon-16.png"), Path.of("icon-32.png"));   // one file per size
Icon.fromBytes(bytes16, bytes32);                                 // the classpath-resource form
```

Any format the JDK decodes will do; each file contributes its own natural size, so the set of sizes is whatever
was drawn rather than anything VexelRay picks.

These two methods are the **only** place in VexelRay's OS layer that touches `java.desktop` (they call
`ImageIO`). They exist because the alternative is that every application hand-rolls a PNG decoder before it can
put its own mark on its own window. They run once at start-up; nothing in the render path goes near them. If you
would rather not have AWT in the process at all, build `Icon.Image` from pixels you decoded yourself — the rest
of the API is unaffected.

---

## 4. When to change an icon

Creation covers the common case, and `WindowConfig.icon` is the right place for it: the icon is applied before
the window is shown, so it never appears under the wrong mark and gets corrected a frame later. On a slow
start-up that is not a brief flicker.

`NativeWindow.setIcon` is for the rest, and the rest is real work rather than an afterthought. A window whose
icon says what is *in* it has to change it when the content does — the document type after a "save as", the
project after the user opens another one, a build turning red. That is the same argument as a window title, and
nobody would expect to fix one of those at creation.

---

## 5. What this is not

**Not the executable icon.** What a file browser shows for the program on disk, and what a pinned taskbar
shortcut shows *before* the program runs, are properties of the packaged binary — resources linked into the
`.exe`, an `.app` bundle's icons — and no running process can change them. This API is the icon of the *running*
application, which is the one an engine can honestly offer. Shipping a branded executable is a packaging step,
not an engine one.

**Not taskbar grouping.** Windows groups taskbar buttons by AppUserModelID, and VexelRay does not set one, so
every window of a process shares the default derived from the executable and their buttons group together under
one icon. Per-window icons still show where windows are represented individually — the title bar, Alt-Tab, the
group's thumbnail flyout — but "three windows, three separate taskbar buttons with three different icons" needs
a per-window AppUserModelID, which is a `shell32` binding that does not exist yet. Worth knowing before
designing a UI around it.

**Not a cursor, and not a tray icon.** Cursors are `NativeWindow.setCursor`. A notification-area icon is a
different OS concept (`Shell_NotifyIcon` on Windows) and is not implemented.

---

## 6. Platform support

| Platform | State | How |
|---|---|---|
| Windows | working | two `HICON`s per window (`ICON_BIG` / `ICON_SMALL`) via `WM_SETICON` |
| Linux | not implemented | would be the `_NET_WM_ICON` property, which takes every size in one array |
| macOS | not implemented | `NSWindow` has no icon of its own for a non-document window; the honest port sets `NSApp.applicationIconImage` and leaves the per-window call a no-op |

Every method defaults to a no-op, so an application calling them on Linux or macOS keeps compiling and keeps the
OS default. A cosmetic request never fails a launch.

### What Windows does with it

Two icons per window rather than one, because Windows keeps two slots and asks for them in different places —
`ICON_SMALL` for the caption and small taskbar views, `ICON_BIG` for Alt-Tab and large ones. Filling only one
leaves the other to be derived by scaling, which is the failure `bestFor` exists to avoid. The sizes come from
`GetSystemMetrics(SM_CXICON / SM_CXSMICON)` rather than from 16 and 32, because on a scaled display they are not
16 and 32.

The colour bitmap is a DIB section with a `BITMAPV5HEADER` and an explicit alpha mask. A device-dependent bitmap
has no mask to declare one with, so Windows treats the top byte as padding and the icon gains a hard halo on
every background but the one it was drawn against — if you are porting or debugging, a haloed icon is that bug.

Both `HICON`s are owned by the window: Windows only borrows what `WM_SETICON` is handed. Replacing an icon hands
the new pair over *before* destroying the old, and `close()` frees them. Nothing here leaks a handle, and nothing
here needs the application's help to avoid it.

---

## 7. Where the code is

| Piece | Where |
|---|---|
| `Icon`, `Icon.Image` | [`vexelray-os-api`](../vexelray-os/vexelray-os-api/src/main/java/dev/vexelray/os/Icon.java) |
| `WindowConfig.icon` | [`WindowConfig`](../vexelray-os/vexelray-os-api/src/main/java/dev/vexelray/os/WindowConfig.java) |
| `NativeWindow.setIcon` | [`NativeWindow`](../vexelray-os/vexelray-os-api/src/main/java/dev/vexelray/os/NativeWindow.java) |
| `NativePlatform.setApplicationIcon` | [`NativePlatform`](../vexelray-os/vexelray-os-api/src/main/java/dev/vexelray/os/NativePlatform.java) |
| Win32 realisation | [`Win32Window`](../vexelray-os/vexelray-os-windows/src/main/java/dev/vexelray/os/windows/Win32Window.java) + `sys/User32`, `sys/Gdi32` |
| Size selection & copying, tested | [`IconTest`](../vexelray-os/vexelray-os-api/src/test/java/dev/vexelray/os/IconTest.java) |
| Live check | [`Win32IconSmoke`](../vexelray-os/vexelray-os-windows/src/test/java/dev/vexelray/os/windows/Win32IconSmoke.java) |

`Win32IconSmoke` opens three windows — one inheriting, one overriding at creation, one changing while on screen
— and reads the handles back with `WM_GETICON`, so *"did an icon reach the window at all"* is checked rather
than eyeballed; that is the half that fails silently, because a `WM_SETICON` that never happened looks exactly
like the OS default. The pixels themselves still have to be judged by eye, so it draws soft-edged discs and
needs no assets:

```
java --enable-native-access=ALL-UNNAMED -cp <test-classpath> \
     dev.vexelray.os.windows.Win32IconSmoke [icon-16.png icon-32.png ...]
```

Pass image files to check real artwork; pass nothing to check the plumbing.
