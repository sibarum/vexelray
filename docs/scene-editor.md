Scene Editor & Supir-Vast integration — Design
===

The Scene Editor builds a **CSG signed-distance-field scene** — primitives combined
with boolean ops — and renders it live in a 3D viewport. It is the first consumer of
the `vexelray-supirvast` integration.

## Module roles

- **`vexelray-supirvast`** — the SDF scene model + the shader-codegen seam.
  - `vexelray.supirvast.sdf` — `SdfScene` (mutable document), `Primitive` (a box today;
    `Kind` is extensible), `BoolOp` (union/subtract/intersect + smooth variants), `Vec3f`.
  - `vexelray.supirvast.codegen.SdfToCore` — lowers an `SdfScene` to Supir-Vast `core`
    (`vastir`) IR: a `float sdScene(float,float,float)` distance function (the same
    sdBox + smin/smax fold as dasum-vis `CsgField`/`vexelray.frag`), wrapped in a compute
    entry point and lowered to **validated SPIR-V** (`toSpirv`). Depends on `dev.supirvast:vastir`.
- **`vexelray-editor-gui`** — `SceneEditor` builds the pane, owns the scene state, maps
  it to the renderer, and registers the tab-scoped commands.

## What works today

- Add **box** primitives and boolean-modify them: union / subtract / intersect, plus
  smooth union / smooth subtract (up to `CsgBox.MAX_OPS` = 48).
- **Rendering**: the scene maps 1:1 to dasum-vis `CsgBox` → `VexelRayLayer.csgBoxes(...)`
  → published to a `SceneView`. Editing re-uploads the uniform array — no shader recompile.
  Orbit (drag) + zoom (wheel) via `SceneViewController`.
- **Ctrl+Space, tab-scoped**: scene commands (`Scene: Add Box — …`, Remove Last, Clear)
  are registered in `CommandRegistry` only while the Scene Editor tab is active, and
  unregistered on leaving — so the Everything menu only lists what's relevant. Driven by
  `EditorApp` subscribing to the active-tab `Property` → `SceneEditor.onActiveTab`.
- **Collapsible side panels**: left = primitive palette (add buttons + edge-rounding
  slider + remove/clear); right = scene inspector (per-primitive op + position). Each
  collapses to a thin strip. Implemented as `DynamicChildren` slots that swap
  expanded/collapsed content; the `SceneView` lives *outside* the swapped subtrees so its
  identity-keyed scene/camera state is never detached.

## Key constraint (why boxes only)

dasum-vis VexelRay R1 is fixed-function: its only composable CSG field is `CSG_BOXES`
(axis-aligned boxes). Sphere/torus/etc. exist only as standalone single-primitive fields
that can't boolean-combine. Arbitrary-primitive CSG needs a **generated shader** — which
is exactly the `SdfToCore` path. Today `SdfToCore` proves the scene round-trips to
validated SPIR-V, but the on-screen render still goes through R1.

## Next steps

See [TODO.md](TODO.md): wire generated SPIR-V/GLSL into the renderer (unlocks non-box
primitives), viewport pick + transform gizmos, persistence, and a CPU-vs-codegen
differential test to keep `SdfToCore` and the GLSL in lockstep.
