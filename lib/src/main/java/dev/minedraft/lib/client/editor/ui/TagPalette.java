package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.editor.TagTool;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.space.SpaceTag;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.List;

/** 공간 지정 도구가 켜져 있을 때 뷰포트 왼쪽 위에 뜨는 태그 목록 (고르기) 과 붓 크기. */
public final class TagPalette extends UiPart<AreaEditorScreen> {
    public static final int WIDTH = 120, ROW = 14;

    public TagPalette(AreaEditorScreen screen) {
        super(screen);
    }

    private TagTool tool() {
        return screen.tagTool;
    }

    @Override
    public boolean visible() {
        return tool().active;
    }

    /** 줄 수: 제목 + 태그들 + (지우기) + 붓. */
    public int rows() {
        return 2 + tool().paintable().size() + (tool().canErase() ? 1 : 0);
    }

    @Override
    public boolean contains(double mx, double my) {
        return visible() && super.contains(mx, my);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        TagTool t = tool();
        g.fill(x, y, x + w, y + h, 0xE0101418);
        Ui.border(g, x, y, w, h, 0xFF4A5563);
        Ui.text(g, Component.translatable("minedraftlib.tool.tags"), x + 5, y + 3, Ui.DIM);
        int ry = y + ROW;
        SpaceTag cur = t.current();
        List<SpaceTag> tags = t.paintable();
        for (SpaceTag tag : tags) {
            boolean active = !t.erase && cur == tag;
            row(g, mx, my, ry, tag.icon(), 0xFF000000 | tag.color(), Component.translatable(tag.nameKey()), active);
            ry += ROW;
        }
        if (t.canErase()) {
            row(g, mx, my, ry, Icons.ERASE, Ui.DEMOLISH, Component.translatable("minedraftlib.tag.erase"), t.erase);
            ry += ROW;
        }
        Ui.text(g, Component.translatable("minedraftlib.editor.tag_brush", t.brush), x + 5, ry + 3, Ui.DIM);
    }

    private void row(GuiGraphics g, int mx, int my, int ry, int icon, int color, Component name, boolean active) {
        boolean hover = Ui.inside(mx, my, x + 1, ry, w - 2, ROW);
        if (hover) g.fill(x + 1, ry, x + w - 1, ry + ROW, Ui.HOVER);
        if (active) g.fill(x + 1, ry, x + 3, ry + ROW, color);
        if (icon >= 0) Ui.icon(g, icon, x + 5, ry - 1, active ? color : Ui.withAlpha(color, 160));
        else g.fill(x + 8, ry + 4, x + 14, ry + 10, color);
        Ui.text(g, Ui.clip(name.getString(), w - 30), x + 24, ry + 3, active ? Ui.TEXT : Ui.DIM);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        TagTool t = tool();
        int i = (int) ((my - y) / ROW) - 1;
        List<SpaceTag> tags = t.paintable();
        if (i >= 0 && i < tags.size()) {
            t.tag = tags.get(i);
            t.erase = false;
        } else if (i == tags.size() && t.canErase()) {
            t.erase = true;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        tool().brushScroll(delta);
        return true;
    }
}
