package dev.vexelray.vulkan;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.os.NativePlatform;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.vulkan.offscreen.OffscreenRenderer;
import dev.vexelray.vulkan.vk.VulkanDevice;
import dev.vexelray.vulkan.vk.VulkanInstance;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * Manual smoke check (not a unit test): the headline feature meeting the runtime. Composes a UV-gradient fragment
 * shader as SupirVast {@code core} IR, lowers it to SPIR-V via {@link ComposedShader}, pairs it with
 * {@link Fullscreen}'s fullscreen-triangle vertex stage, rasterises it into an offscreen image on the GPU, and
 * writes a PNG. Run explicitly with {@code --enable-native-access=ALL-UNNAMED} and an output path.
 */
public final class OffscreenTriangleSmoke {

    public static void main(String[] args) throws IOException {
        int width = 256;
        int height = 256;
        String out = args.length > 0 ? args[0] : "triangle.png";

        byte[] vertexSpirv = Fullscreen.triangleVertexWithUvSpirv();
        byte[] fragmentSpirv = uvGradientFragment();

        NativePlatform platform = NativePlatform.current();
        try (VulkanInstance instance = new VulkanInstance("VexelRay triangle",
                platform.requiredVulkanInstanceExtensions())) {
            VulkanInstance.DeviceSelection selection = instance.selectGraphicsDevice()
                    .orElseThrow(() -> new IllegalStateException("no graphics-capable device"));
            System.out.println("device: " + selection.deviceName());
            System.out.println("vertex SPIR-V: " + vertexSpirv.length + " bytes, fragment: "
                    + fragmentSpirv.length + " bytes");

            try (VulkanDevice device = new VulkanDevice(instance.handle(), selection)) {
                byte[] rgba = OffscreenRenderer.render(device, width, height,
                        vertexSpirv, "main", fragmentSpirv, "main", 3, 0.0f, 0.0f, 0.0f, 1.0f);

                BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int i = (y * width + x) * 4;
                        image.setRGB(x, y, ((rgba[i + 3] & 0xFF) << 24) | ((rgba[i] & 0xFF) << 16)
                                | ((rgba[i + 1] & 0xFF) << 8) | (rgba[i + 2] & 0xFF));
                    }
                }
                ImageIO.write(image, "PNG", new File(out));
                System.out.println("wrote " + new File(out).getAbsolutePath());
                System.out.println("corners RGBA: TL=" + px(rgba, width, 0, 0) + " TR=" + px(rgba, width, width - 1, 0)
                        + " BL=" + px(rgba, width, 0, height - 1) + " BR=" + px(rgba, width, width - 1, height - 1));
            }
        }
    }

    /** {@code fragColor = vec4(vUv.x, vUv.y, 0, 1)} — a red/green gradient across the screen, authored in core IR. */
    private static byte[] uvGradientFragment() {
        Type.Float f32 = Type.float32();
        Type.Vector vec2 = new Type.Vector(f32, 2);
        Type.Vector vec4 = new Type.Vector(f32, 4);

        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, vec2);
        Expr uv = new Expr.InterfaceRead(vUv);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, vec4);
        Expr color = new Expr.VectorConstruct(vec4, List.of(
                new Expr.VectorExtract(uv, 0), new Expr.VectorExtract(uv, 1),
                new Expr.ConstFloat(f32, 0.0), new Expr.ConstFloat(f32, 1.0)));
        Region body = Region.of(new Statement.InterfaceWrite(fragColor, color), new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    private static String px(byte[] rgba, int width, int x, int y) {
        int i = (y * width + x) * 4;
        return (rgba[i] & 0xFF) + "," + (rgba[i + 1] & 0xFF) + "," + (rgba[i + 2] & 0xFF) + "," + (rgba[i + 3] & 0xFF);
    }
}
