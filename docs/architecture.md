# VexelRay — Target Architecture

> **Living document, high-level.** This is the north star, not a snapshot. It deliberately separates **what
> exists today** from **where we're going**; the two differ, on purpose, and the gap is the roadmap. Details
> (exact types, signatures) live in code and in [`native-bindings.md`](native-bindings.md); this stays at the
> level of shapes and intent.

---

## 1. Thesis

VexelRay is a Vulkan engine whose organizing idea is **pluggable render techniques composited into one frame.**
"SDF raymarch," "polygon raster," and "Gaussian splat" are not built-in modes — they are *techniques*, any of
which (first- or third-party) can be wired into a pipeline, alone or in combination, sharing one colour+depth
target. The core knows only "techniques that write colour and depth, in order" — never the word "SDF."

Two properties set it apart:

- **Runtime-composed shaders.** Shaders are generated at runtime as SupirVast `core` IR and lowered to validated
  SPIR-V — the engine composes *meaning*, SupirVast owns the *encoding*.
- **Render == sim, provably.** SupirVast lowers one `core` IR to **both** GPU SPIR-V **and** an executable CPU
  (Truffle) AST with verified agreement. So an SDF drawn on the GPU is the *same function* the CPU evaluates for
  collision, line-of-sight, and physics — no separate collision representation, no drift.

It owns its runtime end to end (instance → device → swapchain → present) via hand-rolled **Panama** bindings —
no LWJGL, no GLFW — targeting a single native-image binary.

---

## 2. Principles & invariants

- **Techniques are open, not a sealed set.** Adding a renderable kind = publishing a new `vexelray-technique-*`
  module that implements the SPI; core is untouched. A closed enumeration of render modes is a smell.
- **One field, drawn and simulated.** Where a technique's representation is CPU-evaluable (SDF), rendering and
  simulation come from the same IR. Never maintain a second representation for physics.
- **Design content to the pipeline's grain.** For SDF: curved, mostly-convex geometry — no long flat parallel
  walls. This dodges the sphere-tracing worst case *and* keeps the one-field invariant (a polygon bounding shell
  would re-split render/sim). The constraint pays twice.
- **Own the runtime; no third-party natives.** Direct-to-OS via Panama, native-image-safe, per the normative
  [binding convention](native-bindings.md). Multi-platform from day one; the build selects the platform.
- **A module per concern.** If "should this be its own module?" is a reasonable question, the answer is yes.
  Aggregate modules into folders **only once flatness becomes an organizational problem** — never preemptively.
- **The public API is pipeline-building.** Composing a pipeline (target + ordered techniques) is the front door a
  client writes, not an internal detail.

---

## 3. Architecture

Layered modules; dependencies point downward. Nested aggregator folders are deferred until the module list
itself hurts (see principles).

```
vexelray-ir            Terse vocabulary for authoring core IR by hand: constants, vectors, arithmetic, and
                       core's type discipline (broadcast, typed zero). Depends on vastir alone.
      ▲
vexelray-core          Value vocabulary: AttachmentFormat, Target, ImageHandle, EngineConfig, LightingModel.
                       No SupirVast, no Vulkan, no technique.
      ▲
vexelray-engine-api    THE public API: the pipeline-building DSL (RenderPipeline + builder), the
                       RenderTechnique SPI, Technique/FrameContext, the VexelEngine facade + EngineProvider
                       service, and EngineEvents (the topics the engine publishes onto an Atchung! bus).
      ▲                         ▲
vexelray-shader        vexelray-vulkan        Composition seam (SupirVast) · Vulkan+Panama substrate
  + Shading/ShaderCache          ▲              (bindings, instance/device/swapchain, buffers, pipeline wrappers)
      ▲                          │
vexelray-surface       Surfaces as data -> a marchable distance field, with the Lipschitz analysis and the
      ▲                symbolic derivative that make an arbitrary implicit safe to march. Also strokes,
      │                per-vertex colour, Bounds, and Cones (the same geometry handed out as numbers).
      │
vexelray-text          MSDF atlas model, glyph layout, the MSDF shader as core IR.  (on -shader)
      ▲
vexelray-canvas        2D immediate-mode API: one fat-vertex batch for shapes, text and sampled images, and
      ▲                its uber-shader as core IR.  (on -text, so on -shader)
      └────────┬────────────────┘
vexelray-engine-vulkan-api   The Vulkan half of the SPI: VulkanTechniqueContext, the subtype a technique casts
      ▲                      to when it must create GPU objects. Its own module so a technique compiles against
      │                      a contract, never against the runtime below it (D18).
      ├───────────────────────────────┐
vexelray-engine        Runtime impl:  │  vexelray-technique-sdf    SdfScene + SdfComposer + SdfRaymarchTechnique
                       implements     │  vexelray-technique-canvas CanvasTechnique
                       VexelEngine,   │                            (later: -raster, -splat, …)
                       realises a     │
                       pipeline, owns │  A technique depends on -engine-api and -engine-vulkan-api. It does NOT
                       window/device/ │  depend on -engine: the runtime depends on the contract, not the
                       swapchain/pass/│  reverse, so a second Vulkan runtime serves the same techniques.
                       depth + the    │
                       frame loop,    │
                       drives         │
                       techniques,    │
                       publishes      │
                       EngineEvents.  │
      ▲                               ▲
vexelray-demo (Fathom)   Reference client app: Fathom, HelloTechnique (the worked example), HybridFrameSmoke.
                         Ships the -Pnative single-binary profile.

vexelray-os  (+ os-windows / os-linux / os-macos)   Direct-OS Panama layer: window + Vulkan surface.
Tactroller (external, sibling repo)                 Input devices (pointer/keyboard). Same Panama +
                                                    ServiceLoader + native-image convention; consumed, not rebuilt.
Atchung (external, sibling repo)                    Event/message fabric. Input, sim, GUI meet here as
                                                    producers/consumers — no direct coupling between them.
```

**Input and events are Tactroller + Atchung, not `vexelray-os`.** `vexelray-os` owns only **window + surface**.
Two sibling first-party projects supply the rest:

- **[Tactroller](../../tactroller)** — device acquisition. It already implements the exact convention `vexelray-os`
  uses (per-OS Panama bindings to system libraries only — `user32`, `libX11`, CoreGraphics — one `InputBackend`
  via `ServiceLoader`, native-image-clean, no bundled natives). The engine polls it on the **render thread** once
  per frame via `Tactroller.snapshot()` — no daemon, no cross-thread hand-off — with the window attached
  (`NativeWindow.ofHwnd(window.osHandle())`) for focus gating and `RAW` pointer-lock for mouselook.
- **[Atchung](../../atchung)** — the event/message fabric every component meets on. Input does not call the sim
  or GUI directly; it publishes onto the bus and they subscribe. The `tactroller-atchung` bridge makes Tactroller
  "just another producer": each frame it snapshots and republishes into the bus's **two integration shapes**.

**Two integration patterns (both used for input):**

- **Pub/Sub** — discrete *edges* (key/button press·release, scroll, focus change) go to a `Topic<InputEvent>`:
  lossless, per-topic FIFO. Fathom subscribes and folds `KeyPressed`/`KeyReleased` into a held-set. This is for
  "what happened," where every event must land.
- **State synchronization** — the *pointer position* goes to a `State<PointerState>`: coalesced, versioned,
  lock-free zero-copy reads. This is for "what is true now," where only the latest value matters and a dropped
  intermediate is harmless.

**Why route local input through a bus at all.** In-JVM, messages pass by reference straight to their destinations,
so Atchung can look superfluous — it is deliberate. The indirection is what makes future capabilities *cheap and
non-invasive*: a transport bridge (planned `atchung-elektroq`) forwards selected topics over a network without
touching the local surface; `State<T>` version numbers are the delta/keyframe hook and named commits are the
replicable unit, so **rewind/replay, remote control, session sharing, and automation** fall out of the same
design. None of that ships today, but the seams for it exist from day one — consumers already couple only to the
bus, never to Tactroller.

This does not breach "own the runtime; no third-party natives": Tactroller and Atchung *are* our runtime, factored
into their own repos — not upstreams like LWJGL/GLFW. (Atchung is pure Java: no native code, no reflection.)

**Substrate vs runtime.** `vexelray-vulkan` holds Vulkan *object wrappers* (device, swapchain, render pass,
depth, pipeline, buffers, and `DrawCommands` — the resolved command handles anything recording into somebody
else's command buffer needs). `vexelray-engine` holds *orchestration* (frame loop, present target, technique
driving, event publication). Techniques target Vulkan directly — a backend abstraction is deferred (YAGNI until
a second backend exists) — but they target the Vulkan *contract*, not this runtime.

**The engine is a publisher.** Given an `Atchung` bus, `VexelEngine.run(pipeline, bus, onFrame)` publishes
`RUN_STARTED`, `FRAME_STARTED`, `RESIZED`, `TECHNIQUE_REALIZED`, `TECHNIQUE_CLOSED`, `DEVICE_LOST` and
`RUN_ENDED` — onto the same bus Tactroller's input already reaches, so input and rendering meet in one place
with one delivery model and one thing to bridge across a process boundary. `FrameCallback` remains for the
application's own tick; it is one callback with one caller and was never the shape a script could attach to.
Everything is published from the render thread, so an inline subscriber's cost is frame time (D17).

**Threading.** `VexelEngine.run` defines the render thread: it creates the window on the caller's thread, pumps
that window's events there, and calls every technique's `realize`/`record`/`close` — and the frame callback —
from it. Nothing in the engine starts a thread. `RenderTechnique`'s javadoc states the contract in full; the
one rule that is not simply "single-threaded" is that a frame can arrive from *inside* the platform's event
pump during a modal move or resize, so a technique must not block in `record`.

**Current vs target.** The topology above has arrived. `vexelray-engine` owns instance, device, surface,
swapchain, render pass, depth and the frame loop, and drives an ordered list it knows only as
`RenderTechnique`; a marched SDF scene and a 2D canvas share one render pass and one command buffer
(`HybridFrameTest`); Fathom composes a pipeline instead of building a runtime. What is still open is listed in
[`TODO.md`](../TODO.md) — chiefly `gl_FragDepth` from the march (so composition can interleave per pixel rather
than only order), frames-in-flight > 1, and `Target.Kind.OFFSCREEN`. §6 has the current state.

---

## 4. Public API — the front door

A client writes two things: **how techniques compose** (the pipeline) and **what each technique renders**
(its content). Everything below the line — instance, device, swapchain, sync — is the engine's, never the app's.

```java
// Compose the pipeline — the public authoring API.
RenderPipeline pipeline = RenderPipeline.builder()
    .target(Target.windowed("Fathom", 800, 600)
        .color(AttachmentFormat.SWAPCHAIN)
        .depth(AttachmentFormat.DEPTH32F))     // depth is always present, so composition is never a retrofit
    .technique(new SdfRaymarchTechnique(scene))
    // .technique(new CanvasTechnique(...))     // add techniques to composite a hybrid, sharing that depth
    .build();

// Run it. EngineConfig is pre-device knowledge only; the target arrives with the pipeline.
try (VexelEngine engine = VexelEngine.create(EngineConfig.of("Fathom"))) {
    engine.run(pipeline, bus, frame -> { /* input + CPU sim -> per-frame data (camera, time) */ });
}
```

`VexelEngine.create` resolves an `EngineProvider` through `ServiceLoader`, so nothing in the public API names
the Vulkan runtime — not even to construct it. The `bus` argument is optional (there is a two-argument
overload); with one, the engine publishes `EngineEvents`.

Two public layers, cleanly separated:

- **Composition** — `RenderPipeline.builder`, `Target`, `EngineConfig`, `VexelEngine.run`. Wires *any* techniques
  together and drives the loop.
- **Content** — each `vexelray-technique-*` module's own authoring API (an SDF scene; a mesh set; a point cloud).

**Extension point:** the `RenderTechnique` SPI. A technique gets one-time setup against the shared target
(`realize`) and a per-frame `record` that binds a pipeline and issues draws into a render pass the runtime owns;
it never touches the swapchain or sync. Third parties add renderable kinds by implementing it — no core change.

---

## 5. Engine capabilities

| Capability | Today | Target |
|---|---|---|
| Runtime ownership | `VexelEngine` facade behind a `ServiceLoader` provider; owns window/instance/device/surface/swapchain/render pass/depth and the frame loop; 1 frame in flight; resize rebuilds the swapchain without re-realising a technique | frames-in-flight > 1 (per-frame command buffers, sync and depth image) |
| Present targets | windowed swapchain. `Target.Kind.OFFSCREEN` is authorable and throws; headless readback exists only as the single-pipeline `OffscreenRenderer`/`OffscreenDraw` | both behind one `Target`, so engine-level tests run and count pixels without a window; screenshot/record built in |
| Render techniques | SDF raymarch and 2D canvas as modules; `FathomTechnique` and `HelloTechnique` authored *outside* the engine's modules | polygon raster, Gaussian splats — same SPI, no core change |
| Composition / hybrid | N techniques sharing one colour+depth target, one render pass, one command buffer, in declared order (`HybridFrameTest`). **Ordering only** — both current techniques declare `Depth.NONE` and say why | per-pixel interleaving: the march writes `gl_FragDepth` from its hit distance, so a marched surface and a mesh cross-occlude |
| Technique authoring | `DrawCommands` for the Panama boilerplate; the runtime sets viewport and scissor before recording; `HelloTechnique` is the worked example in main source | a pooled allocator on `TechniqueContext` when one exists |
| Shaders | runtime SDF composed as `core` IR → SPIR-V, type-checked by `CoreCheck` before the driver sees it | `CoreCheck` covering `MathCall`; the operand-type check pushed upstream into SupirVast |
| Render == sim | SDF evaluated CPU + GPU from one IR; sphere-trace collision | physics/queries against the render field; GPU/CPU placement |
| Resources | each class owns its own allocation; `TechniqueContext` deliberately hands out no allocator, and the unimplemented `ResourceManager` interface has been deleted rather than left as a guess | a pooled/suballocated allocator, added to the context on the day there is one to return |
| Lighting | inline in the SDF shader | pluggable `LightingModel`s folded into composition |
| Input / events | Tactroller snapshot → `tactroller-atchung` bridge → Atchung bus; **and the engine publishes onto the same bus** (`EngineEvents`: run, frame, resize, technique lifecycle, device lost) | GUI/recorder/network consumers with no core change; selected topics bridged over `atchung-elektroq` |
| Platform | Windows (Panama); Linux/macOS skeletons | all three; per-OS reachability metadata |
| Packaging | JVM run + `-Pnative` profile wired | verified single native binary, driver-only |

---

## 6. Status snapshot

Working end to end on Windows/RTX: window + swapchain, offscreen readback, runtime-composed SDF shaders, a live
first-person raymarched scene with WASD, CPU/GPU render==sim collision, and SDF "sprites" (round-extruded 2D
glyphs). The `Canvas` now also **samples**: `CanvasVertex.KIND_IMAGE` is the same analytic rounded box run
through a texel read from a second descriptor set, and a frame divides into `Canvas.Run` spans that say where
the binding layer rebinds it. A canvas that drew no images is still exactly one run and one draw. With
`SampledColorTarget.renderInto` able to host a fullscreen march (optional vertex buffer, fragment push
constants), a scene rendered by one pipeline composites into a 2D frame drawn by another — which is what a
GUI viewport is made of.

The surface compiler has since grown the things a *user-authored* scene needs: `Stroke` (thick polylines whose
corners pass exactly through their vertices), per-vertex colour that costs nothing when unused, `Bounds` so a
camera can be pointed at arbitrary geometry, and — because folding a few hundred segments into a shader was
measured at five seconds of pipeline build on the frame loop — `ConeField`, the same march reading its geometry
from a storage buffer, so one pipeline serves every scene. All of it is
[`docs/surface-compiler.md`](surface-compiler.md) §3.1–§3.4.

**The technique refactor of §3–4 has landed.** `vexelray-engine` is a real runtime behind the front door: it
owns the window, instance, device, surface, swapchain, shared render pass, depth attachment and frame loop, and
drives an ordered list whose members it knows only as `RenderTechnique`. `HybridFrameTest` puts a marched SDF
scene and a canvas batch in one render pass and one command buffer — two independently-authored pipelines with
different vertex inputs, different descriptor set layouts and different push-constant layouts, compositing in
declared order. `Fathom` no longer builds a runtime; it composes a pipeline and writes a technique, and
`HelloTechnique` is the smallest complete one, in main source, for a third party to copy.

`vexelray-engine-vulkan-api` was split out so a technique compiles against `VulkanTechniqueContext` rather than
against the engine that supplies it; `DrawCommands` retired the forty lines of Panama boilerplate each of the
four techniques had reinvented, along with the four private copies of a downcall helper that had been public
all along. The engine publishes `EngineEvents` onto the same Atchung! bus input already reaches.

**What is genuinely not done**, in leverage order, is in [`TODO.md`](../TODO.md):

- **`gl_FragDepth` from the march.** Depth is plumbed end to end and nothing writes a meaningful value, so
  per-pixel interleaving — the entire argument for N techniques in one pass over rendering to textures and
  compositing — is still unproven. It needs `Builtin.FRAG_DEPTH` in SupirVast `core` (only `POSITION` and
  `VERTEX_INDEX` exist), because the march is authored as IR and must stay that way.
- **Frames in flight > 1.** `EngineConfig` accepts 1–3 and the runtime honours exactly 1. Needs per-frame
  command buffers, sync and a depth image per frame; `DepthAttachment` is one image shared by every swapchain
  image, which is safe only at one frame in flight.
- **`Target.Kind.OFFSCREEN`.** Until it exists every engine-level test needs a window, so in a headless
  environment they can only skip rather than run — and none of them can count pixels.
