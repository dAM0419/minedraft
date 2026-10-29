package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.editor.EditorScreen;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** 편집창 위에 뜨는 창 (확인, 저장, 폴더 등록, 블록 선택). 떠 있는 동안 다른 입력을 받지 않는다. */
public abstract class Modal {
    protected final EditorScreen screen;
    protected int x, y, w, h;
    /** 이 창의 입력칸들. 하나만 포커스를 가진다. */
    protected final List<EditBox> boxes = new ArrayList<>();

    protected Modal(EditorScreen screen, int w, int h) {
        this.screen = screen;
        this.w = w;
        this.h = h;
    }

    protected void layout() {
        x = (screen.width - w) / 2;
        y = Math.max(20, (screen.height - h) / 2);
    }

    /** 배경을 어둡게 할지 (JEI 선택 모드는 아니다). */
    protected boolean dims() {
        return true;
    }

    public void render(GuiGraphics g, int mx, int my, float pt) {
        layout();
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        if (dims()) g.fill(0, 0, screen.width, screen.height, 0x90000000);
        Ui.solidPanel(g, x, y, w, h);
        renderContent(g, mx, my, pt);
        g.pose().popPose();
    }

    protected abstract void renderContent(GuiGraphics g, int mx, int my, float pt);

    public boolean mouseClicked(double mx, double my, int button) {
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        return true;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        return true;
    }

    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) close();
        return true;
    }

    public boolean charTyped(char c, int mods) {
        return true;
    }

    protected void close() {
        screen.openModal(null);
    }

    /** 버튼. 주 버튼은 강조색, 위험 버튼은 빨강. 버튼 너비를 돌려준다. */
    protected int button(GuiGraphics g, int bx, int by, Component label, boolean primary, boolean danger, int mx, int my) {
        int bw = Ui.font().width(label) + 16;
        boolean hover = Ui.inside(mx, my, bx, by, bw, 16);
        int bg = danger ? 0xFF7A2626 : primary ? 0xFF1E6F68 : 0xFF262E38;
        if (hover) bg = danger ? 0xFF9A3030 : primary ? 0xFF25877E : 0xFF323C48;
        g.fill(bx, by, bx + bw, by + 16, bg);
        Ui.border(g, bx, by, bw, 16, danger ? Ui.DEMOLISH : primary ? Ui.ACCENT : Ui.EDGE);
        Ui.text(g, label, bx + 8, by + 4, Ui.TEXT);
        return bw;
    }

    protected static int buttonWidth(Component label) {
        return Ui.font().width(label) + 16;
    }

    // ------------------------------------------------------------------
    // 입력칸
    // ------------------------------------------------------------------

    protected EditBox newBox(int maxLength, @Nullable Component hint) {
        EditBox box = new EditBox(Ui.font(), 0, 0, 100, 12, Component.empty());
        box.setBordered(false);
        box.setMaxLength(maxLength);
        box.setTextColor(Ui.TEXT);
        if (hint != null) box.setHint(hint);
        boxes.add(box);
        return box;
    }

    /** 입력칸 그리기 (높이 14). */
    protected void drawBox(GuiGraphics g, EditBox box, int bx, int by, int bw, int mx, int my) {
        boolean f = box.isFocused();
        g.fill(bx, by, bx + bw, by + 14, f ? 0xFF0B0E12 : 0xFF161B22);
        Ui.border(g, bx, by, bw, 14, f ? Ui.ACCENT : Ui.inside(mx, my, bx, by, bw, 14) ? 0xFF6A7584 : Ui.EDGE);
        box.setX(bx + 4);
        box.setY(by + 3);
        box.setWidth(bw - 8);
        box.render(g, mx, my, 0);
    }

    @Nullable
    protected EditBox focusedBox() {
        for (EditBox b : boxes) if (b.isFocused()) return b;
        return null;
    }

    protected void focusBox(@Nullable EditBox box) {
        for (EditBox b : boxes) b.setFocused(b == box);
    }

    /** 눌린 자리의 입력칸에 포커스를 준다. 입력칸을 눌렀으면 true. */
    protected boolean clickBoxes(double mx, double my, int button) {
        for (EditBox b : boxes) {
            if (b.visible && b.isMouseOver(mx, my)) {
                focusBox(b);
                if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) b.setValue("");
                b.mouseClicked(mx, my, GLFW.GLFW_MOUSE_BUTTON_LEFT);
                return true;
            }
        }
        focusBox(null);
        return false;
    }

    /** 포커스된 입력칸에 키를 넘긴다. 넘겼으면 true. */
    protected boolean keyToBox(int key, int scan, int mods) {
        EditBox b = focusedBox();
        if (b == null) return false;
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            focusBox(null);
            return true;
        }
        b.keyPressed(key, scan, mods);
        return true;
    }

    protected boolean charToBox(char c, int mods) {
        EditBox b = focusedBox();
        if (b == null) return false;
        b.charTyped(c, mods);
        return true;
    }
}
