package vexelray.editor.gui;

import sibarum.dasum.gui.core.command.CommandRegistry;
import sibarum.dasum.gui.core.component.AlignItems;
import sibarum.dasum.gui.core.component.Component;
import sibarum.dasum.gui.core.component.Components;
import sibarum.dasum.gui.core.component.Direction;
import sibarum.dasum.gui.core.component.DynamicChildren;
import sibarum.dasum.gui.core.component.JustifyContent;
import sibarum.dasum.gui.core.em.Em;
import sibarum.dasum.gui.core.event.Invalidator;
import sibarum.dasum.gui.core.reactive.Property;
import sibarum.dasum.gui.core.render.Color;
import sibarum.dasum.gui.core.text.Icon;
import sibarum.dasum.gui.core.theme.Themed;
import sibarum.dasum.gui.core.theme.Variant;

import sibarum.dasum.gui.vis.math.CameraSpec;
import sibarum.dasum.gui.vis.math.Vec3;
import sibarum.dasum.gui.vis.scene.CsgBox;
import sibarum.dasum.gui.vis.scene.InteractionSpec;
import sibarum.dasum.gui.vis.scene.SceneSnapshot;
import sibarum.dasum.gui.vis.scene.SceneStates;
import sibarum.dasum.gui.vis.scene.VexelRayLayer;

import vexelray.dasumgui.generated.Icons;
import vexelray.supirvast.sdf.BoolOp;
import vexelray.supirvast.sdf.Primitive;
import vexelray.supirvast.sdf.SdfScene;
import vexelray.supirvast.sdf.Vec3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Scene Editor — build an SDF scene by adding box primitives and boolean-modifying
 * them (union / subtract / intersect, hard & smooth), rendered live in a 3D viewport
 * via dasum-vis VexelRay (CSG_BOXES). Commands are exposed both through collapsible
 * side panels and the tab-scoped Ctrl+Space palette.
 *
 * <p>The scene document is the shared {@link SdfScene} model; it maps 1:1 to dasum-vis
 * {@link CsgBox} for rendering and feeds the {@code vexelray-supirvast} shader codegen.
 */
final class SceneEditor {

    private SceneEditor() {}

    // ---- design tokens ----
    private static final Color TRANSPARENT = new Color(0f, 0f, 0f, 0f);
    private static final Color CONTENT_BG  = new Color(0.07f, 0.09f, 0.13f, 1f);
    private static final Color SCENE_BG    = new Color(0.04f, 0.05f, 0.08f, 1f);
    private static final Color PANEL_BG    = new Color(0.10f, 0.12f, 0.16f, 1f);
    private static final Color CARD_BG     = new Color(0.115f, 0.135f, 0.175f, 1f);
    private static final Color LINE        = new Color(1f, 1f, 1f, 0.07f);
    private static final Color HEADING_FG  = new Color(0.97f, 0.98f, 1.00f, 1f);
    private static final Color BODY_FG     = new Color(0.92f, 0.94f, 0.97f, 1f);
    private static final Color MUTED_FG    = new Color(0.52f, 0.57f, 0.70f, 1f);
    private static final Color ACCENT      = new Color(0.40f, 0.62f, 0.95f, 1f);
    private static final Color SURFACE_COL = new Color(0.78f, 0.82f, 0.95f, 1f);

    private static final Em GAP_XS = Em.of(0.35f);
    private static final Em GAP_SM = Em.of(0.6f);
    private static final Em GAP_MD = Em.of(1.0f);
    private static final Em PANEL_W = Em.of(15.5f);
    private static final Em BTN_W   = Em.of(12.8f);

    // ---- state (single editor instance) ----
    private static final SdfScene scene = new SdfScene(0.03f);
    private static Component.SceneView view;

    private static final Property<Integer> sceneRev = new Property<>(0);
    private static final Property<Boolean> leftCollapsed = new Property<>(false);
    private static final Property<Boolean> rightCollapsed = new Property<>(false);
    private static final Property<Float> rounding = new Property<>(0.03f);

    private static Component.Flex leftSlot;
    private static Component.Flex rightSlot;
    private static final Component[] leftCur = { null };
    private static final Component[] rightCur = { null };

    // ---- pane construction ----

    static Component buildPane() {
        view = new Component.SceneView(null, null, Em.ZERO, SCENE_BG, true, 1);
        SceneStates.setCamera(view, CameraSpec.defaultPerspective());
        SceneStates.setInteraction(view, InteractionSpec.defaults());

        // Seed with a base union box so the CSG layer is valid and something is visible.
        scene.add(Primitive.box(BoolOp.UNION, Vec3f.ZERO, new Vec3f(0.5f, 0.5f, 0.5f), 0f));
        republish();

        Component viewportArea = new Component.Flex(
            null, null, Em.ZERO, SCENE_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(view), false, 1);

        leftSlot = emptySlot();
        rightSlot = emptySlot();
        rebuildLeft();
        rebuildRight();

        leftCollapsed.subscribe(b -> rebuildLeft());
        rightCollapsed.subscribe(b -> rebuildRight());
        sceneRev.subscribe(n -> rebuildRight()); // inspector reflects the current scene

        return new Component.Flex(
            null, null, Em.of(0.8f), CONTENT_BG,
            Direction.ROW, JustifyContent.START, AlignItems.STRETCH, GAP_MD,
            List.of(leftSlot, viewportArea, rightSlot), false, 1);
    }

    private static Component.Flex emptySlot() {
        return new Component.Flex(
            Em.AUTO, null, Em.ZERO, TRANSPARENT,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(), false, 0);
    }

    private static void swap(Component.Flex slot, Component[] holder, Component next) {
        if (holder[0] != null) {
            DynamicChildren.remove(slot, holder[0]);
            Components.detach(holder[0]);
        }
        DynamicChildren.add(slot, next);
        holder[0] = next;
        Invalidator.invalidate();
    }

    private static void rebuildLeft() {
        swap(leftSlot, leftCur, leftCollapsed.get() ? collapsedStrip(false) : leftPanel());
    }

    private static void rebuildRight() {
        swap(rightSlot, rightCur, rightCollapsed.get() ? collapsedStrip(true) : rightPanel());
    }

    // ---- collapsed strip ----

    private static Component collapsedStrip(boolean rightSide) {
        int icon = rightSide ? Icons.CHEVRON_LEFT : Icons.CHEVRON_RIGHT;
        Property<Boolean> flag = rightSide ? rightCollapsed : leftCollapsed;
        Component btn = Themed.iconButton(icon, Em.of(1.7f), Variant.DEFAULT, 0, () -> flag.set(false));
        return new Component.Flex(
            Em.of(2.4f), null, Em.of(0.4f), PANEL_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.CENTER, Em.ZERO,
            List.of(btn), false, 0);
    }

    // ---- left panel: primitive palette ----

    private static Component leftPanel() {
        Component body = new Component.Flex(
            null, Em.AUTO, Em.of(0.9f), TRANSPARENT,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, GAP_SM,
            List.of(
                subLabel("Add box"),
                addBtn("Union", Variant.PRIMARY, BoolOp.UNION),
                addBtn("Subtract", Variant.ERROR, BoolOp.SUBTRACT),
                addBtn("Intersect", Variant.INFO, BoolOp.INTERSECT),
                subLabel("Smooth"),
                addBtn("Smooth Union", Variant.SUCCESS, BoolOp.SMOOTH_UNION),
                addBtn("Smooth Subtract", Variant.WARNING, BoolOp.SMOOTH_SUBTRACT),
                divider(),
                subLabel("Edit"),
                Themed.button("Remove Last", BTN_W, Variant.DEFAULT, 0, SceneEditor::removeLast),
                Themed.button("Clear", BTN_W, Variant.DEFAULT, 0, SceneEditor::clearScene),
                divider(),
                roundingRow()),
            false, 0);
        return panelShell(Icons.BOX, "Primitives", false, body);
    }

    private static Component addBtn(String label, Variant v, BoolOp op) {
        return Themed.button(label, BTN_W, v, 0, () -> addBox(op));
    }

    private static Component roundingRow() {
        Component label = new Component.Text("Edge rounding", Em.of(0.85f), MUTED_FG);
        Component slider = Themed.slider(
            Direction.ROW, BTN_W, Em.of(0.9f), Em.of(0.55f),
            rounding, 0f, 0.2f, Variant.INFO,
            f -> { scene.setRounding(f); republish(); });
        return new Component.Flex(
            null, Em.AUTO, Em.ZERO, TRANSPARENT,
            Direction.COLUMN, JustifyContent.START, AlignItems.START, GAP_XS,
            List.of(label, slider), false, 0);
    }

    // ---- right panel: scene inspector ----

    private static Component rightPanel() {
        List<Component> rows = new ArrayList<>();
        rows.add(new Component.Text(scene.size() + " primitive" + (scene.size() == 1 ? "" : "s"),
            Em.of(0.85f), MUTED_FG));
        rows.add(divider());
        if (scene.isEmpty()) {
            rows.add(new Component.Text("Empty — add a primitive.", Em.of(0.9f), MUTED_FG));
        } else {
            List<Primitive> prims = scene.primitives();
            for (int i = 0; i < prims.size(); i++) {
                rows.add(inspectorRow(i, prims.get(i)));
            }
        }
        Component body = new Component.Flex(
            null, Em.AUTO, Em.of(0.9f), TRANSPARENT,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, GAP_XS,
            rows, false, 0);
        return panelShell(Icons.LAYERS, "Scene", true, body);
    }

    private static Component inspectorRow(int index, Primitive p) {
        Vec3f c = p.center();
        Component head = new Component.Text("#" + index + "  " + p.op(), Em.of(0.9f), BODY_FG);
        Component pos = new Component.Text(
            String.format("(%.2f, %.2f, %.2f)", c.x(), c.y(), c.z()), Em.of(0.78f), MUTED_FG);
        return new Component.Flex(
            null, Em.AUTO, Em.of(0.5f), CARD_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.START, Em.of(0.1f),
            List.of(head, pos), false, 0);
    }

    // ---- panel chrome ----

    private static Component panelShell(int icon, String title, boolean rightSide, Component body) {
        Property<Boolean> flag = rightSide ? rightCollapsed : leftCollapsed;
        int collapseIcon = rightSide ? Icons.CHEVRON_RIGHT : Icons.CHEVRON_LEFT;
        Component spacer = new Component.Box(Em.ZERO, Em.ZERO, Em.ZERO, TRANSPARENT).withFlexGrow(1);
        Component header = new Component.Flex(
            null, Em.AUTO, Em.of(0.5f), PANEL_BG,
            Direction.ROW, JustifyContent.START, AlignItems.CENTER, GAP_XS,
            List.of(
                Icon.of(icon, Em.of(1.05f), ACCENT),
                new Component.Text(title, Em.of(1.0f), HEADING_FG),
                spacer,
                Themed.iconButton(collapseIcon, Em.of(1.4f), Variant.DEFAULT, 0, () -> flag.set(true))),
            false, 0);
        Component scroll = new Component.Scroll(null, null, Em.ZERO, PANEL_BG, body, false, 1);
        return new Component.Flex(
            PANEL_W, null, Em.ZERO, PANEL_BG,
            Direction.COLUMN, JustifyContent.START, AlignItems.STRETCH, Em.ZERO,
            List.of(header, scroll), false, 0);
    }

    private static Component subLabel(String text) {
        return new Component.Text(text, Em.of(0.8f), MUTED_FG);
    }

    private static Component divider() {
        // Box is a leaf — its width is read directly during intrinsic-size measurement,
        // so it must be explicit (not null/fill). Match the button column width.
        return new Component.Box(BTN_W, Em.of(0.08f), Em.ZERO, LINE);
    }

    // ---- mutations ----

    static void addBox(BoolOp op) {
        if (scene.size() >= CsgBox.MAX_OPS) {
            System.out.println("Scene at max " + CsgBox.MAX_OPS + " primitives");
            return;
        }
        int n = scene.size();
        float step = 0.45f;
        Vec3f center = new Vec3f(((n % 3) - 1) * step, (((n / 3) % 3) - 1) * step, 0f);
        float k = op.smooth() ? 0.12f : 0f;
        scene.add(Primitive.box(op, center, new Vec3f(0.35f, 0.35f, 0.35f), k));
        republish();
        bumpRev();
    }

    static void removeLast() {
        if (scene.removeLast()) {
            republish();
            bumpRev();
        }
    }

    static void clearScene() {
        scene.clear();
        republish();
        bumpRev();
    }

    private static void bumpRev() {
        sceneRev.set(sceneRev.get() + 1);
    }

    /** Map the scene to a VexelRay CSG layer and publish it to the viewport. */
    private static void republish() {
        if (scene.isEmpty()) {
            SceneStates.publish(view, SceneSnapshot.of());
            Invalidator.invalidate();
            return;
        }
        List<CsgBox> ops = new ArrayList<>(scene.size());
        for (Primitive p : scene.primitives()) {
            ops.add(toCsgBox(p));
        }
        VexelRayLayer layer = VexelRayLayer.csgBoxes(ops, scene.rounding())
            .withMaxSteps(128)
            .withColor(SURFACE_COL);
        SceneStates.publish(view, SceneSnapshot.of(layer));
        Invalidator.invalidate();
    }

    private static CsgBox toCsgBox(Primitive p) {
        CsgBox.Op op = CsgBox.Op.valueOf(p.op().name());
        Vec3 c = new Vec3(p.center().x(), p.center().y(), p.center().z());
        Vec3 h = new Vec3(p.halfExtents().x(), p.halfExtents().y(), p.halfExtents().z());
        return p.op().smooth() ? new CsgBox(op, c, h, p.smoothK()) : new CsgBox(op, c, h);
    }

    // ---- tab-scoped command palette ----

    private static final List<String> CMD_IDS = new ArrayList<>();

    /** Called when the active tab changes; scopes Ctrl+Space commands to this tab. */
    static void onActiveTab(int index) {
        if (index == EditorApp.TAB_SCENE) {
            registerCommands();
        } else {
            unregisterCommands();
        }
    }

    private static void registerCommands() {
        if (!CMD_IDS.isEmpty()) return; // already registered
        reg("vexelray.scene.add.union", "Scene: Add Box — Union", () -> addBox(BoolOp.UNION));
        reg("vexelray.scene.add.subtract", "Scene: Add Box — Subtract", () -> addBox(BoolOp.SUBTRACT));
        reg("vexelray.scene.add.intersect", "Scene: Add Box — Intersect", () -> addBox(BoolOp.INTERSECT));
        reg("vexelray.scene.add.smoothUnion", "Scene: Add Box — Smooth Union", () -> addBox(BoolOp.SMOOTH_UNION));
        reg("vexelray.scene.add.smoothSubtract", "Scene: Add Box — Smooth Subtract", () -> addBox(BoolOp.SMOOTH_SUBTRACT));
        reg("vexelray.scene.removeLast", "Scene: Remove Last Primitive", SceneEditor::removeLast);
        reg("vexelray.scene.clear", "Scene: Clear", SceneEditor::clearScene);
    }

    private static void reg(String id, String label, Runnable action) {
        CommandRegistry.register(id, label, action);
        CMD_IDS.add(id);
    }

    private static void unregisterCommands() {
        for (String id : CMD_IDS) {
            CommandRegistry.unregister(id);
        }
        CMD_IDS.clear();
    }
}
