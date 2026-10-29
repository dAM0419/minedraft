package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.editor.AreaEditorSession;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;

/** 편집창 가장자리의 UI 조각 (상단 바, 툴바, 옵션 바, 패널…). */
/**
 * @param <T> 이 조각이 붙는 편집창 (모드의 편집창 클래스)
 */
public abstract class UiPart<T extends AreaEditorScreen> {
    protected final T screen;
    public int x, y, w, h;

    protected UiPart(T screen) {
        this.screen = screen;
    }

    protected AreaEditorSession s() {
        return screen.session();
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    /** 바로 쓸 때 필요한 높이 (편집창 아래쪽 바 등). 기본은 지금 높이. */
    public int requiredHeight(int width) {
        return h;
    }

    public boolean visible() {
        return true;
    }

    public boolean contains(double mx, double my) {
        return visible() && Ui.inside(mx, my, x, y, w, h);
    }

    public abstract void render(GuiGraphics g, int mx, int my, float pt);

    public boolean mouseClicked(double mx, double my, int button) {
        return false;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        return false;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return false;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        return false;
    }
}
