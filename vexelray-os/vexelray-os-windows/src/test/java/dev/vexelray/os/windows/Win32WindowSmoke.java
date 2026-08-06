package dev.vexelray.os.windows;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.os.WindowConfig;

/**
 * Manual smoke check (not a unit test): opens a real Win32 window via the platform, pumps its message loop for a
 * short while, prints the framebuffer size, then closes. Run explicitly — it needs a desktop session and
 * {@code --enable-native-access=ALL-UNNAMED}. Proves the user32/kernel32 bindings drive a live window.
 */
public final class Win32WindowSmoke {

    public static void main(String[] args) throws InterruptedException {
        NativePlatform platform = NativePlatform.current();
        System.out.println("platform: " + platform.platform());

        try (NativeWindow window = platform.createWindow(new WindowConfig("VexelRay smoke", 800, 600, true))) {
            System.out.println("window opened, osHandle=0x" + Long.toHexString(window.osHandle())
                    + " size=" + window.width() + "x" + window.height());
            long deadline = System.nanoTime() + 1_000_000_000L;   // ~1 second
            int frames = 0;
            while (System.nanoTime() < deadline && window.pumpEvents()) {
                frames++;
                Thread.sleep(8);
            }
            System.out.println("pumped " + frames + " frames, closeRequested=" + !window.pumpEvents());
        }
        System.out.println("window closed cleanly");
    }
}
