package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.library.HiddenList;
import dev.designandbuild.client.ui.Ui;
import dev.designandbuild.core.TemplateParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 전체 태그 창: 한 축(출처·종류·지형)의 태그를 칩 격자로 모두 보여 주고 여러 개를 이어서 고른다.
 * 창이 열린 채 뒤의 라이브러리 결과가 바로 바뀐다 (배경을 어둡게 하지 않고, 패널 왼쪽에 둔다).
 */
final class TagPicker extends Modal {
    private static final int W = 232, H = 176, CHIP_H = 14, GAP = 3;

    private final LibraryTab tab;
    private final int axis;
    private final EditBox query;
    private boolean byName;
    private int scroll;

    // 이번 프레임의 클릭 영역
    private final List<String> hitTag = new ArrayList<>();
    private final List<int[]> hitRect = new ArrayList<>();
    private int areaX, areaY, areaW, areaH, contentH;
    private int sortX, sortW, linkY, linkW, clearX, clearW, closeX, buttonsY;

    TagPicker(EditorScreen screen, LibraryTab tab, int axis) {
        super(screen, W, H);
        this.tab = tab;
        this.axis = axis;
        this.query = newBox(40, Component.translatable("designandbuild.tagpicker.search"));
    }

    @Override
    protected void layout() {
        super.layout();
        x = Math.max(8, screen.width - SidePanel.WIDTH - w - 12);
    }

    @Override
    protected boolean dims() {
        return false;
    }

    private int hiddenTagCount() {
        String prefix = HiddenList.TAG + TemplateParser.TAG_AXES[axis] + ":";
        int n = 0;
        for (String l : HiddenList.lines()) if (l.startsWith(prefix)) n++;
        return n;
    }

    private List<LibraryTab.Chip> shown() {
        String q = query.getValue().trim().toLowerCase(Locale.ROOT);
        List<LibraryTab.Chip> out = new ArrayList<>();
        for (LibraryTab.Chip c : tab.chipList(axis)) {
            if (q.isEmpty() || LibraryTab.tagName(c.tag()).getString().toLowerCase(Locale.ROOT).contains(q)
                    || c.tag().toLowerCase(Locale.ROOT).contains(q)) {
                out.add(c);
            }
        }
        if (byName) {
            out.sort((a, b) -> LibraryTab.tagName(a.tag()).getString().compareToIgnoreCase(LibraryTab.tagName(b.tag()).getString()));
        } else {
            out.sort((a, b) -> b.count() != a.count() ? b.count() - a.count()
                    : LibraryTab.tagName(a.tag()).getString().compareToIgnoreCase(LibraryTab.tagName(b.tag()).getString()));
        }
        return out;
    }

    @Override
    protected void renderContent(GuiGraphics g, int mx, int my, float pt) {
        List<LibraryTab.Chip> all = tab.chipList(axis);
        Ui.text(g, Component.translatable("designandbuild.tagpicker.title", LibraryTab.axisName(axis), all.size()), x + 8, y + 7, Ui.TEXT);
        // 정렬 토글
        Component sort = Component.translatable(byName ? "designandbuild.tagpicker.sort_name" : "designandbuild.tagpicker.sort_count");
        sortW = buttonWidth(sort);
        sortX = x + w - 8 - sortW;
        button(g, sortX, y + 3, sort, false, false, mx, my);
        g.fill(x + 1, y + 21, x + w - 1, y + 22, Ui.EDGE);
        drawBox(g, query, x + 8, y + 26, w - 16, mx, my);

        // 칩 격자
        areaX = x + 8;
        areaY = y + 46;
        areaW = w - 16;
        areaH = h - 46 - 46;
        List<LibraryTab.Chip> list = shown();
        hitTag.clear();
        hitRect.clear();
        int cx = 0, cy = 0;
        int maxW = areaW - 6;
        int[] cw = new int[list.size()];
        int[] px = new int[list.size()], py = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            LibraryTab.Chip c = list.get(i);
            boolean on = tab.isActive(c.tag());
            cw[i] = Ui.font().width((on ? "✓ " : "") + LibraryTab.tagName(c.tag()).getString() + " " + c.count()) + 8;
            cw[i] = Math.min(cw[i], maxW);
            if (cx > 0 && cx + cw[i] > maxW) {
                cx = 0;
                cy += CHIP_H + GAP;
            }
            px[i] = cx;
            py[i] = cy;
            cx += cw[i] + GAP;
        }
        contentH = list.isEmpty() ? 0 : cy + CHIP_H;
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentH - areaH));
        g.enableScissor(areaX, areaY, areaX + areaW, areaY + areaH);
        for (int i = 0; i < list.size(); i++) {
            LibraryTab.Chip c = list.get(i);
            int ix = areaX + px[i], iy = areaY + py[i] - scroll;
            if (iy + CHIP_H < areaY || iy > areaY + areaH) continue;
            boolean on = tab.isActive(c.tag());
            boolean dim = c.count() == 0 && !on;
            boolean hover = Ui.inside(mx, my, ix, iy, cw[i], CHIP_H) && my >= areaY && my < areaY + areaH;
            g.fill(ix, iy, ix + cw[i], iy + CHIP_H, on ? 0x602EC4B6 : hover ? 0x40FFFFFF : 0x20FFFFFF);
            if (on) Ui.border(g, ix, iy, cw[i], CHIP_H, Ui.ACCENT);
            String name = (on ? "✓ " : "") + Ui.clip(LibraryTab.tagName(c.tag()).getString(), cw[i] - 8 - Ui.font().width(" " + c.count()) - (on ? 8 : 0));
            Ui.text(g, name, ix + 4, iy + 3, on ? Ui.TEXT : dim ? Ui.DISABLED : Ui.DIM);
            Ui.text(g, " " + c.count(), ix + 4 + Ui.font().width(name), iy + 3, on ? Ui.DIM : Ui.DISABLED);
            hitTag.add(c.tag());
            hitRect.add(new int[]{ix, iy, cw[i], CHIP_H});
            if (hover) screen.tooltip = List.of(LibraryTab.tagName(c.tag()),
                    Component.translatable("designandbuild.tagpicker.chip_tip").withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
        }
        g.disableScissor();
        if (list.isEmpty()) {
            Ui.text(g, Component.translatable("designandbuild.tagpicker.none"), areaX + 2, areaY + 4, Ui.DIM);
        }
        if (contentH > areaH) { // 스크롤 막대
            int bh = Math.max(12, areaH * areaH / contentH);
            int by = areaY + (areaH - bh) * scroll / Math.max(1, contentH - areaH);
            g.fill(areaX + areaW - 3, areaY, areaX + areaW - 1, areaY + areaH, 0x30FFFFFF);
            g.fill(areaX + areaW - 3, by, areaX + areaW - 1, by + bh, 0xA0FFFFFF);
        }

        // 아래: 숨긴 태그 링크, 단추
        linkY = y + h - 42;
        int hidden = hiddenTagCount();
        linkW = 0;
        if (hidden > 0) {
            Component link = Component.translatable("designandbuild.tagpicker.hidden_link", hidden);
            linkW = Ui.font().width(link);
            boolean hl = Ui.inside(mx, my, x + 8, linkY, linkW, 10);
            Ui.text(g, link, x + 8, linkY, hl ? Ui.TEXT : Ui.ACCENT);
        }
        buttonsY = y + h - 24;
        Component clear = Component.translatable("designandbuild.tagpicker.clear_axis");
        clearW = buttonWidth(clear);
        clearX = x + 8;
        button(g, clearX, buttonsY, clear, false, false, mx, my);
        Component close = Component.translatable("designandbuild.dialog.close");
        closeX = x + w - 8 - buttonWidth(close);
        button(g, closeX, buttonsY, close, true, false, mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        if (!Ui.inside(mx, my, x, y, w, h)) {
            close();
            return true;
        }
        if (clickBoxes(mx, my, button)) return true;
        if (Ui.inside(mx, my, sortX, y + 3, sortW, 16)) {
            byName = !byName;
            scroll = 0;
            return true;
        }
        if (my >= areaY && my < areaY + areaH) {
            for (int i = 0; i < hitTag.size(); i++) {
                int[] r = hitRect.get(i);
                if (Ui.inside(mx, my, r[0], r[1], r[2], r[3])) {
                    String tag = hitTag.get(i);
                    if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) tab.hideTag(tag);
                    else tab.toggle(tag);
                    return true;
                }
            }
        }
        if (linkW > 0 && Ui.inside(mx, my, x + 8, linkY, linkW, 10)) {
            screen.openModal(new HiddenDialog(screen, null, 1));
            return true;
        }
        if (Ui.inside(mx, my, clearX, buttonsY, clearW, 16)) {
            tab.clearAxis(axis);
            return true;
        }
        if (Ui.inside(mx, my, closeX, buttonsY, buttonWidth(Component.translatable("designandbuild.dialog.close")), 16)) {
            close();
            return true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Mth.clamp(scroll - (int) (delta * 18), 0, Math.max(0, contentH - areaH));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (focusedBox() != null) focusBox(null);
            else close();
            return true;
        }
        if (focusedBox() == null && key != GLFW.GLFW_KEY_ENTER) focusBox(query);
        keyToBox(key, scan, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (focusedBox() == null) focusBox(query);
        charToBox(c, mods);
        return true;
    }
}
