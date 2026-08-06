package dev.vexelray.vulkan;

import dev.vexelray.os.NativePlatform;
import dev.vexelray.vulkan.offscreen.OffscreenReadback;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Manual smoke check (not a unit test): fully headless — no window — render an offscreen image on the GPU, read
 * it back, and write it to a PNG. Run explicitly with {@code --enable-native-access=ALL-UNNAMED} and an output
 * path as the first argument.
 */
public final class OffscreenScreenshotSmoke {

    public static void main(String[] args) throws IOException {
        int width = 256;
        int height = 256;
        String out = args.length > 0 ? args[0] : "offscreen.png";

        NativePlatform platform = NativePlatform.current();
        try (VulkanInstance instance = new VulkanInstance("VexelRay offscreen",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics-capable device"));
            System.out.println("device: " + selection.deviceName());

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection)) {
                byte[] rgba = OffscreenReadback.clearToRgba(device, width, height, 0.10f, 0.45f, 0.72f, 1.0f);

                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int i = (y * width + x) * 4;
                        int red = rgba[i] & 0xFF;
                        int green = rgba[i + 1] & 0xFF;
                        int blue = rgba[i + 2] & 0xFF;
                        int alpha = rgba[i + 3] & 0xFF;
                        image.setRGB(x, y, (alpha << 24) | (red << 16) | (green << 8) | blue);
                    }
                }
                File file = new File(out);
                ImageIO.write(image, "PNG", file);
                System.out.println("wrote " + file.getAbsolutePath() + " (" + width + "x" + height + ")");
                System.out.println("top-left pixel RGBA = " + (rgba[0] & 0xFF) + "," + (rgba[1] & 0xFF)
                        + "," + (rgba[2] & 0xFF) + "," + (rgba[3] & 0xFF));
            }
        }
    }
}
