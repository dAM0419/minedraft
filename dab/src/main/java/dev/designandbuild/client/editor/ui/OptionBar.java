package dev.designandbuild.client.editor.ui;

import dev.minedraft.lib.client.editor.ui.UiPart;
import dev.minedraft.lib.client.ui.NumberField;
import dev.designandbuild.client.ui.TagColors;

import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.designandbuild.client.editor.PlacementStates;
import dev.designandbuild.client.library.ClientLibrary;
import dev.minedraft.lib.client.ui.Icons;
import dev.designandbuild.client.ui.Thumbnails;
import dev.minedraft.lib.client.ui.Ui;
import dev.designandbuild.core.DesignTag;
import dev.designandbuild.core.Requests;
import dev.designandbuild.core.StructureInstance;
import dev.designandbuild.core.TemplateData;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 하단 옵션 바: 고른 도구의 옵션만 한 줄로. 화면이 좁으면 두 줄로 접힌다.
 * 직접조작: 조작 4종 | 범위 형태 | 중심 | X·Y·Z | 비율 | 재료 3칸 | 태그 | 방향
 */
public final class OptionBar extends DabPart {
    private static final int ROW = 22;
    private static final int BTN = 16;

    private final NumberField fx, fy, fz, fPercent;
    private final NumberField[] fWeights = new NumberField[3];
    private final List<Item> items = new ArrayList<>();
    private int rows = 1;

    /** 옵션 하나: 너비, 그리기, 누르기, 휠, 도움말. */
    private static final class Item {
        final int w;
        final Painter painter;
        @Nullable
        final Clicker clicker;
        @Nullable
        final NumberField field;
        @Nullable
        final Supplier<List<Component>> tip;
        int x, y;

        Item(int w, Painter painter, @Nullable Clicker clicker, @Nullable NumberField field, @Nullable Supplier<List<Component>> tip) {
            this.w = w;
            this.painter = painter;
            this.clicker = clicker;
            this.field = field;
            this.tip = tip;
        }
    }

    private interface Painter {
        void paint(GuiGraphics g, int x, int y, boolean hover);
    }

    private interface Clicker {
        void click(int button);
    }

    public OptionBar(EditorScreen screen) {
        super(screen);
        EditorSession s = screen.session();
        fx = new NumberField(1, 128, () -> s.sizeX, v -> setSize(0, v));
        fy = new NumberField(1, 128, () -> s.sizeY, v -> setSize(1, v));
        fz = new NumberField(1, 128, () -> s.sizeZ, v -> setSize(2, v));
        fPercent = new NumberField(1, 100, () -> s.percent, v -> s.percent = v);
        for (int i = 0; i < 3; i++) {
            int idx = i;
            fWeights[i] = new NumberField(1, 99, () -> s.weights[idx], v -> s.weights[idx] = v);
        }
    }

    /** 비율 고정이면 나머지 축도 같은 배율로. */
    private void setSize(int axis, int value) {
        EditorSession s = s();
        int[] size = {s.sizeX, s.sizeY, s.sizeZ};
        int old = size[axis];
        size[axis] = value;
        if (s.ratioLock && old > 0) {
            double k = value / (double) old;
            for (int a = 0; a < 3; a++) {
                if (a != axis) size[a] = Mth.clamp((int) Math.round(size[a] * k), 1, 128);
            }
        }
        s.sizeX = size[0];
        s.sizeY = size[1];
        s.sizeZ = size[2];
    }

    /** 항목을 지금 상태로 다시 만들고 필요한 높이를 센다. 편집창이 매 프레임 그리기 전에 부른다. */
    public int requiredHeight(int width) {
        build();
        int x0 = 4, r = 1;
        for (Item it : items) {
            if (x0 + it.w > width - 4 && x0 > 4) {
                r++;
                x0 = 4;
            }
            x0 += it.w + 2;
        }
        rows = r;
        return r * ROW;
    }

    // ------------------------------------------------------------------

    private void build() {
        items.clear();
        EditorSession s = screen.session();
        switch (s.tool) {
            case DIRECT -> buildDirect(s);
            case PLACE -> buildPlace(s);
            case SELECT -> buildSelect(s);
        }
    }

    private void sep() {
        items.add(new Item(5, (g, x, y, h) -> Ui.separator(g, x + 2, y, ROW - 2), null, null, null));
    }

    private void iconButton(int icon, Supplier<Boolean> active, Runnable action, String tipKey, @Nullable String key) {
        items.add(new Item(BTN + 2, (g, x, y, h) -> Ui.iconButton(g, x, y + 2, BTN + 2, BTN + 2, icon, active.get(), h, true),
                b -> action.run(), null, () -> {
            List<Component> l = new ArrayList<>();
            l.add(key == null ? Component.translatable(tipKey) : Component.translatable(tipKey).append(" (" + key + ")"));
            String desc = tipKey + ".tip";
            if (net.minecraft.client.resources.language.I18n.exists(desc)) l.add(Component.translatable(desc).withStyle(st -> st.withColor(Ui.DIM & 0xFFFFFF)));
            return l;
        }));
    }

    private void label(String text, int color) {
        int w = Ui.font().width(text) + 4;
        items.add(new Item(w, (g, x, y, h) -> Ui.text(g, text, x + 2, y + 7, color), null, null, null));
    }

    private void field(String label, NumberField f, int width, String tipKey) {
        int lw = label.isEmpty() ? 0 : Ui.font().width(label) + 2;
        items.add(new Item(lw + width, (g, x, y, h) -> {
            if (lw > 0) Ui.text(g, label, x, y + 7, Ui.DIM);
            f.layout(x + lw, y + 5, width);
        }, null, f, () -> List.of(Component.translatable(tipKey))));
    }

    private void buildDirect(EditorSession s) {
        EditorSession.Op[] ops = EditorSession.Op.values();
        int[] opIcons = {Icons.INSTALL, Icons.FILL, Icons.REPLACE, Icons.ERASE};
        for (int i = 0; i < 4; i++) {
            EditorSession.Op op = ops[i];
            iconButton(opIcons[i], () -> s.op == op, () -> s.op = op, "designandbuild.op." + op.name().toLowerCase(), Integer.toString(i + 1));
        }
        sep();
        if (s.op == EditorSession.Op.REPLACE) replaceTargets(s);
        EditorSession.Shape[] shapes = EditorSession.Shape.values();
        int[] shapeIcons = {Icons.BOX, Icons.CYLINDER, Icons.SPHERE};
        for (int i = 0; i < 3; i++) {
            EditorSession.Shape sh = shapes[i];
            iconButton(shapeIcons[i], () -> s.shape == sh, () -> s.shape = sh, "designandbuild.shape." + sh.name().toLowerCase(), null);
        }
        if (s.shape == EditorSession.Shape.CYLINDER) {
            items.add(new Item(14, (g, x, y, h) -> {
                if (h) g.fill(x, y + 3, x + 14, y + ROW - 3, Ui.HOVER);
                String a = s.cylinderAxis.getName().toUpperCase();
                Ui.text(g, a, x + 7 - Ui.font().width(a) / 2, y + 7, Ui.ACCENT);
            }, b -> s.cylinderAxis = Direction.Axis.values()[(s.cylinderAxis.ordinal() + 1) % 3], null,
                    () -> List.of(Component.translatable("designandbuild.shape.axis"))));
        }
        sep();
        EditorSession.Anchor[] anchors = EditorSession.Anchor.values();
        int[] anchorIcons = {Icons.ANCHOR_OUT, Icons.ANCHOR_IN, Icons.ANCHOR_CENTER};
        for (int i = 0; i < 3; i++) {
            EditorSession.Anchor an = anchors[i];
            iconButton(anchorIcons[i], () -> s.anchor == an, () -> s.anchor = an, "designandbuild.anchor." + an.name().toLowerCase(), null);
        }
        sep();
        field("X", fx, 22, "designandbuild.size.x");
        field("Y", fy, 22, "designandbuild.size.y");
        field("Z", fz, 22, "designandbuild.size.z");
        iconButton(Icons.CHAIN, () -> s.ratioLock, () -> s.ratioLock = !s.ratioLock, "designandbuild.size.lock", null);
        if (s.op != EditorSession.Op.ERASE) {
            sep();
            field("%", fPercent, 22, "designandbuild.percent");
            sep();
            int mats = 0;
            for (ItemStack st : s.materials) if (!st.isEmpty()) mats++;
            for (int i = 0; i < 3; i++) {
                int idx = i;
                items.add(new Item(18, (g, x, y, h) -> {
                    int sy = y + 2;
                    g.fill(x, sy, x + 18, sy + 18, 0xFF161B22);
                    Ui.border(g, x, sy, 18, 18, h ? 0xFF6A7584 : Ui.EDGE);
                    ItemStack st = s.materials[idx];
                    if (!st.isEmpty()) {
                        g.renderItem(st, x + 1, sy + 1);
                    } else if (idx == 0) {
                        Ui.icon(g, Icons.PLUS, x + 1, sy + 1, Ui.DIM);
                    }
                }, b -> {
                    if (b == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                        s.materials[idx] = ItemStack.EMPTY;
                    } else {
                        screen.openPicker(new BlockPicker.Target(BlockPicker.Kind.MATERIAL, idx));
                    }
                }, null, () -> materialTip(s, idx)));
                if (mats >= 2 && !s.materials[idx].isEmpty()) field("", fWeights[idx], 16, "designandbuild.weight");
            }
            sep();
            StructureInstance editing = s.selected != 0 ? s.model.structure(s.selected) : null;
            if (editing != null) {
                // 구조물 선택 중: 모든 조작이 그 구조물에 쓰이므로 태그 대신 대상 구조물을 보여 준다
                items.add(new Item(BTN + 2, (g, x, y, h) -> Ui.icon(g, Icons.TAG_STRUCTURE, x + 1, y + 3, TagColors.tagColor(DesignTag.STRUCTURE)),
                        b -> {}, null, () -> List.of(Component.translatable("designandbuild.editing.tag_locked"))));
                label(Ui.clip(editing.name, 110), Ui.TEXT);
                iconButton(Icons.X_SMALL, () -> false, () -> s.selected = 0, "designandbuild.select.deselect", "Esc");
            } else {
            DesignTag[] tags = {DesignTag.FLOOR, DesignTag.PATH, DesignTag.STRUCTURE};
            int[] tagIcons = {Icons.TAG_FLOOR, Icons.TAG_PATH, Icons.TAG_STRUCTURE};
            for (int i = 0; i < 3; i++) {
                DesignTag t = tags[i];
                int icon = tagIcons[i];
                items.add(new Item(BTN + 2, (g, x, y, h) -> {
                    boolean active = s.tag == t;
                    if (h) g.fill(x, y + 2, x + BTN + 2, y + BTN + 4, Ui.HOVER);
                    if (active) g.fill(x + 2, y + BTN + 2, x + BTN, y + BTN + 3, TagColors.tagColor(t));
                    Ui.icon(g, icon, x + 1, y + 3, active ? TagColors.tagColor(t) : Ui.withAlpha(TagColors.tagColor(t), 140));
                }, b -> s.tag = t, null, () -> List.of(Component.translatable("designandbuild.tag." + t.key()),
                        Component.translatable("designandbuild.tag." + t.key() + ".tip").withStyle(st -> st.withColor(Ui.DIM & 0xFFFFFF)))));
            }
            }
            sep();
            items.add(new Item(BTN + 2, (g, x, y, h) -> {
                boolean oriented = OrientationPopup.oriented(s);
                BlockState preview = PlacementStates.stateFor(s.materials[0], s.lookH, s.lookV, s.face, s.upperHalf);
                if (preview == null || !oriented) {
                    Ui.iconButton(g, x, y + 2, BTN + 2, BTN + 2, Icons.CUBE, screen.orientationPopup != null, h, oriented);
                } else {
                    // 설치될 결과 블록을 작게 그려 방향을 한눈에
                    Ui.iconButton(g, x, y + 2, BTN + 2, BTN + 2, -1, screen.orientationPopup != null, h, true);
                    OrientationPopup.drawBlock(g, preview, x + (BTN + 2) / 2.0F, y + 2 + (BTN + 2) / 2.0F + 1, 8.0F,
                            s.camera.yaw());
                }
            }, b -> {
                if (screen.orientationPopup != null) {
                    screen.orientationPopup = null;
                } else if (screen.popupJustClosed) {
                    // 방금 이 클릭으로 닫혔다: 다시 열지 않는다
                } else if (!OrientationPopup.oriented(s)) {
                    screen.session().toast(Component.translatable("designandbuild.orientation.none"));
                } else {
                    Item self = items.get(items.size() - 1);
                    screen.orientationPopup = new OrientationPopup(screen, self.x, this.y);
                }
            }, null, () -> List.of(Component.translatable("designandbuild.orientation"),
                    Component.translatable("designandbuild.orientation.summary", Ui.dirName(s.lookH),
                            Component.translatable(s.lookV > 0 ? "designandbuild.look.up" : s.lookV < 0 ? "designandbuild.look.down" : "designandbuild.look.level"),
                            Ui.dirName(s.face)))));
        } else {
            sep();
            label(s.selected != 0 ? Component.translatable("designandbuild.erase.air").getString()
                    : Component.translatable("designandbuild.erase.demolish").getString(), Ui.DIM);
        }
    }

    /** 대체 대상 칸 3개: 비어 있으면 모든 블록을 대체, 넣은 블록만 대체. 좌클릭 = 고르기, 우클릭 = 비우기. */
    private void replaceTargets(EditorSession s) {
        label(Component.translatable("designandbuild.replace.targets").getString(), Ui.DIM);
        boolean any = false;
        for (ItemStack st : s.replaceTargets) if (!st.isEmpty()) any = true;
        boolean all = !any;
        for (int i = 0; i < 3; i++) {
            int idx = i;
            items.add(new Item(18, (g, x, y, h) -> {
                int sy = y + 2;
                g.fill(x, sy, x + 18, sy + 18, 0xFF161B22);
                Ui.border(g, x, sy, 18, 18, h ? 0xFF6A7584 : Ui.EDGE);
                ItemStack st = s.replaceTargets[idx];
                if (!st.isEmpty()) {
                    g.renderItem(st, x + 1, sy + 1);
                } else if (idx == 0) {
                    Ui.icon(g, Icons.PLUS, x + 1, sy + 1, Ui.DIM);
                }
            }, b -> {
                if (b == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                    s.replaceTargets[idx] = ItemStack.EMPTY;
                } else {
                    screen.openPicker(new BlockPicker.Target(BlockPicker.Kind.REPLACE_TARGET, idx));
                }
            }, null, () -> {
                List<Component> l = new ArrayList<>();
                ItemStack st = s.replaceTargets[idx];
                l.add(st.isEmpty() ? Component.translatable("designandbuild.replace.target_empty") : st.getHoverName());
                l.add(Component.translatable("designandbuild.replace.tip").withStyle(x -> x.withColor(Ui.DIM & 0xFFFFFF)));
                return l;
            }));
        }
        if (all) label(Component.translatable("designandbuild.replace.all").getString(), Ui.DIM);
        sep();
    }

    private List<Component> materialTip(EditorSession s, int idx) {
        List<Component> l = new ArrayList<>();
        ItemStack st = s.materials[idx];
        if (st.isEmpty()) {
            l.add(Component.translatable("designandbuild.material.empty"));
        } else {
            l.add(st.getHoverName());
            int total = 0;
            for (int i = 0; i < 3; i++) if (!s.materials[i].isEmpty()) total += Math.max(1, s.weights[i]);
            if (total > 0) l.add(Component.translatable("designandbuild.material.share", Math.round(100f * Math.max(1, s.weights[idx]) / total)));
        }
        l.add(Component.translatable("designandbuild.material.tip").withStyle(x -> x.withColor(Ui.DIM & 0xFFFFFF)));
        return l;
    }

    private void buildPlace(EditorSession s) {
        if (s.placing == null) {
            label(Component.translatable("designandbuild.place.none").getString(), Ui.DIM);
            return;
        }
        TemplateData t = ClientLibrary.template(s.placing);
        items.add(new Item(20, (g, x, y, h) -> {
            if (t != null) Thumbnails.draw(g, t, x, y + 1, 20, 20, 45, 100);
        }, null, null, null));
        label(Ui.clip(s.placing.name(), 140), Ui.TEXT);
        if (t != null) label(t.sizeX + "×" + t.sizeY + "×" + t.sizeZ, Ui.DIM);
        sep();
        // 배치 방식: 설치 / 채우기 / 대체 / 지우기 (조각의 공기 아닌 칸에 대해)
        EditorSession.Op[] ops = EditorSession.Op.values();
        int[] opIcons = {Icons.INSTALL, Icons.FILL, Icons.REPLACE, Icons.ERASE};
        for (int i = 0; i < 4; i++) {
            EditorSession.Op op = ops[i];
            iconButton(opIcons[i], () -> s.placeOp == op, () -> s.placeOp = op, "designandbuild.place.op." + op.name().toLowerCase(),
                    Integer.toString(i + 1));
        }
        sep();
        if (s.placeOp == EditorSession.Op.REPLACE) replaceTargets(s);
        iconButton(Icons.ROTATE_R, () -> false, () -> s.placeRotation = (s.placeRotation + 1) & 3, "designandbuild.place.rotate", "R");
        iconButton(Icons.MIRROR, () -> s.placeMirror, () -> s.placeMirror = !s.placeMirror, "designandbuild.place.mirror", "F");
        sep();
        iconButton(Icons.ANCHOR_OUT, () -> !s.placeInto, () -> s.placeInto = false, "designandbuild.place.outside", null);
        iconButton(Icons.ANCHOR_IN, () -> s.placeInto, () -> s.placeInto = true, "designandbuild.place.into", null);
        sep();
        if (t != null) {
            String mount = Component.translatable("designandbuild.place.mount", Ui.dirName(t.mount)).getString();
            label(mount, Ui.DIM);
            if (t.mount.getAxis().isHorizontal()) {
                items.add(new Item(16, (g, x, y, h) -> Ui.icon(g, Icons.LOCK, x, y + 3, Ui.DIM), null, null,
                        () -> List.of(Component.translatable("designandbuild.editor.rotation_locked"))));
            }
        }
        sep();
        label(Component.translatable("designandbuild.place.hint").getString(), Ui.DIM);
    }

    private void buildSelect(EditorSession s) {
        StructureInstance st = s.selected != 0 ? s.model.structure(s.selected) : null;
        if (st == null) {
            label(Component.translatable("designandbuild.select.none").getString(), Ui.DIM);
            return;
        }
        label((s.model.orderIndexOf(st.id) + 1) + ". " + Ui.clip(st.name, 120), Ui.TEXT);
        if (st.bounds() != null) {
            label(st.bounds().sizeX() + "×" + st.bounds().sizeY() + "×" + st.bounds().sizeZ(), Ui.DIM);
        }
        sep();
        iconButton(Icons.ROTATE_R, () -> false, () -> s.send(new Requests.StructMove(st.id, 0, 0, 0, 1, false)), "designandbuild.select.rotate", "R");
        iconButton(Icons.MIRROR, () -> false, () -> s.send(new Requests.StructMove(st.id, 0, 0, 0, 0, true)), "designandbuild.select.mirror", "F");
        iconButton(Icons.SAVE, () -> false, () -> screen.openModal(new SaveDialog(screen, st.id)), "designandbuild.select.save", null);
        iconButton(Icons.DELETE, () -> false, () -> s.send(new Requests.StructDelete(st.id)), "designandbuild.select.delete", "Del");
        iconButton(Icons.X_SMALL, () -> false, () -> s.selected = 0, "designandbuild.select.deselect", "Esc");
        sep();
        label(Component.translatable("designandbuild.select.hint").getString(), Ui.DIM);
    }

    // ------------------------------------------------------------------

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(x, y, x + w, y + h, Ui.PANEL);
        g.fill(x, y, x + w, y + 1, Ui.EDGE);
        // 항목은 이 프레임의 배치(requiredHeight, 그리기 직전에 부름)에서 이미 만들었다
        int cx = x + 4, cy = y, row = 0;
        for (Item it : items) {
            if (cx + it.w > x + w - 4 && cx > x + 4) {
                row++;
                cx = x + 4;
                cy = y + row * ROW;
            }
            it.x = cx;
            it.y = cy;
            boolean hover = Ui.inside(mx, my, cx, cy, it.w, ROW);
            it.painter.paint(g, cx, cy, hover && it.clicker != null);
            if (it.field != null) it.field.render(g, mx, my, pt);
            if (hover && it.tip != null) screen.tooltip = it.tip.get();
            cx += it.w + 2;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        for (Item it : items) {
            if (!Ui.inside(mx, my, it.x, it.y, it.w, ROW)) continue;
            if (it.field != null && it.field.contains(mx, my)) {
                NumberField f = it.field;
                screen.focus(f.box, f::commit);
                f.box.mouseClicked(mx, my, button);
                return true;
            }
            if (it.clicker != null) it.clicker.click(button);
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        for (Item it : items) {
            if (it.field != null && it.field.contains(mx, my)) {
                it.field.scroll(delta, net.minecraft.client.gui.screens.Screen.hasShiftDown());
                return true;
            }
        }
        return true;
    }

    public int rows() {
        return rows;
    }
}
