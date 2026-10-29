package dev.minedraft.lib.client.editor.ui;

import net.minecraft.client.gui.GuiGraphics;

/** 오른쪽 창의 탭. 모드가 {@link dev.minedraft.lib.client.editor.AreaEditorScreen#panelTabs} 에 넣는다. */
public interface PanelTab {
    /** 탭 이름 문구 키. */
    String title();

    /** 창을 접었을 때 세로줄에 보일 아이콘. */
    int icon();

    /** 탭 내용 (창이 잘라 그린다). */
    void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my);

    /** 잘라 그리기가 끝난 뒤 (탭 밖으로 나갈 수 있는 툴팁·메뉴). 어느 탭이 열려 있든 모든 탭에 부른다. */
    default void renderTooltip() {
    }

    default boolean mouseClicked(double mx, double my, int button) {
        return true;
    }

    default boolean mouseReleased(double mx, double my, int button) {
        return false;
    }

    default boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    default boolean mouseScrolled(double mx, double my, double delta) {
        return true;
    }
}
