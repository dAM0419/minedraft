package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.editor.TagTool;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.space.SpaceTag;
import dev.minedraft.lib.space.TagGroup;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 공간 지정 도구가 켜져 있을 때 뷰포트 왼쪽 위에 뜨는 태그 목록. 묶음마다 제목, 태그들, (지우기).
 * 맨 아래는 붓 크기.
 */
public final class TagPalette extends UiPart<AreaEditorScreen> {
    public static final int WIDTH = 120, ROW = 14;

    /** 한 줄: 묶음 제목(tag·erase 없음) / 태그 / 묶음 지우기. */
    private record Row(TagGroup group, @Nullable SpaceTag tag, boolean erase) {}

    private final List<Row> rows = new ArrayList<>();

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

    private List<Row> rowList() {
        rows.clear();
        for (TagGroup g : tool().groups()) {
            List<SpaceTag> tags = tool().paintable(g);
            boolean erase = tool().canErase(g);
            if (tags.isEmpty() && !erase) continue;
            rows.add(new Row(g, null, false));
            for (SpaceTag t : tags) rows.add(new Row(g, t, false));
            if (erase) rows.add(new Row(g, null, true));
        }
        return rows;
    }

    /** 지금 묶음이 개체 묶음이면 '고른 개체' 줄이 하나 더 있다. */
    private boolean instanceRow() {
        TagGroup g = tool().currentGroup();
        return g != null && g.instanced();
    }

    /** 줄 수 (개체·태그 표시·붓 줄 포함). */
    public int rows() {
        return rowList().size() + 2 + (instanceRow() ? 1 : 0);
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
        SpaceTag cur = t.current();
        TagGroup curGroup = t.currentGroup();
        int ry = y + 1;
        for (Row r : rowList()) {
            if (r.tag() == null && !r.erase()) {
                Ui.text(g, Ui.clip(Component.translatable(r.group().nameKey()).getString(), w - 10), x + 5, ry + 3, Ui.DIM);
            } else if (r.erase()) {
                boolean active = cur == null && curGroup == r.group();
                row(g, mx, my, ry, Icons.ERASE, Ui.DEMOLISH, Component.translatable("minedraftlib.tag.erase"), active);
            } else {
                SpaceTag tag = r.tag();
                row(g, mx, my, ry, tag.icon(), 0xFF000000 | tag.color(), Component.translatable(tag.nameKey()), cur == tag);
            }
            ry += ROW;
        }
        // 개체 묶음: 고른 개체 (눌러서 놓으면 다음 끌기는 새 개체)
        if (instanceRow()) {
            int sel = t.selectedInstance(curGroup.id());
            boolean hv = sel > 0 && Ui.inside(mx, my, x + 1, ry, w - 2, ROW);
            if (hv) g.fill(x + 1, ry, x + w - 1, ry + ROW, Ui.HOVER);
            Component label = sel > 0
                    ? Component.translatable("minedraftlib.tag.instance", screen.tagInstanceName(curGroup.id(), sel))
                    : Component.translatable("minedraftlib.tag.instance_new");
            Ui.text(g, Ui.clip(label.getString(), w - 10), x + 5, ry + 3, sel > 0 ? Ui.TEXT : Ui.DIM);
            ry += ROW;
        }
        // 태그 표시 (T): 칸마다 태그 색으로 덮어 바닥·길·구조물 등을 구분해 본다
        boolean on = screen.tagView;
        boolean hover = Ui.inside(mx, my, x + 1, ry, w - 2, ROW);
        if (hover) g.fill(x + 1, ry, x + w - 1, ry + ROW, Ui.HOVER);
        Ui.icon(g, Icons.EYE, x + 5, ry - 1, on ? Ui.ACCENT : Ui.DIM);
        Ui.text(g, Component.translatable(on ? "minedraftlib.tag.view_on" : "minedraftlib.tag.view_off"), x + 24, ry + 3,
                on ? Ui.TEXT : Ui.DIM);
        ry += ROW;
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
        int i = (int) ((my - y - 1) / ROW);
        List<Row> list = rowList();
        if (i >= 0 && i < list.size()) {
            Row r = list.get(i);
            if (r.erase()) tool().selectErase(r.group());
            else if (r.tag() != null) tool().select(r.tag());
        } else {
            int k = i - list.size();
            if (instanceRow()) {
                TagGroup cg = tool().currentGroup();
                if (k == 0 && cg != null) tool().selectInstance(cg.id(), 0);
                k--;
            }
            if (k == 0) screen.tagView = !screen.tagView;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        tool().brushScroll(delta);
        return true;
    }
}
