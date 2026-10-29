package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.client.editor.ui.UiPart;
import dev.minedraft.lib.client.LibConfig;

import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 우측 패널: 모드가 등록한 탭({@link AreaEditorScreen#panelTabs}) 중 하나를 보인다. Tab 으로 접는다.
 * 화면이 좁아 자동으로 접혔을 때는 아이콘 세로줄이 되고, 누르면 뷰포트 위에 겹쳐 열린다.
 */
public final class SidePanel extends UiPart<AreaEditorScreen> {
    public static final int WIDTH = 156, STRIP = 18, HEADER = 18;

    private int tab;
    private boolean collapsed;
    private boolean overlayOpen;

    public SidePanel(AreaEditorScreen screen) {
        super(screen);
    }

    private List<PanelTab> tabs() {
        return screen.panelTabs;
    }

    /** 지금 펼쳐 보이는 탭 (접혀 있으면 null). */
    @Nullable
    public PanelTab shownTab() {
        if (!expanded() || tabs().isEmpty()) return null;
        return tabs().get(Math.min(tab, tabs().size() - 1));
    }

    @Nullable
    private PanelTab current() {
        return tabs().isEmpty() ? null : tabs().get(Math.min(tab, tabs().size() - 1));
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        if (!collapsed) overlayOpen = false;
    }

    public void toggleOverlay() {
        overlayOpen = !overlayOpen;
    }

    /** 내용이 그려지는 영역 (펼쳤을 때 / 겹쳐 열었을 때). */
    private int contentX() {
        return collapsed ? x - WIDTH : x;
    }

    private boolean expanded() {
        return !collapsed || overlayOpen;
    }

    @Override
    public boolean contains(double mx, double my) {
        if (Ui.inside(mx, my, x, y, w, h)) return true;
        return collapsed && overlayOpen && Ui.inside(mx, my, contentX(), y, WIDTH, h);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        List<PanelTab> tabs = tabs();
        int n = tabs.size();
        if (collapsed) {
            g.fill(x, y, x + w, y + h, Ui.PANEL);
            g.fill(x, y, x + 1, y + h, Ui.EDGE);
            for (int i = 0; i < n; i++) {
                int by = y + 4 + i * 20;
                boolean hover = Ui.inside(mx, my, x + 1, by, 16, 16);
                Ui.iconButton(g, x + 1, by, 16, 16, tabs.get(i).icon(), overlayOpen && tab == i, hover, true);
                if (hover) screen.tooltip = List.of(Component.translatable(tabs.get(i).title()));
            }
            if (!overlayOpen) return;
        }
        int px = contentX();
        g.fill(px, y, px + WIDTH, y + h, collapsed ? Ui.PANEL_SOLID : Ui.PANEL);
        g.fill(px, y, px + 1, y + h, Ui.EDGE);
        // 탭 머리
        int tx = px + 4;
        for (int i = 0; i < n; i++) {
            Component label = Component.translatable(tabs.get(i).title());
            int tw = Ui.font().width(label) + 8;
            boolean hover = Ui.inside(mx, my, tx, y, tw, HEADER);
            if (hover) g.fill(tx, y + 2, tx + tw, y + HEADER - 2, Ui.HOVER);
            Ui.text(g, label, tx + 4, y + 5, tab == i ? Ui.TEXT : Ui.DIM);
            if (tab == i) g.fill(tx + 2, y + HEADER - 3, tx + tw - 2, y + HEADER - 2, Ui.ACCENT);
            tx += tw + 2;
        }
        int cbx = px + WIDTH - 16;
        boolean hc = Ui.inside(mx, my, cbx, y + 1, 14, 16);
        if (hc) {
            g.fill(cbx, y + 1, cbx + 14, y + 17, Ui.HOVER);
            screen.tooltip = List.of(Component.translatable("minedraftlib.panel.collapse"));
        }
        Ui.text(g, "»", cbx + 4, y + 5, Ui.DIM);
        g.fill(px, y + HEADER, px + WIDTH, y + HEADER + 1, Ui.EDGE);
        int cy = y + HEADER + 1, ch = h - HEADER - 1;
        g.enableScissor(px, cy, px + WIDTH, cy + ch);
        PanelTab cur = current();
        if (cur != null) cur.render(g, px, cy, WIDTH, ch, mx, my);
        g.disableScissor();
        for (PanelTab t : tabs) t.renderTooltip();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (collapsed) {
            for (int i = 0; i < tabs().size(); i++) {
                if (Ui.inside(mx, my, x + 1, y + 4 + i * 20, 16, 16)) {
                    if (screen.panelAutoCollapsed) {
                        overlayOpen = !(overlayOpen && tab == i);
                    } else {
                        LibConfig.get().panelCollapsed = false;
                    }
                    tab = i;
                    return true;
                }
            }
            if (!overlayOpen) return Ui.inside(mx, my, x, y, w, h);
        }
        int px = contentX();
        if (Ui.inside(mx, my, px + WIDTH - 16, y + 1, 14, 16)) {
            if (collapsed) {
                overlayOpen = false;
            } else {
                LibConfig.get().panelCollapsed = true;
            }
            return true;
        }
        if (my < y + HEADER) {
            int tx = px + 4;
            for (int i = 0; i < tabs().size(); i++) {
                int tw = Ui.font().width(Component.translatable(tabs().get(i).title())) + 8;
                if (Ui.inside(mx, my, tx, y, tw, HEADER)) {
                    tab = i;
                    return true;
                }
                tx += tw + 2;
            }
            return true;
        }
        PanelTab cur = current();
        return cur == null ? true : cur.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!expanded()) return false;
        PanelTab cur = current();
        return cur == null ? false : cur.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!expanded()) return false;
        PanelTab cur = current();
        return cur == null ? false : cur.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!expanded()) return true;
        PanelTab cur = current();
        return cur == null ? true : cur.mouseScrolled(mx, my, delta);
    }

    /** 바깥을 누르면 겹쳐 연 패널을 닫는다 (편집창이 부름). */
    public void closeOverlayIfOutside(double mx, double my) {
        if (collapsed && overlayOpen && !contains(mx, my)) overlayOpen = false;
    }
}
