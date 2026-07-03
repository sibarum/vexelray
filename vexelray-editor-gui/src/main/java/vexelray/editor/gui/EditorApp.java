package vexelray.editor.gui;

import sibarum.dasum.gui.core.GlfwContext;
import sibarum.dasum.gui.core.command.EverythingMenu;
import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Components;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.DynamicChildren;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.em.EmContext;
import sibarum.dasum.gui.core.event.EventLoop;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.graph.ConnectionSelection;
import sibarum.dasum.gui.core.input.ConnectionDragController;
import sibarum.dasum.gui.core.input.ConnectionSelectionController;
import sibarum.dasum.gui.core.input.ContextMenuController;
import sibarum.dasum.gui.core.input.CursorManager;
import sibarum.dasum.gui.core.input.FocusState;
import sibarum.dasum.gui.core.input.GraphSurfaceController;
import sibarum.dasum.gui.core.input.Handlers;
import sibarum.dasum.gui.core.input.HoverState;
import sibarum.dasum.gui.core.input.InputState;
import sibarum.dasum.gui.core.input.ScrollbarController;
import sibarum.dasum.gui.core.input.SliderController;
import sibarum.dasum.gui.core.input.TabsController;
import sibarum.dasum.gui.core.input.TextInputController;
import sibarum.dasum.gui.core.input.TextStates;
import sibarum.dasum.gui.core.layout.HitTest;
import sibarum.dasum.gui.core.layout.LatestLayout;
import sibarum.dasum.gui.core.layout.Layout;
import sibarum.dasum.gui.core.layout.LayoutResult;
import sibarum.dasum.gui.core.layout.PixelRect;
import sibarum.dasum.gui.core.layout.Render;
import sibarum.dasum.gui.core.overlay.OverlayStack;
import sibarum.dasum.gui.core.overlay.TooltipController;
import sibarum.dasum.gui.core.overlay.TooltipRenderer;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.render.Batcher;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.render.DrawCommand;
import sibarum.dasum.gui.core.render.Projection;
import sibarum.dasum.gui.core.render.RenderStats;
import sibarum.dasum.gui.core.text.Icon;
import sibarum.dasum.gui.core.theme.Theme;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;
import sibarum.dasum.gui.core.window.Window;
import sibarum.dasum.gui.natives.gl.Gl;
import sibarum.dasum.gui.natives.glfw.Glfw;
import sibarum.dasum.gui.natives.glfw.GlfwCallbacks;
import sibarum.dasum.gui.vis.DasumVis;
import sibarum.dasum.gui.vis.pointcloud.SceneViewController;

import vexelray.dasumgui.Atlases;
import vexelray.dasumgui.generated.Icons;

import java.util.List;

import static sibarum.dasum.gui.natives.gl.Gl.GL_COLOR_BUFFER_BIT;

/**
 * VexelRay editor — application shell.
 *
 * <p>This is the GUI skeleton, not the feature set: a {@code Tabs} strip for
 * top-level navigation (Project Settings · Node Editor · Scene Editor · Scripts
 * · Preferences), a docked status bar at the bottom, and scrolling containers
 * where panes can overflow. Built on the DasumGUIshi framework via the shared
 * {@link Atlases} bootstrap in {@code vexelray-dasumgui}.
 *
 * <p>Pane contents are deliberately minimal where the design isn't settled yet:
 * Project Settings and Preferences are blank scroll panes with placeholders;
 * Node Editor and Scene Editor host an empty {@code GraphSurface} / {@code SceneView}
 * ready for content; Scripts is an editable monospace text area.
 */
public final class EditorApp {

    private EditorApp() {}

    // ---------- design tokens ----------
    private static final Color TRANSPARENT   = new Color(0f, 0f, 0f, 0f);
    private static final Color FRAME_BG      = new Color(0.05f, 0.06f, 0.09f, 1f);
    private static final Color CONTENT_BG    = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color CARD_BG       = new Color(0.115f, 0.135f, 0.175f, 1f);
    private static final Color SURFACE_BG    = new Color(0.05f, 0.07f, 0.10f, 1f);
    private static final Color EDITOR_BG     = new Color(0.06f, 0.07f, 0.10f, 1f);
    private static final Color STATUSBAR_BG  = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static final Color LINE          = new Color(1f, 1f, 1f, 0.07f);

    private static final Color HEADING_FG    = new Color(0.97f, 0.98f, 1.00f, 1f);
    private static final Color BODY_TEXT     = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color SUBTITLE_FG   = new Color(0.62f, 0.68f, 0.82f, 1f);
    private static final Color MUTED_FG      = new Color(0.52f, 0.57f, 0.70f, 1f);
    private static final Color ACCENT        = new Color(0.40f, 0.62f, 0.95f, 1f);
    private static final Color OK_GREEN      = new Color(0.35f, 0.80f, 0.55f, 1f);

    private static final Em GAP_XS  = Em.of(0.35f);
    private static final Em GAP_SM  = Em.of(0.7f);
    private static final Em GAP_MD  = Em.of(1.2f);
    private static final Em GAP_LG  = Em.of(1.8f);
    private static final Em PAD_CARD = Em.of(1.1f);
    private static final Em PAD_PANE = Em.of(1.5f);

    // Tab order — keep TAB_NAMES aligned with the panel order in buildTabs().
    private static final String[] TAB_NAMES = {
        "Project Settings", "Node Editor", "Scene Editor", "Scripts", "Preferences"
    };
    static final int TAB_SCENE = 2;

    public static void main(String[] args) {
        Property<Integer> activeTab = new Property<>(0);

        try (GlfwContext ctx = GlfwContext.init();
             Window window = Window.create(1280, 800, "VexelRay Editor");
             Batcher batcher = new Batcher();
             CursorManager cursors = new CursorManager(window.handle().address())) {

            Gl.load();
            batcher.init();
            cursors.init();
            EmContext.setDpiScale(window.contentScaleX());

            // DasumVis registers the SceneView renderer; init after Gl.load().
            // Atlases loads + registers the primary / mono / icon font groups.
            try (DasumVis vis = DasumVis.init();
                 Atlases atlases = Atlases.loadAndRegister()) {

                Component root = buildUi(activeTab);
                wireInput(window, cursors);

                // Scope the Ctrl+Space palette to the active tab's commands.
                activeTab.subscribe(SceneEditor::onActiveTab);
                SceneEditor.onActiveTab(activeTab.get());

                RenderStats stats = new RenderStats();
                EventLoop loop = new EventLoop(window, () -> renderFrame(window, batcher, root, stats));
                loop.run();
            }
        }
    }

    // ---------- shell ----------

    private static Component buildUi(Property<Integer> activeTab) {
        return new Component.Flex(
            null, null, Em.ZERO, FRAME_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(buildTabs(activeTab), buildStatusBar(activeTab)),
            false, 0
        );
    }

    /** Top-level navigation. Fills all vertical space above the status bar. */
    private static Component buildTabs(Property<Integer> activeTab) {
        return Themed.tabs(
            null, null,
            Em.of(2.6f), Em.of(1.1f), Em.ZERO, Em.of(1.0f),
            List.of(
                new Component.Tabs.TabPanel("Project Settings", buildProjectSettingsPane()),
                new Component.Tabs.TabPanel("Node Editor",      buildNodeEditorPane()),
                new Component.Tabs.TabPanel("Scene Editor",     SceneEditor.buildPane()),
                new Component.Tabs.TabPanel("Scripts",          buildScriptsPane()),
                new Component.Tabs.TabPanel("Preferences",      buildPreferencesPane())
            ),
            activeTab,
            Variant.PRIMARY
        ).withFlexGrow(1);
    }

    /** Docked status bar: status on the left, active section + hint on the right. */
    private static Component buildStatusBar(Property<Integer> activeTab) {
        Component left = new Component.Flex(
            Em.AUTO, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, GAP_XS,
            List.of(
                Icon.of(Icons.CHECK, Em.of(0.9f), OK_GREEN),
                new Component.Text("Ready", Em.of(0.85f), SUBTITLE_FG)),
            false, 0);

        Component spacer = new Component.Box(Em.ZERO, Em.ZERO, Em.ZERO, TRANSPARENT).withFlexGrow(1);
        Component sep = new Component.Box(Em.of(0.08f), Em.of(1.1f), Em.ZERO, LINE);

        Component hint = new Component.Flex(
            Em.AUTO, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.END, AlignItems.CENTER, GAP_XS,
            List.of(
                Icon.of(Icons.SEARCH, Em.of(0.85f), MUTED_FG),
                new Component.Text("Ctrl+Space", Em.of(0.8f), MUTED_FG)),
            false, 0);

        Component right = new Component.Flex(
            Em.AUTO, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.END, AlignItems.CENTER, GAP_SM,
            List.of(statusSection(activeTab), sep, hint),
            false, 0);

        return new Component.Flex(
            null, Em.of(1.9f), Em.of(0.6f), STATUSBAR_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, GAP_SM,
            List.of(left, spacer, right),
            false, 0);
    }

    /**
     * A status-bar field that shows the active section name and updates as the
     * tab changes. The component tree is rebuilt-in-place via DynamicChildren:
     * subscribing to {@code activeTab} swaps the label text for the new section.
     */
    private static Component statusSection(Property<Integer> activeTab) {
        Component.Flex slot = new Component.Flex(
            Em.AUTO, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(), false, 0);
        Component[] current = { null };
        Runnable update = () -> {
            int i = activeTab.get();
            String name = (i >= 0 && i < TAB_NAMES.length) ? TAB_NAMES[i] : "";
            Component next = new Component.Text(name, Em.of(0.85f), HEADING_FG);
            if (current[0] != null) {
                DynamicChildren.remove(slot, current[0]);
                Components.detach(current[0]);
            }
            DynamicChildren.add(slot, next);
            current[0] = next;
            Invalidator.invalidate();
        };
        activeTab.subscribe(i -> update.run());
        update.run();
        return slot;
    }

    // ---------- panes ----------

    private static Component buildProjectSettingsPane() {
        return blankScrollPane(Icons.SETTINGS, "Project Settings",
            "Project-wide configuration and metadata",
            "Project settings fields haven't been designed yet — intentionally left blank for now.");
    }

    private static Component buildPreferencesPane() {
        return blankScrollPane(Icons.SLIDERS, "Preferences",
            "Editor and application preferences",
            "Preference controls haven't been designed yet — intentionally left blank for now.");
    }

    /** Node editor — header above a full-bleed, interactive (empty) graph surface. */
    private static Component buildNodeEditorPane() {
        Component surface = new Component.GraphSurface(null, null, SURFACE_BG, List.of(), true, 1);
        return new Component.Flex(
            null, null, PAD_PANE, CONTENT_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, GAP_MD,
            List.of(
                paneHeader(Icons.BOX, "Node Editor", "Visual node graph"),
                surface),
            false, 1);
    }

    /** Scripts — header above an editable, line-numbered monospace text area. */
    private static Component buildScriptsPane() {
        Component editor = new Component.Text("", Em.of(0.95f), BODY_TEXT)
            .withFontGroup(Atlases.MONO)
            .withEditable(true)
            .withAcceptsTab(true)
            .withLineNumbers(true)
            .withClip(true)
            .withFlexGrow(1);
        Component editorScroll = new Component.Scroll(null, null, PAD_CARD, EDITOR_BG, editor, false, 1);
        return new Component.Flex(
            null, null, PAD_PANE, CONTENT_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, GAP_MD,
            List.of(
                paneHeader(Icons.FILE, "Scripts", "Edit project scripts"),
                editorScroll),
            false, 1);
    }

    // ---------- pane building blocks ----------

    private static Component blankScrollPane(int icon, String title, String subtitle, String note) {
        Component column = new Component.Flex(
            null, Em.AUTO, PAD_PANE, CONTENT_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, GAP_LG,
            List.of(paneHeader(icon, title, subtitle), placeholder(note)),
            false, 0);
        return new Component.Scroll(null, null, Em.ZERO, CONTENT_BG, column, false, 1);
    }

    private static Component paneHeader(int icon, String title, String subtitle) {
        Component titleRow = new Component.Flex(
            Em.AUTO, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, GAP_SM,
            List.of(
                Icon.of(icon, Em.of(1.5f), ACCENT),
                new Component.Text(title, Em.of(1.4f), HEADING_FG)),
            false, 0);
        Component sub = new Component.Text(subtitle, Em.of(0.95f), SUBTITLE_FG);
        return new Component.Flex(
            null, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.COLUMN, JustifyContent.START, AlignItems.START, GAP_XS,
            List.of(titleRow, sub),
            false, 0);
    }

    private static Component placeholder(String text) {
        return new Component.Flex(
            null, Em.AUTO, PAD_CARD, CARD_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, GAP_SM,
            List.of(
                Icon.of(Icons.INFO, Em.of(1.0f), MUTED_FG),
                new Component.Text(text, Em.of(0.95f), MUTED_FG).withWrapWidth(Em.of(42f))),
            false, 0);
    }

    // ---------- render ----------

    private static void renderFrame(Window window, Batcher batcher, Component root, RenderStats stats) {
        int fbW = window.framebufferWidth();
        int fbH = window.framebufferHeight();
        float[] projection = Projection.orthoTopLeft(fbW, fbH);

        Gl.glViewport(0, 0, fbW, fbH);
        Gl.glClearColor(0.03f, 0.03f, 0.05f, 1f);
        Gl.glClear(GL_COLOR_BUFFER_BIT);

        PixelRect viewport = new PixelRect(0f, 0f, fbW, fbH);
        LayoutResult mainLayout = Layout.compute(root, viewport);
        java.util.Map<Component, PixelRect> mergedRects = new java.util.IdentityHashMap<>(mainLayout.rects());
        OverlayStack.layoutInto(mergedRects, viewport);
        LayoutResult layout = new LayoutResult(mergedRects);
        LatestLayout.store(root, layout);

        batcher.beginFrame(fbH);
        Render.render(root, layout, batcher, projection);

        // Each z-layer needs its own flush so a later layer's solid fills
        // don't draw under an earlier layer's text (the batcher flushes
        // solids then text per flush).
        if (OverlayStack.isActive()) {
            batcher.flush(projection);
            if (OverlayStack.anyModal()) {
                batcher.submit(new DrawCommand.ColoredQuad(
                    viewport.x(), viewport.y(), viewport.width(), viewport.height(),
                    Theme.overlayBackdrop()));
                batcher.flush(projection);
            }
            for (OverlayStack.Overlay o : OverlayStack.active()) {
                Render.render(o.component(), layout, batcher, projection);
                batcher.flush(projection);
            }
        }

        // Tooltips ride above everything.
        Component ttRoot = OverlayStack.activeInputRoot(root);
        TooltipController.resolveBeforeRender(layout, ttRoot, InputState.mouseX(), InputState.mouseY());
        batcher.flush(projection);
        TooltipRenderer.render(batcher, projection, viewport);

        batcher.endFrame(projection);
        stats.recordFrame(batcher.drawCallsThisFrame(), batcher.verticesThisFrame());
    }

    // ---------- input ----------

    private static Component pressTarget = null;

    private static void wireInput(Window window, CursorManager cursors) {
        GlfwCallbacks.setKeyListener((win, key, scancode, action, mods) -> {
            InputState.setMods(mods);
            TooltipController.onModsChanged(mods);
            if (action != Glfw.GLFW_PRESS && action != Glfw.GLFW_REPEAT) return;
            boolean ctrl  = (mods & Glfw.GLFW_MOD_CONTROL) != 0;
            boolean shift = (mods & Glfw.GLFW_MOD_SHIFT)   != 0;

            if (ctrl && key == Glfw.GLFW_KEY_SPACE && !EverythingMenu.isOpen()) {
                EverythingMenu.open();
                return;
            }
            if (ContextMenuController.handleKey(key)) return;
            if (EverythingMenu.handleKey(key)) return;

            if (ctrl && key == 'C' && TextInputController.onCopy(window.handle())) return;
            if (ctrl && key == 'X' && TextInputController.onCut(window.handle())) return;
            if (ctrl && key == 'V' && TextInputController.onPaste(window.handle())) return;
            if (ctrl && key == 'A' && TextInputController.onSelectAll()) return;
            if (ctrl && key == 'Z') {
                if (shift) { if (TextInputController.onRedo()) return; }
                else       { if (TextInputController.onUndo()) return; }
            }
            if (ctrl && key == 'Y' && TextInputController.onRedo()) return;

            if (key == Glfw.GLFW_KEY_BACKSPACE && TextInputController.onBackspace(ctrl)) return;
            if (key == Glfw.GLFW_KEY_DELETE    && TextInputController.onDelete(ctrl))    return;
            if ((key == Glfw.GLFW_KEY_DELETE || key == Glfw.GLFW_KEY_BACKSPACE)
                && ConnectionSelectionController.onDelete()) return;
            if (key == Glfw.GLFW_KEY_ENTER     && TextInputController.onEnter())         return;

            if (key == Glfw.GLFW_KEY_SPACE || key == Glfw.GLFW_KEY_ENTER) {
                Component focused = FocusState.focused();
                if (focused instanceof Component.Checkbox || focused instanceof Component.Radio<?>) {
                    Handlers.activate(focused, LatestLayout.root());
                    return;
                }
            }

            if (key == Glfw.GLFW_KEY_TAB && TextInputController.onTab()) return;

            if (TextInputController.onKey(key, shift, ctrl)) return;
            if (SliderController.onKey(key)) return;
            if (TabsController.onKey(key))   return;

            if (key == Glfw.GLFW_KEY_ESCAPE && action == Glfw.GLFW_PRESS) {
                if (ConnectionDragController.isDragging()) { ConnectionDragController.cancelDrag(); return; }
                if (OverlayStack.isActive()) { OverlayStack.pop(); return; }
                if (ConnectionSelection.has()) { ConnectionSelection.clear(); return; }
                Component focused = FocusState.focused();
                if (focused instanceof Component.Text t && t.selectable() && TextStates.of(focused).hasSelection()) {
                    TextStates.of(focused).collapseToCaret();
                    Invalidator.invalidate();
                } else if (focused != null) {
                    FocusState.clear();
                } else {
                    Glfw.glfwSetWindowShouldClose(window.handle(), true);
                    Invalidator.invalidate();
                }
            } else if (key == Glfw.GLFW_KEY_TAB) {
                Component layoutRoot = OverlayStack.activeInputRoot(LatestLayout.root());
                if (layoutRoot != null) FocusState.cycle(layoutRoot, shift);
            } else if (ctrl && key == Glfw.GLFW_KEY_EQUAL) {
                EmContext.multiplyZoom(1.1f);
            } else if (ctrl && key == Glfw.GLFW_KEY_MINUS) {
                EmContext.multiplyZoom(1f / 1.1f);
            } else if (ctrl && key == Glfw.GLFW_KEY_0) {
                EmContext.setZoom(1f);
            }
        });

        GlfwCallbacks.setCursorPosListener((win, x, y) -> {
            InputState.updateMousePos(x, y);
            TooltipController.onCursorMove(x, y);
            ScrollbarController.onCursorMove(x, y);
            if (ScrollbarController.isDragging()) return;
            ConnectionDragController.onCursorMove(x, y);
            if (ConnectionDragController.isDragging()) return;
            GraphSurfaceController.onCursorMove(x, y);
            if (GraphSurfaceController.isDragging()) return;
            SceneViewController.onCursorMove(x, y);
            if (SceneViewController.isDragging()) return;
            ContextMenuController.onCursorMove(x, y);

            LayoutResult lr = LatestLayout.result();
            Component layoutRoot = LatestLayout.root();
            if (lr == null || layoutRoot == null) return;

            Component hitRoot = OverlayStack.activeInputRoot(layoutRoot);
            Component hit = HitTest.test(hitRoot, lr, (float) x, (float) y);
            HoverState.update(hit);
            cursors.setShape(cursorShapeFor(hit));

            TextInputController.onCursorMove(hit, x, y);
            SliderController.onCursorMove(x, y);
            TabsController.onCursorMove(x, y);
        });

        GlfwCallbacks.setMouseButtonListener((win, button, action, mods) -> {
            InputState.setMods(mods);
            TooltipController.onModsChanged(mods);
            if (button == Glfw.GLFW_MOUSE_BUTTON_RIGHT) {
                if (action == Glfw.GLFW_PRESS) {
                    ContextMenuController.onMouseDown(InputState.mouseX(), InputState.mouseY(), mods, window.handle());
                }
                return;
            }
            if (button != Glfw.GLFW_MOUSE_BUTTON_LEFT) return;
            boolean pressed = (action == Glfw.GLFW_PRESS);
            InputState.setLeftButtonHeld(pressed);
            boolean shift = (mods & Glfw.GLFW_MOD_SHIFT) != 0;

            if (pressed) {
                if (ScrollbarController.onMouseDown(InputState.mouseX(), InputState.mouseY())) { pressTarget = null; return; }
                if (TabsController.onMouseDown(InputState.mouseX(), InputState.mouseY()))      { pressTarget = null; return; }
                if (ConnectionDragController.onMouseDown(InputState.mouseX(), InputState.mouseY())) { pressTarget = null; return; }
                if (GraphSurfaceController.onMouseDown(InputState.mouseX(), InputState.mouseY()))   { pressTarget = null; return; }
                if (SceneViewController.onMouseDown(HoverState.hovered(), InputState.mouseX(), InputState.mouseY())) {
                    pressTarget = null; FocusState.set(HoverState.hovered()); return;
                }
                if (ConnectionSelectionController.onMouseDown(InputState.mouseX(), InputState.mouseY())) { pressTarget = null; return; }

                if (OverlayStack.isActive()) {
                    LayoutResult lr = LatestLayout.result();
                    if (OverlayStack.isOutsideTopmost(lr, (float) InputState.mouseX(), (float) InputState.mouseY())) {
                        if (OverlayStack.anyModal()) OverlayStack.pop();
                        pressTarget = null;
                        return;
                    }
                    Component overlayRoot = OverlayStack.activeInputRoot(null);
                    Component hit = (lr != null && overlayRoot != null)
                        ? HitTest.test(overlayRoot, lr, (float) InputState.mouseX(), (float) InputState.mouseY())
                        : null;
                    pressTarget = hit;
                    if (hit != null) FocusState.set(hit);
                    TextInputController.onMouseDown(hit, InputState.mouseX(), InputState.mouseY(), shift);
                    SliderController.onMouseDown(hit, InputState.mouseX(), InputState.mouseY());
                    return;
                }
                Component hovered = HoverState.hovered();
                pressTarget = hovered;
                if (hovered != null) FocusState.set(hovered);
                else                 FocusState.clear();
                TextInputController.onMouseDown(hovered, InputState.mouseX(), InputState.mouseY(), shift);
                SliderController.onMouseDown(hovered, InputState.mouseX(), InputState.mouseY());
            } else {
                boolean scrollDrag = ScrollbarController.isDragging();
                boolean sliderDrag = SliderController.isDragging();
                boolean canvasDrag = GraphSurfaceController.isDragging();
                boolean connectionDrag = ConnectionDragController.isDragging();
                boolean sceneDrag = SceneViewController.isDragging();
                ScrollbarController.onMouseUp();
                SliderController.onMouseUp();
                GraphSurfaceController.onMouseUp();
                ConnectionDragController.onMouseUp();
                SceneViewController.onMouseUp();

                LayoutResult lr2 = LatestLayout.result();
                Component dispatchRoot = OverlayStack.activeInputRoot(LatestLayout.root());
                Component released = (lr2 != null && dispatchRoot != null)
                    ? HitTest.test(dispatchRoot, lr2, (float) InputState.mouseX(), (float) InputState.mouseY())
                    : null;
                if (!scrollDrag && !sliderDrag && !canvasDrag && !connectionDrag && !sceneDrag
                    && pressTarget != null && released == pressTarget) {
                    Handlers.activate(pressTarget, dispatchRoot);
                }
                pressTarget = null;
            }
        });

        GlfwCallbacks.setCharListener((win, codepoint) -> TextInputController.onCharInput(codepoint));

        GlfwCallbacks.setCursorEnterListener((win, entered) -> {
            if (!entered) {
                HoverState.clear();
                TextStates.clearAllHoverCarets();
                ScrollbarController.clearHover();
                TabsController.clearHover();
                TooltipController.hideAll();
                cursors.setShape(CursorManager.CursorShape.ARROW);
                Invalidator.invalidate();
            }
        });

        GlfwCallbacks.setWindowFocusListener((win, focused) -> {
            if (!focused) {
                SliderController.cancelDrag();
                ScrollbarController.cancelDrag();
                GraphSurfaceController.cancelDrag();
                ConnectionDragController.cancelDrag();
                SceneViewController.cancelDrag();
                HoverState.clear();
                TextStates.clearAllHoverCarets();
                TooltipController.hideAll();
                InputState.setLeftButtonHeld(false);
            }
            Invalidator.invalidate();
        });

        // Wheel zoom for the 3D scene viewport.
        SceneViewController.installWheelHandler();
    }

    private static CursorManager.CursorShape cursorShapeFor(Component hit) {
        if (hit instanceof Component.Text t && t.selectable()) return CursorManager.CursorShape.IBEAM;
        if (hit instanceof Component.GraphSurface) return CursorManager.CursorShape.ARROW;
        if (hit != null) return CursorManager.CursorShape.HAND;
        return CursorManager.CursorShape.ARROW;
    }
}
