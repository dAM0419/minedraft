package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.designandbuild.client.ui.Icons;
import dev.designandbuild.client.ui.Ui;
import dev.designandbuild.core.Requests;
import dev.designandbuild.core.StructureInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 순서 탭: 배치된 구조물을 건축 순서대로. 끌어서 순서 바꾸기, 마우스를 올리면 뷰포트에서 볼륨 강조,
 * 덮어쓰기 충돌은 주황 ! (누르면 그 칸으로), 오른쪽 아이콘으로 저장·삭제. 이름을 두 번 누르면 이름 바꾸기.
 */
final class OrderTab {
    private static final int ROW = 18;

    private final EditorScreen screen;
    private int scroll;
    int hovered;
    private int pressId;
    private double pressY;
    private boolean dragging;
    private int dropIndex = -1;
    private long lastClick;
    private int lastClickId;
    private final EditBox rename;
    private int renamingId;
    private int px, py, pw, ph;

    OrderTab(EditorScreen screen) {
        this.screen = screen;
        this.rename = new EditBox(Ui.font(), 0, 0, 80, 12, Component.empty());
        rename.setBordered(false);
        rename.setMaxLength(64);
        rename.setTextColor(Ui.TEXT);
    }

    private EditorSession s() {
        return screen.session();
    }

    void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        px = x;
        py = y;
        pw = w;
        ph = h;
        hovered = 0;
        List<StructureInstance> list = s().model.orderedStructures();
        Ui.text(g, Component.translatable("designandbuild.order.count", list.size()), x + 6, y + 5, Ui.DIM);
        int ly = y + 16;
        int max = Math.max(0, list.size() * ROW - (h - 20));
        scroll = Mth.clamp(scroll, 0, max);
        if (rename.isFocused() && screen.focusedBox() != rename) renamingId = 0;
        for (int i = 0; i < list.size(); i++) {
            StructureInstance st = list.get(i);
            int ry = ly + i * ROW - scroll;
            if (ry + ROW < ly || ry > y + h) continue;
            boolean hover = Ui.inside(mx, my, x + 2, ry, w - 4, ROW - 1) && my >= ly;
            if (hover) hovered = st.id;
            boolean sel = s().selected == st.id;
            g.fill(x + 2, ry, x + w - 2, ry + ROW - 1, sel ? 0x40FFFFFF : hover ? 0x22FFFFFF : 0x10FFFFFF);
            if (sel) Ui.border(g, x + 2, ry, w - 4, ROW - 1, 0xFFFFFFFF);
            Ui.text(g, Integer.toString(i + 1), x + 6, ry + 5, Ui.DIM);
            int nameX = x + 22;
            int iconsX = x + w - 40;
            int conflicts = s().model.conflictsOf(st.id);
            if (renamingId == st.id) {
                g.fill(nameX - 1, ry + 2, iconsX - 2, ry + 15, 0xFF0B0E12);
                Ui.border(g, nameX - 1, ry + 2, iconsX - nameX + 1, 13, Ui.ACCENT);
                rename.setX(nameX + 1);
                rename.setY(ry + 5);
                rename.setWidth(iconsX - nameX - 4);
                rename.render(g, mx, my, 0);
            } else {
                String name = st.isEmpty() ? st.name + " " + Component.translatable("designandbuild.order.empty").getString() : st.name;
                Ui.text(g, Ui.clip(name, iconsX - nameX - (conflicts > 0 ? 14 : 2)), nameX, ry + 5, st.isEmpty() ? Ui.DIM : Ui.TEXT);
            }
            if (conflicts > 0) {
                int wx = iconsX - 14;
                Ui.icon(g, Icons.WARN, wx - 2, ry + 1, Ui.WARN);
                if (Ui.inside(mx, my, wx, ry, 12, ROW)) {
                    screen.tooltip = List.of(Component.translatable("designandbuild.order.conflicts", conflicts),
                            Component.translatable("designandbuild.order.conflicts.tip"));
                }
            }
            boolean hs = Ui.inside(mx, my, iconsX, ry + 1, 16, 16);
            boolean hd = Ui.inside(mx, my, iconsX + 18, ry + 1, 16, 16);
            if (hover || sel) {
                Ui.iconButton(g, iconsX, ry + 1, 16, 16, Icons.SAVE, false, hs, true);
                Ui.iconButton(g, iconsX + 18, ry + 1, 16, 16, Icons.DELETE, false, hd, true);
            }
            if (hs) screen.tooltip = List.of(Component.translatable("designandbuild.select.save"));
            if (hd) screen.tooltip = List.of(Component.translatable("designandbuild.select.delete"));
        }
        if (dragging && dropIndex >= 0) {
            int dy = ly + dropIndex * ROW - scroll - 1;
            g.fill(x + 2, dy, x + w - 2, dy + 2, Ui.ACCENT);
        }
        if (list.isEmpty()) {
            for (int i = 0; i < 3; i++) {
                Ui.text(g, Component.translatable("designandbuild.order.none." + i), x + 6, ly + 4 + i * 10, Ui.DIM);
            }
        }
    }

    private int indexAt(double my) {
        return (int) Math.floor((my - (py + 16) + scroll) / ROW);
    }

    boolean mouseClicked(double mx, double my, int button) {
        List<StructureInstance> list = s().model.orderedStructures();
        int i = indexAt(my);
        if (my < py + 16 || i < 0 || i >= list.size()) return true;
        StructureInstance st = list.get(i);
        int ry = py + 16 + i * ROW - scroll;
        int iconsX = px + pw - 40;
        if (Ui.inside(mx, my, iconsX, ry + 1, 16, 16)) {
            screen.openModal(new SaveDialog(screen, st.id));
            return true;
        }
        if (Ui.inside(mx, my, iconsX + 18, ry + 1, 16, 16)) {
            s().send(new Requests.StructDelete(st.id));
            return true;
        }
        if (s().model.conflictsOf(st.id) > 0 && Ui.inside(mx, my, iconsX - 14, ry, 12, ROW)) {
            long p = s().model.firstConflictOf(st.id);
            if (p != Long.MIN_VALUE) screen.focusCamera(BlockPos.getX(p) + 0.5, BlockPos.getY(p), BlockPos.getZ(p) + 0.5);
            return true;
        }
        if (renamingId == st.id) {
            rename.mouseClicked(mx, my, button);
            return true;
        }
        long now = System.currentTimeMillis();
        if (lastClickId == st.id && now - lastClick < 350) {
            renamingId = st.id;
            rename.setValue(st.name);
            int id = st.id;
            screen.focus(rename, () -> {
                String v = rename.getValue().trim();
                if (!v.isEmpty()) s().send(new Requests.StructRename(id, v));
                renamingId = 0;
            });
            return true;
        }
        lastClick = now;
        lastClickId = st.id;
        s().selected = st.id;
        screen.focusStructure(st.id);
        pressId = st.id;
        pressY = my;
        dragging = false;
        return true;
    }

    boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (pressId == 0) return false;
        if (!dragging && Math.abs(my - pressY) > 4) dragging = true;
        if (dragging) {
            int n = s().model.structureCount();
            dropIndex = Mth.clamp((int) Math.round((my - (py + 16) + scroll) / (double) ROW), 0, n);
        }
        return true;
    }

    boolean mouseReleased(double mx, double my, int button) {
        if (pressId == 0) return false;
        if (dragging && dropIndex >= 0) {
            int from = s().model.orderIndexOf(pressId);
            int to = dropIndex > from ? dropIndex - 1 : dropIndex;
            if (to != from) s().send(new Requests.StructReorder(pressId, to));
        }
        pressId = 0;
        dragging = false;
        dropIndex = -1;
        return true;
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, scroll - (int) (delta * 18));
        return true;
    }
}
