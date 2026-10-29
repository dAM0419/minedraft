package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.library.ClientLibrary;
import dev.designandbuild.client.library.HiddenList;
import dev.designandbuild.client.library.LibraryEntry;
import dev.minedraft.lib.client.ui.Icons;
import dev.designandbuild.client.ui.Thumbnails;
import dev.minedraft.lib.client.ui.Ui;
import dev.designandbuild.core.TemplateData;
import dev.designandbuild.core.TemplateParser;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 라이브러리 탭: 검색창, 태그 칩 3줄(출처·종류·지형), 최근·즐겨찾기 줄, 썸네일 격자.
 *
 * 태그 선택은 여러 개를 동시에 고를 수 있다. 같은 축 안에서는 합집합, 다른 축끼리는 교집합.
 * 칩 목록은 그 축의 태그 전부(숨긴 태그 제외)이고, 칩을 눌러도 다른 칩이 사라지지 않는다.
 * 숨긴 구조물·태그는 목록에서 빠지고, 조건에 맞는 숨김이 있으면 격자 아래에 개수를 보여 준다.
 */
final class LibraryTab {
    private static final int CELL = 46, CHIP_ROW = 12, MENU_ITEM = 14, NOTE_H = 14, CLEAR_H = 11;

    /** 태그와, 다른 조건을 지킨 채 이 태그를 더하면 나오는 구조물 수. */
    record Chip(String tag, int count) {}

    private final EditorScreen screen;
    private final EditBox search;
    private final Set<String> active = new LinkedHashSet<>();
    private final int[] chipScroll = new int[3];
    private int scroll;

    // refilter 결과
    private List<LibraryEntry> filtered = List.of();
    private int hiddenMatches;
    @SuppressWarnings("unchecked")
    private final List<Chip>[] chipData = new List[]{List.of(), List.of(), List.of()};
    private String filterKey = "";

    // 이번 프레임에 그린 클릭 영역
    private record Hit(int axis, String tag, int x, int w) {}

    private final List<Hit> hits = new ArrayList<>();
    private final int[] axisBtnW = new int[3];
    private final int[] plusX = {-1, -1, -1}, plusW = new int[3];
    private int noteY = -1;

    // 썸네일 우클릭 메뉴
    @Nullable
    private LibraryEntry menuEntry;
    private int menuX, menuY, menuW;

    @Nullable
    private LibraryEntry press;
    private double pressX, pressY;
    private int px, py, pw, ph;

    LibraryTab(EditorScreen screen) {
        this.screen = screen;
        this.search = new EditBox(Ui.font(), 0, 0, 100, 12, Component.empty());
        search.setBordered(false);
        search.setMaxLength(48);
        search.setTextColor(Ui.TEXT);
        search.setHint(Component.translatable("designandbuild.library.search"));
    }

    // ------------------------------------------------------------------
    // 이름
    // ------------------------------------------------------------------

    static Component tagName(String tag) {
        int c = tag.indexOf(':');
        String axis = c < 0 ? "" : tag.substring(0, c);
        String value = c < 0 ? tag : tag.substring(c + 1);
        String key = "designandbuild.tagname." + value;
        if (I18n.exists(key)) return Component.translatable(key);
        if ("source".equals(axis)) {
            var mod = ModList.get().getModContainerById(value);
            if (mod.isPresent()) return Component.literal(mod.get().getModInfo().getDisplayName());
        }
        String pretty = value.replace('_', ' ');
        return Component.literal(pretty.isEmpty() ? value : Character.toUpperCase(pretty.charAt(0)) + pretty.substring(1));
    }

    static Component axisName(int axis) {
        return Component.translatable("designandbuild.tagaxis." + TemplateParser.TAG_AXES[axis]);
    }

    // ------------------------------------------------------------------
    // 거르기
    // ------------------------------------------------------------------

    private static boolean matches(LibraryEntry e, String q) {
        if (e.name().toLowerCase(Locale.ROOT).contains(q)) return true;
        for (String t : e.tags()) {
            if (t.toLowerCase(Locale.ROOT).contains(q)) return true;
            if (tagName(t).getString().toLowerCase(Locale.ROOT).contains(q)) return true;
        }
        return false;
    }

    private static boolean axisOk(LibraryEntry e, Set<String> selected) {
        if (selected.isEmpty()) return true;
        for (String t : e.tags()) if (selected.contains(t)) return true;
        return false;
    }

    private List<Set<String>> activeByAxis() {
        List<Set<String>> out = new ArrayList<>(3);
        for (int a = 0; a < 3; a++) {
            String prefix = TemplateParser.TAG_AXES[a] + ":";
            Set<String> s = new LinkedHashSet<>();
            for (String t : active) if (t.startsWith(prefix)) s.add(t);
            out.add(s);
        }
        return out;
    }

    private int selectedCount(int axis) {
        String prefix = TemplateParser.TAG_AXES[axis] + ":";
        int n = 0;
        for (String t : active) if (t.startsWith(prefix)) n++;
        return n;
    }

    private void refilter() {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        String key = q + "|" + active + "|" + ClientLibrary.revision() + "|" + HiddenList.revision();
        if (key.equals(filterKey)) return;
        filterKey = key;

        List<Path> folders = ClientConfig.allFolders();
        Set<String> lines = HiddenList.lines();
        List<Set<String>> act = activeByAxis();
        List<LibraryEntry> out = new ArrayList<>();
        int hiddenCount = 0;
        List<Map<String, Integer>> counts = List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());

        for (LibraryEntry e : ClientLibrary.all()) {
            boolean hid = HiddenList.isHidden(e, lines, folders);
            if (!q.isEmpty() && !matches(e, q)) continue;
            boolean[] ok = {axisOk(e, act.get(0)), axisOk(e, act.get(1)), axisOk(e, act.get(2))};
            boolean all = ok[0] && ok[1] && ok[2];
            if (hid) {
                if (all) hiddenCount++;
                continue;
            }
            if (all) out.add(e);
            for (String t : e.tags()) {
                int c = t.indexOf(':');
                if (c < 0) continue;
                int a = axisIndex(t.substring(0, c));
                if (a < 0) continue;
                // 이 축의 조건만 빼고 나머지를 지켰는가
                boolean others = true;
                for (int b = 0; b < 3; b++) if (b != a && !ok[b]) others = false;
                counts.get(a).merge(t, others ? 1 : 0, Integer::sum);
            }
        }
        filtered = out;
        hiddenMatches = hiddenCount;
        for (int a = 0; a < 3; a++) {
            Map<String, Integer> m = counts.get(a);
            for (String t : act.get(a)) m.putIfAbsent(t, 0); // 고른 태그는 항상 보인다
            List<Chip> list = new ArrayList<>(m.size());
            for (var en : m.entrySet()) list.add(new Chip(en.getKey(), en.getValue()));
            final Set<String> sel = act.get(a);
            list.sort((x, y) -> {
                boolean sx = sel.contains(x.tag()), sy = sel.contains(y.tag());
                if (sx != sy) return sx ? -1 : 1;
                if (x.count() != y.count()) return y.count() - x.count();
                return tagName(x.tag()).getString().compareToIgnoreCase(tagName(y.tag()).getString());
            });
            chipData[a] = list;
        }
        scroll = Mth.clamp(scroll, 0, maxScroll());
    }

    private static int axisIndex(String axis) {
        for (int i = 0; i < TemplateParser.TAG_AXES.length; i++) if (TemplateParser.TAG_AXES[i].equals(axis)) return i;
        return -1;
    }

    // ------------------------------------------------------------------
    // 다른 창이 쓰는 것
    // ------------------------------------------------------------------

    /** 그 축의 태그 전부(숨긴 것 제외)와 구조물 수. */
    List<Chip> chipList(int axis) {
        refilter();
        return chipData[axis];
    }

    boolean isActive(String tag) {
        return active.contains(tag);
    }

    void toggle(String tag) {
        if (!active.remove(tag)) active.add(tag);
        scroll = 0;
    }

    void clearAxis(int axis) {
        String prefix = TemplateParser.TAG_AXES[axis] + ":";
        active.removeIf(t -> t.startsWith(prefix));
        scroll = 0;
    }

    void clearAll() {
        active.clear();
        scroll = 0;
    }

    void hideTag(String tag) {
        HiddenList.hideTag(tag);
        active.remove(tag);
        scroll = 0;
        screen.session().toast(Component.translatable("designandbuild.hidden.tag_done", tagName(tag)));
    }

    /** 지금 검색·태그 조건 (숨김 여부는 따지지 않음). 숨김 관리 창이 같은 조건으로 걸러 보여 주는 데 쓴다. */
    Predicate<LibraryEntry> filterSnapshot() {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        List<Set<String>> act = activeByAxis();
        return e -> (q.isEmpty() || matches(e, q)) && axisOk(e, act.get(0)) && axisOk(e, act.get(1)) && axisOk(e, act.get(2));
    }

    private void openHidden(boolean filtered) {
        screen.openModal(new HiddenDialog(screen, filtered ? filterSnapshot() : null));
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    private int extra() {
        return active.isEmpty() ? 0 : CLEAR_H;
    }

    private int chipsTop() {
        return py + 20;
    }

    private int quickTop() {
        return chipsTop() + 3 * (CHIP_ROW + 2) + 2 + extra();
    }

    private int gridTop() {
        return quickTop() + 22;
    }

    private int contentHeight() {
        int rows = (filtered.size() + 2) / 3;
        return rows * CELL + (hiddenMatches > 0 ? NOTE_H : 0);
    }

    private int maxScroll() {
        return Math.max(0, contentHeight() - (py + ph - gridTop()));
    }

    void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my) {
        px = x;
        py = y;
        pw = w;
        ph = h;
        refilter();
        scroll = Mth.clamp(scroll, 0, maxScroll());
        hits.clear();
        noteY = -1;
        boolean menuOpen = menuEntry != null;
        int hmx = menuOpen ? -1000 : mx, hmy = menuOpen ? -1000 : my; // 메뉴가 열려 있으면 뒤는 호버하지 않는다

        // 검색 + 새로고침 + 폴더 + 숨김 관리
        int sw = w - 62;
        g.fill(x + 4, y + 4, x + 4 + sw, y + 16, search.isFocused() ? 0xFF0B0E12 : 0xFF161B22);
        Ui.border(g, x + 4, y + 4, sw, 12, search.isFocused() ? Ui.ACCENT : Ui.EDGE);
        search.setX(x + 7);
        search.setY(y + 6);
        search.setWidth(sw - 6);
        search.render(g, hmx, hmy, 0);
        int rx = x + w - 56;
        boolean hr = Ui.inside(hmx, hmy, rx, y + 2, 16, 16);
        Ui.iconButton(g, rx, y + 2, 16, 16, Icons.REFRESH, ClientLibrary.isScanning(), hr, true);
        if (hr) screen.tooltip = List.of(Component.translatable("designandbuild.library.refresh"));
        boolean hg = Ui.inside(hmx, hmy, rx + 18, y + 2, 16, 16);
        Ui.iconButton(g, rx + 18, y + 2, 16, 16, Icons.FOLDER, false, hg, true);
        if (hg) screen.tooltip = List.of(Component.translatable("designandbuild.library.folders"));
        boolean he = Ui.inside(hmx, hmy, rx + 36, y + 2, 16, 16);
        boolean anyHidden = !HiddenList.lines().isEmpty();
        Ui.iconButton(g, rx + 36, y + 2, 16, 16, Icons.EYE, anyHidden, he, true);
        if (he) {
            screen.tooltip = List.of(Component.translatable("designandbuild.hidden.open"),
                    Component.translatable("designandbuild.hidden.open_count", HiddenList.lines().size())
                            .withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
        }

        // 태그 칩 3줄
        int cy = chipsTop();
        int labelW = 0;
        String[] labels = new String[3];
        for (int axis = 0; axis < 3; axis++) {
            int n = selectedCount(axis);
            labels[axis] = axisName(axis).getString() + (n > 0 ? " " + n : "") + " ▾";
            axisBtnW[axis] = Ui.font().width(labels[axis]) + 6;
            labelW = Math.max(labelW, axisBtnW[axis]);
        }
        for (int axis = 0; axis < 3; axis++) {
            int ry = cy + axis * (CHIP_ROW + 2);
            boolean hb = Ui.inside(hmx, hmy, x + 2, ry, labelW, CHIP_ROW);
            boolean has = selectedCount(axis) > 0;
            g.fill(x + 2, ry, x + 2 + labelW, ry + CHIP_ROW, has ? 0x402EC4B6 : hb ? 0x40FFFFFF : 0x20FFFFFF);
            Ui.border(g, x + 2, ry, labelW, CHIP_ROW, has ? Ui.ACCENT : Ui.EDGE);
            Ui.text(g, labels[axis], x + 5, ry + 2, has ? Ui.TEXT : Ui.DIM);
            if (hb) screen.tooltip = List.of(Component.translatable("designandbuild.library.axis_tip", axisName(axis)));

            List<Chip> chips = chipData[axis];
            int areaL = x + 2 + labelW + 2, areaR = x + w - 2;
            // 칩 너비 전체
            int total = 0;
            int[] cw = new int[chips.size()];
            for (int i = 0; i < chips.size(); i++) {
                Chip c = chips.get(i);
                cw[i] = Ui.font().width(chipLabel(c)) + 6;
                total += cw[i] + 2;
            }
            boolean overflow = total > areaR - areaL;
            int plusWidth = overflow ? Ui.font().width("+99") + 6 : 0;
            int clipR = areaR - plusWidth - (overflow ? 2 : 0);
            chipScroll[axis] = Mth.clamp(chipScroll[axis], 0, Math.max(0, total - (clipR - areaL)));
            int cx = areaL - chipScroll[axis];
            int hidden = 0;
            g.enableScissor(areaL, ry, clipR, ry + CHIP_ROW);
            for (int i = 0; i < chips.size(); i++) {
                Chip c = chips.get(i);
                boolean on = active.contains(c.tag());
                boolean dim = c.count() == 0 && !on;
                boolean hover = Ui.inside(hmx, hmy, cx, ry, cw[i], CHIP_ROW) && hmx >= areaL && hmx < clipR;
                g.fill(cx, ry, cx + cw[i], ry + CHIP_ROW, on ? 0x602EC4B6 : hover ? 0x40FFFFFF : 0x20FFFFFF);
                if (on) Ui.border(g, cx, ry, cw[i], CHIP_ROW, Ui.ACCENT);
                Component name = tagName(c.tag());
                Ui.text(g, name, cx + 3, ry + 2, on ? Ui.TEXT : dim ? Ui.DISABLED : Ui.DIM);
                int nameW = Ui.font().width(name);
                Ui.text(g, " " + c.count(), cx + 3 + nameW, ry + 2, on ? Ui.DIM : Ui.DISABLED);
                if (cx < clipR && cx + cw[i] > areaL) hits.add(new Hit(axis, c.tag(), Math.max(cx, areaL), Math.min(cx + cw[i], clipR) - Math.max(cx, areaL)));
                if (cx + cw[i] > clipR) hidden++;
                if (hover) {
                    screen.tooltip = List.of(name, Component.translatable("designandbuild.library.chip_tip")
                            .withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
                }
                cx += cw[i] + 2;
            }
            g.disableScissor();
            if (overflow) {
                int bx = areaR - plusWidth;
                boolean hp = Ui.inside(hmx, hmy, bx, ry, plusWidth, CHIP_ROW);
                g.fill(bx, ry, bx + plusWidth, ry + CHIP_ROW, hp ? 0x60FFFFFF : 0x30FFFFFF);
                Ui.border(g, bx, ry, plusWidth, CHIP_ROW, Ui.EDGE);
                Ui.text(g, "+" + Math.max(1, hidden), bx + 3, ry + 2, Ui.TEXT);
                plusX[axis] = bx;
                plusW[axis] = plusWidth;
            } else {
                plusX[axis] = -1;
            }
        }

        // 모두 해제
        if (!active.isEmpty()) {
            int ly = cy + 3 * (CHIP_ROW + 2);
            Component clear = Component.translatable("designandbuild.library.clear_all", active.size());
            int cw = Ui.font().width(clear);
            boolean hc = Ui.inside(hmx, hmy, x + w - cw - 8, ly, cw + 6, CLEAR_H);
            Ui.text(g, clear, x + w - cw - 5, ly + 1, hc ? Ui.TEXT : Ui.ACCENT);
        }

        // 최근·즐겨찾기 줄
        int fy = quickTop();
        List<LibraryEntry> quick = quickRow();
        Ui.icon(g, Icons.STAR, x + 2, fy + 1, Ui.DIM);
        for (int i = 0; i < quick.size(); i++) {
            int qx = x + 20 + i * 22;
            LibraryEntry e = quick.get(i);
            boolean hover = Ui.inside(hmx, hmy, qx, fy, 20, 18);
            g.fill(qx, fy, qx + 20, fy + 18, hover ? 0x40FFFFFF : 0x20FFFFFF);
            TemplateData t = ClientLibrary.template(e);
            if (t != null) Thumbnails.draw(g, t, qx, fy, 20, 18, 45, 100);
            if (hover) screen.tooltip = entryTip(e, t);
        }

        // 격자
        int gy = gridTop();
        g.fill(x + 2, gy - 2, x + w - 2, gy - 1, Ui.EDGE);
        int gh = y + h - gy;
        g.enableScissor(x, gy, x + w, gy + gh);
        int cols = 3;
        int cellW = (w - 8) / cols;
        for (int i = 0; i < filtered.size(); i++) {
            int col = i % cols, row = i / cols;
            int ex = x + 4 + col * cellW, ey = gy + row * CELL - scroll;
            if (ey + CELL < gy || ey > gy + gh) continue;
            LibraryEntry e = filtered.get(i);
            boolean hover = Ui.inside(hmx, hmy, ex, ey, cellW - 2, CELL - 2) && hmy >= gy;
            boolean chosen = screen.session().placing != null && screen.session().placing.id().equals(e.id());
            g.fill(ex, ey, ex + cellW - 2, ey + CELL - 2, chosen ? 0x402EC4B6 : hover ? 0x30FFFFFF : 0x18FFFFFF);
            if (chosen) Ui.border(g, ex, ey, cellW - 2, CELL - 2, Ui.ACCENT);
            TemplateData t = ClientLibrary.template(e);
            if (t != null) {
                if (!Thumbnails.draw(g, t, ex + 2, ey + 2, cellW - 6, CELL - 14, 45, 100)) {
                    Ui.text(g, "…", ex + cellW / 2 - 3, ey + 14, Ui.DIM);
                }
            } else {
                Ui.text(g, ClientLibrary.isMissing(e) ? "×" : "…", ex + cellW / 2 - 3, ey + 14, Ui.DIM);
            }
            if (ClientConfig.get().favorites.contains(e.id())) Ui.icon(g, Icons.STAR, ex + cellW - 18, ey, Ui.WARN);
            String label = Ui.clip(e.name(), cellW - 6);
            Ui.text(g, label, ex + 2, ey + CELL - 12, Ui.DIM);
            if (hover) screen.tooltip = entryTip(e, t);
        }
        int contentBottom = gy + ((filtered.size() + 2) / 3) * CELL - scroll;
        if (filtered.isEmpty()) {
            Ui.text(g, Component.translatable(ClientLibrary.all().isEmpty() ? "designandbuild.library.loading" : "designandbuild.library.none"),
                    x + 6, gy + 6, Ui.DIM);
            contentBottom = gy + 20;
        }
        if (hiddenMatches > 0) {
            noteY = contentBottom + 2;
            Component note = Component.translatable("designandbuild.library.hidden_note", hiddenMatches);
            boolean hn = Ui.inside(hmx, hmy, x + 2, noteY, w - 4, NOTE_H - 2) && hmy >= gy;
            Ui.icon(g, Icons.EYE, x + 2, noteY - 3, hn ? Ui.TEXT : Ui.DIM);
            Ui.text(g, Ui.clip(note.getString(), w - 24), x + 18, noteY + 1, hn ? Ui.ACCENT : Ui.DIM);
        }
        g.disableScissor();

        if (menuEntry != null) renderMenu(g, mx, my);
    }

    private static String chipLabel(Chip c) {
        return tagName(c.tag()).getString() + " " + c.count();
    }

    private List<String> menuLabels() {
        boolean fav = menuEntry != null && ClientConfig.get().favorites.contains(menuEntry.id());
        return List.of(
                Component.translatable(fav ? "designandbuild.library.menu.unfav" : "designandbuild.library.menu.fav").getString(),
                Component.translatable("designandbuild.library.menu.hide").getString());
    }

    private void renderMenu(GuiGraphics g, int mx, int my) {
        List<String> labels = menuLabels();
        menuW = 0;
        for (String s : labels) menuW = Math.max(menuW, Ui.font().width(s) + 12);
        menuX = Math.min(menuX, px + pw - menuW - 2);
        menuY = Math.min(menuY, py + ph - labels.size() * MENU_ITEM - 4);
        g.pose().pushPose();
        g.pose().translate(0, 0, 200);
        Ui.solidPanel(g, menuX, menuY, menuW, labels.size() * MENU_ITEM + 4);
        for (int i = 0; i < labels.size(); i++) {
            int iy = menuY + 2 + i * MENU_ITEM;
            boolean hover = Ui.inside(mx, my, menuX + 2, iy, menuW - 4, MENU_ITEM);
            if (hover) g.fill(menuX + 2, iy, menuX + menuW - 2, iy + MENU_ITEM, 0x40FFFFFF);
            Ui.text(g, labels.get(i), menuX + 6, iy + 3, Ui.TEXT);
        }
        g.pose().popPose();
    }

    void renderTooltip() {
    }

    private List<LibraryEntry> quickRow() {
        List<LibraryEntry> out = new ArrayList<>();
        List<LibraryEntry> all = ClientLibrary.all();
        Set<String> lines = HiddenList.lines();
        List<Path> folders = ClientConfig.allFolders();
        for (String id : ClientConfig.get().favorites) {
            for (LibraryEntry e : all) {
                if (e.id().equals(id) && !out.contains(e) && !HiddenList.isHidden(e, lines, folders)) out.add(e);
            }
            if (out.size() >= 6) return out;
        }
        for (String id : ClientConfig.get().recent) {
            for (LibraryEntry e : all) {
                if (e.id().equals(id) && !out.contains(e) && !HiddenList.isHidden(e, lines, folders)) out.add(e);
            }
            if (out.size() >= 6) return out;
        }
        return out;
    }

    private List<Component> entryTip(LibraryEntry e, @Nullable TemplateData t) {
        List<Component> l = new ArrayList<>();
        l.add(Component.literal(e.name()));
        if (t != null) {
            l.add(Component.translatable("designandbuild.library.size", t.sizeX, t.sizeY, t.sizeZ).withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
            l.add(Component.translatable("designandbuild.library.blocks", t.realBlockCount()).withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
            l.add(Component.translatable("designandbuild.place.mount", Ui.dirName(t.mount)).withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
        } else if (e.sizeX() > 0) {
            l.add(Component.translatable("designandbuild.library.size", e.sizeX(), e.sizeY(), e.sizeZ()).withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
        }
        StringBuilder tags = new StringBuilder();
        for (String tag : e.tags()) {
            if (tags.length() > 0) tags.append(", ");
            tags.append(tagName(tag).getString());
        }
        l.add(Component.literal(tags.toString()).withStyle(s -> s.withColor(Ui.PATH & 0xFFFFFF)));
        l.add(Component.translatable("designandbuild.library.tip").withStyle(s -> s.withColor(Ui.DIM & 0xFFFFFF)));
        return l;
    }

    // ------------------------------------------------------------------
    // 입력
    // ------------------------------------------------------------------

    @Nullable
    private LibraryEntry entryAt(double mx, double my) {
        int gy = gridTop();
        if (my < gy) return null;
        int cellW = (pw - 8) / 3;
        int col = (int) ((mx - px - 4) / cellW);
        int row = (int) ((my - gy + scroll) / CELL);
        if (col < 0 || col > 2) return null;
        int i = row * 3 + col;
        return i >= 0 && i < filtered.size() ? filtered.get(i) : null;
    }

    boolean mouseClicked(double mx, double my, int button) {
        // 열려 있는 메뉴가 먼저
        if (menuEntry != null) {
            List<String> labels = menuLabels();
            LibraryEntry e = menuEntry;
            menuEntry = null;
            for (int i = 0; i < labels.size(); i++) {
                if (Ui.inside(mx, my, menuX + 2, menuY + 2 + i * MENU_ITEM, menuW - 4, MENU_ITEM)) {
                    if (i == 0) {
                        List<String> fav = ClientConfig.get().favorites;
                        if (!fav.remove(e.id())) fav.add(0, e.id());
                        ClientConfig.save();
                    } else {
                        HiddenList.hideStructure(e);
                        screen.session().toast(Component.translatable("designandbuild.hidden.struct_done", e.name()));
                    }
                    break;
                }
            }
            return true;
        }
        // 검색창
        if (Ui.inside(mx, my, px + 4, py + 4, pw - 62, 12)) {
            screen.focus(search, null);
            search.mouseClicked(mx, my, button);
            return true;
        }
        int rx = px + pw - 56;
        if (Ui.inside(mx, my, rx, py + 2, 16, 16)) {
            ClientLibrary.rescanLocal();
            return true;
        }
        if (Ui.inside(mx, my, rx + 18, py + 2, 16, 16)) {
            screen.openModal(new FolderDialog(screen));
            return true;
        }
        if (Ui.inside(mx, my, rx + 36, py + 2, 16, 16)) {
            openHidden(false);
            return true;
        }
        // 태그 칩 줄
        int cy = chipsTop();
        for (int axis = 0; axis < 3; axis++) {
            int ry = cy + axis * (CHIP_ROW + 2);
            if (my < ry || my >= ry + CHIP_ROW) continue;
            if (mx >= px + 2 && mx < chipAreaLeft() - 2) {
                screen.openModal(new TagPicker(screen, this, axis));
                return true;
            }
            if (plusX[axis] >= 0 && mx >= plusX[axis] && mx < plusX[axis] + plusW[axis]) {
                screen.openModal(new TagPicker(screen, this, axis));
                return true;
            }
            for (Hit h : hits) {
                if (h.axis() == axis && mx >= h.x() && mx < h.x() + h.w()) {
                    if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) hideTag(h.tag());
                    else toggle(h.tag());
                    return true;
                }
            }
            return true;
        }
        // 모두 해제
        if (!active.isEmpty()) {
            int ly = cy + 3 * (CHIP_ROW + 2);
            if (my >= ly && my < ly + CLEAR_H) {
                clearAll();
                return true;
            }
        }
        // 최근·즐겨찾기
        int fy = quickTop();
        List<LibraryEntry> quick = quickRow();
        for (int i = 0; i < quick.size(); i++) {
            if (Ui.inside(mx, my, px + 20 + i * 22, fy, 20, 18)) {
                screen.selectForPlacement(quick.get(i));
                return true;
            }
        }
        // 숨긴 결과 n개 · 보기
        if (noteY >= 0 && my >= noteY && my < noteY + NOTE_H - 2 && my >= gridTop()) {
            openHidden(true);
            return true;
        }
        LibraryEntry e = entryAt(mx, my);
        if (e == null) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            menuEntry = e;
            menuX = (int) mx;
            menuY = (int) my;
            return true;
        }
        press = e;
        pressX = mx;
        pressY = my;
        return true;
    }

    private int chipAreaLeft() {
        int labelW = 0;
        for (int w : axisBtnW) labelW = Math.max(labelW, w);
        return px + 2 + labelW + 2;
    }

    boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (press != null && Math.abs(mx - pressX) + Math.abs(my - pressY) > 4) {
            screen.libraryDrag = press;
            press = null;
            return true;
        }
        return press != null;
    }

    boolean mouseReleased(double mx, double my, int button) {
        if (press == null) return false;
        LibraryEntry e = press;
        press = null;
        screen.selectForPlacement(e);
        return true;
    }

    boolean mouseScrolled(double mx, double my, double delta) {
        menuEntry = null;
        int cy = chipsTop();
        for (int axis = 0; axis < 3; axis++) {
            int ry = cy + axis * (CHIP_ROW + 2);
            if (my >= ry && my < ry + CHIP_ROW) {
                chipScroll[axis] = Math.max(0, chipScroll[axis] - (int) (delta * 24));
                return true;
            }
        }
        scroll = Mth.clamp(scroll - (int) (delta * 24), 0, maxScroll());
        return true;
    }
}
