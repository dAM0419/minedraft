package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.client.LibConfig;
import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.edit.EditRequests;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 좌측 툴바: 위에 모드가 등록한 도구({@link AreaEditorScreen#tools}),
 * 아래에 (아래부터) 다시 하기·되돌리기·블록 테두리, 모드가 등록한 보기 단추({@link AreaEditorScreen#toggles}), 도움말.
 */
public final class ToolBar extends UiPart<AreaEditorScreen> {
    private static final int B = 18;
    private final List<ToolButton> bottom = new ArrayList<>();

    public ToolBar(AreaEditorScreen screen) {
        super(screen);
    }

    private int toolY(int i) {
        return y + 4 + i * (B + 4);
    }

    private int bottomY(int i) {
        return y + h - 4 - (i + 1) * (B + 2);
    }

    /** 아래쪽 묶음 (아래부터 위로). */
    private List<ToolButton> bottom() {
        bottom.clear();
        bottom.add(new ToolButton(Icons.REDO, null, "minedraftlib.editor.redo", null, () -> false,
                () -> s().send(new EditRequests.Redo())));
        bottom.add(new ToolButton(Icons.UNDO, null, "minedraftlib.editor.undo", null, () -> false,
                () -> s().send(new EditRequests.Undo())));
        bottom.add(new ToolButton(Icons.GRID, null, "minedraftlib.editor.outlines", null, () -> LibConfig.get().blockOutlines,
                screen::toggleOutlines));
        bottom.addAll(screen.toggles);
        bottom.add(new ToolButton(Icons.HELP, null, "minedraftlib.editor.help", null, () -> screen.showHelp,
                () -> screen.showHelp = !screen.showHelp));
        return bottom;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        g.fill(x, y, x + w, y + h, Ui.PANEL);
        g.fill(x + w - 1, y, x + w, y + h, Ui.EDGE);
        List<ToolButton> tools = screen.tools;
        for (int i = 0; i < tools.size(); i++) {
            ToolButton t = tools.get(i);
            int by = toolY(i);
            boolean hover = Ui.inside(mx, my, x + 2, by, B, B);
            Ui.iconButton(g, x + 2, by, B, B, t.icon(), t.active().getAsBoolean(), hover, true);
            if (hover) screen.tooltip = t.tooltip();
        }
        List<ToolButton> bottom = bottom();
        for (int i = 0; i < bottom.size(); i++) {
            ToolButton t = bottom.get(i);
            int by = bottomY(i);
            boolean hover = Ui.inside(mx, my, x + 2, by, B, B);
            Ui.iconButton(g, x + 2, by, B, B, t.icon(), t.active().getAsBoolean(), hover, true);
            if (hover) screen.tooltip = t.tooltip();
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        List<ToolButton> tools = screen.tools;
        for (int i = 0; i < tools.size(); i++) {
            if (Ui.inside(mx, my, x + 2, toolY(i), B, B)) {
                tools.get(i).action().run();
                return true;
            }
        }
        List<ToolButton> bottom = bottom();
        for (int i = 0; i < bottom.size(); i++) {
            if (Ui.inside(mx, my, x + 2, bottomY(i), B, B)) {
                bottom.get(i).action().run();
                return true;
            }
        }
        return true;
    }
}
