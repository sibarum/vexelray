package dev.vexelray.technique.sdf;

import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.Shadings;
import dev.vexelray.surface.Bounds;
import dev.vexelray.surface.Surface;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * What culling a march to its box saves (D31): milliseconds a frame, offscreen at 1280×720, for the unmasked march,
 * the same box as a plain D30 mask (pixels culled, the ray not), and the culled march (both).
 *
 * <p>Not a test: a number, and one that depends on the GPU. The scene is a cluster of forty spheres filling some of
 * the frame from a few units away, so the field costs something per call and there is sky on every side of it.
 * Each case is timed over a short and a long run and the difference divided out, which takes away the engine's
 * start-up and the pipeline build.
 */
public final class BoxCullSmoke {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int SHORT = 20;
    private static final int LONG = 220;

    public static void main(String[] args) {
        List<Surface> spheres = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            double a = i * 2.399963;                       // the golden angle, so they do not line up
            double r = 0.25 + 0.9 * Math.sqrt(i / 40.0);
            spheres.add(new Surface.Sphere(r * Math.cos(a), r * Math.sin(a), 6 + 0.3 * Math.sin(i), 0.22));
        }
        SdfScene scene = SdfScene.of(Surface.union(spheres.toArray(Surface[]::new)))
                .withShading(Shadings.defaultKeyLight());
        Bounds box = SdfRaymarchTechnique.padded(Bounds.of(scene.surface()).orElseThrow());

        report("unmasked", scene, s -> new SdfRaymarchTechnique(s));
        report("box as a D30 mask", scene,
                s -> new SdfRaymarchTechnique(s).mask(SdfRaymarchTechnique.boxTriangles(box)));
        report("culled to its box", scene, s -> new SdfRaymarchTechnique(s).cullToBounds());
    }

    private static void report(String name, SdfScene scene, Function<SdfScene, SdfRaymarchTechnique> make) {
        // Warm once so the first case does not pay for loading the driver.
        time(scene, make, SHORT);
        double perFrame = (time(scene, make, LONG) - time(scene, make, SHORT)) / (LONG - SHORT);
        System.out.printf("%-20s %7.3f ms a frame%n", name, perFrame / 1e6);
    }

    private static long time(SdfScene scene, Function<SdfScene, SdfRaymarchTechnique> make, int frames) {
        SdfRaymarchTechnique march = make.apply(scene).camera(0, 0, 0, 0, 0);
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.offscreen(WIDTH, HEIGHT).color(AttachmentFormat.RGBA8_UNORM)
                        .depth(AttachmentFormat.DEPTH32F).build())
                .technique(march)
                .build();
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("BoxCullSmoke"))) {
            long start = System.nanoTime();
            engine.run(pipeline, frame -> frame.frameIndex() < frames);
            return System.nanoTime() - start;
        }
    }

    private BoxCullSmoke() {
    }
}
