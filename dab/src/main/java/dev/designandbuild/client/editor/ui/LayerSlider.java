package dev.designandbuild.client.editor.ui;

import dev.minedraft.lib.client.editor.ui.UiPart;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import dev.designandbuild.core.StructureInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 뷰포트 오른쪽 가장자리의 단면 도구. 평소에는 버튼 하나(L)만, 켜면 아래 도구와 축별 슬라이더.
 * 위에서부터: 단면 켜기, 커서 버블(X), 이 면만 / 이 면까지, 축 토글 Y·X·Z (여러 개 동시에).
 * 켜진 축마다 슬라이더가 하나씩 열려 깊이를 따로 정한다. 슬라이더 위쪽 = 더 드러남(숨긴 쪽 끝).
 * 테두리가 밝은 축이 조작 축(Shift+휠, Ctrl+클릭)이다. Y 단면에서 구조물이 선택돼 있으면 상대 층(2층 / 5층)으로 표시한다.
 */
public final class LayerSlider extends DabPart {
    private static final Direction.Axis[] ORDER = {Direction.Axis.Y, Direction.Axis.X, Direction.Axis.Z};
    private static final int AXIS_Y0 = 56, AXIS_H = 14, TRACKS_Y0 = AXIS_Y0 + 3 * (AXIS_H + 2) + 4;
    /** 슬라이더 하나의 머리(축 글자) 높이와 사이 간격. */
    private static final int HEAD = 10, GAP = 6;

    @Nullable
    private Direction.Axis dragging;

    public LayerSlider(EditorScreen screen) {
        super(screen);
    }

    /** 켜진 축들 (Y, X, Z 순서). */
    private List<Direction.Axis> enabled() {
        List<Direction.Axis> out = new ArrayList<>(3);
        for (Direction.Axis a : ORDER) if (s().cutOn[a.ordinal()]) out.add(a);
        return out;
    }

    /** 축 슬라이더의 [위, 아래] 화면 좌표 (머리 제외). */
    private int[] track(Direction.Axis axis) {
        List<Direction.Axis> on = enabled();
        int i = on.indexOf(axis);
        int top = y + TRACKS_Y0, bottom = y + h - 6;
        int seg = Math.max(30, (bottom - top + GAP) / Math.max(1, on.size()));
        int t = top + i * seg + HEAD, b = top + (i + 1) * seg - GAP;
        return new int[]{t, Math.max(t + 10, b)};
    }

    @Override
    public boolean contains(double mx, double my) {
        if (!s().cutActive) return Ui.inside(mx, my, x + 8, y, 18, 18);
        return super.contains(mx, my);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        EditorSession s = s();
        boolean h0 = Ui.inside(mx, my, x + 8, y, 18, 18);
        g.fill(x + 8, y, x + 26, y + 18, Ui.PANEL);
        Ui.iconButton(g, x + 8, y, 18, 18, Icons.LAYER, s.cutActive, h0, true);
        if (h0) screen.tooltip = List.of(Component.translatable("designandbuild.layer.toggle"), Component.translatable("designandbuild.layer.toggle.tip"));
        if (!s.cutActive) return;

        boolean h1 = Ui.inside(mx, my, x + 8, y + 20, 18, 18);
        g.fill(x + 8, y + 20, x + 26, y + 38, Ui.PANEL);
        Ui.iconButton(g, x + 8, y + 20, 18, 18, Icons.BUBBLE, s.bubble, h1, true);
        if (h1) screen.tooltip = List.of(Component.translatable("designandbuild.layer.bubble"), Component.translatable("designandbuild.layer.bubble.tip"));
        boolean h2 = Ui.inside(mx, my, x + 8, y + 40, 18, 14);
        g.fill(x + 8, y + 40, x + 26, y + 54, h2 ? 0xF0262E38 : Ui.PANEL);
        String mode = Component.translatable(s.cutOnlyLayer ? "designandbuild.layer.only.short" : "designandbuild.layer.below.short").getString();
        Ui.text(g, mode, x + 17 - Ui.font().width(mode) / 2, y + 43, Ui.TEXT);
        if (h2) screen.tooltip = List.of(Component.translatable(s.cutOnlyLayer ? "designandbuild.layer.only" : "designandbuild.layer.below"),
                Component.translatable("designandbuild.layer.mode.tip"));

        // 축 토글 (여러 개 동시에)
        for (int k = 0; k < ORDER.length; k++) {
            Direction.Axis a = ORDER[k];
            int by = y + AXIS_Y0 + k * (AXIS_H + 2);
            boolean on = s.cutOn[a.ordinal()];
            boolean hv = Ui.inside(mx, my, x + 8, by, 18, AXIS_H);
            g.fill(x + 8, by, x + 26, by + AXIS_H, on ? 0xF0204A48 : hv ? 0xF0262E38 : Ui.PANEL);
            if (on && s.cutAxis == a) Ui.border(g, x + 8, by, 18, AXIS_H, Ui.ACCENT);
            String name = a.getName().toUpperCase(Locale.ROOT);
            Ui.text(g, name, x + 17 - Ui.font().width(name) / 2, by + 3, on ? Ui.ACCENT : Ui.DIM);
            if (hv) screen.tooltip = List.of(Component.translatable("designandbuild.layer.axis", name),
                    Component.translatable("designandbuild.layer.axis.tip"));
        }

        // 켜진 축마다 슬라이더
        for (Direction.Axis a : enabled()) {
            int[] t = track(a);
            int top = t[0], bottom = t[1];
            String head = a.getName().toUpperCase(Locale.ROOT);
            Ui.text(g, head, x + 17 - Ui.font().width(head) / 2, top - HEAD, s.cutAxis == a ? Ui.ACCENT : Ui.DIM);
            int tx = x + 16;
            g.fill(tx, top, tx + 2, bottom, 0xA0000000);
            g.fill(tx, top, tx + 1, bottom, Ui.EDGE);
            int[] r = s.cutRange(a);
            int value = s.cutAt[a.ordinal()];
            int hy = handleY(a, value, r, top, bottom);
            boolean hh = Ui.inside(mx, my, x + 6, hy - 4, 22, 9);
            g.fill(x + 8, hy - 3, x + 26, hy + 4, hh || dragging == a ? Ui.ACCENT : 0xFFB8C2CC);
            String label;
            StructureInstance st = s.selected != 0 ? s.model.structure(s.selected) : null;
            if (st != null && st.bounds() != null && a == Direction.Axis.Y) {
                int floors = st.bounds().sizeY();
                int floor = Mth.clamp(value - st.bounds().minY() + 1, 0, floors);
                label = Component.translatable("designandbuild.layer.floor", floor, floors).getString();
            } else {
                label = head + " " + value;
            }
            int lw = Ui.font().width(label) + 6;
            g.fill(x + 6 - lw, hy - 5, x + 6, hy + 6, 0xE0101418);
            Ui.text(g, label, x + 9 - lw, hy - 3, Ui.TEXT);
            if (hh) screen.tooltip = List.of(Component.translatable("designandbuild.layer.drag"));
        }
    }

    /** 위쪽 = 숨긴 쪽 끝 (다 드러남). 숨기는 쪽이 - 방향이면 값 방향을 뒤집는다. */
    private int handleY(Direction.Axis a, int value, int[] r, int top, int bottom) {
        float k = (value - r[0]) / (float) Math.max(1, r[1] - r[0]);
        if (s().cutSign(a) < 0) k = 1 - k;
        return Mth.clamp(Math.round(bottom - k * (bottom - top)), top, bottom);
    }

    private int valueAt(Direction.Axis a, double my, int[] r, int top, int bottom) {
        float k = (float) ((bottom - my) / Math.max(1, bottom - top));
        if (s().cutSign(a) < 0) k = 1 - k;
        return Mth.clamp(Math.round(r[0] + k * (r[1] - r[0])), r[0] - 1, r[1] + 1);
    }

    /** 이 높이에 있는 슬라이더 축 (머리 포함). */
    @Nullable
    private Direction.Axis trackAt(double my) {
        for (Direction.Axis a : enabled()) {
            int[] t = track(a);
            if (my >= t[0] - HEAD && my <= t[1] + 4) return a;
        }
        return null;
    }

    private void setFromMouse(Direction.Axis a, double my) {
        int[] t = track(a);
        s().cutAt[a.ordinal()] = valueAt(a, my, s().cutRange(a), t[0], t[1]);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        EditorSession s = s();
        if (Ui.inside(mx, my, x + 8, y, 18, 18)) {
            s.toggleCut();
            return true;
        }
        if (!s.cutActive) return false;
        if (Ui.inside(mx, my, x + 8, y + 20, 18, 18)) {
            s.bubble = !s.bubble;
            return true;
        }
        if (Ui.inside(mx, my, x + 8, y + 40, 18, 14)) {
            s.cutOnlyLayer = !s.cutOnlyLayer;
            return true;
        }
        for (int k = 0; k < ORDER.length; k++) {
            int by = y + AXIS_Y0 + k * (AXIS_H + 2);
            if (Ui.inside(mx, my, x + 8, by, 18, AXIS_H)) {
                s.toggleCutAxis(ORDER[k]);
                return true;
            }
        }
        Direction.Axis a = trackAt(my);
        if (a != null) {
            s.focusCutAxis(a);
            dragging = a;
            setFromMouse(a, my);
        }
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (dragging == null) return false;
        setFromMouse(dragging, my);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (dragging == null) return false;
        dragging = null;
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        Direction.Axis a = trackAt(my);
        if (a == null) a = s().cutAxis;
        s().focusCutAxis(a);
        s().moveCut(a, delta > 0 ? 1 : -1);
        return true;
    }
}
