package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;

/** 편집창 가장자리의 UI 조각 (상단 바, 툴바, 옵션 바, 패널…). */
public abstract class UiPart {
    protected final EditorScreen screen;
    public int x, y, w, h;

    protected UiPart(EditorScreen screen) {
        this.screen = screen;
    }

    protected EditorSession s() {
        return screen.session();
    }

    public void setBounds(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
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
