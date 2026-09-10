package dev.vexelray.demo;

import dev.vexelray.canvas.Color;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.technique.canvas.CanvasTechnique;
import dev.vexelray.technique.sdf.SdfRaymarchTechnique;
import dev.vexelray.technique.sdf.SdfScene;

/**
 * Manual smoke check (not a unit test): a marched SDF scene and a 2D canvas in <b>one frame</b> — the thing the
 * pipeline model was built to make possible, and the thing that was impossible before it.
 *
 * <h2>Why this is the interesting one</h2>
 *
 * <p>Both halves of this picture already worked, separately, and had for some time. What could not happen was
 * both at once: {@code SdfComposer} produced a fragment that a hand-wired presenter drew, and {@code Canvas}
 * produced a vertex batch that a different hand-wired presenter drew, and each presenter owned an instance, a
 * device, a swapchain and a frame loop of its own. Two of them could not share a window because the presenter
 * held exactly one pipeline. The nearest thing to this frame that existed was
 * {@code SampledColorTarget} — render one to a texture and let the other sample it, which works and costs a
 * full-screen image, a second render pass, and a copy.
 *
 * <p>This is the same picture without any of that: one instance, one device, one swapchain, one render pass,
 * one command buffer, two pipelines. The application below names no Vulkan object.
 *
 * <h2>What the composition is</h2>
 *
 * <p>Order, declared. The march runs first and covers every pixel (it writes sky where it misses); the canvas
 * runs second and alpha-blends chrome over it. So this frame is ordered compositing, which is exactly the
 * case {@code RenderPipeline}'s ordered list is for.
 *
 * <p><b>And the two techniques take opposite sides of the depth question, both deliberately.</b> The march
 * tests and writes depth — it can, now that its fragment writes {@code gl_FragDepth} from its own hit
 * distance — so a third technique with real geometry would interleave with the scene per pixel. The canvas
 * declares {@code Depth.NONE}, so chrome is not occluded by the scene it annotates, which is what chrome is
 * for. The picture is unchanged from when neither participated; what changed is that only one of them is
 * opting out, and it is opting out for a reason rather than for want of a mechanism.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED}; an optional first argument sets the frame count.
 */
public final class HybridFrameSmoke {

    private static final int DEFAULT_FRAMES = 300;

    public static void main(String[] args) {
        long presented = measure(args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_FRAMES);

        System.out.println("frames         " + presented);
        System.out.println();
        System.out.println(presented > 0
                ? "PASS -- an SDF march and a canvas batch composited in one render pass, one command buffer"
                : "FAIL -- no frame was presented");
        if (presented == 0) {
            System.exit(1);
        }
    }

    /** Frames presented, separated from {@code main} so HybridFrameTest drives exactly this. */
    public static long measure(int frames) {

        SdfRaymarchTechnique march = new SdfRaymarchTechnique(
                SdfScene.of(Surface.union(
                                new Surface.Sphere(0, 1.0, 0, 1.0),
                                new Surface.Sphere(2.2, 0.6, 1.4, 0.6),
                                new Surface.Sphere(-2.0, 0.8, -1.0, 0.8),
                                Surface.Plane.ground()))
                        .withAlbedo(new Surface.Rgb(0.78, 0.80, 0.86)));

        CanvasTechnique chrome = new CanvasTechnique(900, 600);

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("VexelRay — one frame, two techniques", 900, 600)
                        .color(AttachmentFormat.SWAPCHAIN)
                        .depth(AttachmentFormat.DEPTH32F))
                .technique(march)      // the scene, covering every pixel
                .technique(chrome)     // then chrome over it — order is the composition
                .build();

        long[] presented = {0};
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("HybridFrameSmoke"))) {
            System.out.println("engine         " + engine.getClass().getName());
            engine.run(pipeline, frame -> {
                presented[0] = frame.frameIndex() + 1;

                double angle = frame.timeSeconds() * 0.6;
                double radius = 6.0;
                march.camera(Math.sin(angle) * radius, 2.2, -Math.cos(angle) * radius, angle, -0.18);

                // Both techniques take their per-frame data through their own APIs, in their own vocabulary —
                // a camera here, a drawing there. The runtime mediates neither and knows the layout of neither.
                chrome.draw(c -> {
                    c.fillRoundRect(24, 24, 260, 96, 12, new Color(0.05f, 0.06f, 0.08f, 0.72f));
                    c.fillRoundRect(24, 24, 260, 4, 2, new Color(0.30f, 0.72f, 0.98f, 1f));
                    float bar = (float) (0.5 + 0.5 * Math.sin(frame.timeSeconds() * 1.4));
                    c.fillRoundRect(40, 88, 228 * bar, 10, 5, new Color(0.30f, 0.72f, 0.98f, 0.9f));
                    c.fillRoundRect(40, 88, 228, 10, 5, new Color(1f, 1f, 1f, 0.08f));
                });

                return frame.frameIndex() < frames;
            });
        }

        return presented[0];
    }

    private HybridFrameSmoke() {
    }
}
