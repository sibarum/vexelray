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
vexelray-core          Value vocabulary: Attachment, AttachmentFormat, Target, EngineConfig, Frame,
                       ResourceManager (iface), GpuBuffer, LightingModel. No SupirVast, no Vulkan, no technique.
      ▲
vexelray-engine-api    THE public API: the pipeline-building DSL (RenderPipeline + builder), the
                       RenderTechnique SPI, Technique/FrameContext, RuntimeManager/VexelEngine ifaces, FrameGraph.
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
vexelray-engine        Runtime impl: implements RuntimeManager/VexelEngine, realises a pipeline, owns the frame
                       loop + present targets (windowed swapchain / offscreen), drives techniques.
      ▲
vexelray-technique-sdf   First technique: SdfScene + SdfComposer today, RenderTechnique once the runtime lands.
      ▲                                                              (later: -raster, -splat, …)
vexelray-demo (Fathom)   Reference client app. Ships the -Pnative single-binary profile.

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

**Substrate vs runtime.** `vexelray-vulkan` holds Vulkan *object wrappers* (device, swapchain, pipeline,
buffers). `vexelray-engine` holds *orchestration* (frame loop, present targets, technique driving). Techniques
target the Vulkan runtime directly — a backend abstraction is deferred (YAGNI until a second backend exists).

**Current vs target.** The topology above is the target the refactor moves toward, and it is half arrived.
`vexelray-engine-api` exists and carries the SPI plus the pipeline DSL; `vexelray-technique-sdf` exists as a
module; `-text` and `-canvas` landed beside `-surface`. What is *not* there is `vexelray-engine` — there is no
runtime behind the front door — so today the runtime is still low-level and `Fathom` hand-wires it, as do the
demos under `vexelray-vulkan`. Read the front door as designed and validated rather than as load-bearing until
that module exists. §6 has the current state.

---

## 4. Public API — the front door

A client writes two things: **how techniques compose** (the pipeline) and **what each technique renders**
(its content). Everything below the line — instance, device, swapchain, sync — is the engine's, never the app's.

```java
// Compose the pipeline — the public authoring API.
RenderPipeline pipeline = RenderPipeline.builder()
    .target(Target.windowed()
        .color(AttachmentFormat.SWAPCHAIN)
        .depth(AttachmentFormat.DEPTH32F))     // depth is always present, so composition is never a retrofit
    .technique(new SdfRaymarchTechnique(scene))
    // .technique(new SpriteTechnique(...))     // add techniques to composite a hybrid, sharing that depth
    .build();

// Run it.
try (VexelEngine engine = VexelEngine.create(EngineConfig.windowed("Fathom", 800, 600))) {
    engine.run(pipeline, frame -> { /* input + CPU sim -> per-frame data (camera, time) */ });
}
```

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
| Runtime ownership | instance/device/swapchain/present, frame loop (1 frame in flight) | RuntimeManager/VexelEngine facade; frames-in-flight; resize-robust |
| Present targets | windowed swapchain + headless offscreen→PNG | both behind one `Target`; screenshot/record built in |
| Render techniques | SDF raymarch (path, not yet modular) | SDF, polygon raster, Gaussian splats — as modules, composable |
| Composition / hybrid | single technique per pass; a pass's output **sampled into another** — `SampledColorTarget` → `Canvas.image` (a marched region inside a 2D frame) | N techniques sharing one colour+**depth** target (cross-occlusion) |
| Shaders | runtime SDF composed as `core` IR → SPIR-V | full technique-authored shaders; a reusable SDF-scene layer |
| Render == sim | SDF evaluated CPU + GPU from one IR; sphere-trace collision | physics/queries against the render field; GPU/CPU placement |
| Resources | ad hoc per class | `ResourceManager` impl; pooled/suballocated memory |
| Lighting | inline in the SDF shader | pluggable `LightingModel`s folded into composition |
| Input / events | Tactroller snapshot → `tactroller-atchung` bridge → Atchung bus; Fathom subscribes (edges as `Topic<InputEvent>`, pointer as `State<PointerState>`) | same fabric; add GUI/recorder/network consumers with no core change |
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

The **next architectural move** is still the technique refactor in §3–4, now half-landed.
`vexelray-engine-api` is stood up (the SPI plus the pipeline DSL) and the SDF path is wrapped as
`vexelray-technique-sdf`. What remains is the part that changes how anything *runs*: a real runtime in
`vexelray-engine`, which does not exist as a module yet, and moving `Fathom` onto the front-door API. Until
then the working demos drive `vexelray-vulkan` directly — the front door is expressible and validated
(`HybridPipelineTest`) rather than load-bearing, and it is worth being plain about which of those it is.
