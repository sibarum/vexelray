# Writing a render technique

A **technique** is VexelRay's one extension point: a unit of rendering that writes colour, and optionally depth,
into a frame it shares with other techniques. An application lists techniques in a `RenderPipeline`; the engine
records them in that order into one render pass, one command buffer, one colour+depth target. That is the whole
model. The engine never learns what any technique draws — it does not know the words "SDF", "mesh" or "camera"
— so a new kind of rendering is a new module that implements the interface, with no change to the engine.

This guide is for writing one. [`architecture.md`](architecture.md) has the reasons the engine is shaped this
way; the javadoc on `RenderTechnique` is the contract in full and wins if the two ever disagree.

---

## 1. What the engine gives you, and what it keeps

The engine owns everything that is the same for every technique: the instance, the device and its queues, the
window, the swapchain and its framebuffers, the fences and semaphores, the render pass's begin and end, the
clear, the submit and the present. A technique never touches any of them.

What a technique gets is a command buffer **inside a render pass that is already begun**, with the viewport and
scissor already set to the frame's size and nothing else bound. It binds its own pipeline, pushes its own
per-frame data, draws, and returns. It must not begin or end the render pass, and must not submit.

The reason for drawing the line there: every technique that owned its own swapchain would be the only thing on
screen. Owning nothing but its draws is what lets a ray-marched scene, a rasterised mesh and a 2D overlay land in
one frame and occlude each other per pixel, rather than being rendered into three images and pasted together.

## 2. The SPI

Four types, in two modules.

**`vexelray-engine-api`** (`dev.vexelray.engine`) — no Vulkan in it:

| Type | What it is |
| --- | --- |
| `RenderTechnique` | the interface: `realize(TechniqueContext)`, `record(FrameContext)`, `close()` |
| `TechniqueContext` | given at realise time: `colorFormat()`, `depthFormat()`, `hasDepth()`, `width()`, `height()`, and `renderPass()` — the `VkRenderPass` handle as a `long` |
| `FrameContext` | given at record time, a record: `commandBuffer()` (a Panama `MemorySegment`), `frameIndex()`, `timeSeconds()`, `deltaSeconds()`, `width()`, `height()` |

**`vexelray-engine-vulkan-api`** (`dev.vexelray.engine.vulkan`):

| Type | What it is |
| --- | --- |
| `VulkanTechniqueContext` | `TechniqueContext` plus `device()`, the `VulkanDevice` every object you create belongs to |

The split is deliberate. A technique that only needs formats and a render-pass handle compiles against the
engine API alone and never sees Vulkan. A technique that creates anything — a pipeline, a buffer, a descriptor
set — needs the device, and there is no honest way to hand that over without naming Vulkan, so it is named in a
module of its own. You reach it with one cast in `realize`:

```java
VulkanDevice device = ((VulkanTechniqueContext) ctx).device();
```

That cast is the documented coupling, not a leak: a backend abstraction is deferred until there is a second
backend. What the split *does* guarantee is that a technique depends on a contract and never on a runtime — so
the same technique runs under either host the engine has (§6).

A technique module's dependencies are therefore `vexelray-engine-api`, `vexelray-engine-vulkan-api`, and
`vexelray-vulkan` for the object wrappers (`GraphicsPipeline`, `DrawCommands`, `VertexBuffer`, …). It does
**not** depend on `vexelray-engine`; tests and demos that run a real engine take it at `test` scope, as
`vexelray-technique-canvas/pom.xml` does.

## 3. The lifecycle

| Method | When | Does |
| --- | --- | --- |
| `realize(ctx)` | once, before the first frame | everything that makes a GPU object: compose the shader, build the pipeline against `ctx.renderPass()`, allocate buffers |
| `record(frame)` | every frame | bind, push, draw. Makes nothing |
| `close()` | once per successful `realize` | release what `realize` made |

Three guarantees make this simple to write against:

- **`close` never races a frame.** The engine waits for the device to go idle first, so nothing you free is
  still being read.
- **A failed `realize` elsewhere is cleaned up for you.** If a later technique's `realize` throws, the engine
  closes the ones already realised, in reverse order. Make `close` null-guarded so it is safe on a technique
  that got only part of the way.
- **`realize` is never called twice over live objects.** It runs again only when the target is genuinely
  rebuilt beneath the pipeline (a new format, a new target), and always after a `close`.

**A resize is not a rebuild.** Resizing the window rebuilds the swapchain images and framebuffers but not the
render pass — a render pass depends on formats, and formats do not change with size — so your pipeline stays
valid and `realize` is *not* called. The size changes anyway, every frame and without warning. So:

- build the pipeline with **dynamic viewport and scissor** (the engine sets both before your `record`);
- read the size from `frame.width()`/`frame.height()`, never from a `ctx.width()` cached at realise time.

Caching the realise-time width is the bug that passes every test that never resizes a window.

## 4. Per-frame data is your API, not the engine's

There is no channel in `FrameContext` for "the camera" or "the drawing". Each technique declares its own content
API — `SdfRaymarchTechnique.camera(...)`, `CanvasTechnique.draw(...)`, `HelloTechnique.tint(...)` — and the
application calls it from the engine's frame callback. `record` then turns whatever was last set into bytes in
whatever push-constant or buffer layout this technique declared.

That is what lets techniques with completely different push-constant layouts and descriptor sets share one
command buffer without agreeing on anything. It also keeps the expensive part out of the render pass: build
geometry, lay out text, walk your scene in the callback, and keep `record` to binding and drawing.

The flip side is that two techniques that need the same number are each told it. An application with a march and
a mesh calls `camera(...)` on both, with the same five arguments. That is on purpose: a camera owned by the
engine would make the engine know what a camera is.

## 5. Threading

**Every method on a technique runs on one thread: the one that called `VexelEngine.run`.** Within a frame the
order is: the frame callback, then every technique's `record` in pipeline order. Nothing is ever concurrent and
nothing re-enters, so fields are plain — no `volatile`, no locks. All four built-in techniques are written that
way.

The one rule that is not simply "single-threaded": a frame can arrive from **inside the platform's event pump**,
during a Win32 modal move or resize, while the host's own loop is suspended. So `record` must never block on work
the host loop would have driven.

If you want a content API other threads can call — a worker building next frame's geometry — say so in your
technique's javadoc and supply the memory barrier yourself. Otherwise assume, and document, render-thread-only.

## 6. Depth and composition

Techniques record in list order into **one** colour attachment and, if the target declares one, **one** depth
attachment. Order is the composition when depth is absent or ignored; depth decides when it is not.

The shared state:

- the colour and depth attachments are cleared at the start of the pass; depth clears to `1.0`;
- the depth test is `COMPARE_OP_LESS`;
- `TechniqueContext.hasDepth()` says whether the pass has a depth attachment at all.

Your pipeline declares one of three states, `GraphicsPipeline.Config.Depth`:

| State | For |
| --- | --- |
| `TEST_AND_WRITE` | opaque geometry — what makes two techniques occlude each other per pixel |
| `TEST_ONLY` | something that must be hidden by what is in front of it without hiding what comes after — a translucent overlay in the world |
| `NONE` | something meant to sit in front of everything, in submission order — 2D chrome. Also the **only** legal state when the pass has no depth |

Match it to `ctx.hasDepth()`. Both mistakes are silent: depth state in a pass without depth is invalid usage the
loader does not always report, and no depth state in a pass with depth means your draws land in submission order,
which looks exactly like a technique-ordering bug.

### Agreeing about what depth *means*

A shared depth attachment only makes techniques write to the same place. For their numbers to be comparable they
must share a **convention**: the same near and far planes, the same curve between them, and the same answer to
whether "depth" is distance to the eye or to the image plane. In VexelRay that convention is a value,
`dev.vexelray.shader.ClipDepth`, carried with the focal length in a scene's `Lens`. Take it from the scene —
`scene.lens()` — rather than choosing your own numbers; a technique with its own near plane will consistently win
or lose, and it will look like a bug in whichever one lost.

- A **rasterising** technique emits `ClipDepth.clipZ(viewZ)` as `gl_Position.z`. The hardware's interpolation then
  reproduces the march's depth curve exactly, and early-z is kept.
- A **marching** technique writes `gl_FragDepth` with `ClipDepth.write(t, cosForward)`. The `cosForward` is not
  optional: a sphere-tracer's `t` is radial, a rasteriser's depth is planar, and writing `t` raw makes a flat
  marched wall bulge toward the camera at the edges of the screen. A fragment that writes `gl_FragDepth` on one
  path must write it on every path, so a ray that missed uses `missed()` (sky, drawn at the far plane) or
  `missedUnseen()` (no sky — the pixel is left to whatever else is drawn there).

Writing `gl_FragDepth` costs early-z for that pipeline. For a fullscreen march that is the price of being
occludable at all; for anything with real geometry, `clipZ` is the better path.

## 7. The four built-in techniques

Each is its own module, written against exactly the SPI above, and each is worth reading as an example of one
kind of technique.

| Module · class | What it is for | Depth |
| --- | --- | --- |
| `vexelray-technique-sdf` · `SdfRaymarchTechnique` | a scene of signed-distance surfaces (`SdfScene`), composed at runtime into one fullscreen ray-march. The same field is what the CPU evaluates for collision. Content API: `camera(x, y, z, yaw, pitch)`, `parameter(id, value)`; `mask(...)` and `cullToBounds()` restrict which pixels march | writes `gl_FragDepth` from the hit distance, through `ClipDepth` |
| `vexelray-technique-canvas` · `CanvasTechnique` | 2D shapes, text and sampled images from the immediate-mode `Canvas`, in one fat-vertex batch and one draw (plus a rebind per image run). Content API: `draw(canvas -> …)` from the frame callback | `NONE` — chrome sits in front of the frame in submission order, and depth would make a translucent edge occlude its own neighbours |
| `vexelray-technique-panel` · `PanelTechnique` | the same canvas on a plane in the world, projected through the scene's camera. Vector art, not a texture: no resolution was chosen, so approaching it shows more detail rather than more blur. Content API: `camera(...)`, `draw(...)` | `clipZ`; tests and does not write by default (it is alpha-blended); `occluding()` turns the write on for an opaque panel |
| `vexelray-technique-mesh` · `MeshTechnique` | polygon meshes through the rasteriser, projected by the march's own pinhole and lit by the scene's `Shading`, so meshes and marched surfaces light and occlude alike. Meshes upload once at realise; `Instance`s move every frame as push constants. Not simulated — a mesh has no CPU twin | `clipZ`, `TEST_AND_WRITE`; the result is the same wherever it sits in the list |

A typical 3D pipeline is a march, then meshes and panels that share its depth, then a canvas over the top:

```java
SdfRaymarchTechnique march = new SdfRaymarchTechnique(scene);
MeshTechnique meshes = new MeshTechnique(scene.lens(), scene.shading());
CanvasTechnique hud = new CanvasTechnique(1280, 720);

RenderPipeline pipeline = RenderPipeline.builder()
        .target(Target.windowed("Demo", 1280, 720)
                .color(AttachmentFormat.SWAPCHAIN)
                .depth(AttachmentFormat.DEPTH32F))   // without this, order alone composites
        .technique(march)
        .technique(meshes)
        .technique(hud)
        .build();
```

`HybridFrameTest` checks that two independently-authored techniques — different vertex inputs, descriptor sets
and push-constant layouts — coexist in one render pass and command buffer. `MeshOcclusionTest` and
`PanelOcclusionTest` measure the per-pixel interleaving headlessly, on an offscreen target, rather than leaving
it to be looked at.

## 8. Walk-through: `HelloTechnique`

`vexelray-demo/src/main/java/dev/vexelray/demo/HelloTechnique.java` is the smallest complete technique, in main
source, written to be copied. It draws a pulsing gradient — the least interesting picture that proves every step
happened. Read it alongside this section.

**State, in two groups.** Content state the application writes (`red`, `green`, `blue`) and GPU state made in
`realize` and released in `close` (`pipeline`, `cmds`, `push`). Plain fields, per §5.

**The content API.** One method:

```java
public HelloTechnique tint(float r, float g, float b) { … }
```

The engine has no word for what these numbers mean, which is the point (§4).

**`realize`.** Cast for the device; describe the pipeline; build it against the shared render pass:

```java
VulkanDevice device = ((VulkanTechniqueContext) ctx).device();

GraphicsPipeline.Config config = new GraphicsPipeline.Config(
        0, List.of(), new long[0],          // no vertex buffer, no attributes, no descriptor sets
        false,                              // no blending
        Vk.SHADER_STAGE_FRAGMENT_BIT, PUSH_BYTES,
        true)                               // dynamic viewport and scissor — §3
        .withDepth(ctx.hasDepth()
                ? GraphicsPipeline.Config.Depth.TEST_AND_WRITE
                : GraphicsPipeline.Config.Depth.NONE);   // match the pass — §6

this.pipeline = new GraphicsPipeline(device, ctx.renderPass(), ctx.width(), ctx.height(),
        Fullscreen.triangleVertexWithUvSpirv(), "main", fragmentSpirv(), "main", config);

this.cmds = new DrawCommands(device);
this.push = cmds.allocatePushConstants(PUSH_BYTES);
```

`DrawCommands` is the one object for every command a technique issues — bind, push, bind vertex buffer, bind
descriptor set, draw — plus the scratch memory they need. Use it rather than resolving Vulkan entry points by
hand; before it existed, each technique carried the same forty lines of Panama boilerplate.

The push-constant block is 16 bytes (`r, g, b, pulse`). Vulkan guarantees only 128 bytes of push constants
across all stages, so they are for per-frame scalars; a light list or a transform array belongs in a buffer.

**The shader is `core` IR, not GLSL.** `fragmentSpirv()` builds the fragment as a SupirVast `CoreModule` — an
interface input for the UV, an output for the colour, a `PushConstants` block, an expression for each channel —
and lowers it with `ComposedShader.lower`, which type-checks it before the driver sees it. That check is not a
nicety: a mistyped `Ir.mul(vec2, float)` once composed and lowered cleanly and then crashed inside the driver's
shader compiler, taking the JVM with it. The vertex stage is SupirVast's `Fullscreen.triangleVertexWithUvSpirv()`,
which makes a triangle covering the screen from `gl_VertexIndex` alone.

**`record`.** Bind, write the push constants, push, draw three vertices:

```java
MemorySegment cmd = frame.commandBuffer();
cmds.bindPipeline(cmd, pipeline);

float pulse = (float) (0.5 + 0.5 * Math.sin(frame.timeSeconds() * 2.0));
push.setAtIndex(JAVA_FLOAT, 0, red);
push.setAtIndex(JAVA_FLOAT, 1, green);
push.setAtIndex(JAVA_FLOAT, 2, blue);
push.setAtIndex(JAVA_FLOAT, 3, pulse);
cmds.pushFragment(cmd, pipeline, push, PUSH_BYTES);

cmds.draw(cmd, 3);
```

Animation reads `frame.timeSeconds()`, not a counter of its own, so the picture runs at the same speed at 30 and
at 300 frames a second. Nothing is allocated here.

**`close`.** Null-guard each object, close it, null it, so a second call or a close after a partial realise is a
no-op.

**The application side** is four statements, none of which names Vulkan, a window or a swapchain:

```java
HelloTechnique hello = new HelloTechnique();

RenderPipeline pipeline = RenderPipeline.builder()
        .target(Target.windowed("Hello, technique", 800, 600)
                .color(AttachmentFormat.SWAPCHAIN))
        .technique(hello)
        .build();

try (VexelEngine engine = VexelEngine.create(EngineConfig.of("HelloTechnique"))) {
    engine.run(pipeline, frame -> {
        hello.tint(1.0f, 0.6f, 0.2f);          // per-frame data, through the technique's own API
        return frame.frameIndex() < frames - 1; // false ends the run
    });
}
```

`VexelEngine.create` finds the runtime through `ServiceLoader`, so `vexelray-engine` need only be on the runtime
class path. `run` has an overload that also takes an Atchung! bus, onto which the engine publishes `EngineEvents`
— including `TECHNIQUE_REALIZED` and `TECHNIQUE_CLOSED` for each technique.

**Running it.** After `mvn install` (see the README for the sibling projects it needs):

```bash
mvn -pl vexelray-demo exec:exec -Dfathom.mainClass=dev.vexelray.demo.HelloTechnique -Dfathom.args=300
```

`exec:exec` runs a fresh JVM with `--enable-native-access=ALL-UNNAMED`, which the Panama calls need. The argument
is the number of frames.

### Testing a technique: `HelloTechniqueTest`

`vexelray-demo/src/test/java/dev/vexelray/demo/HelloTechniqueTest.java` has two tests, and the split is the
pattern to copy:

- **Device-free:** `fragmentSpirv()` composes, type-checks and lowers to something long enough to be a SPIR-V
  module. No GPU, no window, so it runs in CI everywhere — and it is the half that catches a mistyped IR
  expression before it reaches a driver. (`fragmentSpirv` is package-private for this test alone; in your own
  technique it would be private.)
- **Through the engine:** run eight frames and assert at least one was presented. It first looks for an
  `EngineProvider` whose `isAvailable()` is true and skips with `assumeTrue` when there is none, so a machine
  without a Vulkan device or a display skips rather than fails.

To check a picture rather than "it ran", render to `Target.offscreen(w, h)` instead of a window: the run ends
after the frames the callback asks for, and `engine.lastFrameRgba()` returns the last one as bytes to count
pixels in. That is how `MeshOcclusionTest` and `PanelOcclusionTest` measure composition.

## 9. A second host

The same technique, unchanged, also runs under `vexelray-engine-embedded`'s `SampledTechniqueHost`. That host
owns no window, swapchain or loop: it composites an ordered technique list into a `SampledColorTarget` somebody
else minted — a GUI viewport, which the GUI then draws as a box with chrome around it. It realises on the first
`render(...)`, re-realises (after closing) when `retarget(...)` hands it a new target, and calls everything on the
caller's thread. Whether depth exists is that target's answer, which is one more reason to branch on
`ctx.hasDepth()` rather than assume it.

A technique that keeps to this guide — everything it makes in `realize`, the size from the frame, depth state
from the context, per-frame data through its own API — cannot tell which host is driving it.

## 10. Checklist

- [ ] Depends on `vexelray-engine-api` and `vexelray-engine-vulkan-api`, not on `vexelray-engine`.
- [ ] Every GPU object is made in `realize` and released in a null-guarded `close`.
- [ ] Pipeline built against `ctx.renderPass()`, with dynamic viewport and scissor.
- [ ] Size read from `FrameContext`, never cached from realise time.
- [ ] Depth state chosen from `ctx.hasDepth()`; depth values from the scene's `ClipDepth`.
- [ ] Per-frame data through the technique's own methods, called from the frame callback; `record` only binds,
      pushes and draws, and never blocks.
- [ ] Shaders authored as `core` IR and lowered with `ComposedShader.lower`, with a device-free test that they
      still lower.
- [ ] Javadoc says which methods are render-thread-only (all of them, unless you say otherwise).
