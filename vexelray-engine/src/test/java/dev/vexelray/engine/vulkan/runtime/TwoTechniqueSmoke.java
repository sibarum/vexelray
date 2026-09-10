package dev.vexelray.engine.vulkan.runtime;

import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;

/**
 * Manual smoke check (not a unit test): drive two {@link dev.vexelray.engine.RenderTechnique}s through the real
 * runtime, into one window, one render pass, one command buffer — and say in numbers whether the SPI did what it
 * claims.
 *
 * <h2>The question it answers</h2>
 *
 * <p>Before this ran, "N techniques composite into one shared target" was a sentence in an interface's javadoc
 * with no implementation under it. Six features in this repository each reached the screen by building their own
 * instance, device, swapchain and presenter, so no two of them had ever appeared in the same frame. This is the
 * first frame in the project's history containing two independently-built pipelines.
 *
 * <h2>What it measures</h2>
 *
 * <p>A picture is not the measurement — a window showing one box could mean the second technique drew nothing,
 * drew underneath, or was never called, and those look identical. So the lifecycle is counted instead, and the
 * counts are what pass or fail:
 *
 * <ul>
 *   <li><b>realise once, each.</b> More than once means the runtime re-realised on a resize, which the SPI
 *       promises it does not; zero means a technique was in the pipeline and never set up.</li>
 *   <li><b>record once per presented frame, each.</b> Not "at least once" — a technique recorded twice in a
 *       frame would draw twice, and a technique recorded in only some frames flickers.</li>
 *   <li><b>close once, each.</b> Zero is a GPU-object leak the JVM will not report.</li>
 * </ul>
 *
 * <p>The two techniques are given different colours and different insets so the frame is also legible to a
 * person: a large box and a smaller one inside it, the second visibly over the first. That is ordering, not
 * occlusion — see {@link TintTechnique} for why proving occlusion needs geometry this does not have.
 *
 * <p>Run with {@code --enable-native-access=ALL-UNNAMED}; an optional first argument sets the frame count.
 */
final class TwoTechniqueSmoke {

    private static final int DEFAULT_FRAMES = 120;

    public static void main(String[] args) {
        Result result = measure(args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_FRAMES);

        System.out.println("frames         " + result.presented());
        report("background", result.background(), result.presented());
        report("foreground", result.foreground(), result.presented());

        boolean ok = result.passed();
        System.out.println();
        System.out.println(ok
                ? "PASS -- two techniques realised once, recorded every frame, and released"
                : "FAIL -- the lifecycle above is not what RenderTechnique promises");
        if (!ok) {
            System.exit(1);
        }
    }

    /**
     * What one run establishes: how many frames reached the screen, and what the SPI did to each technique.
     *
     * <p>Returned rather than judged inside {@code measure} so {@link TwoTechniqueTest} asserts on the numbers
     * individually — a single boolean tells you something failed and not which promise was broken, and
     * "realised twice" and "never recorded" are different bugs in different places.
     */
    record Result(long presented, TintTechnique background, TintTechnique foreground) {
        boolean passed() {
            // Qualified, because inside the record the bare name resolves to this method rather than the
            // two-argument one outside it.
            return TwoTechniqueSmoke.passed(background, presented)
                    && TwoTechniqueSmoke.passed(foreground, presented);
        }
    }

    static Result measure(int frames) {
        TintTechnique background = new TintTechnique(new float[]{0.10f, 0.12f, 0.16f}, 0.05f);
        TintTechnique foreground = new TintTechnique(new float[]{0.30f, 0.72f, 0.98f}, 0.55f);

        // Depth is declared even though neither technique needs it, deliberately: it exercises the pass with two
        // attachments, the framebuffers with two views, the depth image, and the pipelines' depth-stencil state.
        // Those are the parts most likely to be silently wrong, and a target that never asks for them never
        // finds out.
        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("VexelRay — two techniques, one frame", 900, 600)
                        .color(AttachmentFormat.SWAPCHAIN)
                        .depth(AttachmentFormat.DEPTH32F))
                .technique(background)
                .technique(foreground)
                .build();

        long[] presented = {0};
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("TwoTechniqueSmoke"))) {
            System.out.println("engine         " + engine.getClass().getName());
            engine.run(pipeline, frame -> {
                presented[0] = frame.frameIndex() + 1;
                return frame.frameIndex() < frames;
            });
        }

        return new Result(presented[0], background, foreground);
    }

    /**
     * A technique records once per frame the presenter actually recorded, which is not every frame the loop
     * counted: a frame whose image acquire came back out of date is rebuilt and skipped, and nothing is
     * recorded for it. So the check is an upper bound plus "it happened at all", not equality — equality would
     * fail the first time someone resized the window, and a check that fails on correct behaviour gets deleted.
     */
    private static boolean passed(TintTechnique technique, long frames) {
        return technique.realizeCount() == 1
                && technique.closeCount() == 1
                && technique.recordCount() > 0
                && technique.recordCount() <= frames;
    }

    private static void report(String label, TintTechnique technique, long frames) {
        System.out.printf("%-14s realise %d, record %d (of %d frames), close %d%n",
                label, technique.realizeCount(), technique.recordCount(), frames, technique.closeCount());
    }

    private TwoTechniqueSmoke() {
    }
}
