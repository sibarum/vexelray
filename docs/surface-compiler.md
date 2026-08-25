# The Surface Compiler — design

> **Living design doc.** A path from *an expression describing a surface* to *a running SDF renderer*, at
> runtime, with no Java recompile in the loop. It is mostly a **normalization** problem, not a codegen problem:
> VexelRay can already compile an `Expr` field into SPIR-V in-process
> ([`Raymarcher.fragmentSpirv`](../vexelray-experimental/src/main/java/dev/vexelray/experimental/Raymarcher.java)).
> What it cannot do is accept a surface expression that is *not already a distance field* — which is every
> surface a human would actually type. This doc is about closing that gap.
>
> Prototyped in [`vexelray-surface`](../vexelray-surface); lands eventually as a `RenderTechnique` atop the
> Phase 2 runtime (see [`architecture.md`](architecture.md), [`refactor-decisions.md`](refactor-decisions.md)).

---

## 1. What already exists

Almost all of the machinery this feature seems to need is built:

| Piece | Where | State |
|---|---|---|
| A surface as an AST | `ShapeField.sdf(Expr point) -> Expr` | exists — but it is a *Java class*, not data |
| AST to SPIR-V, in-process, at runtime | `Raymarcher.fragmentSpirv` | exists, and emits the field as one called `sdf(vec3)` (D12) |
| The composer seat | `ShaderComposer<D>` javadoc: *"an SDF composer turns a signed-distance scene into a fullscreen fragment"* | reserved, unimplemented |
| Cache identity | `ShaderKey` (structural `equals` by default) | exists, unused (Phase 3 polish) |
| Same AST on the CPU | SupirVast `vast` / Truffle backend | exists — this is what render==sim rides on |

So the honest framing: this is **not** a new compiler. It is (a) making the surface *data* instead of code, and
(b) adding the analysis passes that let arbitrary data be marched safely.

---

## 2. The actual problem: an implicit surface is not an SDF

`ShapeField`'s contract requires "a conservative (never-overshooting) lower bound on the true Euclidean
distance," because sphere-tracing converges only if every step is safe. Hand-authored fields
(`Sdf.sphere`, `Sdf.capsule`, `Sdf.smin`) satisfy that by construction.

An arbitrary implicit does not. `x^2 + y^2 + z^2 - 1` vanishes on the unit sphere — but at radius 3 it reads
`8` where the true distance is `2`. Marched directly it **overshoots by 4x** and punches holes through the
surface. The wigglier the expression (`sin x + sin y + sin z`), the worse it gets: the gradient magnitude varies
over the domain, so no single global rescale is both safe and tight.

Two ways out, and we want both.

### 2.1 Lipschitz normalization (symbolic AD)

Divide by a bound on the gradient: `f' = f / max(|grad f|, eps)`. The sphere example above becomes `8/6 = 1.33`
— an *under*-estimate of the true 2, which is the right direction to be wrong in: a short step costs iterations,
a long one loses the surface. It is exact for a **linear** `f`, and exact to first order at the surface, which is
where a march needs the accuracy. Everywhere else it is a *local* correction — safe where `|grad f|` does not
fall off along the ray, and safe everywhere (but loose) if a conservative global constant `L` is used instead.

The pass is cheap to build because `Expr` is a **sealed interface of records** — `Binary`, `Unary`, `MathCall`,
`VectorConstruct`, `VectorExtract`, `ConstFloat`, `Param` — so forward-mode differentiation is an exhaustive
`switch` with no visitor scaffolding and no unhandled-case risk. Differentiating with respect to a `vec3` point
means carrying three partials alongside the value; `grad f` is then just `VectorConstruct(dx, dy, dz)`, itself
`core` IR, so it lowers to both backends like everything else.

Cost, measured rather than guessed, and worse than it first looks:

| `f` | primal nodes | `grad f` nodes | ratio |
|---|---|---|---|
| sphere SDF | 9 | 58 | 6.4x |
| torus SDF | 29 | 316 | 10.9x |
| box SDF | 84 | 1,090 | 13.0x |
| capsule SDF | 88 | 1,372 | 15.6x |
| one `normalize` | 17 | 562 | 33.1x |
| four nested `normalize` | 35 | **51,736** | **1478x** |

Two effects stack. The three seeds each re-emit the primal, and individual rules embed primal operands again on
top — the quotient rule uses its divisor twice, `length` needs both the vector and its length, a `min`/`max`
select needs both operands to build the `step`. For a flat field that lands at 6–16x. But the duplication
**compounds through nesting**: each nested `normalize` multiplies the result by about four, so the ratio is not a
constant at all — it is exponential in nesting depth. Nothing downstream does CSE, so every one of those nodes
reaches the GPU.

Two consequences, both acted on rather than noted:

- **the output has to be bounded, not just the input** — a 35-node expression is far inside any sane input limit
  and still differentiates to 51k nodes, so `SurfaceLimits` caps the *lowered* size too (§7);
- **the real fix is let-binding, not just a single pass.** One pass carrying all three partials removes the 3x
  seed factor; it does not touch the ~4x-per-level compounding, which comes from rules using a sub-tangent more
  than once. Binding each tangent to a `LocalVar` and emitting the gradient as a function body — `core` has the
  statements for it — turns the multiplication into addition. That is the change worth making, and it is bigger
  than the "mechanical rewrite" this section originally called it.

### 2.2 Interval / affine arithmetic

Strictly stronger, and it pays for itself three separate times:

- **it needs no Lipschitz assumption at all** — evaluate `f` over a *box* rather than a point; if the resulting
  interval excludes zero, the surface provably does not intersect that box. Correct for any implicit, including
  ones where AD normalization is unsafe;
- **the box bound *is* empty-space skipping** — the same numbers that prove "no surface here" tell the ray how
  far it may leap;
- **the box bound over an octree node *is* a prefiltered vexel** — this is the analysis
  [`vexel-world.md`](vexel-world.md) needs anyway.

That last point is the strategic argument for building this: the surface compiler and the vexel world model want
*the same pass over the same IR*. Affine arithmetic (Comba–Stolfi) is the better version — it tracks first-order
correlation between inputs and so avoids the dependency-problem blowup that makes naive intervals uselessly
wide on expressions that mention `x` more than once (which is all of them).

**Sequencing:** AD normalization first (smaller, unblocks the end-to-end path, and validates the pass
infrastructure); interval/affine second, as its own pass, shared with the vexel work.

---

## 3. Surfaces as data

`Surface` is a sealed record tree: bounded primitives, transforms, combinators, and one escape hatch.

```
Surface
├── Sphere / Box / Capsule / Plane / Torus      — proper bounded SDFs, exact
├── Translate / Scale                           — domain transforms (Lipschitz-tracked)
├── Union / Intersection / Difference           — min / max combinators
├── SmoothUnion(k, ...)                         — N-ary soft-min; <= min, so still conservative
├── Shell(t) / Round(r)                         — |d| - t, d - r
└── Implicit(Expr f)                            — ANY expression; normalized on lowering (§2)
```

Three properties fall out of it being records:

1. **Structural equality is free**, so `ShaderKey.of(composer, surface)` collapses two identical scenes onto one
   compiled pipeline with no extra fingerprinting code — exactly what the `ShaderKey` javadoc asks for.
2. **It serializes.** SupirVast's `supir` module already lexes/parses/prints `core` IR as text, so a surface can
   round-trip through a file, a socket, or an editor buffer.
3. **Each node knows its own Lipschitz story.** `Sphere` is 1-Lipschitz and says so; `Implicit` is unknown and
   must be normalized; `Scale(s)` divides the constant through. The lowering carries a bound alongside the
   `Expr` and only inserts the (expensive) gradient division where a subtree admits it cannot vouch for itself.
   Hand-authored scenes therefore compile to *exactly what they compile to today* — no regression, no cost paid
   for a generality they don't use. That is the invariant the harness will hold us to (§6).

---

## 4. Two compile modes, tiered

"Real-time" means two different things depending on whether a human is mid-keystroke:

| | **Interpret** | **Specialize** |
|---|---|---|
| How | upload the AST to a buffer; a small stack machine in the shader walks it | bake the AST into the shader (what `Raymarcher` does today) |
| Recompile cost | **zero** — an edit is a buffer write | SPIR-V lower + `vkCreateGraphicsPipelines` |
| Steady-state speed | slow (interpreter overhead per march step, per pixel) | fast |
| Good for | the editing moment | everything after it |

Edit in interpreted mode; promote to specialized when the user stops typing (debounce, compile on a worker,
atomic swap). The swap is invisible because **both modes come from the same `Surface`** — and, being the same
IR, they can be differentially tested against each other, which is the only way to trust a hot-swap.

---

### 4.1 Duplication is the recurring enemy, at every layer

Worth stating on its own, because it has now bitten three times in three different disguises:

- **D12**: the field inlined at all eight of its sample sites → 22 MB of SPIR-V. Fixed by emitting it once as a
  called function.
- **§2.1**: the derivative re-emitting its primal per seed and per rule, compounding through nesting → 1478x at
  depth four. Fix staged as S0.5.
- **S1**: `Shading.shade` originally returned a bare `Expr`. Lambert broadcasts its diffuse term into three
  colour channels, and the surface normal is reachable from it — so the normal's six field calls were emitted
  three times. Eight samples per pixel became twenty, silently, in code written the same afternoon as the
  paragraph warning about exactly this.

The root cause is the same each time: `core` expressions are immutable value trees and **nothing downstream does
common-subexpression elimination**, so a repeated expression is repeated *work*, and the operands in question
are field evaluations rather than registers. The general remedy is let-binding. S1 applies it at the interface:
`shade` receives a `Bindings`, and the composer binds the normal before shading ever sees it. S0.5 applies the
same remedy inside the derivative. Any future IR-emitting interface in this codebase should take a `Bindings`
from the start rather than learn this a fourth time.

---

## 5. render == sim, extended to user-authored geometry

The same `Expr` lowers to Truffle. So a surface typed into a text box is **immediately queryable on the CPU** —
collision, line-of-sight, physics — with no second implementation and no drift between what is drawn and what is
simulated. This is the payoff that is unique to the stack, and the reason to do this at the IR level rather than
by generating GLSL strings: a string generator gives you pixels and nothing else.

It is also the project's hard rule taken to its conclusion — render-path code is authored as core IR, never as
per-backend Java copies. A user-authored surface is just more core IR.

---

## 6. Proving it

`ComparisonHarness` already renders any `ShapeField` headlessly and reports SPIR-V size, compose time, cold
render time, CPU-eval cost, and fidelity against a high-step reference. That is the acceptance test:

1. **Parity.** Re-express `BlendedPrimitivesField` as a `Surface` value. It must produce a **byte-identical
   SPIR-V module** to the hand-written field — proving the data path costs nothing when the input is already a
   proper SDF.
2. **Correctness of normalization.** `Implicit(x^2+y^2+z^2-1)` must render as a unit sphere with no holes, and
   agree with `Sphere(1)` within the harness's fidelity threshold.
3. **Gradient pass.** Symbolic `grad f` vs. central finite differences of `f`, over a sampled domain, per node
   kind.
4. **render==sim.** GPU-marched hit points vs. CPU-evaluated distances at the same points, at the tolerance the
   existing differential tests use.

---

## 7. Risks, honestly

- **Loose bounds are correctness-preserving and performance-destroying.** A badly-bounded implicit marches
  correctly and slowly. Interval-based empty-space skipping is the mitigation, which is why it is not optional
  in the long run.
- **Local gradient normalization is not a proof.** `f / |grad f|` is the standard rescue and it is what §2.1
  ships, but it is safe only where the gradient does not collapse ahead of the ray. Where a surface must be
  *guaranteed* hole-free, §2.2's interval test is the one that actually proves it. Documented as a known
  limitation of S0, not papered over.
- **Unbounded and periodic expressions never terminate.** The existing step budget and clamped step
  (`min(d, 0.4)`) already bound this; keep them, and add an explicit far plane.
- **An outside AST is compiler input, and bounding the input is not enough.** Node count and depth are capped
  before lowering — but §2.1's compounding means a 35-node expression can differentiate to 51,000, so the
  *lowered* size is capped as well. Without both, a surface arriving over a network is a denial of service, not
  a scene. This one was found by measuring rather than by reasoning; the input-only check shipped first and would
  have waved the interesting case straight through.
- **Compile must not block the frame.** Lower and create the pipeline on a worker, swap atomically. Runtime
  SPIR-V generation already works in-process; it is the `vkCreateGraphicsPipelines` call that hitches.
- **`smin` is not associative** (D13). Inherited, not introduced — but a `Surface` tree makes it easy to build
  deep left-folds by accident, so `SmoothUnion` is **N-ary from the start** and the weighted soft-min from
  `vexel-world.md` §2 replaces its body once that lands.

---

## 8. Staged plan

- **S0 — module, tree, lowering, gradient. DONE.** `vexelray-surface`: `Surface` records, `Expr` lowering with
  Lipschitz tracking, forward-mode `Gradient`, `Normalize`, input *and* output limits. 38 tests, standalone, no
  GPU — derivatives checked against central differences, normalisation checked against true distance, the
  soft-min checked for conservatism and order-independence.
- **S0.5 — let-bound gradients.** Bind tangents to `LocalVar`s and emit `grad f` as a function body, turning
  §2.1's compounding into addition. Promoted out of "someday" by the measurements above.
- **S1 — the composer. DONE.** `vexelray-technique-sdf`: `SdfComposer implements ShaderComposer<SdfScene>`,
  emitting the `Fullscreen` vertex plus a ray-march fragment, with the field as one called `float sdf(vec3)`.
  `ShaderCache` wired for real. The generated SPIR-V is checked by `spirv-val` in the test suite. Along the way
  `LightingModel` finally got its IR-emitting half — as `Shading` in `vexelray-shader`, because
  `vexelray-core` carries no SupirVast dependency and so cannot name an `Expr` (§4.1).
- **S2 — harness parity.** A `Surface`-backed `ShapeField`; the four proofs of §6.
- **S3 — interval / affine pass.** Empty-space skipping; shared with the vexel work.
- **S4 — interpreted mode and hot swap.** The editing loop; differentially tested against specialized mode.
- **S5 — a front-end.** Infix text to `Surface`. Deliberately last: it is the easy part, and doing it first
  would have hidden that §2 is the whole problem.
