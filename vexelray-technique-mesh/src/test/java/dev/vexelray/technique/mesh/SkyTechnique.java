package dev.vexelray.technique.mesh;

import dev.supirvast.vastir.core.Builtin;
import dev.supirvast.vastir.core.CoreModule;
import dev.supirvast.vastir.core.EntryPoint;
import dev.supirvast.vastir.core.Expr;
import dev.supirvast.vastir.core.Function;
import dev.supirvast.vastir.core.InterfaceVar;
import dev.supirvast.vastir.core.LocalVar;
import dev.supirvast.vastir.core.PushConstants;
import dev.supirvast.vastir.core.Region;
import dev.supirvast.vastir.core.ShaderStage;
import dev.supirvast.vastir.core.Statement;
import dev.supirvast.vastir.tools.Fullscreen;
import dev.supirvast.vastir.type.Type;
import dev.supirvast.vulkan.Vk;
import dev.supirvast.vulkan.VulkanDevice;
import dev.vexelray.engine.FrameContext;
import dev.vexelray.engine.RenderTechnique;
import dev.vexelray.engine.TechniqueContext;
import dev.vexelray.engine.vulkan.VulkanTechniqueContext;
import dev.vexelray.ir.Ir;
import dev.vexelray.shader.ComposedShader;
import dev.vexelray.shader.Lens;
import dev.vexelray.vulkan.present.DrawCommands;
import dev.vexelray.vulkan.present.GraphicsPipeline;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static java.lang.foreign.ValueLayout.JAVA_FLOAT;

/**
 * Prototype (test scope): {@link Sky} behind everything. A fullscreen triangle whose fragment builds the pixel's ray
 * exactly as the march does ({@code SdfComposer.primaryRay}) and colours it by the sky.
 *
 * <p>It sits a hair in front of the far plane and tests depth without writing it, so drawn after everything else
 * it fills exactly the pixels nothing real has claimed. That matters because a march paints its own flat
 * {@code SdfScene.sky()} where it misses, a float in front of the far plane: this sits one float nearer still, so
 * it paints over that and nothing else.
 */
final class SkyTechnique implements RenderTechnique {

    private static final int PUSH_FLOATS = 4;
    private static final int PUSH_BYTES = PUSH_FLOATS * Float.BYTES;

    private final double focalLength;
    private double yaw;
    private double pitch;

    private GraphicsPipeline pipeline;
    private DrawCommands cmds;
    private MemorySegment push;

    SkyTechnique(Lens lens) {
        this.focalLength = lens.focalLength();
    }

    /** The scene's camera direction; where the eye stands does not matter to a sky at infinity. */
    void camera(double yaw, double pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
    }

    @Override
    public void realize(TechniqueContext ctx) {
        VulkanDevice device = ((VulkanTechniqueContext) ctx).device();
        GraphicsPipeline.Config config = new GraphicsPipeline.Config(
                0, List.of(), new long[0], false, Vk.SHADER_STAGE_FRAGMENT_BIT, PUSH_BYTES, true)
                .withDepth(ctx.hasDepth()
                        ? GraphicsPipeline.Config.Depth.TEST_ONLY
                        : GraphicsPipeline.Config.Depth.NONE);
        this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
                Fullscreen.triangleVertexWithUvSpirv(), "main",
                ComposedShader.lower(ShaderStage.FRAGMENT, fragment(), "main").spirv(), "main", config);
        this.cmds = new DrawCommands(device);
        this.push = cmds.allocatePushConstants(PUSH_BYTES);
    }

    @Override
    public void record(FrameContext frame) {
        MemorySegment cmd = frame.commandBuffer();
        push.setAtIndex(JAVA_FLOAT, 0, (float) yaw);
        push.setAtIndex(JAVA_FLOAT, 1, (float) pitch);
        push.setAtIndex(JAVA_FLOAT, 2, frame.height() == 0 ? 1f : (float) frame.width() / frame.height());
        push.setAtIndex(JAVA_FLOAT, 3, (float) focalLength);
        cmds.bindPipeline(cmd, pipeline);
        cmds.pushFragment(cmd, pipeline, push, PUSH_BYTES);
        cmds.draw(cmd, 3);
    }

    @Override
    public void close() {
        if (pipeline != null) {
            pipeline.close();
        }
        if (cmds != null) {
            cmds.close();
        }
    }

    private static CoreModule fragment() {
        List<PushConstants.Member> members = new ArrayList<>();
        for (String name : new String[]{"yaw", "pitch", "aspect", "focal"}) {
            members.add(new PushConstants.Member(name, Ir.F32));
        }
        PushConstants pc = new PushConstants(members);
        InterfaceVar vUv = InterfaceVar.input("vUv", Fullscreen.UV_LOCATION, Ir.V2);
        InterfaceVar fragColor = InterfaceVar.output("fragColor", 0, Ir.V4);
        Expr u = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 0);
        Expr v = new Expr.VectorExtract(new Expr.InterfaceRead(vUv), 1);
        Expr yaw = pc.read(0);
        Expr pitch = pc.read(1);
        Expr focal = pc.read(3);

        // SdfComposer.primaryRay, the same arithmetic: screen to camera frame, then pitch, then yaw.
        Expr sx = Ir.mul(Ir.sub(Ir.mul(u, Ir.f(2.0)), Ir.f(1.0)), pc.read(2));
        Expr sy = Ir.sub(Ir.f(1.0), Ir.mul(v, Ir.f(2.0)));
        Expr cp = Expr.MathCall.cos(pitch);
        Expr sp = Expr.MathCall.sin(pitch);
        Expr py = Ir.sub(Ir.mul(sy, cp), Ir.mul(focal, sp));
        Expr pz = Ir.add(Ir.mul(sy, sp), Ir.mul(focal, cp));
        Expr cy = Expr.MathCall.cos(yaw);
        Expr sny = Expr.MathCall.sin(yaw);
        Expr rx = Ir.add(Ir.mul(sx, cy), Ir.mul(pz, sny));
        Expr rz = Ir.sub(Ir.mul(pz, cy), Ir.mul(sx, sny));

        LocalVar dir = new LocalVar("dir", Ir.V3);
        LocalVar colour = new LocalVar("colour", Ir.V3);
        List<Statement> body = new ArrayList<>();
        body.add(new Statement.DeclareVar(dir, Expr.MathCall.normalize(Ir.v3(rx, py, rz))));
        body.add(new Statement.DeclareVar(colour, Sky.colour(new Expr.Read(dir))));
        // Two floats short of the far plane's 1.0: in front of a cleared pixel (1.0) and of a march's own sky (at
        // most one float short, which ties a LESS test), behind anything real.
        body.add(new Statement.BuiltinWrite(Builtin.FRAG_DEPTH, Ir.f(Math.nextDown(Math.nextDown(1.0f)))));
        body.add(new Statement.InterfaceWrite(fragColor, new Expr.VectorConstruct(Ir.V4, List.of(
                Ir.x(new Expr.Read(colour)), Ir.y(new Expr.Read(colour)), Ir.z(new Expr.Read(colour)),
                Ir.f(1.0)))));
        body.add(new Statement.ReturnVoid());
        Function main = new Function("main", new Type.FunctionType(Type.VOID, List.of()), new Region(body));
        return new CoreModule().addFunction(Sky.FUNCTION).addEntryPoint(EntryPoint.of(main, ShaderStage.FRAGMENT));
    }
}
