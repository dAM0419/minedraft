package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;
import java.util.List;

/** 일반 확인 창. danger 면 Enter 로는 확인되지 않고, 취소가 기본 선택이다. */
public class ConfirmDialog extends Modal<AreaEditorScreen> {
    private final Component title;
    private final List<Component> lines;
    @Nullable
    private final Component warning;
    private final Component confirm;
    private final boolean danger;
    private final Runnable onConfirm;
    private int cancelX, confirmX, buttonsY;

    public ConfirmDialog(AreaEditorScreen screen, Component title, List<Component> lines, @Nullable Component warning,
                         Component confirm, boolean danger, Runnable onConfirm) {
        super(screen, 260, 0);
        this.title = title;
        this.lines = lines;
        this.warning = warning;
        this.confirm = confirm;
        this.danger = danger;
        this.onConfirm = onConfirm;
    }

    private List<FormattedCharSequence> wrapped(Component c) {
        return Ui.wrap(c, w - 20);
    }

    @Override
    protected void layout() {
        int lh = 0;
        if (warning != null) lh += wrapped(warning).size() * 10 + 6;
        for (Component c : lines) lh += wrapped(c).size() * 10;
        h = 22 + lh + 30;
        super.layout();
    }

    @Override
    protected void renderContent(GuiGraphics g, int mx, int my, float pt) {
        Ui.text(g, title, x + 10, y + 8, Ui.TEXT);
        g.fill(x + 1, y + 20, x + w - 1, y + 21, Ui.EDGE);
        int ty = y + 26;
        if (warning != null) {
            List<FormattedCharSequence> wl = wrapped(warning);
            g.fill(x + 6, ty - 3, x + w - 6, ty + wl.size() * 10 + 1, 0x40E04848);
            g.fill(x + 6, ty - 3, x + 8, ty + wl.size() * 10 + 1, Ui.DEMOLISH);
            for (FormattedCharSequence s : wl) {
                g.drawString(Ui.font(), s, x + 12, ty, 0xFFFF8A8A, false);
                ty += 10;
            }
            ty += 6;
        }
        for (Component c : lines) {
            for (FormattedCharSequence s : wrapped(c)) {
                g.drawString(Ui.font(), s, x + 10, ty, Ui.DIM, false);
                ty += 10;
            }
        }
        buttonsY = y + h - 24;
        Component cancel = Component.translatable("minedraftlib.dialog.cancel");
        confirmX = x + w - 10 - buttonWidth(confirm);
        cancelX = confirmX - 6 - buttonWidth(cancel);
        button(g, cancelX, buttonsY, cancel, danger, false, mx, my);
        button(g, confirmX, buttonsY, confirm, !danger, danger, mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (Ui.inside(mx, my, confirmX, buttonsY, buttonWidth(confirm), 16)) {
            close();
            onConfirm.run();
        } else if (Ui.inside(mx, my, cancelX, buttonsY, buttonWidth(Component.translatable("minedraftlib.dialog.cancel")), 16)) {
            close();
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            close();
        } else if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && !danger) {
            close();
            onConfirm.run();
        }
        return true;
    }
}
