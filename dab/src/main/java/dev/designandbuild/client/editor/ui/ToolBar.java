package dev.designandbuild.client.editor.ui;

import dev.minedraft.lib.client.editor.ui.UiPart;
import dev.minedraft.lib.client.LibConfig;

import dev.minedraft.lib.edit.EditRequests;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import dev.designandbuild.core.Requests;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 좌측 툴바: 도구 3개(선택·직접조작·배치), 아래에 도움말·태그 보기·블록 테두리·되돌리기·다시 하기. */
public final class ToolBar extends DabPart {
    private static final int B = 18;

    public ToolBar(EditorScreen screen) {
        super(screen);
    }

    private int toolY(int i) {
        return y + 4 + i * (B + 4);
    }

    private int bottomY(int i) {
        return y + h - 4 - (i + 1) * (B + 2);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        EditorSession s = s();
        g.fill(x, y, x + w, y + h, Ui.PANEL);
        g.fill(x + w - 1, y, x + w, y + h, Ui.EDGE);
        EditorSession.Tool[] tools = EditorSession.Tool.values();
        int[] icons = {Icons.SELECT, Icons.DIRECT, Icons.PLACE};
        String[] keys = {"V", "B", "P"};
        for (int i = 0; i < 3; i++) {
            int by = toolY(i);
            boolean hover = Ui.inside(mx, my, x + 2, by, B, B);
            Ui.iconButton(g, x + 2, by, B, B, icons[i], s.tool == tools[i], hover, true);
            if (hover) {
                screen.tooltip = List.of(Component.translatable("designandbuild.tool." + tools[i].name().toLowerCase()).append(" (" + keys[i] + ")"),
                        Component.translatable("designandbuild.tool." + tools[i].name().toLowerCase() + ".tip"));
            }
        }
        int[] bottom = {Icons.REDO, Icons.UNDO, Icons.GRID, Icons.TAGS, Icons.HELP};
        String[] tips = {"designandbuild.editor.redo", "designandbuild.editor.undo", "designandbuild.editor.outlines", "designandbuild.editor.tag_view", "designandbuild.editor.help"};
        boolean[] active = {false, false, LibConfig.get().blockOutlines, screen.tagView, screen.showHelp};
        for (int i = 0; i < bottom.length; i++) {
            int by = bottomY(i);
            boolean hover = Ui.inside(mx, my, x + 2, by, B, B);
            Ui.iconButton(g, x + 2, by, B, B, bottom[i], active[i], hover, true);
            if (hover) screen.tooltip = List.of(Component.translatable(tips[i]));
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        EditorSession.Tool[] tools = EditorSession.Tool.values();
        for (int i = 0; i < 3; i++) {
            if (Ui.inside(mx, my, x + 2, toolY(i), B, B)) {
                screen.setTool(tools[i]);
                return true;
            }
        }
        for (int i = 0; i < 5; i++) {
            if (!Ui.inside(mx, my, x + 2, bottomY(i), B, B)) continue;
            switch (i) {
                case 0 -> s().send(new EditRequests.Redo());
                case 1 -> s().send(new EditRequests.Undo());
                case 2 -> screen.toggleOutlines();
                case 3 -> screen.tagView = !screen.tagView;
                case 4 -> screen.showHelp = !screen.showHelp;
                default -> {
                }
            }
            return true;
        }
        return true;
    }
}
