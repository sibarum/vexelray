package dev.vexelray.demo;

import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderPipeline;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.VexelEngine;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.ir.Ir;
import dev.vexelray.runtime.EngineConfig;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.target.AttachmentFormat;
import dev.vexelray.target.Target;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;
import dev.vexelray.vulkan.vk.Vk;
import dev.vexelray.vulkan.vk.VulkanDevice;

import java.lang.foreign.MemorySegment;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * <b>The worked example: the smallest complete {@link RenderTechnique}, written to be read.</b>
 *
 * <p>Everything a technique must do and nothing else — compose a shader, build a pipeline against somebody
 * else's render pass, take per-frame data through an API of its own, record a draw, release what it made. It
 * draws a gradient that pulses, which is the least interesting picture that still proves every one of those
 * five things happened.
 *
 * <p>It exists because for a while the only implementation of {@code RenderTechnique} outside a real feature
 * lived in the engine's <em>test</em> scope, so a third party writing technique number one had nothing to read
 * — and the four techniques that did exist had each independently rebuilt the same forty lines of Panama
 * boilerplate, and each grown a private copy of a helper that had been public all along. A worked example is
 * the cheapest fix for both.
 *
 * <p>Run it: {@code mvn -pl vexelray-demo exec:java -Dexec.mainClass=dev.vexelray.demo.HelloTechnique}, with
 * {@code --enable-native-access=ALL-UNNAMED}. An optional first argument sets the frame count.
 *
 * <h2>1. What the engine gives you, and what it keeps</h2>
 *
 * <p>You get a command buffer inside a render pass that is already begun, with the frame's viewport and scissor
 * already set. You do not get, and must not touch: the instance, the device's queues, the swapchain, the
 * framebuffers, the fences and semaphores, the render pass's begin and end, the submit, the present. Those are
 * identical for every technique, which is exactly why the engine owns them.
 *
 * <h2>2. The two lifecycles</h2>
 *
 * <p>{@link #realize} runs once, when the pipeline is realised, and is where every GPU object is made.
 * {@link #record} runs every frame and makes nothing. {@link #close} releases what {@code realize} made, and
 * the engine has already waited for the GPU to go idle before it calls you. If a later technique's
 * {@code realize} throws, yours is closed for you — you never have to make {@code realize} survive being
 * called twice over live objects.
 *
 * <h2>3. Per-frame data is your API, not the engine's</h2>
 *
 * <p>{@link #tint} below is this technique's whole content API. The engine never learns what a tint is; the
 * application calls it from the run callback and {@code record} turns it into bytes in whatever layout this
 * class declared. That is what lets two techniques with completely different push-constant layouts share one
 * command buffer without agreeing on anything (docs/refactor-decisions.md D5).
 *
 * <h2>4. The extent is the frame's, never realise time's</h2>
 *
 * <p>A window resize changes {@link FrameContext#width()} without recreating the render pass and without
 * calling {@code realize} again. So the pipeline is built with <b>dynamic</b> viewport and scissor (the last
 * {@code true} in {@link GraphicsPipeline.Config}), and any size-dependent number is read from the frame. This
 * technique's picture is in UV space and needs neither, which is the ideal case; caching {@code ctx.width()}
 * and drawing to it is the bug that survives every test that never resizes a window.
 *
 * <h2>5. Threading</h2>
 *
 * <p>All three methods are the engine's render thread and only ever one at a time — see
 * {@link RenderTechnique} for the contract. The fields below are therefore plain: no {@code volatile}, no
 * lock. {@link #tint} is called from the run callback, which is the same thread, immediately before recording.
 */
public final class HelloTechnique implements RenderTechnique {

    /**
     * {@code r, g, b, pulse} — this technique's push-constant layout, and nobody else's.
     *
     * <p>Vulkan guarantees only 128 bytes of push constants, shared by every stage, so a layout this small is
     * the right shape for per-frame scalars. Anything larger (a light list, a transform array) belongs in a
     * uniform or storage buffer.
     */
    private static final int PUSH_BYTES = 4 * Float.BYTES;

    private static final int DEFAULT_FRAMES = 300;

    // ---- content state: written by the application, read by record ----------------------------------------

    private float red = 1.0f;
    private float green = 0.6f;
    private float blue = 0.2f;

    // ---- GPU state: made in realize, released in close ----------------------------------------------------

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private MemorySegment push;

    /**
     * The base colour the gradient is tinted with — the whole of this technique's content API.
     *
     * <p>Call it from the engine's frame callback. Returning {@code this} is only so a caller can chain; the
     * important property is that the engine's vocabulary contains no word for what these three numbers mean.
     */
    public HelloTechnique tint(float r, float g, float b) {
        this.red = r;
        this.green = g;
        this.blue = b;
        return this;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        // The one cast the SPI documents (D3). A technique that needed only formats and a render-pass handle
        // would not do this; one that creates Vulkan objects needs a device, and this is where that is
        // admitted rather than hidden behind a fake abstraction.
        VulkanDevice device = ((VulkanTechniqueContext) ctx).device();

        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                // no vertex buffer (the fullscreen triangle is generated from gl_VertexIndex), no vertex
                // attributes, no descriptor sets, no blending, fragment-stage push constants, dynamic viewport
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, PUSH_BYTES, true)
                // Depth state exactly when the shared pass has depth. Wrong in either direction is invisible:
                // state without an attachment is invalid usage the loader may not report, and an attachment
                // without state means draws land in submission order and read as a technique-ordering bug.
                .withDepth(ctx.hasDepth()
                        ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                        : GraphicsPipeline.Config.Depth.NONE);

        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                Fullscreen.triangleVertexWithUvSpirv(), "main", fragmentSpirv(), "main", config);

        // One object for every command this will issue, and the scratch memory they need. Before this class
        // existed each technique resolved five method handles by hand and opened an arena of its own.
        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(PUSH_BYTES);
    }

    @Override
    public void record(FrameContext frame) {
        MemorySegment cmd = frame.commandBuffer();

        cmds.bindPipeline(cmd, pipeline);

        // The clock is the frame's. Deriving animation from frame.timeSeconds() rather than from a counter of
        // our own is what makes the picture the same speed at 30 and at 300 frames per second.
        float pulse = (float) (0.5 + 0.5 * Math.sin(frame.timeSeconds() * 2.0));
        push.setAtIndex(JAVA_FLOAT, 0, red);
        push.setAtIndex(JAVA_FLOAT, 1, green);
        push.setAtIndex(JAVA_FLOAT, 2, blue);
        push.setAtIndex(JAVA_FLOAT, 3, pulse);
        cmds.pushFragment(cmd, pipeline, push, PUSH_BYTES);

        // Three vertices, no vertex buffer: Fullscreen's vertex shader builds the triangle from
        // gl_VertexIndex, which is the cheapest way to cover every pixel exactly once.
        cmds.draw(cmd, 3);
    }

    @Override
    public void close() {
        // Null-guarded and nulled, so a second close is a no-op — the engine calls close once, but a rollback
        // from a failed realize elsewhere can reach a technique that never got as far as making these.
        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }
        if (cmds != null) {
            cmds.close();
            cmds = null;
        }
    }

    /**
     * The fragment shader, authored as {@code core} IR and lowered to SPIR-V — never as GLSL source.
     *
     * <p>{@link ComposedShader#lower} type-checks the module before the driver sees it, which is not a nicety:
     * an {@code Ir.mul(vec2, float)} once lowered to a type-mismatched instruction that composed fine, lowered
     * fine, and then faulted inside NVIDIA's shader compiler and took the JVM down with it.
     *
     * <p>Package-private rather than private only so {@code HelloTechniqueTest} can assert it still lowers
     * without a GPU; in your own technique this is private.
     */
    static byte[] fragmentSpirv() {
        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, Ir.V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        PushConstants pc = new PushConstants(List.of(
                new PushConstants.Member("r", Ir.F32),
                new PushConstants.Member("g", Ir.F32),
                new PushConstants.Member("b", Ir.F32),
                new PushConstants.Member("pulse", Ir.F32)));

        Expr uv = new Expr.InterfaceRead(vUv);
        // colour = vec4(uv.x * r, uv.y * g, pulse * b, 1)
        Expr r = Ir.mul(Ir.x(uv), pc.read(0));
        Expr g = Ir.mul(Ir.y(uv), pc.read(1));
        Expr b = Ir.mul(pc.read(3), pc.read(2));

        Region body = Region.of(
                new Statement.InterfaceWrite(fragColor,
                        new Expr.VectorConstruct(Ir.V4, List.of(r, g, b, Ir.f(1.0)))),
                new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), body);
        CoreModule module = new CoreModule().addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
        return ComposedShader.lower(ShaderStage.FRAGMENT, module, "main").spirv();
    }

    /**
     * The other half of the example: what an application does with a technique.
     *
     * <p>Four statements — a config, a target, a pipeline, a run — and not one of them names Vulkan, a window,
     * a swapchain or a queue. {@link VexelEngine#create} finds the runtime through {@code ServiceLoader}, so
     * this method would compile and run unchanged against a second implementation.
     */
    public static void main(String[] args) {
        int frames = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_FRAMES;
        System.out.println(run(frames) + " frames presented");
    }

    /** Frames presented, separated from {@code main} so a test can drive exactly this. */
    public static long run(int frames) {
        HelloTechnique hello = new HelloTechnique();

        RenderPipeline pipeline = RenderPipeline.builder()
                .target(Target.windowed("Hello, technique", 800, 600)
                        .color(AttachmentFormat.SWAPCHAIN))
                .technique(hello)
                .build();

        long[] presented = {0};
        try (VexelEngine engine = VexelEngine.create(EngineConfig.of("HelloTechnique"))) {
            engine.run(pipeline, frame -> {
                // Per-frame application logic goes here: read input, advance the simulation, then push the
                // results to each technique through its own API. Nothing here is GPU work.
                hello.tint(1.0f, 0.6f, 0.2f);
                presented[0] = frame.frameIndex() + 1;
                return frame.frameIndex() < frames - 1;
            });
        }
        return presented[0];
    }
}
