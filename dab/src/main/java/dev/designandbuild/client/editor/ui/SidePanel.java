package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.EditorScreen;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 우측 패널: 라이브러리 · 순서 · 범위 탭 (한 번에 하나만). Tab 으로 접는다.
 * 화면이 좁아 자동으로 접혔을 때는 아이콘 세로줄이 되고, 누르면 뷰포트 위에 겹쳐 열린다.
 */
public final class SidePanel extends UiPart {
    public static final int WIDTH = 156, STRIP = 18, HEADER = 18;

    private int tab;
    private boolean collapsed;
    private boolean overlayOpen;
    private final LibraryTab library;
    private final OrderTab order;
    private final RangeTab range;

    public SidePanel(EditorScreen screen) {
        super(screen);
        this.library = new LibraryTab(screen);
        this.order = new OrderTab(screen);
        this.range = new RangeTab(screen);
    }

    public void setCollapsed(boolean collapsed) {
        this.collapsed = collapsed;
        if (!collapsed) overlayOpen = false;
    }

    public void toggleOverlay() {
        overlayOpen = !overlayOpen;
    }

    public int hoveredStructure() {
        return (tab == 1 && (!collapsed || overlayOpen)) ? order.hovered : 0;
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
        String[] names = {"designandbuild.panel.library", "designandbuild.panel.order", "designandbuild.panel.range"};
        int[] icons = {Icons.LIBRARY, Icons.ORDER, Icons.RANGE};
        if (collapsed) {
            g.fill(x, y, x + w, y + h, Ui.PANEL);
            g.fill(x, y, x + 1, y + h, Ui.EDGE);
            for (int i = 0; i < 3; i++) {
                int by = y + 4 + i * 20;
                boolean hover = Ui.inside(mx, my, x + 1, by, 16, 16);
                Ui.iconButton(g, x + 1, by, 16, 16, icons[i], overlayOpen && tab == i, hover, true);
                if (hover) screen.tooltip = List.of(Component.translatable(names[i]));
            }
            if (!overlayOpen) return;
        }
        int px = contentX();
        g.fill(px, y, px + WIDTH, y + h, collapsed ? Ui.PANEL_SOLID : Ui.PANEL);
        g.fill(px, y, px + 1, y + h, Ui.EDGE);
        // 탭 머리
        int tx = px + 4;
        for (int i = 0; i < 3; i++) {
            Component label = Component.translatable(names[i]);
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
            screen.tooltip = List.of(Component.translatable("designandbuild.panel.collapse"));
        }
        Ui.text(g, "»", cbx + 4, y + 5, Ui.DIM);
        g.fill(px, y + HEADER, px + WIDTH, y + HEADER + 1, Ui.EDGE);
        int cy = y + HEADER + 1, ch = h - HEADER - 1;
        g.enableScissor(px, cy, px + WIDTH, cy + ch);
        switch (tab) {
            case 0 -> library.render(g, px, cy, WIDTH, ch, mx, my);
            case 1 -> order.render(g, px, cy, WIDTH, ch, mx, my);
            default -> range.render(g, px, cy, WIDTH, ch, mx, my);
        }
        g.disableScissor();
        library.renderTooltip();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (collapsed) {
            for (int i = 0; i < 3; i++) {
                if (Ui.inside(mx, my, x + 1, y + 4 + i * 20, 16, 16)) {
                    if (screen.panelAutoCollapsed) {
                        overlayOpen = !(overlayOpen && tab == i);
                    } else {
                        ClientConfig.get().panelCollapsed = false;
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
                ClientConfig.get().panelCollapsed = true;
            }
            return true;
        }
        if (my < y + HEADER) {
            String[] names = {"designandbuild.panel.library", "designandbuild.panel.order", "designandbuild.panel.range"};
            int tx = px + 4;
            for (int i = 0; i < 3; i++) {
                int tw = Ui.font().width(Component.translatable(names[i])) + 8;
                if (Ui.inside(mx, my, tx, y, tw, HEADER)) {
                    tab = i;
                    return true;
                }
                tx += tw + 2;
            }
            return true;
        }
        return switch (tab) {
            case 0 -> library.mouseClicked(mx, my, button);
            case 1 -> order.mouseClicked(mx, my, button);
            default -> range.mouseClicked(mx, my, button);
        };
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (!expanded()) return false;
        return switch (tab) {
            case 0 -> library.mouseReleased(mx, my, button);
            case 1 -> order.mouseReleased(mx, my, button);
            default -> range.mouseReleased(mx, my, button);
        };
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!expanded()) return false;
        return switch (tab) {
            case 0 -> library.mouseDragged(mx, my, button, dx, dy);
            case 1 -> order.mouseDragged(mx, my, button, dx, dy);
            default -> range.mouseDragged(mx, my, button, dx, dy);
        };
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (!expanded()) return true;
        return switch (tab) {
            case 0 -> library.mouseScrolled(mx, my, delta);
            case 1 -> order.mouseScrolled(mx, my, delta);
            default -> range.mouseScrolled(mx, my, delta);
        };
    }

    /** 바깥을 누르면 겹쳐 연 패널을 닫는다 (EditorScreen 이 부름). */
    public void closeOverlayIfOutside(double mx, double my) {
        if (collapsed && overlayOpen && !contains(mx, my)) overlayOpen = false;
    }
}
