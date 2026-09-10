# Technique-Refactor — Decisions Log

> **Purpose.** A running record of the *key decisions* made while moving VexelRay from its current shape onto the
> target architecture in [`architecture.md`](architecture.md) §3–4. Each entry states the decision, the
> alternatives, and why — so they can be reviewed and reversed cheaply. Per project direction: converge toward
> general-purpose, decoupled, well-engineered modular parts; things that are changeable can be imperfect, things
> that are hard to change must be as right as we can make them.
>
> Status legend: **DONE** shipped · **WIP** in progress · **PLANNED** not started.

---

## 0. The situation this refactor addresses

The codebase has **two disconnected realities**:

1. **An inert public layer** in `vexelray-core`: `RenderPipeline` + a *sealed* `Pass` set
   (`Raymarch/Raster/Compute/Post`), plus `RuntimeManager`/`VexelEngine` interfaces, `EngineConfig`, `Frame`,
   and the `ShaderComposer<D>` SPI. It is well-documented but **nothing implements or invokes it** — there is
   no `RuntimeManager` impl, nothing calls `realize()`/`run()`.

2. **A working hand-wired engine** in `vexelray-vulkan` + `Fathom`: the demo runs by directly wiring
   `VulkanInstance → VulkanDevice → VulkanSwapchain → GraphicsPipeline → WindowedPresenter`, composing the SDF
   shader inline (bypassing `ShaderComposer`), with its *own* `Frame` interface on `WindowedPresenter`.

The refactor's job is to **make the working machinery sit behind the public API**, and in doing so replace the
sealed `Pass` set with the open `RenderTechnique` SPI (architecture.md §2: "a closed enumeration of render
modes is a smell").

The four concrete gaps:

| # | Target | Today |
|---|--------|-------|
| 1 | Open `RenderTechnique` SPI; core never says "SDF" | Sealed `Pass` enum-of-modes |
| 2 | `RenderPipeline = target + ordered techniques` | `RenderPipeline = Map<Attachment> + List<Pass>` |
| 3 | `engine-api` (contract) + `engine` (impl) + `technique-sdf` modules | All in `core`; no impl; SDF inline in demo |
| 4 | Techniques build pipelines against a render pass **the runtime owns** (shared colour+depth) | `GraphicsPipeline` owns its *own* render pass — no sharing possible |

---

## 1. Migration strategy — strangler, Fathom green at every step

**DECISION (D1, PLANNED→WIP).** Build the real thing first and prove it by moving Fathom onto it, *then*
delete the inert types. Do **not** start by editing `core`'s interfaces in the abstract.

Phase order:

- **Phase 0 — SPI seam. DONE.** `vexelray-engine-api` module stood up (wired into the reactor) with
  `RenderTechnique` + `TechniqueContext`/`FrameContext`, the `Target` DSL, the technique-based `RenderPipeline`
  DSL, and the `VexelEngine` facade contract. Compiles against `core` + JDK only. No behaviour wired yet.
- **Phase 1 — Split the render pass out of `GraphicsPipeline`. DONE (build-verified).** New
  `VulkanRenderPass` component (single colour attachment, clear→store, parameterised final layout — lifted
  verbatim from `GraphicsPipeline`). `GraphicsPipeline` now takes a `long renderPass` and is a pure
  `VkPipeline`+layout+modules builder that no longer creates or destroys the pass. `WindowedPresenter` takes the
  render pass explicitly. All call sites (Fathom, `WindowedTriangleDemo` test) create a `VulkanRenderPass` and
  wire its handle into both the pipeline and the framebuffers — explicit component wiring, no magic (D8). Full
  reactor `install` green. *Runtime (on-GPU) verification of Fathom still pending — build-level only here.*
  `OffscreenRenderer` left as its self-contained monolith; it gets folded into the runtime in Phase 2.
- **Phase 2 — `vexelray-engine` + real `VulkanRuntimeManager`.** Lift the loop out of `WindowedPresenter` /
  `OffscreenRenderer` into a runtime that realises a pipeline and drives techniques. Collapse the duplicate
  `Frame` types.
- **Phase 3 — `vexelray-technique-sdf`.** Move Fathom's IR-authoring into `SdfRaymarchTechnique` + an SDF-scene
  authoring API, implemented via the `ShaderComposer<SdfScene>` SPI. The CPU-lowering (render==sim collision)
  moves here too.
- **Phase 4 — Fathom onto the front door.** Rewrite to the architecture.md §4 snippet.
- **Phase 5 — Retire sealed `Pass`.** Delete `Pass`/`*Pass`/`PassKind`; move public API types out of `core`
  into `engine-api`; `core` keeps only value vocabulary.

Rationale: each phase leaves a runnable Fathom, so regressions are caught immediately and mistakes stay cheap.

---

## 2. Module layering — where the SPI lives, and the one deviation from the diagram

architecture.md §3's diagram puts `engine-api` *below* `shader`/`vulkan` (they depend on it). On inspection that
inverts the natural dependency: `vulkan` is a substrate that shouldn't need to know the public API, and the
public API shouldn't drag in Vulkan. The target we build toward:

```
core          value vocabulary (Attachment(Format), ResourceManager iface, EngineConfig, Frame, lighting)
  ▲
engine-api    PUBLIC API: RenderPipeline DSL, Target, RenderTechnique SPI, Frame/TechniqueContext, VexelEngine iface
  ▲   ▲
  │   shader / vulkan   substrate (SupirVast composition · Panama Vulkan wrappers) — depend on core (+api where useful)
  ▲
engine        VulkanRuntimeManager: realises a pipeline, owns loop + present targets, drives techniques
  ▲
technique-sdf first technique + SDF-scene authoring; depends on engine + vulkan
  ▲
demo (Fathom)
```

**DECISION (D2).** `engine-api` depends only on `core` and the JDK. Its context types expose Vulkan handles as
**JDK primitives** (`long renderPass`, `java.lang.foreign.MemorySegment commandBuffer`) — never `vexelray-vulkan`
types — so the public contract stays binding-light without inventing a premature backend abstraction.

**DECISION (D3).** Honor architecture.md §3 "techniques target the Vulkan runtime directly — a backend
abstraction is deferred (YAGNI)." The `engine` module defines a Vulkan-bearing sub-interface of the realize
context (e.g. `VulkanTechniqueContext extends TechniqueContext`) that also exposes the `VulkanDevice`. A
technique (which depends on `engine` + `vulkan`) consumes the richer context to build its `VkPipeline`; a
technique that only needs handles works against the base interface. This keeps `engine-api` pure while giving
real techniques real device access — no fake abstraction layer.

*If a second backend ever appears, D2/D3 are the seam to generalise; until then, YAGNI.*

---

## 3. The SPI shape (Phase 0)

**DECISION (D4).** Three types, mirroring realise-once / record-per-frame:

```java
interface RenderTechnique extends AutoCloseable {
    void realize(TechniqueContext ctx);   // one-time: compose shader, build VkPipeline vs ctx.renderPass()
    void record(FrameContext frame);      // per-frame: bind, push constants, draw into the runtime's pass
    void close();                         // release GPU objects
}
```

- `TechniqueContext` (realise-time): `width/height`, `colorFormat`, `Optional<depthFormat>`, `renderPass()`
  (the shared handle), `resources()` (core `ResourceManager`). Vulkan device arrives via the `engine`-level
  subtype (D3).
- `FrameContext` (record-time): `commandBuffer()` (`MemorySegment`), `frameIndex`, `timeSeconds`,
  `deltaSeconds`, `width/height`.

**DECISION (D5) — per-frame app data flows through technique state, not raw bytes.** The `VexelEngine.run`
callback (`frame -> …`) mutates *technique-owned* state (e.g. `sdf.camera(x,y,z)`), and the technique writes its
own push constants in `record()`. This replaces Fathom's current "app writes raw push-constant bytes" model.
Rationale: the technique owns its push-constant *layout*; the app shouldn't know byte offsets. Keeps composition
open — each technique manages its own per-frame data independently.

**DECISION (D6) — pipeline is `Target` + ordered `List<RenderTechnique>`.** New `RenderPipeline` (in
`engine-api`, package `dev.vexelray.engine`) is a small record with a builder:
`RenderPipeline.builder().target(Target.windowed(...).color(...).depth(...)).technique(t).build()`. Techniques
run in declared order into one shared colour+depth target (architecture.md §4). The old attachment-graph
`RenderPipeline`/`FrameGraph` in `core` stays untouched until Phase 5 — no big-bang rename.

**DECISION (D7) — `Target` is the public authoring surface; `Attachment(Format)` are its value types.**
Per project steer (converge on the general-purpose, decoupled shape), adopt architecture.md §4's `Target` DSL
rather than exposing `SurfaceTarget`/`Attachment` directly. `Target` carries surface kind (windowed/offscreen) +
extent + colour/depth `AttachmentFormat` (reused from `core`). `core`'s `SurfaceTarget` becomes an internal
detail the runtime derives from a `Target` (or is retired) in Phase 2.

---

## 4. No magic in the base API — components you connect (D8)

**DECISION (D8).** The base public API (`engine-api`) is a set of **separate components the caller explicitly
connects** — no hidden wiring, no convenience that constructs several things behind one call, no implicit
defaults that change behaviour. Every piece (runtime, realised pipeline, target, technique, resource manager) is
a distinct object; composing them is the app's explicit act. **Additional higher-level APIs will be built atop
this one** — that is where ergonomic presets, one-call bootstraps, and sensible defaults belong.

Consequences, applied going forward:

- **The explicit runtime seam is the base API.** A `RuntimeManager`-style component (owns the device; `realize`
  a pipeline; `renderFrame`; `waitIdle`; `close`) operating on the new `RenderPipeline` is the primitive. It is
  wired up from its parts, not summoned by a factory. Landed with Phase 2, in `engine-api` (contract) + `engine`
  (impl).
- **`VexelEngine.create(...)` / `.run(...)` is explicitly a *thin convenience*, not base machinery.** It is
  documented as sugar over the explicit seam and must hide no behaviour the seam doesn't expose. If it starts to
  feel like a bootstrap that "just works," it belongs one layer up, not in the base contract. Re-evaluate its
  placement when Phase 2 makes the explicit seam concrete.
- **Named constructors/presets are suspect at the base layer.** `Target.windowed(...)`'s vsync default,
  `EngineConfig.windowed(...)`'s "validation on, double-buffered" preset, and similar are conveniences; keep them
  minimal and explicit (a named constructor that sets one obvious thing is fine), and push richer presets to the
  higher-level API rather than growing them here.
- **No global/ambient state.** Platform selection, device selection, etc. are passed in as components, never
  reached through a static "current()" from inside the base API.

*Rationale: a magic-free base keeps every seam independently testable and recomposable, and leaves room for the
higher-level APIs to make different ergonomic choices without fighting hidden behaviour underneath.*

## 5. `exec:exec` reactor fix (D9)

**DECISION (D9, DONE).** Made the documented `mvn -pl vexelray-demo -am … exec:exec` one-liner actually work.
It was already broken on `master` (not a refactor regression): `-am` pulls the root aggregator + sibling modules
into the reactor, and a direct `exec:exec` CLI goal fires on all of them — the root/siblings have no
`executable` and fail before the demo ever runs.

Two Maven subtleties drove the fix:
1. `pluginManagement` supplies only a *version* to a direct CLI goal, **not** `<configuration>` — so a skip flag
   there is ignored. The plugin must be in real `<build><plugins>` for its plugin-level config to reach the CLI
   goal.
2. `executable` is a **required** parameter, validated *before* `execute()` runs — so `skip=true` alone can't
   save a module that lacks `executable`; validation fails first.

Fix: declare `exec-maven-plugin` in the root `<build><plugins>` with `skip=true` **and** a default
`executable=${java.home}/bin/java` (inherited by all modules, no phase binding so it never runs in a normal
build). `vexelray-demo` overrides with `skip=false` + its arguments. Result: the goal no-ops on every module
except the demo. Verified: `-am … exec:exec -Dfathom.args=--verify` runs the render==sim check; full `install`
still green.

## 6. Reusable value-noise primitive in SupirVast (D10)

**DECISION (D10, DONE).** Fathom's flat floor (`ground = y(point)`) was the sphere-tracing worst case
architecture.md §2 warns about — a long flat plane whose grazing horizon rays blow the step budget and smear.
Replaced it with a smooth value-noise heightfield.

The noise (value noise + fBm, authored as pure `core` IR) went into **SupirVast** `vastir-tools` as a new
`Noise` primitive alongside `Fullscreen`, not into Fathom — per the "enhance SupirVast when it's reusable across
projects" directive. Any SupirVast consumer now has procedural noise that lowers to both GPU SPIR-V and the CPU
Truffle backend (verified: both backends implement `sin`/`floor`/`fract`/`mix`/`smoothstep`/`dot`, so render ==
sim holds — the CPU now evaluates terrain height at the same points the GPU draws).

Fathom's floor is `(y - h(x,z)) * k` with `h` a centred fBm heightfield and `k` a conservative Lipschitz factor
so the heightfield stays a non-overshooting sphere-trace distance. This also satisfies §2's "design content to
the pipeline's grain" — curved, no long flat parallels.

*Remaining: faint grazing-angle stepping streaks on the mid-field terrain (heightfield under-stepping). The
horizon complaint is resolved; the streaks are tunable (lower `k`, more march steps, or distance-relative hit
epsilon) if they bother the eye.*

## 7. `vexelray-experimental` — technique bake-off harness (D11)

**DECISION (D11, DONE).** Added a `vexelray-experimental` module (reactor leaf; nothing depends on it) as the
home for building, running, and comparing shape-definition + rendering techniques — so bake-offs stop living as
one-off hacks in Fathom.

Design: a `ShapeField` SPI (a candidate contributes only the field math as `core` IR) plugged into one shared
`Raymarcher`, so every candidate is rendered through the *identical* march + shading and differences are
attributable to the technique alone. The `ComparisonHarness` runs all candidates headlessly (offscreen, no
window — CI-reproducible) and reports, per the user's axes:
- **performance** — shader-compose time, SPIR-V size, cold render time (median), and CPU-eval ns/call (the
  render==sim side, via the Truffle backend);
- **fidelity** — RMSE of a cheap candidate render vs a high-step reference render of the same field, plus a
  labelled side-by-side montage;
- **applicability** — a qualitative note per field.

Seeded with `flat-plane`, `value-noise` (fbm2), `perlin` (fbmPerlin2). First run confirmed the whole terrain
discussion visually and numerically: value noise shows the grid quilting; perlin is organic at ~1.5× the value
cost; the flat plane is cheapest but is the grazing-horizon worst case. Outputs land in
`vexelray-experimental/target/experiments/` (per-field PNGs, `montage.png`, `report.md`).

*Render time is measured cold (OffscreenRenderer builds a pipeline per call, so driver shader-compile dominates
for large SPIR-V). It is a comparative signal, not pure GPU frame time — timestamp-query GPU timing is a noted
refinement. This module is also the natural place to prototype the control-point/B-spline surface technique next.*

## 8. Inline → function refactor for composed SDFs (D12)

**DECISION (D12, DONE, in the harness).** The ray-march was inlining the field expression at every use (march +
6 normal taps + hit ≈ 8× per fragment), so a field's shader size scaled ~8× and `perlin-analytic` (which itself
evaluates the height 5×) hit **22 MB** of SPIR-V. Changed the `Raymarcher` to emit the field as one callable
`float sdf(vec3)` `core` function (`CoreModule.addFunction` + `Expr.Call` → `OpFunctionCall`; a differential test
in SupirVast confirms call semantics agree GPU vs CPU) and call it everywhere. Result: ~30× smaller SPIR-V
(value 1.3 MB→46 KB, perlin 2.2 MB→74 KB, analytic 22 MB→746 KB) and proportional compose/render drops; CPU-eval
and fidelity unchanged (runtime work; same field); render==sim intact (CPU path lowers the same function).

*Fathom still inlines its `sceneSdf` (~9× copies, ~1 MB shader) — the same refactor should be applied there too,
folded into the Phase 2/technique work.*

## 9. Vexel world model — a research direction (D13)

**DECISION (D13, DOCUMENTED — not yet built).** The terrain/noise work opened into a larger world-representation
idea, now captured in its own north-star doc: [`vexel-world.md`](vexel-world.md). In brief: a world = a sparse
octree of small, AABB-bounded SDF primitives that soft-blend through a **material matrix**, where each node is a
"vexel" — a prefiltered, self-contained, shadeable surface definition at its scale. It is **seam-free by
construction** (proper bounded SDFs + soft-min, unlike the heightfield overshoot of D10–D12), gets **continuous
free LOD** (cone-marching prefiltered vexels → dissolve-not-fog + nearly-free DoF), frustum-culls via the octree,
and preserves render==sim (shared buffer; `exp`/`log` on both backends).

Key resolved point: the blend operator must be a **weighted soft-min (log-sum-exp, N-ary/associative)**, not
literal per-pair `smin` (non-associative). Key open problem: **faithful prefiltering** of the vexel payload
(normal-distribution/NDF to avoid specular aliasing, coverage, inter-level interpolation) — that's where the real
engineering is. Plan: prototype V0→V3 in `vexelray-experimental` as `ShapeField`s before committing; it lands
eventually as a `RenderTechnique` atop the Phase 2 runtime. See the doc's §7 for the staged plan.

## 10. Surface compiler — surfaces as data, normalized to fields (D14)

**DECISION (D14, DOCUMENTED → WIP).** Accept a *surface expression* as data and compile it into the SDF render
path at runtime. Design doc: [`surface-compiler.md`](surface-compiler.md); new module `vexelray-surface`.

The key finding, and the reason this is worth a module rather than a helper: **the compiler already exists**
(`ShapeField` is an AST, `Raymarcher.fragmentSpirv` lowers it in-process, `ShaderComposer` reserves the SDF
composer seat, `ShaderKey` is the cache identity). What does not exist is the ability to accept an expression
that is **not already a distance field** — and every surface a human types is such an expression. So the module
is a *normalization* pass, not a codegen pass:

- **`Surface`** is a sealed record tree, so structural equality — and therefore `ShaderKey` collapse — is free,
  and a surface serializes through the existing `supir` text form.
- **Lipschitz tracking during lowering.** Each node states its own bound; the gradient division is inserted only
  where a subtree cannot vouch for itself. Consequence, and the acceptance test: a hand-authored scene expressed
  as a `Surface` must lower to **byte-identical SPIR-V** — generality costs nothing when it is not used.
- **Forward-mode symbolic AD over `Expr`**, exhaustive over the sealed record set. `grad f` is itself `core` IR,
  so it lowers to both backends and render==sim survives into user-authored geometry — a typed-in surface is
  immediately CPU-queryable for collision with no second implementation.
- **`SmoothUnion` is N-ary from the start**, not a binary fold, so D13's non-associativity is not re-introduced
  by a tree that makes deep left-folds easy to build by accident.

**Alternative rejected:** generate GLSL/HLSL text from the surface. Faster to a first picture, but it forfeits
the CPU backend (so render==sim dies exactly where it is most valuable) and violates the standing rule that
render-path code is authored as core IR, never as per-backend copies.

**Known limitation of the first stage:** `f / max(|grad f|, eps)` is a local correction, not a proof — it is
safe where the gradient does not collapse ahead of the ray. Interval/affine arithmetic (staged next) is what
actually proves a march is hole-free, and it is the *same* pass the vexel world model needs for prefiltered
node bounds — which is the strategic argument for the ordering.

**S0 landed** (`vexelray-surface`, 38 tests): the tree, the lowering with Lipschitz tracking, the derivative
pass, normalisation, and the input/output limits. Two findings from measuring it that changed the design:

- **The derivative's size multiplier is not a constant.** Flat fields differentiate at 6–16x, but the
  duplication *compounds* through nesting — ~4x per nested `normalize`, so 35 nodes becomes 51,736 at depth
  four. Consequence: the planned "single pass carrying three partials" fixes only the 3x seed factor. The
  compounding comes from rules using a sub-tangent more than once, and the actual fix is to bind each tangent to
  a `LocalVar` and emit the gradient as a function body rather than a pure expression tree.
- **Bounding compiler input does not bound compiler work.** `SurfaceLimits` originally capped only the incoming
  tree, which the above walks straight through. It now caps the lowered output as well. Found by measurement,
  not by review — the input-only version looked obviously sufficient.

## 11. The lighting model's IR half, and the SDF composer (D15)

**DECISION (D15, DONE).** `vexelray-technique-sdf` stood up with `SdfComposer implements
ShaderComposer<SdfScene>` — the seat `ShaderComposer`'s javadoc reserved for "an SDF composer turns a
signed-distance scene into a fullscreen fragment". `ShaderCache` (keyed on `ShaderKey`) is wired for real, so
the "shader cache" open question below is closed. Generated SPIR-V is gated through `spirv-val` in the tests.

**`LightingModel` gets its IR method — one layer up, not where it was promised.** The interface has been
holding a placeholder: *"the IR-emitting method is intentionally absent … and will land with the first concrete
composer."* It cannot land there: `vexelray-core` is deliberately SupirVast-free and so cannot name an `Expr`.
Resolution — `dev.vexelray.shader.Shading extends LightingModel` adds `Expr shade(ShadingPoint, Bindings)`, with
`ShadingPoint` (position, normal, view, albedo, roughness, metallic) written to be filled equally by a marcher's
finite-difference normal or a rasteriser's interpolated one. Core keeps configuration; the shader seam keeps
composition. A model implementing only `LightingModel` can be named in a pipeline but not compiled into one,
which is the honest state of `cookTorrance()` today.

**`shade` takes a `Bindings`, and that is not incidental.** Written first as a bare `Expr`-returning method, the
Lambert model broadcast its diffuse term into three colour channels; the surface normal is reachable from that
term, so the normal's six field calls were emitted three times and a pixel went from 8 field samples to 20.
Same root cause as D12 and as the derivative blow-up in D14 — `core` expressions are value trees and nothing
downstream does CSE. `Bindings` lets a model bind a value once; the composer also binds the normal before
shading sees it. Now pinned by a test asserting exactly 8 `OpFunctionCall`s. **Any future IR-emitting interface
here should take a `Bindings` from the start.**

*Also decided:* a directional light is a parameter of the model, not a scene resource, so its parameters are
part of the model's `id()` — two differently-lit Lamberts must not collide in the shader cache. A light *buffer*
waits until there is a second light to shape the ABI. Camera position, orientation, and viewport aspect are push
constants, so turning the camera or resizing the window is a 24-byte upload rather than a recompile.

## 12. One IR-authoring vocabulary, in its own module (D16)

**DECISION (D16, DONE).** `Ir` — the terse helpers for writing `core` IR by hand — had been copied into
`vexelray-experimental` and `vexelray-surface`, and was starting to drift: the surface copy had grown `zero`,
`broadcast`, `scale`, and a typed-`call` escape hatch; the experimental copy had `xz` and width-specific
`mulS2`/`mulS3`. Consolidated into **`vexelray-ir`**, depending on nothing but `vastir`.

Why a module rather than picking one of the two homes: it is genuinely the layer *below* everything that emits a
shader (surfaces, shading, the SDF technique, the harness), and the alternatives were worse — putting it in
`vexelray-shader` would force `vexelray-surface` to depend on shader composition it does not use, and leaving it
in `vexelray-surface` would have `dev.vexelray.surface.Ir` serving the canvas and the harness. The project's own
principle settles it: *"if 'should this be its own module?' is a reasonable question, the answer is yes."*

Merge decisions: `mulS2`/`mulS3` dropped in favour of the width-agnostic `scale` (they were the same IR, just
told the width instead of asking the operand); `isConst` dropped as never used; `xz` kept. `Shadings` and
`ShadingPoint` moved onto it as well, rather than leaving a third copy in the module that *is* the SupirVast
seam.

**Verified output-neutral.** The generated SPIR-V was hashed before and after across both consumers — the SDF
composer's fragment and five `Raymarcher` fields, 4 KB to 765 KB — and all six are byte-identical. For a pure
refactor that is the check worth having: the test suite proves the code still works, the hashes prove it still
produces *the same thing*.

**Not moved:** `Fold` (the identity-folding constructors) stays package-private in `vexelray-surface`. It has
exactly one consumer, and its contract — only exact identities, never reassociate — is tied to that use. It
moves when a second consumer appears, not before.

**Still duplicated, deliberately:** `Fathom`, `CanvasShader`, and `Raymarcher` keep small private helper sets.
Those are local to substantial classes and folding them in is a separate sweep; `vexelray-canvas` and
`vexelray-text` would each gain a dependency for a handful of call sites.

## 13. The engine is a publisher, and Atchung! is the bus (D17)

**DECISION (D17, DONE).** `VexelEngine.run(pipeline, bus, onFrame)` publishes `EngineEvents` — `RUN_STARTED`,
`FRAME_STARTED`, `RESIZED`, `TECHNIQUE_REALIZED`, `TECHNIQUE_CLOSED`, `DEVICE_LOST`, `RUN_ENDED` — onto an
`Atchung` bus. `vexelray-engine-api` gains a dependency on `atchung-core`. The two-argument overload publishes
nothing and is unchanged for every existing caller.

**Why the engine publishes at all.** Atchung was Fathom's input plumbing and nothing else. The engine published
no frame, resize, device-lost or technique-lifecycle event, so **event-based scripting had no surface to attach
to**: a script attaches to a *running* engine it did not construct, and `FrameCallback` is one callback held by
whoever called `run`. Chaining a second interested party onto it means the code that owns the callback has to
know that party exists, and ordering between them is whatever the chaining code happened to do. It had exactly
one user, which is the cheapest moment to change the shape of a front door.

**Why the bus rather than an `EngineListener` in `engine-api`.** A listener interface would have kept
`engine-api` dependency-free, and that was the alternative considered. It loses on the thing that matters: the
input fabric is *already* an Atchung bus on the other side of the same application, so a listener would be a
second observer mechanism to learn, a second delivery model to reason about, and a second thing to bridge when
a replay or a remote viewer wants both halves of a run. One bus means one `Backpressure` policy, one set of
delivery modes (inline / async / pumped), and `atchung-elektroq` carrying selected topics over the wire with no
change here. Fathom now passes the same bus its input already reaches.

**The cost, stated.** `vexelray-engine-api` — the module whose whole claim is that it names no runtime — now
names a bus. That is a real coupling, chosen deliberately: a bus is not a backend, Atchung is pure Java with no
natives and no reflection, and it is first-party. The bus is optional at the call site.

**Delivery is the render thread.** Every event is published from it, so an inline subscriber's cost is frame
time; anything doing real work subscribes async or pumped. Events are published even with nothing subscribed —
a publish to an empty topic is a map lookup, and the bus counts it, which is the diagnostic for "the event
never arrived". Gating on `subscriberCount` would save one small record per frame and delete that signal. A run
with no bus builds no event objects at all.

**Topic names are a wire format** the moment an `ElektroBridge` carries one, so they are spelled out as
constants rather than derived from class names, and a test asserts they are distinct and namespaced.

**Device loss became a type to make one of these events truthful.** `Ffm.check` now raises
`DeviceLostException` (a `NativeException` subclass — that class stopped being `final` for this) for
`VK_ERROR_DEVICE_LOST`, rather than one more failed call with a number in its message. An event that could only
be raised by string-matching an exception message is an event nobody could trust.

## 14. A technique compiles against a contract, not against a runtime (D18)

**DECISION (D18, DONE).** `VulkanTechniqueContext` is an **interface** in its own module,
`vexelray-engine-vulkan-api`. The engine's implementation is a record, `SharedTargetContext`, in
`dev.vexelray.engine.vulkan.runtime` alongside `VulkanEngine` and `VulkanEngineProvider`.

It had been a record in `vexelray-engine`, so `vexelray-technique-sdf` and `vexelray-technique-canvas` both
compile-depended on the concrete runtime — its window, its swapchain, its frame loop — to reach one accessor.
That pointed the dependency arrow the wrong way: a runtime should depend on what techniques are written
against, not the reverse. It also meant a second Vulkan runtime — an offscreen one for tests, or one embedding
into somebody else's swapchain — could not serve the techniques that already exist.

An interface rather than a record for the same reason: what a technique needs is the device, not one runtime's
particular way of carrying it alongside four other fields.

**The runtime moved packages rather than the contract taking a new name.** `dev.vexelray.engine.vulkan` now
belongs solely to the API module, so no technique's import changed and there is no split package across two
jars — which works on the classpath today and would block JPMS or `jlink` later. The implementation is the
thing that should be hidden, so the implementation is the thing that moved. Techniques keep `vexelray-engine`
only in **test** scope, where a `ServiceLoader` lookup needs a runtime present.

## 15. Generation one deleted, and a runtime built where it stood (D19)

**DECISION (D19, DONE).** The inert public layer §0 describes is gone and `vexelray-engine` stands in its
place. Six decisions arrived together, and they only make sense together.

**Deleted, not migrated.** The sealed `Pass` enumeration (the closed set of render modes architecture.md §2
names as a smell), `FrameGraph`, `RuntimeManager` (written against LWJGL) and `SurfaceTarget` (GLFW) — 16
files, unreferenced outside `vexelray-core`. Migrating a design nothing had ever implemented would have carried
its assumptions into the thing that replaced it.

**`EngineConfig` split at the device boundary.** The config carries only what is knowable *before a device
exists*; the surface, extent and title belong to `Target`, which arrives with a pipeline at `run`. The engine
therefore survives to run a second pipeline, which is precisely why the surface could not stay on the config.

**`VexelEngine.create` resolves an `EngineProvider` through `ServiceLoader`.** Nothing in the public API names
the Vulkan runtime — not even to construct it. The alternative, a static factory in `engine-api` constructing a
class from the module above it, is either a compile-time cycle or a string handed to reflection, and reflection
is what a native-image build cannot see through. A service is declared in `module-info` (or
`META-INF/services`), which the native-image agent reads. Same convention `vexelray-os` and Tactroller use.

**Depth in the substrate, not bolted on.** `DepthAttachment` (D32_SFLOAT), a second attachment slot in
`VulkanRenderPass` that lowers to the byte-identical colour-only pass when `NO_DEPTH` is passed, and
`GraphicsPipeline.Config.Depth` as three named states, presenter-owned and rebuilt on resize. "Depth is always
present, so composition is never a retrofit" is only true if the substrate can express it.

**The `Recorder` seam.** The eight commands between `vkCmdBeginRenderPass` and `vkCmdEndRenderPass` moved out
of `WindowedPresenter`; fence, acquire, semaphores, submit, present and rebuild stayed. That line is not
tidiness — it is exactly the boundary between what every renderer needs identically and what each needs
differently, and until it existed a frame could hold exactly one pipeline, so no two features in this
repository could appear in the same window.

**Techniques as modules,** `vexelray-technique-sdf` and `vexelray-technique-canvas`, with `vexelray-vulkan`'s
test scope down to `vexelray-shader` — so the substrate knows about no feature at all. And **`CoreCheck` before
the driver sees the SPIR-V**, after `Ir.mul(vec2, float)` composed fine, lowered fine, and then faulted inside
`nvgpucomp64.dll` and took the JVM with it. A type check at VexelRay's lowering seam is the last place that
failure is a Java exception rather than a process death.

## 16. `RenderTechnique` has a threading contract (D20)

**DECISION (D20, DONE).** Render thread only, and the render thread is the thread that called
`VexelEngine.run`. `realize`, `record`, `close` and the frame callback all run there, never concurrently and
never nested. Fields on a technique are plain — no `volatile`, no lock, no atomic. A technique may publish a
thread-safe content API of its own but must say so; unless it does, every method on it is render-thread-only.
`close` never races a frame in flight: the runtime waits for the device to go idle first.

There had been **nothing** — not a note, not an annotation, not a sentence. The failure mode of that is not
"someone gets it wrong"; it is that every technique written from here assumes something slightly different, the
assumptions disagree silently, and the first symptom is a corrupted push constant on somebody else's machine.
Writing the answer down was cheap, and gets monotonically more expensive with every technique that ships —
which is what put it in P0 rather than in polish.

The one rule that is not simply "single-threaded": **a frame can arrive from inside the platform's event
pump.** During a Win32 modal move or resize the host's loop is suspended inside the OS's own and the platform
pulls frames instead. Same thread, but the application is otherwise not making progress — so a technique must
not block in `record` waiting on work the host loop would have driven.

**What would change it, and what would not.** Frames-in-flight > 1 does not: it duplicates per-frame GPU
resources, not threads. Recording techniques in parallel into secondary command buffers would — and that is a
different interface, opted into, rather than this one quietly acquiring a second caller.

## 17. `DrawCommands`, the runtime's viewport, and a worked example (D21)

**DECISION (D21, DONE).** The forty lines of Panama boilerplate every technique needs — five to seven
`device.command(...)` lookups with their `FunctionDescriptor`s, a shared `Arena`, a `VkViewport` and a
`VkRect2D` — are one class, `dev.vexelray.vulkan.present.DrawCommands`. Dynamic viewport and scissor are set by
the runtime before the recorder runs, so no technique sets them at all.

It had been written independently **four** times: `SdfRaymarchTechnique`, `CanvasTechnique`, `FathomTechnique`
and the engine's own test technique. Each of the four also carried a private copy of
`invoke(MethodHandle, Object...)` while `Ffm.invoke`/`Ffm.invokeVoid` had been public in `vexelray-vulkan.vk`
the whole time. Four independent reinventions of an existing public helper is not four mistakes; it is one
missing class, and one missing worked example.

**Named `DrawCommands`, not `TechniqueCommands`, and it lives in `vexelray-vulkan`.** It names no technique and
no feature, so the substrate keeps the property D19 gave it: a caller that is not a technique — an offscreen
draw, a tool — uses it identically, and `vexelray-vulkan` stays testable without the engine's vocabulary.

**Viewport and scissor are the runtime's job** because they are the one piece of state every recorder needs
identically and derives from a number only the runtime knows before anyone draws. Forgetting them draws
nothing; reading a stale extent draws the previous size. A recorder wanting a sub-rectangle still overrides
them and puts them back.

**A worked example in main source: `HelloTechnique`.** The only implementation of `RenderTechnique` outside a
real feature lived in the engine's *test* scope, so a third party writing technique number one had nothing to
read. It is deliberately the smallest thing that exercises every part of the contract, its javadoc is the
tutorial, and `HelloTechniqueTest` runs it — a reference implementation nothing runs is documentation that rots
silently, and the first person to follow it is the one who finds out.

*Also fixed here:* `SdfRaymarchTechnique.record` no longer calls `SdfComposer.pushConstantBytes`, which rebuilt
the scene's `ParamBlock` — a walk of the surface tree — and allocated a `float[]` and a `byte[]` every frame, a
few lines from a comment about how carefully the arena is reused. `SdfComposer.writePushConstants` and
`ParamBlock.writeFloats` write into caller-owned storage. And `FrameContext.deltaSeconds` now carries the
frame's actual delta; it had been hard-coded to zero since the context was introduced.

## 18. One colour type per side of the compositing/shading line (D22)

**DECISION (D22, DONE).** Two colour types, and the boundary is now stated in both javadocs.
`dev.vexelray.canvas.Color` is `float` RGBA — a *compositing* colour: `float` because it is one of eight
numbers in a vertex the GPU reads as `float`, with alpha because coverage is the whole business of a 2D batch.
`Surface.Rgb` is `double` RGB — a *shading* colour: `double` because it is an operand in a compiler that
carries every other number as `double` and rounds once at lowering, with no alpha because a signed-distance
surface either is or is not at a point, and there is no expression in the field for half of one. Both are
linear, so the difference is never colour space.

`SdfScene.Rgb`, a third and byte-identical copy of `Surface.Rgb` in a module that already depended on
`vexelray-surface`, is deleted; `SdfScene` uses `Surface.Rgb`.

*Also in the same sweep:*

- `Canvas.Run.image` and `Canvas.image(...)` take `dev.vexelray.target.ImageHandle` rather than `Object`. The
  layering intent was always right — a canvas must not know what a texture is — but `Object` spends type safety
  to say so and accepts a `String` just as happily as an image. The marker lives in `vexelray-core` so
  `vexelray-canvas` and `vexelray-vulkan` can share it without either naming the other; `SampledImage` extends
  it.
- `GraphicsPipeline.VertexAttribute.floats(...)` replaced the `components -> VK_FORMAT_*` switch that had been
  written four times, once in `CanvasTechnique` and once in each of three canvas demos.
- `ResourceManager`, `GpuBuffer`, `BufferUsage` and `MemoryDomain` are deleted. Nothing implemented them, which
  is why `TechniqueContext.resources()` had to be removed in D19 — the accessor could not have been honoured by
  any runtime. An interface with no implementor is a guess; the day there is a pooled allocator, adding it back
  is a one-line change to `TechniqueContext`.

## 19. `Target.Kind.OFFSCREEN`, and a frame you can count (D23)

**DECISION (D23, DONE).** The engine renders an offscreen target through a new `OffscreenPresenter`, and the
frame it finishes on is readable afterwards as `VexelEngine.lastFrameRgba()`.

`Target.Kind.OFFSCREEN` had been authorable since the target API existed and `VulkanEngine` threw on it. The
cost of that was not the missing feature, it was what it did to the checks: every engine-level test needed a
window, and the windowed path does no readback, so a headless environment could only skip them and *none of
them could assert anything about the picture*. A pipeline whose fragment shader wrote nothing at all passed
`TwoTechniqueTest`, `SdfEngineTest`, `HybridFrameTest` and `EngineEventsTest` alike. "Techniques composite in
list order" was a claim about the code with no measurement under it.

**`OffscreenPresenter` beside `WindowedPresenter`, not inside the existing headless paths.** `OffscreenDraw`
and `OffscreenRenderer` are single-pipeline and per-call — they build an image, a framebuffer, a command
buffer and a readback buffer, record one draw with the pipeline they were given, and tear all of it down.
There is no seam for a caller to record into, so a pipeline of techniques cannot share their frame, and
nothing survives between calls, so a run of frames pays full setup each time. They stay, because "render this
one thing to a texture" is a real shape and is what the Canvas texture target wants. What was missing is a
frame *loop* with no window.

So: colour image + optional `DepthAttachment` + framebuffer + command pool + two command buffers + fence +
a permanently-mapped readback buffer, all created once; `frame(pushBytes, perFrame, recorder)` records into a
begun pass; `readRgba()` after. `VulkanRenderPass` and `DepthAttachment` are reused unchanged — the only
difference in the pass is `TRANSFER_SRC_OPTIMAL` instead of `PRESENT_SRC_KHR`, and `VulkanRenderPass` already
adds the right outgoing subpass dependency for it without being told which of the two it is building.

**One frame loop, two presenters.** `VulkanEngine.driveTechniques` and `VulkanEngine.loop` are shared by both
paths through a small internal `FrameTarget` (one frame, plus this frame's extent — a method rather than two
numbers, because a window resize moves it without re-realising anything). That sharing is the point rather
than a tidiness: a headless capture is evidence about what a window would show *only while* the two runs
differ in the presenter and nowhere else. The moment a technique can tell which one it is under, a passing
offscreen test stops meaning anything about the interactive one.

Two consequences worth naming:

- **An offscreen run requires a frame callback.** A windowed run ends when its window closes; an offscreen
  one has no window and no swapchain that can go out of date, so the callback returning `false` is the only
  thing that can end it. Passing `null` is refused before a device is touched, because the alternative is not
  a bug that fails, it is a bug that hangs — and CI reports a hang as a timeout with no stack.
- **The capture is the last frame, and only the last frame.** Copying every frame out of device memory would
  make a thousand-frame headless run pay a full image copy a thousand times for pixels nobody asked for;
  capturing none leaves the whole point of an offscreen target unreachable. The copy command buffer is
  recorded once at construction and submitted on demand, so a run that never reads pays nothing. A run that
  needs a specific intermediate frame stops at it and runs again.

`OffscreenEngineTest` is what this was for: `SolidTechnique` fills the frame with a pushed RGBA, and the test
asserts *exact bytes* — every pixel of a 64×64 capture, then a two-technique pipeline run twice with the list
reversed, which measures rather than assumes that order is the composition.

*Also in the same sweep:*

- **`Recorder` and `FrameUpdate` are top-level**, no longer nested in `WindowedPresenter`. Both presenters
  take them and the engine hands the same lambda to each; a type named after one of its two implementations
  would have made the shared path read like a borrowing.
- **`VkStructs`** — the layouts that appeared privately in `WindowedPresenter`, `OffscreenDraw`,
  `OffscreenRenderer` and `OffscreenReadback`, with field names that had already drifted (`area_w` in one,
  `area_extent_width` in another, for the same field). Done *with* this change rather than after it, because
  the alternative was a fifth copy. `OffscreenReadback` also loses its private clone of `Ffm`.
  `VkStructsTest` pins every layout's size and the offsets a padding mistake actually moves — the migration is
  mechanical but its failure mode is a driver reading the wrong bytes, which no compiler catches and which
  only a machine with a GPU would otherwise notice.
- **`VulkanInstance` resolves its two `VK_KHR_surface` commands lazily.** They exist only when the extension
  is enabled, and a headless instance does not enable it, so eager resolution made *every* extension-less
  instance die in the constructor naming a surface function to a caller that had not mentioned surfaces.

## Open questions (to revisit as phases land)

- **Frames-in-flight vs technique state.** With N frames in flight, per-technique push-constant buffers need
  per-frame-slot copies — a technique's arena is one block written each frame, which is safe only because the
  previous frame has been waited on. So does the depth image: `DepthAttachment` is one image shared by every
  swapchain image. `EngineConfig.framesInFlight` accepts 1–3 and the runtime honours exactly 1, with
  `EngineConfigTest` pinning the default to what the presenter actually does rather than to what the config
  claims. Still open, and it now depends on D20's threading contract being the thing that does *not* change.
- **Writing `gl_FragDepth` needs `core` to be able to say it.** `Builtin` has `POSITION` and `VERTEX_INDEX`;
  there is no `FRAG_DEPTH`, and adding one means a SupirVast change (the enum, its type and direction, the
  `BuiltIn` decoration in `CoreToSpirv`, and the `DepthReplacing` execution mode). Authoring the march as
  hand-written GLSL to avoid that is not available — the render path is `core` IR by rule, because the CPU
  lowers the identical function.
- ~~**Depth buffer creation.** `GraphicsPipeline`/`OffscreenRenderer` are colour-only today; the shared depth
  attachment (§4 "depth is always present") is new work in Phase 1/2.~~
  **Closed by D19** — `DepthAttachment` (D32_SFLOAT), a second attachment slot in `VulkanRenderPass`, and
  `GraphicsPipeline.Config.Depth` as three named states. The *plumbing* is done end to end; what nothing does
  yet is write a meaningful depth value, which is the question above.
- ~~**Shader cache.** `ShaderComposer.keyFor` exists but is unused; wiring the cache is Phase 3 polish.~~
  **Closed by D15** — `ShaderCache` in `vexelray-shader`, keyed on `ShaderKey`, in use by the SDF composer.
