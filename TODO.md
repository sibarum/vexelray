# VexelRay — TODO

A Vulkan engine whose organizing idea is **pluggable render techniques composited into one frame**. As of
`1f1b33b` that idea has a runtime behind it: `vexelray-engine` owns instance, device, surface, swapchain, render
pass, depth and the frame loop, and drives an ordered list it knows only as `RenderTechnique`. A marched SDF
scene and a 2D canvas share one render pass and one command buffer, and Fathom composes a pipeline instead of
building a runtime.

This list is what turns that from working into finished. Priority tiers are ordered by **leverage**, and P0 has
a specific meaning here: *these get more expensive with every technique that ships*. Each one shapes how
techniques are written, so doing them after five techniques exist means rewriting five techniques. Everything
below P0 is real work that stays the same price.

Longer-range capability research lives in [`docs/architecture.md`](docs/architecture.md),
[`docs/vexel-world.md`](docs/vexel-world.md) and [`docs/surface-compiler.md`](docs/surface-compiler.md); this
file is about the engine and its API.

---

## Done

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

---

## P0 — Before more techniques exist

> Each of these shapes how a technique is written. They are cheapest now and get more expensive monotonically.

- [ ] **Give `RenderTechnique` a threading contract**, even if the answer is "render thread only, for now."
      There is currently *nothing* — not a note, not a `@NotThreadSafe`, not a sentence. Every technique written
      from here will assume something different, and the assumptions will disagree silently. The contract should
      say who calls `realize`, who calls `record`, whether a technique may touch its own state from another
      thread between frames, and what happens on `close` while a frame is in flight.
- [ ] **Decide whether the engine is a publisher.** Atchung is Fathom's input plumbing and nothing else; the
      engine publishes no frame, resize, device-lost or technique-lifecycle event, so **event-based scripting has
      no surface to attach to**. `VexelEngine.run(pipeline, callback)` is one callback, and `FrameCallback` is
      the wrong shape to grow into a bus. It has exactly one user today — after more, changing it is a breaking
      change to the front door.
- [ ] **`TechniqueCommands` helper, plus one reference technique in main source.** The same ~40 lines of Panama
      boilerplate are now written four times (`TintTechnique`, `SdfRaymarchTechnique`, `CanvasTechnique`,
      `FathomTechnique`): resolve `vkCmdBindPipeline`/`vkCmdPushConstants`/`vkCmdDraw`/`vkCmdSetViewport`/
      `vkCmdSetScissor`, allocate a shared arena, and set viewport and scissor from the frame. Dynamic viewport
      and scissor are arguably the *runtime's* job — it already knows the extent and could set them once before
      the recorder runs. The only example technique lives in test scope, so a third party writing technique #1
      has nothing to read.

      Each of the four also carries a private copy of `invoke(MethodHandle, Object...)` — and
      `Ffm.invoke`/`Ffm.invokeVoid` have been public in `vexelray-vulkan.vk` all along. Four independent
      reinventions of an existing public helper is the clearest signal that the technique-authoring path has no
      worked example: nobody writing one has been shown what the substrate already provides.

---

## P1 — Real gaps, stable price

- [ ] **`gl_FragDepth` from the march.** The depth plumbing is exercised end to end and nothing writes
      meaningful depth, so per-pixel interleaving — the entire argument for N techniques in one pass over
      rendering to textures and compositing — is **still unproven**. Both current techniques declare
      `Depth.NONE` and say why. Costs early-z for that pipeline; needs a smoke where a marched surface and a
      second technique occlude each other, which is the picture that would prove it.
- [ ] **Frames in flight > 1.** `EngineConfig.framesInFlight` accepts 1–3 and the runtime honours exactly 1;
      `EngineConfigTest` pins the default to what the presenter actually does rather than to what the config
      claims. Needs per-frame command buffers and sync, and a depth image per frame — `DepthAttachment` is one
      image shared by every swapchain image, which is safe *only* at one frame in flight. That hazard is
      documented in the class and is still a trap. Depends on the P0 threading contract.
- [ ] **Move `VulkanTechniqueContext` out of `vexelray-engine`.** Techniques currently compile against the
      runtime *implementation* to reach a device, so every technique is coupled to the concrete engine. Either a
      small `engine-vulkan-api` module holding just that interface, or put the device behind an interface in
      `engine-api`.
- [ ] **Stop allocating per frame in `SdfRaymarchTechnique.record`.** It calls
      `SdfComposer.pushConstantBytes(...)`, which allocates a `ByteBuffer` and a `byte[]` every frame, then
      copies into the arena. Write the floats straight into the `MemorySegment`. This sits next to code with
      careful comments about exactly this.
- [ ] **Implement `Target.Kind.OFFSCREEN` in the engine.** It is authorable and throws. Until it exists, every
      engine-level test needs a window and a GPU, so `TwoTechniqueTest`/`SdfEngineTest`/`HybridFrameTest` can
      only ever skip in a headless environment rather than run — and none of them can count pixels, because the
      windowed path does no readback.

---

## P2 — Consistency and polish

- [ ] **Two colour types.** `canvas.Color` (float RGBA) and `Surface.Rgb` (linear RGB) in one project. Pick
      one, or state the boundary between them in both javadocs.
- [ ] **`Canvas.Run.image` is `Object`**, which the technique `instanceof`-checks back to `SampledImage`. The
      layering intent is right (canvas knows nothing about textures) and the cost is type safety at the seam;
      a canvas-side marker interface would keep the intent and lose the cast.
- [ ] **`vkFormat(int components)` duplicated** between `CanvasTechnique` and three canvas demos.
- [ ] **`ResourceManager`: implement or delete.** Nothing implements it, which is why
      `TechniqueContext.resources()` had to be removed — the accessor could not have been honoured by any
      runtime. It is forward-looking rather than superseded, so it survived generation one's deletion, but an
      interface with no implementor is a guess.
- [ ] **Convert the five remaining hand-wiring demos.** `CanvasDemo`, `DynamicCanvasDemo`,
      `SampledSurfaceDemo`, `TextWindowDemo`, `Sdf2DWindowDemo` still build their own swapchain and presenter.
      Deliberate for now — they are the only coverage of `WindowedPresenter`'s single-pipeline path, which still
      exists and still has to work. Convert them *after* that path is either kept on purpose or retired.

---

## P3 — Docs and infrastructure

- [ ] **`docs/architecture.md` §3, §5 and §6 are materially wrong.** §6 still says there is no runtime behind
      the front door; the module table predates `vexelray-engine` and `vexelray-technique-canvas` and still
      lists the deleted pass model; §5's capability table understates depth and composition.
- [ ] **`docs/refactor-decisions.md` stops at D16.** This week's decisions are undocumented: deleting
      generation one, the `EngineConfig`/`Target` split, `ServiceLoader` discovery, depth in the substrate, the
      `Recorder` seam, techniques-as-modules, and `CoreCheck`.
- [ ] **`CoreCheck` does not check `MathCall`**, deliberately — GLSL built-ins are polymorphic in ways `core`
      does not record, and a wrong shape table is worse than none because it rejects correct shaders and then
      gets switched off. A per-function arity and shape table would close the last real gap in the checker.
- [ ] **Push the operand-type check upstream into SupirVast.** `Expr.Binary` accepts mismatched operands and
      reports `lhs.type()` as its own; `CoreCheck` catches that at VexelRay's lowering seam, but every other
      SupirVast consumer is still one typo from a driver fault.
