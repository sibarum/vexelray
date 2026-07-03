TODO / Tech Debt Log
===

Running ledger of tech debt and follow-ups, logged at the moment of discovery.

## Tech Debt

- **MSDF plugin incremental mode ignores inline `<charset>` changes.** Regeneration is
  skipped when outputs are newer than the *font/manifest/glyph-list* files; editing the
  `<charset>` text in the pom does NOT trigger a rebuild. Delete the affected
  `*.png`/`*.json` under `src/main/resources/dasum/atlas/` to force a clean regen.
  (Upstream behavior in `dasum-msdf-maven-plugin`.)
- **Scene Editor renders via dasum-vis R1, NOT the supirvast-generated shader.** The
  on-screen SDF is drawn by dasum-vis VexelRay `CSG_BOXES` (fixed-function). The
  `vexelray-supirvast` `SdfToCore` codegen emits *validated SPIR-V* from the same scene
  (verified, ~3.5 KB for 2 boxes) but that SPIR-V is **not yet wired into the renderer**.
  Closing this is what unlocks non-box primitives (sphere/torus/etc.) in one CSG tree.
- **`SdfToCore` is a hand-mirror of the GLSL / `CsgField`.** The box SDF + smin/smax fold
  in `SdfToCore` must match dasum-vis `vexelray.frag` / `CsgField.of()` by hand (same
  caveat dasum flags on `CsgField`). Keep the three in lockstep until one generates the
  others. A CPU-vs-codegen differential test (supirvast's harness pattern) would pin this.

## Follow-ups

- Scaffold `src/main/java` + `src/test/java` per module as code needs a home, using the base packages below.
- Populate `README.md` once the bootstrap sprint is complete.
- **Editor GUI — blank panes to design** (see [editor-gui-design.md](editor-gui-design.md)):
  - Project Settings: actual fields (project name, paths, build/output config, …).
  - Preferences: actual controls (theme/variant, zoom default, editor prefs, …).
  - Node Editor: node palette + port types + spawn/context menus (canvas is wired,
    but empty).
  - Scripts: pick the script language + run/execute model; the editor area works
    but the content is unstyled and there's no run action.
- Editor status bar shows only a static "Ready" + reactive section name; wire it to
  real state (cursor pos, selection, dirty flag, zoom %) as those exist.
- **Scene Editor — next steps** (see [scene-editor.md](scene-editor.md)):
  - Wire the supirvast-generated SPIR-V/GLSL into the renderer to replace dasum-vis R1
    (the prerequisite for non-box primitives). GLSL via `vastir-tools` SPIRV-Cross.
  - Primitive selection + transform (move/scale) from the viewport — today primitives
    are placed on a fixed spread; there's no pick/gizmo, only add/remove-last/clear.
  - Add sphere/torus/etc. `Primitive.Kind`s (needs the generated-shader path + an
    `sdXxx` term in `SdfToCore`).
  - Scene persistence (save/load the `SdfScene`).
  - Per-tab command sets for the other tabs (only Scene Editor scopes Ctrl+Space today).

## Conventions

Maven coordinates: all modules inherit groupId `vexelray.core` from the parent.

Java base package per module (applied at scaffold time):

| Module               | Base package          |
|----------------------|-----------------------|
| vexelray-core        | `vexelray.core`       |
| vexelray-editor-gui  | `vexelray.editor.gui` |
| vexelray-viewer-gui  | `vexelray.viewer.gui` |
| vexelray-supirvast   | `vexelray.supirvast`  |
| vexelray-pontif      | `vexelray.pontif`     |
| vexelray-dasumgui    | `vexelray.dasumgui`   |

DasumGUIshi dependency: `sibarum.dasum.gui` artifacts, version pinned via the
`dasum.version` property in the parent pom (installed in local `.m2` from
`~/IdeaProjects/dasumGUIshi`). Fonts are vendored per-module under
`vexelray-dasumgui/fonts/`; atlases are generated to `src/main/resources/dasum/atlas/`
at the `generate-resources` phase. macOS/Linux: build with `-P msdf-prebuilt`.

Fonts in `vexelray-dasumgui` (3 atlases):
- **`primary`** — Noto Sans (Latin/Greek/Cyrillic/currency/punctuation) merged with
  Noto Sans Math (arrows U+2190–21FF + math U+2200–22FF). 1168 glyphs. The broad-coverage face.
- **`mono`** — JetBrains Mono (sourced from the bundled IntelliJ JBR), for editor/code surfaces.
- **`icons`** — Lucide subset → generated `vexelray.dasumgui.generated.Icons`.
All TTFs are OFL-licensed (license files kept beside each font).
