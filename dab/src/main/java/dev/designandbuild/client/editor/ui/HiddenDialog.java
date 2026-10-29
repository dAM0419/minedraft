package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.library.ClientLibrary;
import dev.designandbuild.client.library.HiddenList;
import dev.designandbuild.client.library.LibraryEntry;
import dev.designandbuild.client.ui.Thumbnails;
import dev.minedraft.lib.client.ui.Ui;
import dev.designandbuild.core.TemplateData;
import dev.designandbuild.core.TemplateParser;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 숨김 관리 창: 숨긴 구조물 / 숨긴 태그를 보고 하나씩 또는 모두 해제하고, 숨김 목록을 파일로 내보내거나 불러온다.
 * 이 컴퓨터에 없는 구조물·태그도 목록에는 흐리게 남는다.
 */
final class HiddenDialog extends Modal {
    private static final int W = 320, H = 236, ROW = 22, ROWS = 6;

    /** @param line 해제할 줄 (구조물 줄 또는 그 구조물을 숨기게 한 태그 줄) */
    private record Row(String line, @Nullable LibraryEntry entry, String title, String sub, boolean present, boolean byTag) {}

    @Nullable
    private final Predicate<LibraryEntry> filter;
    private boolean filterOn;
    private int tab; // 0 = 숨긴 구조물, 1 = 숨긴 태그
    private boolean importMode;
    private final EditBox query;

    private List<Row> rows = List.of();
    private String rowsKey = "";
    private int scroll;
    private boolean confirmClear, confirmReplace;
    private List<HiddenList.Listing> files = List.of();
    private int selectedFile = -1;
    @Nullable
    private Path lastExport;

    private int listX, listY, listW, tabsY, buttonsY, noteY;
    private final int[] tabX = new int[2], tabW = new int[2];
    private int filterX, filterW;

    HiddenDialog(EditorScreen screen, @Nullable Predicate<LibraryEntry> filter) {
        this(screen, filter, 0);
    }

    HiddenDialog(EditorScreen screen, @Nullable Predicate<LibraryEntry> filter, int startTab) {
        super(screen, W, H);
        this.filter = filter;
        this.filterOn = filter != null;
        this.tab = startTab;
        this.query = newBox(40, Component.translatable("designandbuild.hidden.search"));
    }

    // ------------------------------------------------------------------
    // 목록 만들기
    // ------------------------------------------------------------------

    private int countOf(String prefix) {
        int n = 0;
        for (String l : HiddenList.lines()) if (l.startsWith(prefix)) n++;
        return n;
    }

    private void rebuild() {
        String q = query.getValue().trim().toLowerCase(Locale.ROOT);
        String key = tab + "|" + filterOn + "|" + q + "|" + HiddenList.revision() + "|" + ClientLibrary.revision();
        if (key.equals(rowsKey)) return;
        rowsKey = key;
        List<Path> folders = ClientConfig.allFolders();
        List<LibraryEntry> all = ClientLibrary.all();
        List<Row> out = new ArrayList<>();
        if (tab == 0) {
            if (filterOn && filter != null) {
                // 조건에 맞는 숨긴 구조물 (구조물을 직접 숨겼거나, 숨긴 태그가 붙어서)
                for (LibraryEntry e : all) {
                    if (!filter.test(e)) continue;
                    String line = HiddenList.structureLine(e, folders);
                    boolean direct = HiddenList.lines().contains(line);
                    String tagLine = null;
                    if (!direct) {
                        for (String t : e.tags()) if (HiddenList.lines().contains(HiddenList.TAG + t)) tagLine = HiddenList.TAG + t;
                        if (tagLine == null) continue;
                    }
                    if (!q.isEmpty() && !e.name().toLowerCase(Locale.ROOT).contains(q)) continue;
                    out.add(new Row(direct ? line : tagLine, e, e.name(), sourceOf(e), true, !direct));
                }
            } else {
                Map<String, LibraryEntry> byLine = new HashMap<>();
                for (LibraryEntry e : all) byLine.put(HiddenList.structureLine(e, folders), e);
                for (String line : HiddenList.lines()) {
                    if (!line.startsWith(HiddenList.STRUCT)) continue;
                    LibraryEntry e = byLine.get(line);
                    String title = e != null ? e.name() : line.substring(HiddenList.STRUCT.length());
                    if (!q.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(q)) continue;
                    out.add(new Row(line, e, title, e != null ? sourceOf(e) : "", e != null, false));
                }
            }
            out.sort((a, b) -> a.present() != b.present() ? (a.present() ? -1 : 1) : a.title().compareToIgnoreCase(b.title()));
        } else {
            Map<String, Integer> counts = new HashMap<>();
            for (LibraryEntry e : all) for (String t : e.tags()) counts.merge(t, 1, Integer::sum);
            for (String line : HiddenList.lines()) {
                if (!line.startsWith(HiddenList.TAG)) continue;
                String tag = line.substring(HiddenList.TAG.length());
                int c = tag.indexOf(':');
                String axis = c < 0 ? "" : tag.substring(0, c);
                String title = axisLabel(axis) + " · " + LibraryTab.tagName(tag).getString();
                if (!q.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(q) && !tag.toLowerCase(Locale.ROOT).contains(q)) continue;
                int n = counts.getOrDefault(tag, 0);
                out.add(new Row(line, null, title, Component.translatable("designandbuild.hidden.tag_count", n).getString(), n > 0, false));
            }
            out.sort((a, b) -> a.title().compareToIgnoreCase(b.title()));
        }
        rows = out;
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - ROWS));
    }

    private static String axisLabel(String axis) {
        for (int i = 0; i < TemplateParser.TAG_AXES.length; i++) {
            if (TemplateParser.TAG_AXES[i].equals(axis)) return LibraryTab.axisName(i).getString();
        }
        return axis;
    }

    private static String sourceOf(LibraryEntry e) {
        for (String t : e.tags()) if (t.startsWith("source:")) return LibraryTab.tagName(t).getString();
        return "";
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    @Override
    protected void renderContent(GuiGraphics g, int mx, int my, float pt) {
        Ui.text(g, Component.translatable(importMode ? "designandbuild.hidden.import_title" : "designandbuild.hidden.title"), x + 10, y + 7, Ui.TEXT);
        g.fill(x + 1, y + 20, x + w - 1, y + 21, Ui.EDGE);
        listX = x + 8;
        listW = w - 16;
        listY = y + 62;
        buttonsY = y + h - 24;
        noteY = y + h - 42;
        if (importMode) {
            renderImport(g, mx, my);
            return;
        }
        rebuild();

        // 탭
        tabsY = y + 25;
        String[] names = {Component.translatable("designandbuild.hidden.tab_structs", countOf(HiddenList.STRUCT)).getString(),
                Component.translatable("designandbuild.hidden.tab_tags", countOf(HiddenList.TAG)).getString()};
        int tx = x + 8;
        for (int i = 0; i < 2; i++) {
            tabW[i] = Ui.font().width(names[i]) + 16;
            tabX[i] = tx;
            boolean on = tab == i;
            boolean hover = Ui.inside(mx, my, tx, tabsY, tabW[i], 16);
            g.fill(tx, tabsY, tx + tabW[i], tabsY + 16, on ? 0xFF1E6F68 : hover ? 0xFF323C48 : 0xFF262E38);
            Ui.border(g, tx, tabsY, tabW[i], 16, on ? Ui.ACCENT : Ui.EDGE);
            Ui.text(g, names[i], tx + 8, tabsY + 4, Ui.TEXT);
            tx += tabW[i] + 2;
        }
        // 필터 안내
        filterW = 0;
        if (filter != null && tab == 0) {
            Component f = Component.translatable(filterOn ? "designandbuild.hidden.filter_on" : "designandbuild.hidden.filter_off");
            filterW = Ui.font().width(f);
            filterX = x + w - 8 - filterW;
            boolean hf = Ui.inside(mx, my, filterX, tabsY + 3, filterW, 10);
            Ui.text(g, f, filterX, tabsY + 4, hf ? Ui.TEXT : Ui.ACCENT);
        }
        drawBox(g, query, x + 8, y + 44, w - 16, mx, my);

        // 목록
        int shown = Math.min(ROWS, rows.size());
        scroll = Mth.clamp(scroll, 0, Math.max(0, rows.size() - ROWS));
        g.fill(listX, listY, listX + listW, listY + ROWS * ROW, 0x20000000);
        for (int r = 0; r < shown; r++) {
            Row row = rows.get(r + scroll);
            int ry = listY + r * ROW;
            boolean hover = Ui.inside(mx, my, listX, ry, listW, ROW - 2);
            g.fill(listX, ry, listX + listW, ry + ROW - 2, hover ? 0x28FFFFFF : 0x14FFFFFF);
            int tx2 = listX + 4;
            if (tab == 0) {
                if (row.entry() != null) {
                    TemplateData t = ClientLibrary.template(row.entry());
                    if (t != null) Thumbnails.draw(g, t, listX + 2, ry + 1, 28, ROW - 4, 45, 100);
                }
                tx2 = listX + 34;
            }
            int color = row.present() ? Ui.TEXT : Ui.DISABLED;
            int btnW = 40;
            int textW = listW - (tx2 - listX) - btnW - 10;
            Ui.text(g, Ui.clip(row.title(), textW), tx2, ry + 2, color);
            String sub = row.byTag() ? Component.translatable("designandbuild.hidden.by_tag", LibraryTab.tagName(row.line().substring(HiddenList.TAG.length()))).getString()
                    : !row.present() && tab == 0 ? Component.translatable("designandbuild.hidden.not_here").getString() : row.sub();
            if (!sub.isEmpty()) Ui.text(g, Ui.clip(sub, textW), tx2, ry + 12, Ui.DIM);
            int bx = listX + listW - btnW - 3;
            Component un = Component.translatable("designandbuild.hidden.unhide");
            boolean hb = Ui.inside(mx, my, bx, ry + 2, btnW, ROW - 6);
            g.fill(bx, ry + 2, bx + btnW, ry + ROW - 4, hb ? 0xFF323C48 : 0xFF262E38);
            Ui.border(g, bx, ry + 2, btnW, ROW - 6, Ui.EDGE);
            Ui.centered(g, un, bx + btnW / 2, ry + 5, Ui.TEXT);
            if (hover && Ui.font().width(row.title()) > textW) screen.tooltip = List.of(Component.literal(row.title()));
        }
        if (rows.isEmpty()) {
            Ui.text(g, Component.translatable(HiddenList.lines().isEmpty() ? "designandbuild.hidden.empty" : "designandbuild.hidden.no_match"),
                    listX + 6, listY + 8, Ui.DIM);
        }
        if (rows.size() > ROWS) {
            int bh = Math.max(10, ROWS * ROW * ROWS / rows.size());
            int by = listY + (ROWS * ROW - bh) * scroll / Math.max(1, rows.size() - ROWS);
            g.fill(listX + listW - 2, listY, listX + listW, listY + ROWS * ROW, 0x30FFFFFF);
            g.fill(listX + listW - 2, by, listX + listW, by + bh, 0xA0FFFFFF);
        }

        // 내보내기 결과
        if (lastExport != null) {
            Component done = Component.translatable("designandbuild.hidden.exported", lastExport.getFileName().toString());
            Ui.text(g, Ui.clip(done.getString(), w - 120), x + 10, noteY, Ui.GOOD);
            Component open = Component.translatable("designandbuild.hidden.open_folder");
            int ow = Ui.font().width(open);
            boolean ho = Ui.inside(mx, my, x + w - 10 - ow, noteY - 1, ow, 10);
            Ui.text(g, open, x + w - 10 - ow, noteY, ho ? Ui.TEXT : Ui.ACCENT);
        }

        // 단추
        int bx = x + 8;
        Component clear = Component.translatable(confirmClear ? "designandbuild.hidden.clear_confirm" : "designandbuild.hidden.clear_all");
        bx += button(g, bx, buttonsY, clear, false, confirmClear, mx, my) + 4;
        bx += button(g, bx, buttonsY, Component.translatable("designandbuild.hidden.export"), false, false, mx, my) + 4;
        button(g, bx, buttonsY, Component.translatable("designandbuild.hidden.import"), false, false, mx, my);
        Component close = Component.translatable("designandbuild.dialog.close");
        button(g, x + w - 8 - buttonWidth(close), buttonsY, close, true, false, mx, my);
    }

    private void renderImport(GuiGraphics g, int mx, int my) {
        Ui.text(g, Component.translatable("designandbuild.hidden.import_text", "config/designandbuild/hidden"), x + 10, y + 26, Ui.DIM);
        int shown = Math.min(ROWS, files.size());
        scroll = Mth.clamp(scroll, 0, Math.max(0, files.size() - ROWS));
        g.fill(listX, listY, listX + listW, listY + ROWS * ROW, 0x20000000);
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
        for (int r = 0; r < shown; r++) {
            HiddenList.Listing f = files.get(r + scroll);
            int ry = listY + r * ROW;
            boolean sel = r + scroll == selectedFile;
            boolean hover = Ui.inside(mx, my, listX, ry, listW, ROW - 2);
            g.fill(listX, ry, listX + listW, ry + ROW - 2, sel ? 0x402EC4B6 : hover ? 0x28FFFFFF : 0x14FFFFFF);
            if (sel) Ui.border(g, listX, ry, listW, ROW - 2, Ui.ACCENT);
            Ui.text(g, Ui.clip(f.file().getFileName().toString(), listW - 12), listX + 6, ry + 2, Ui.TEXT);
            Ui.text(g, Component.translatable("designandbuild.hidden.file_info", f.count(), fmt.format(Instant.ofEpochMilli(f.modified()))),
                    listX + 6, ry + 12, Ui.DIM);
        }
        if (files.isEmpty()) {
            Ui.text(g, Component.translatable("designandbuild.hidden.no_files"), listX + 6, listY + 8, Ui.DIM);
        }
        int bx = x + 8;
        boolean has = selectedFile >= 0 && selectedFile < files.size();
        bx += importButton(g, bx, Component.translatable("designandbuild.hidden.merge"), has, false, mx, my) + 4;
        bx += importButton(g, bx, Component.translatable(confirmReplace ? "designandbuild.hidden.replace_confirm" : "designandbuild.hidden.replace"),
                has, confirmReplace, mx, my) + 4;
        Component open = Component.translatable("designandbuild.hidden.open_folder");
        button(g, bx, buttonsY, open, false, false, mx, my);
        Component back = Component.translatable("designandbuild.hidden.back");
        button(g, x + w - 8 - buttonWidth(back), buttonsY, back, true, false, mx, my);
    }

    private int importButton(GuiGraphics g, int bx, Component label, boolean enabled, boolean danger, int mx, int my) {
        if (enabled) return button(g, bx, buttonsY, label, true, danger, mx, my);
        int bw = buttonWidth(label);
        g.fill(bx, buttonsY, bx + bw, buttonsY + 16, 0xFF1A2028);
        Ui.border(g, bx, buttonsY, bw, 16, 0xFF30363F);
        Ui.text(g, label, bx + 8, buttonsY + 4, Ui.DISABLED);
        return bw;
    }

    // ------------------------------------------------------------------
    // 입력
    // ------------------------------------------------------------------

    private void unhide(Row row) {
        HiddenList.unhide(row.line());
        confirmClear = false;
    }

    private void doExport() {
        try {
            lastExport = HiddenList.export();
            screen.session().toast(Component.translatable("designandbuild.hidden.exported", lastExport.getFileName().toString()));
        } catch (IOException e) {
            screen.session().toast(Component.translatable("designandbuild.hidden.export_failed"));
        }
    }

    private void doImport(boolean replace) {
        if (selectedFile < 0 || selectedFile >= files.size()) return;
        try {
            HiddenList.Parsed p = HiddenList.parse(files.get(selectedFile).file());
            int[] r = HiddenList.apply(p, replace, ClientLibrary.all());
            screen.session().toast(r[2] > 0
                    ? Component.translatable("designandbuild.hidden.imported_skipped", r[0], r[1], r[2])
                    : Component.translatable("designandbuild.hidden.imported", r[0], r[1]));
        } catch (IOException e) {
            screen.session().toast(Component.translatable("designandbuild.hidden.import_failed"));
        }
        importMode = false;
        confirmReplace = false;
        scroll = 0;
    }

    private void openFolder() {
        try {
            java.nio.file.Files.createDirectories(HiddenList.folder());
        } catch (IOException ignored) {
        }
        Util.getPlatform().openFile(HiddenList.folder().toFile());
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        layout();
        if (!Ui.inside(mx, my, x, y, w, h)) return true;
        if (importMode) return importClicked(mx, my);
        if (clickBoxes(mx, my, button)) return true;
        boolean wasConfirm = confirmClear;
        confirmClear = false;
        for (int i = 0; i < 2; i++) {
            if (Ui.inside(mx, my, tabX[i], tabsY, tabW[i], 16)) {
                tab = i;
                scroll = 0;
                return true;
            }
        }
        if (filterW > 0 && Ui.inside(mx, my, filterX, tabsY + 3, filterW, 10)) {
            filterOn = !filterOn;
            scroll = 0;
            return true;
        }
        int shown = Math.min(ROWS, rows.size());
        for (int r = 0; r < shown; r++) {
            int ry = listY + r * ROW;
            int bx = listX + listW - 40 - 3;
            if (Ui.inside(mx, my, bx, ry + 2, 40, ROW - 6)) {
                unhide(rows.get(r + scroll));
                return true;
            }
        }
        if (lastExport != null) {
            Component open = Component.translatable("designandbuild.hidden.open_folder");
            int ow = Ui.font().width(open);
            if (Ui.inside(mx, my, x + w - 10 - ow, noteY - 1, ow, 10)) {
                openFolder();
                return true;
            }
        }
        int bx = x + 8;
        Component clear = Component.translatable(wasConfirm ? "designandbuild.hidden.clear_confirm" : "designandbuild.hidden.clear_all");
        int cw = buttonWidth(clear);
        if (Ui.inside(mx, my, bx, buttonsY, cw, 16)) {
            if (wasConfirm) {
                HiddenList.clear();
            } else if (!HiddenList.lines().isEmpty()) {
                confirmClear = true;
            }
            return true;
        }
        bx += cw + 4;
        Component exp = Component.translatable("designandbuild.hidden.export");
        int ew = buttonWidth(exp);
        if (Ui.inside(mx, my, bx, buttonsY, ew, 16)) {
            if (HiddenList.lines().isEmpty()) {
                screen.session().toast(Component.translatable("designandbuild.hidden.empty"));
            } else {
                doExport();
            }
            return true;
        }
        bx += ew + 4;
        Component imp = Component.translatable("designandbuild.hidden.import");
        if (Ui.inside(mx, my, bx, buttonsY, buttonWidth(imp), 16)) {
            importMode = true;
            files = HiddenList.listFiles();
            selectedFile = files.isEmpty() ? -1 : 0;
            confirmReplace = false;
            scroll = 0;
            return true;
        }
        if (Ui.inside(mx, my, x + w - 8 - buttonWidth(Component.translatable("designandbuild.dialog.close")), buttonsY,
                buttonWidth(Component.translatable("designandbuild.dialog.close")), 16)) {
            close();
            return true;
        }
        return true;
    }

    private boolean importClicked(double mx, double my) {
        boolean wasConfirm = confirmReplace;
        confirmReplace = false;
        int shown = Math.min(ROWS, files.size());
        for (int r = 0; r < shown; r++) {
            if (Ui.inside(mx, my, listX, listY + r * ROW, listW, ROW - 2)) {
                selectedFile = r + scroll;
                return true;
            }
        }
        int bx = x + 8;
        Component merge = Component.translatable("designandbuild.hidden.merge");
        int mw = buttonWidth(merge);
        if (Ui.inside(mx, my, bx, buttonsY, mw, 16)) {
            doImport(false);
            return true;
        }
        bx += mw + 4;
        Component rep = Component.translatable(wasConfirm ? "designandbuild.hidden.replace_confirm" : "designandbuild.hidden.replace");
        int rw = buttonWidth(rep);
        if (Ui.inside(mx, my, bx, buttonsY, rw, 16)) {
            if (selectedFile >= 0 && selectedFile < files.size()) {
                if (wasConfirm) doImport(true);
                else confirmReplace = true;
            }
            return true;
        }
        bx += rw + 4;
        Component open = Component.translatable("designandbuild.hidden.open_folder");
        if (Ui.inside(mx, my, bx, buttonsY, buttonWidth(open), 16)) {
            openFolder();
            return true;
        }
        Component back = Component.translatable("designandbuild.hidden.back");
        if (Ui.inside(mx, my, x + w - 8 - buttonWidth(back), buttonsY, buttonWidth(back), 16)) {
            importMode = false;
            scroll = 0;
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int size = importMode ? files.size() : rows.size();
        scroll = Mth.clamp(scroll - (int) Math.signum(delta), 0, Math.max(0, size - ROWS));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (importMode) {
                importMode = false;
                scroll = 0;
            } else if (focusedBox() != null) {
                focusBox(null);
            } else {
                close();
            }
            return true;
        }
        if (importMode) return true;
        if (focusedBox() == null && key != GLFW.GLFW_KEY_ENTER) focusBox(query);
        keyToBox(key, scan, mods);
        return true;
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (importMode) return true;
        if (focusedBox() == null) focusBox(query);
        charToBox(c, mods);
        return true;
    }
}
