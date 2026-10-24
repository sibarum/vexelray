package dev.vexelray.os.macos;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.Platform;
import dev.vexelray.os.WindowConfig;
import dev.vexelray.os.macos.sys.ObjC;

import java.lang.foreign.MemorySegment;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The macOS {@link NativePlatform}: an {@code NSWindow} backed by a {@code CAMetalLayer} (AppKit/QuartzCore) and
 * a {@code VkSurfaceKHR} via {@code VK_EXT_metal_surface} on MoltenVK.
 *
 * <p>Skeleton — present from day one so the multi-platform contract holds, with the binding implementation to
 * follow per {@code docs/native-bindings.md}. Reports its surface extensions (pure data) already.
 */
public final class MacosPlatform implements NativePlatform {

    @Override
    public Platform platform() {
        return Platform.MACOS;
    }

    /** {@code open}, which is Launch Services from the command line. Needs no binding, so it is here before they are. */
    @Override
    public boolean open(String target) {
        try {
            new ProcessBuilder("open", target)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    @Override
    public List<String> requiredVulkanInstanceExtensions() {
        return List.of("VK_KHR_surface", "VK_EXT_metal_surface");
    }

    /**
     * {@code ~/Library/Application Support}, the folder macOS gives each user for application data. Pure data, like
     * the surface extensions, so it is here before the bindings are.
     */
    @Override
    public Optional<Path> userDataDir() {
        return Optional.of(Path.of(System.getProperty("user.home"), "Library", "Application Support"));
    }

    private static final String APPKIT = "/System/Library/Frameworks/AppKit.framework/AppKit";

    /**
     * {@code [[NSWorkspace sharedWorkspace] accessibilityDisplayShouldReduceMotion]}: System Settings → Accessibility
     * → Display → "Reduce motion". The first binding this module has, and the smallest: two messages to one shared
     * object, so it can be here before the windowing is.
     */
    @Override
    public Optional<Boolean> reducedMotion() {
        try {
            ObjC.framework(APPKIT);
            MemorySegment workspaceClass = ObjC.getClass("NSWorkspace");
            if (workspaceClass.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            MemorySegment workspace = ObjC.sendObject(workspaceClass, ObjC.selector("sharedWorkspace"));
            if (workspace.equals(MemorySegment.NULL)) {
                return Optional.empty();
            }
            return Optional.of(ObjC.sendBool(workspace, ObjC.selector("accessibilityDisplayShouldReduceMotion")));
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();   // no AppKit (a headless or very old system): this platform cannot say
        }
    }

    @Override
    public NativeWindow createWindow(WindowConfig config) {
        throw new UnsupportedOperationException(
                "macOS windowing not yet implemented — AppKit/QuartzCore bindings pending "
                        + "(see docs/native-bindings.md §4)");
    }
}
