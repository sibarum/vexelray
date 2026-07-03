Editor GUI — Design
===

Design notes for the VexelRay editor GUI (`vexelray-editor-gui`), built on the
DasumGUIshi framework.

## Module layout

- **`vexelray-dasumgui`** — shared dasum integration layer. Owns the MSDF atlas
  resources (`primary`, `mono`, `icons`), the generated `Icons` class, and the
  `Atlases` bootstrap helper. Re-exports `dasum-core` + `dasum-vis` (compile) and
  the glfw/nfd natives (runtime) transitively.
- **`vexelray-editor-gui`** — the editor application. Depends only on
  `vexelray-dasumgui`; `EditorApp` is the entry point.
- **`vexelray-viewer-gui`** — (future) the viewer app, will depend on
  `vexelray-dasumgui` the same way.

## Shell

```
+--------------------------------------------------+
| [Project Settings][Node Editor][Scene Editor]... |  <- Tabs header (top nav)
|--------------------------------------------------|
|                                                  |
|                 active tab content               |  <- flexGrow 1
|                                                  |
|--------------------------------------------------|
| ✓ Ready                  <Section>  | Ctrl+Space  |  <- docked status bar
+--------------------------------------------------+
```

- Root is a `Flex` COLUMN: `[ Tabs (flexGrow 1), status bar (fixed ~1.9em) ]`.
- Navigation is the `Themed.tabs` header strip (Variant.PRIMARY).
- Status bar: "Ready" on the left; the active section name (reactive, via a
  `DynamicChildren` slot subscribed to the tab's `Property<Integer>`) and a
  command-palette hint on the right.

## Tabs

| Tab | Content | Status |
|---|---|---|
| Project Settings | scroll pane | **blank** — placeholder only |
| Node Editor | empty interactive `GraphSurface` | canvas ready, no nodes |
| Scene Editor | box-CSG SDF editor (see [scene-editor.md](scene-editor.md)) | **implemented** |
| Scripts | editable, line-numbered `mono` text area in a `Scroll` | usable |
| Preferences | scroll pane | **blank** — placeholder only |

Scrolling containers are used in every pane that can overflow (the two blank
form panes, and the Scripts editor).

## Deliberately blank (see TODO.md)

Per the "leave it blank if unsure" directive, pane contents that aren't yet
specified are left as placeholders: Project Settings fields, Preferences
controls, Node Editor node palette/types, Scene Editor scene tooling, and the
Scripts language + run model.
