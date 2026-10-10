package dev.vexelray.present.dxgi;

import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import dev.supirvast.vulkan.VulkanInstance;
import dev.vexelray.os.NativeWindow;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.present.PresenterProvider;
import dev.vexelray.vulkan.present.VulkanRenderPass;
import dev.vexelray.vulkan.present.WindowPresenter;
import sibarum.dxgi.DxgiContext;

import java.util.List;

/** {@code dxgi}: present through DXGI, which is how VexelRay presents on Windows. See {@link DxgiPresenter}. */
public final class DxgiPresenterProvider implements PresenterProvider {

    @Override
    public String name() {
        return "dxgi";
    }

    @Override
    public boolean supported() {
        return System.getProperty("os.name", "").startsWith("Windows");
    }

    @Override
    public List<String> deviceExtensions() {
        return DxgiContext.REQUIRED_DEVICE_EXTENSIONS;
    }

    @Override
    public boolean timelineSemaphore() {
        return true;
    }

    @Override
    public Backend open(VulkanInstance instance, VulkanDevice device) {
        DxgiContext context = DxgiContext.create(instance, device);
        return new Backend() {
            @Override
            public int colorFormat() {
                return Vk.FORMAT_B8G8R8A8_UNORM;   // the shared image's, matching DXGI_FORMAT_B8G8R8A8_UNORM
            }

            @Override
            public int finalLayout() {
                return VulkanRenderPass.IMAGE_LAYOUT_GENERAL;
            }

            @Override
            public WindowPresenter create(NativeWindow window, long renderPass, GraphicsPipeline pipeline) {
                return new DxgiPresenter(device, context, window, renderPass, pipeline);
            }

            @Override
            public void close() {
                context.close();
            }
        };
    }
}
