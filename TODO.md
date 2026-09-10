# VexelRay — TODO

A Vulkan engine whose organizing idea is **pluggable render techniques composited into one frame**.
`vexelray-engine` owns instance, device, surface, swapchain, render pass, depth and the frame loop, and drives
an ordered list it knows only as `RenderTechnique`. A marched SDF scene and a 2D canvas share one render pass
and one command buffer, and Fathom composes a pipeline instead of building a runtime.

This list is what turns that from working into finished. Priority tiers are ordered by **leverage**, and P0 has
a specific meaning here: *these get more expensive with every technique that ships*. Each one shapes how
techniques are written, so doing them after five techniques exist means rewriting five techniques.

**P0 is empty.** Everything that shaped how a technique is written has landed: the threading contract, the
event surface, and the authoring path. What is left below is real work that stays the same price.

Longer-range capability research lives in [`docs/architecture.md`](docs/architecture.md),
[`docs/vexel-world.md`](docs/vexel-world.md) and [`docs/surface-compiler.md`](docs/surface-compiler.md); this
file is about the engine and its API. Decisions are logged in
[`docs/refactor-decisions.md`](docs/refactor-decisions.md).

---

## Done

### Generation one, and the runtime that replaced it (D19)

- [x] **Generation-one API deleted** — the sealed `Pass` enumeration (a closed set of render modes, the smell
      architecture.md §2 names), `FrameGraph`, `RuntimeManager` (written against LWJGL), `SurfaceTarget` (GLFW).
      16 files, unreferenced outside `vexelray-core`
- [x] **One front door** — `AttachmentFormat` + `Target` consolidated into `dev.vexelray.target`;
      `dev.vexelray.pipeline` gone, so "pipeline" names exactly one thing
- [x] **`EngineConfig` split at the device boundary** — pre-device knowledge only; the surface belongs to
      `Target`, which arrives with a pipeline
- [x] **`VexelEngine.create`** — `ServiceLoader` over `EngineProvider`, so nothing in the public API names the
      Vulkan runtime, not even to construct it
- [x] **Depth in the substrate** — `DepthAttachment` (D32_SFLOAT), a second attachment slot in
      `VulkanRenderPass` that lowers to the byte-identical colour-only pass when `NO_DEPTH` is passed,
      `GraphicsPipeline.Config.Depth` as three named states, presenter-owned and rebuilt on resize
- [x] **`WindowedPresenter.Recorder`** — the eight commands between `vkCmdBeginRenderPass` and
      `vkCmdEndRenderPass` move out; fence, acquire, semaphores, submit, present and rebuild stay
- [x] **`vexelray-engine`** — realise-with-rollback, close-failures-as-suppressed, offscreen target refused
      rather than silently windowed, non-`SWAPCHAIN` colour format reported through `Diagnostics.dropped`
- [x] **`vexelray-technique-sdf` / `vexelray-technique-canvas`** — techniques as modules; `vexelray-vulkan`'s
      test scope is down to `vexelray-shader`, so the substrate knows about no feature at all
- [x] **Fathom on the engine** — window, instance, device selection, device, surface, swapchain, render pass,
      pipeline, presenter and frame loop deleted, with ten imports; `FathomTechnique` is the first technique
      authored outside the engine's own modules
- [x] **`CoreCheck` in `ComposedShader.lower`** — a type check before the driver sees the SPIR-V, after
      `Ir.mul(vec2, float)` reached `nvgpucomp64.dll` and killed the JVM
- [x] **The checks that render are in the build** — `MarchSmokeTest`, `TwoTechniqueTest`, `SdfEngineTest`,
      `HybridFrameTest`, `DebugMessengerTest`, skipping visibly where there is no device
- [x] **`ConeMarchSmoke` draws again** — broken since `79ed04c` by the deprecated `cameraBytes` leaving
      `focalLength` unwritten; 12,403 non-sky pixels, the count `02402e7` recorded

### What P0 was

- [x] **`RenderTechnique` has a threading contract (D20)** — render thread only, and the render thread is the
      thread that called `VexelEngine.run`; never concurrent, never nested; fields plain; `close` after a
      device wait-idle. The one rule that is not "single-threaded" is that a frame can arrive from inside the
      platform's event pump during a modal resize, so `record` must not block. Stated on `RenderTechnique`,
      cross-referenced from `VexelEngine`, `FrameContext` and `VulkanEngine`
- [x] **The engine is a publisher (D17)** — `VexelEngine.run(pipeline, bus, onFrame)` publishes
      `EngineEvents` (`RUN_STARTED`, `FRAME_STARTED`, `RESIZED`, `TECHNIQUE_REALIZED`, `TECHNIQUE_CLOSED`,
      `DEVICE_LOST`, `RUN_ENDED`) onto an Atchung! bus — the same bus Tactroller's input already reaches, so
      event-based scripting has a surface to attach to. `FrameCallback` stays for the application's own tick.
      Device loss is a type now (`DeviceLostException`), so `DEVICE_LOST` is raised structurally rather than by
      matching an exception message
- [x] **`DrawCommands`, and the runtime sets the viewport (D21)** — the ~40 lines of Panama boilerplate written
      independently four times are one substrate class; the four private copies of `invoke(MethodHandle, ...)`
      are gone in favour of `Ffm`, which had been public all along; dynamic viewport and scissor are set once
      by the runtime before any technique records
- [x] **A worked example in main source** — `HelloTechnique` in `vexelray-demo`: the smallest complete
      technique, written to be read, with a `main` that runs it through the engine and a test that keeps it
      honest

### Since

- [x] **Techniques compile against a contract, not a runtime (D18)** — `VulkanTechniqueContext` is an
      interface in `vexelray-engine-vulkan-api`; the engine's `SharedTargetContext` implements it from
      `dev.vexelray.engine.vulkan.runtime`, so there is no split package and no technique import changed
- [x] **No per-frame allocation in `SdfRaymarchTechnique.record`** — `SdfComposer.writePushConstants` and
      `ParamBlock.writeFloats` write into caller-owned storage; the old path rebuilt the scene's `ParamBlock`
      (a walk of the surface tree) and allocated a `float[]` and a `byte[]` every frame
- [x] **`FrameContext.deltaSeconds` carries the frame's delta** — it had been hard-coded to zero
- [x] **Two colour types, with the boundary stated (D22)** — `canvas.Color` (float RGBA, compositing) and
      `Surface.Rgb` (double RGB, shading), each javadoc naming the other and why. `SdfScene.Rgb`, a third and
      identical copy, is deleted
- [x] **`Canvas.Run.image` is an `ImageHandle`**, not `Object` — a marker in `vexelray-core` so canvas and
      vulkan share it without either naming the other
- [x] **`vkFormat(int)` deduplicated** — `GraphicsPipeline.VertexAttribute.floats(...)`, replacing four copies
- [x] **`ResourceManager` deleted** — with `GpuBuffer`, `BufferUsage`, `MemoryDomain`. Nothing implemented it,
      which is why `TechniqueContext.resources()` had to go; an interface with no implementor is a guess
- [x] **`docs/architecture.md` §3–§6 and `docs/refactor-decisions.md` (D17–D22)** brought up to date

### `Target.Kind.OFFSCREEN`, and the first check that counts pixels (D23)

- [x] **`OffscreenPresenter`** beside `WindowedPresenter` — colour image + optional `DepthAttachment` +
      framebuffer + command pool + two command buffers + fence + a permanently-mapped readback buffer, all
      created once; `frame(pushBytes, perFrame, recorder)` into a begun pass, `readRgba()` after. The copy is
      pre-recorded and submitted on demand, so a run that never reads pays nothing. `VulkanRenderPass` and
      `DepthAttachment` reused unchanged
- [x] **The engine runs an offscreen target**, and `VexelEngine.lastFrameRgba()` hands back the frame it
      finished on. `driveTechniques` and `loop` are shared by both paths through an internal `FrameTarget`, so
      a capture is evidence about what a window would show rather than about a second implementation. An
      offscreen run without a frame callback is refused before a device is touched — nothing else could end it
- [x] **`OffscreenEngineTest` asserts pixels.** Exact bytes over a whole 64×64 capture, and a two-technique
      pipeline run twice with the list reversed, which *measures* that order is the composition. Before this,
      a pipeline whose fragment shader wrote nothing passed every engine-level test in the build
- [x] **`Recorder` and `FrameUpdate` are top-level**, not nested in `WindowedPresenter` — both presenters take
      them and the engine hands the same lambda to each
- [x] **`VkStructs`, and `VkStructsTest` to pin it** — the layouts that appeared privately in four classes with
      field names already disagreeing. Done with the presenter rather than after it, because the alternative
      was a fifth copy; the test pins every size and the offsets a padding mistake moves, which is the failure
      no compiler catches. `OffscreenReadback` also loses its private clone of `Ffm`
- [x] **`VulkanInstance` resolves its `VK_KHR_surface` commands lazily** — eager resolution made every
      extension-less (headless) instance fail in the constructor, naming a surface function to a caller that
      had never mentioned surfaces

---

## P1 — Real gaps, stable price

- [ ] **`gl_FragDepth` from the march.** The depth plumbing is exercised end to end and nothing writes
      meaningful depth, so per-pixel interleaving — the entire argument for N techniques in one pass over
      rendering to textures and compositing — is **still unproven**. Both current techniques declare
      `Depth.NONE` and say why.

      **This needs a SupirVast change first.** `core`'s `Builtin` has `POSITION` and `VERTEX_INDEX` and no
      `FRAG_DEPTH`; adding it is the enum entry (type `float`, output), the `BuiltIn` decoration in
      `CoreToSpirv`, and the `DepthReplacing` execution mode on the fragment entry point. Writing the march in
      hand-authored GLSL to sidestep that is not available — the render path is `core` IR by rule, because the
      CPU lowers the identical function.

      Then, in VexelRay: the march already has the hit distance `t`, and turning it into a clip depth needs a
      **projection convention shared with whatever it is occluding against**. There is none today — the march
      is a camera plus a focal length, with no near/far. That convention is the real design work; the shader
      change is small once it exists.

      Costs early-z for that pipeline. Needs a check where a marched surface and a second technique occlude
      each other — and **that check can now be written**: `OffscreenEngineTest` runs a pipeline headlessly and
      asserts exact pixels, so occlusion is a count rather than a screenshot somebody looks at. The
      measurement half is no longer the blocker; the projection convention is.

- [ ] **Frames in flight > 1.** `EngineConfig.framesInFlight` accepts 1–3 and the runtime honours exactly 1;
      `EngineConfigTest` pins the default to what the presenter actually does rather than to what the config
      claims. Needs per-frame command buffers and sync, a depth image per frame — `DepthAttachment` is one
      image shared by every swapchain image, which is safe *only* at one frame in flight — and per-frame-slot
      copies of each technique's push-constant arena, which is one block written every frame and safe today
      only because the previous frame has been waited on. The threading contract does **not** change: this
      duplicates GPU resources, not threads.

---

## P2 — Consistency and polish

- [ ] **The pipeline-state struct layouts are still declared twice.** `VkStructs` (D23) absorbed the layouts
      the four presenter/readback classes shared, but `OffscreenRenderer` still carries its own
      `VkPipelineShaderStageCreateInfo`, `VkPipelineVertexInputStateCreateInfo`, the viewport/rasterisation/
      multisample/colour-blend states and `VkGraphicsPipelineCreateInfo` — a second copy of what
      `GraphicsPipeline` has. Smaller than the one D23 closed and less urgent, because the two copies build
      pipelines that are deliberately different; the real question underneath is whether `OffscreenRenderer`
      should build a pipeline at all now that `OffscreenPresenter` exists to drive one it is handed.
- [ ] **Convert the five remaining hand-wiring demos.** `CanvasDemo`, `DynamicCanvasDemo`,
      `SampledSurfaceDemo`, `TextWindowDemo`, `Sdf2DWindowDemo` still build their own swapchain and presenter.
      Deliberate for now — they are the only coverage of `WindowedPresenter`'s single-pipeline path, which
      still exists and still has to work. Convert them *after* that path is either kept on purpose or retired.
- [ ] **`WindowedPresenter`'s single-pipeline path: keep or retire.** It carries `configureDraw`,
      `setVertexCount`, `setRuns` and a `pushConstantBytes` argument that exist only for the demos above. Decide
      before either grows another user; the answer decides the item above it.

---

## P3 — Docs and infrastructure

- [ ] **`CoreCheck` does not check `MathCall`**, deliberately — GLSL built-ins are polymorphic in ways `core`
      does not record, and a wrong shape table is worse than none because it rejects correct shaders and then
      gets switched off. A per-function arity and shape table would close the last real gap in the checker.
- [ ] **Push the operand-type check upstream into SupirVast.** `Expr.Binary` accepts mismatched operands and
      reports `lhs.type()` as its own; `CoreCheck` catches that at VexelRay's lowering seam, but every other
      SupirVast consumer is still one typo from a driver fault.
- [ ] **`README.md`'s module map drifts.** It has been corrected twice now by hand after a module moved. It is
      derivable from the reactor's `<module>` list and each pom's `<description>`; generating it would make the
      drift impossible rather than noticed late.
