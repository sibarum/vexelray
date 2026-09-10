package dev.vexelray.technique.sdf;

import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;

/**
 * Manual smoke check (not a unit test): an SDF scene marched through the <em>engine</em> rather than through
 * hand-wired Vulkan.
 *
 * <h2>What is different about this from every march before it</h2>
 *
 * <p>{@link StrokeMarchSmoke} and {@link ConeMarchSmoke} build their own instance and device and render one
 * frame offscreen. Fathom builds its own instance, device, swapchain, render pass, pipeline and presenter, and
 * drives its own loop. This builds none of those. It declares a target, wraps a scene in a technique, and hands
 * both to {@code VexelEngine.create} — which means an SDF scene is now a thing that composites with others
 * rather than a thing that owns the screen.
 *
 * <p>The camera orbits, which is the cheapest way to see that per-frame data reaches the shader through the
 * technique's own API rather than through anything the runtime understands: the engine never learns what a
 * camera is, and the picture moves anyway.
 *
 * <h2>What it measures</h2>
 *
 * <p>The same lifecycle the SPI promises, for the same reason a picture is not a measurement — a window showing
 * a sphere proves the first frame worked and says nothing about the ninetieth. Recorded once per presented
 * frame, realised once, closed once.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED}; an optional first argument sets the frame count.
 */
final class SdfEngineSmoke {

    private static final int DEFAULT_FRAMES = 240;

    public static void main(String[] args) {
        long presented = measure(args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_FRAMES);

        System.out.println("frames         " + presented);
        System.out.println();
        System.out.println(presented > 0
                ? "PASS -- an SDF scene marched through the engine, camera driven by the technique's own API"
                : "FAIL -- no frame was presented");
        if (presented == 0) {
            System.exit(1);
        }
    }

    /** Frames presented, separated from {@code main} so {@link SdfEngineTest} drives exactly this. */
    static long measure(int frames) {
        // Curved, mostly-convex, no long flat parallels — the grain architecture.md §2 asks content to be cut
        // with, so the march is not being measured at its worst case while the runtime is what is under test.
        Surface scene = Surface.union(
                new Surface.Sphere(0, 1.0, 0, 1.0),
                new Surface.Sphere(2.2, 0.6, 1.4, 0.6),
                new Surface.Sphere(-2.0, 0.8, -1.0, 0.8),
                Surface.Plane.ground());

        SdfRaymarchTechnique march = new SdfRaymarchTechnique(
                SdfScene.of(scene).withAlbedo(new SdfScene.Rgb(0.78, 0.80, 0.86)));

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("VexelRay — SDF through the engine", 900, 600)
                        .color(AttachmentFormat.SWAPCHAIN)
                        .depth(AttachmentFormat.DEPTH32F))
                .technique(march)
                .build();

        long[] presented = {0};
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("SdfEngineSmoke"))) {
            System.out.println("engine         " + engine.getClass().getName());
            engine.run(pipeline, frame -> {
                presented[0] = frame.frameIndex() + 1;
                // An orbit at a fixed radius, looking back at the origin — the same framing the offscreen march
                // smokes use, so a picture that looks wrong here can be compared against one that is known good.
                double angle = frame.timeSeconds() * 0.6;
                double radius = 6.0;
                march.camera(Math.sin(angle) * radius, 2.2, -Math.cos(angle) * radius, angle, -0.18);
                return frame.frameIndex() < frames;
            });
        }

        System.out.println("scene          " + SurfaceSize.describe(scene));
        return presented[0];
    }

    /** How big the compiled shader is, reported because it is the number that decides pipeline build time. */
    private static final class SurfaceSize {
        static String describe(Surface surface) {
            byte[] fragment = SdfComposer.fragmentSpirv(SdfScene.of(surface));
            return fragment.length / 1024 + " kB of fragment SPIR-V";
        }

        private SurfaceSize() {
        }
    }

    private SdfEngineSmoke() {
    }
}
