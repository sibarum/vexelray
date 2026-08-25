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

## Open questions (to revisit as phases land)

- **Frames-in-flight vs technique state.** With N frames in flight, per-technique push-constant buffers may need
  per-frame-slot copies. Deferred until Phase 2 makes frames-in-flight real (today: 1 in flight).
- **Depth buffer creation.** `GraphicsPipeline`/`OffscreenRenderer` are colour-only today; the shared depth
  attachment (§4 "depth is always present") is new work in Phase 1/2.
- **Shader cache.** `ShaderComposer.keyFor` exists but is unused; wiring the cache is Phase 3 polish.
