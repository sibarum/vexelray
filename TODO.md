# VexelRay — TODO

A Vulkan engine whose organizing idea is **pluggable render techniques composited into one frame**.
`vexelray-engine` owns instance, device, surface, swapchain, render pass, depth and the frame loop, and drives
an ordered list it knows only as `RenderTechnique`. A marched SDF scene and a 2D canvas share one render pass
and one command buffer, and Fathom composes a pipeline instead of building a runtime.

This list is what turns that from working into finished. Priority tiers are ordered by **leverage**, and P0 has
a specific meaning here: *these get more expensive with every technique that ships*. Each one shapes how
techniques are written, so doing them after five techniques exist means rewriting five techniques.

**P0 and P1 are both empty.** Everything that shaped how a technique is written has landed — the threading
contract, the event surface, the authoring path — and so has every gap that was going to stay the same price
however long it waited: headless rendering with pixel capture (D23), `gl_FragDepth` from the march so
composition interleaves per pixel rather than only in order (D24), and frames in flight (D25).

What is left below is consistency, polish and documentation. None of it changes what the engine can do.

**A full `mvn test` is clean under the Vulkan validation layer** — the only message it produces is the one
`DebugMessengerTest` submits deliberately to prove the messenger works. That is a recent property and worth
keeping: pointing the layer at a whole run for the first time found a leaked surface, a pipeline built
without depth-stencil state, and a storage buffer the fragment stage was allowed to write. Run one with
`VK_LAYER_PATH` pointed at an SDK's `Bin` and `-Dvexelray.vulkan.validation`.

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

### The march writes depth, and per-pixel interleaving is measured (D24)

- [x] **`Builtin.FRAG_DEPTH` in SupirVast** — the enum entry, the `BuiltIn` decoration, and the
      `DepthReplacing` execution mode, declared only when a module actually writes the built-in.
      `FragDepthShaderTest` checks both directions with `spirv-val`
- [x] **`ClipDepth`** in `vexelray-shader` — near, far, and the one mapping from a camera-space distance to
      a `[0,1]` clip depth. The projection convention as a *value*, because a shared depth attachment does
      not make two techniques agree about depth, it only makes them write to the same place. Its central
      warning is that a march's `t` is **radial** and a depth buffer holds the **planar** distance, so
      `ofRadial` takes the cosine as an argument a caller must supply rather than a step they can forget
- [x] **`SdfScene.clipDepth()`** — derived, not stored, so its far plane cannot drift from the march's
      `farPlane`; the scene gains only `nearPlane`, the one number of the convention it chooses
- [x] **The march writes it.** `SdfComposer` computes the ray's cosine to the forward axis once per pixel
      (before the rotation, since a rigid rotation does not change that angle), and both branches write
      depth — the hit from its own `t`, the miss from the far plane, because a branch that writes none leaves
      the pixel's depth undefined. `SdfRaymarchTechnique` now declares `TEST_AND_WRITE` wherever the shared
      target has depth
- [x] **`DepthInterleaveTest`** — a flat depth and a ramp, with the ramp drawn *second* and losing half the
      frame anyway. Ordering cannot produce that picture, which is what makes it evidence. Two controls: no
      depth attachment (the last technique wins everything), and the list reversed (identical bytes)
- [x] **`MarchDepthTest`** — two marched spheres at disjoint depth ranges, occluding at the geometry. The
      near sphere keeps all 1696 of its pixels; the far one keeps 396 of 1058, and both silhouettes match
      their predicted areas to within 1%
- [x] **`MarchProjectionTest`** — the radial-vs-planar check, and the only one that catches a dropped cosine:
      two marched surfaces are scaled by the same factor at the same pixel, so they cannot detect it. A
      marched wall against a planar reference wall behind it. Verified by deliberately breaking the cosine,
      which drops the marched wall from 16384 pixels to 4208 — a bowl, exactly as predicted — while
      `MarchDepthTest` goes on passing

### Frames in flight, and the validation layer earning its keep (D25)

- [x] **The windowed runtime honours `EngineConfig.framesInFlight`** (1–3). Per slot: a command buffer, an
      in-flight fence and an image-available semaphore. Per swapchain image: a depth attachment, a
      framebuffer and — the subtle one — the render-finished semaphore, because a present's wait completes
      at a moment no application can observe. Plus an in-flight table, since acquire may hand back an image
      whose previous frame is still running. The threading contract is untouched: this duplicates GPU
      objects, not threads
- [x] **The native structs stay shared**, and the TODO's assumption that push-constant arenas need
      per-slot copies turned out to be wrong: every Vulkan call reads its host memory during the call, so
      what cannot be shared is the objects, not the structs that point at them
- [x] **The default stays at one, with a new reason.** Not "it is all the runtime does" any more but "it is
      what both present paths do" — an offscreen run is always one frame in flight, and D23's argument that
      a capture is evidence about a windowed frame is worth more than throughput no test wants. An offscreen
      run asked for more says so through `Diagnostics`
- [x] **`FramesInFlightTest` and `PresenterResizeTest`**, both asserting the validation layer stays silent —
      the only measurement available, because frames in flight changes no picture. Both skip honestly when
      the layer is absent, via a new `VulkanInstance.validationLayerActive()`: `VulkanDebugMessenger
      .available()` tests the *extension*, which is present nearly everywhere, so gating on it made the
      assertion vacuous. Verified by breaking the rebuild, which produces five distinct VUID violations
- [x] **Two real bugs the layer found immediately**, both pre-existing and both invisible without it: the
      engine leaked a `VkSurfaceKHR` per windowed run (every hand-wired demo destroys one; the engine that
      replaced them did not), and `GraphicsPipeline` omitted `pDepthStencilState` for `Depth.NONE`, which is
      invalid whenever the pass *has* depth — exactly the hybrid frame's canvas technique

---

### A canvas on a plane in the world (D27)

- [x] **`vexelray-technique-panel`** — the 2D canvas projected through the scene's camera, sharing the frame's
      depth buffer. `Panel` places a sheet of canvas coordinates in the world (centre, yaw/pitch/roll in the
      camera's own convention, world units per canvas unit) and resolves it to an affine canvas-to-view map per
      frame, allocating nothing; `PanelShader` is the canvas uber-shader with the camera in front of it;
      `PanelTechnique` records it, `TEST_ONLY` by default and `occluding()` when it must hold its ground
      against a later technique
- [x] **Nothing in it has a pixel size**, which is the point. A width is a measurement on a surface, so it
      scales with distance — and the *edge* does not, because the fragment stage computes its own AA width from
      the closed-form screen-space Jacobian of the projection instead of assuming one canvas unit is one pixel.
      No texture, so no resolution was chosen in advance; no `fwidth`, so no new SupirVast instruction. MSDF
      text gets the same factor the other way up and stays sharp as a panel is approached
- [x] **`ClipDepth.clipZ`** — the raster half of D24's convention. `ofViewZ` is `a - b/z`, so `a*z - b` is
      affine in the view position and the rasteriser's own interpolation of `z/w` reproduces the march's depth
      curve exactly. The panel keeps early-z rather than writing `gl_FragDepth` to compute the same number
- [x] **`CanvasShader.coverage`, `Attributes` and `Varyings` are public seams**, so the world placement reuses
      the coverage core rather than forking it — the two stages differ in two scalars, both of which are the
      identity for the screen-space canvas. `Canvas` itself is unchanged: it already stamped the raw canvas
      position into every vertex for the clip SDF, which is the seam a world placement needed
- [x] **`PanelTechnique.atlas(AtlasSource)`** — an atlas built at realise from the device the runtime hands
      over. Without it, text on a panel was available only to an application that wires its own Vulkan, because
      an `AtlasTexture` needs a device and a pipeline is composed before one exists
- [x] **Three checks.** `PanelProjectionTest` (no device): the march's ray through the pixel a panel point
      projects to points back at that point — the check that catches a sign, where four y conventions meet and
      three of the four ways to be wrong still render a plausible picture. `PanelOcclusionTest`: a sphere and a
      panel pitched through it, each losing pixels to the other, which ordering cannot produce; plus the
      reversed frame byte-identical, a no-depth control, and the default depth state measured on its own.
      `PanelScaleTest`: the width doubles when the distance halves *and* matches the pinhole's absolute
      prediction, while the partially covered pixels down its column stay at about two at both distances

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

- [ ] **A panel the march can sample (the sibling D27 did not build).** `vexelray-technique-panel` draws its
      canvas as geometry, which is why it stays sharp and why it exists only inside the pass that draws it.
      The other half of the fork — canvas into a `SampledColorTarget`, sampled onto a placed quad — makes the
      result an *image*, and an image is a descriptor the march itself could read: a screen visible in a
      reflection, or a panel that lights the wall behind it. Most of it exists already (`SampledColorTarget`
      renders a canvas and ends in `SHADER_READ_ONLY`; `SampledSurfaceDemo` samples one onto a tilted quad),
      and it would share `Panel` rather than reinventing placement. What it costs is the thing the vector path
      does not have: a resolution, chosen in advance.
- [ ] **A panel is told the focal length and the `ClipDepth` by hand.** `PanelTechnique`'s constructor takes
      both and its javadoc says they must be the scene's; nothing enforces it, and a panel projecting through a
      different lens from the frame it stands in looks like a placement bug. A `Lens` value carrying focal
      length and depth convention together — handed to the scene *and* to the panel — would make that agreement
      structural. Worth doing when a second technique needs the same two numbers, which is the point at which
      the pattern is real rather than anticipated.

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
