TODO / Tech Debt Log
===

Running ledger of tech debt and follow-ups, logged at the moment of discovery.

## Tech Debt

- **MSDF plugin incremental mode ignores inline `<charset>` changes.** Regeneration is
  skipped when outputs are newer than the *font/manifest/glyph-list* files; editing the
  `<charset>` text in the pom does NOT trigger a rebuild. Delete the affected
  `*.png`/`*.json` under `src/main/resources/dasum/atlas/` to force a clean regen.
  (Upstream behavior in `dasum-msdf-maven-plugin`.)

## Follow-ups

- Scaffold `src/main/java` + `src/test/java` per module as code needs a home, using the base packages below.
- Populate `README.md` once the bootstrap sprint is complete.

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
