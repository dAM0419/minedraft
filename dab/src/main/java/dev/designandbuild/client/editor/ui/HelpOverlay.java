package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.editor.EditorScreen;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** H: 단축키 표. 뷰포트 오른쪽 아래에 반투명으로 뜨고, H·Esc 또는 표를 눌러 닫는다. */
public final class HelpOverlay {
    private HelpOverlay() {}

    /** 묶음 제목 키, 그 아래 (키, 설명 키) 쌍. */
    private static final String[][][] GROUPS = {
            {{"designandbuild.help.tools"}, {"V", "designandbuild.tool.select"}, {"B", "designandbuild.tool.direct"}, {"P", "designandbuild.tool.place"},
                    {"1 2 3 4", "designandbuild.help.ops"}},
            {{"designandbuild.help.view"}, {"designandbuild.key.rmb_drag", "designandbuild.help.pan"}, {"@move", "designandbuild.help.pan_keys"}, {"designandbuild.key.wheel", "designandbuild.help.zoom"},
                    {"Q / E", "designandbuild.help.rotate_view"}, {"L", "designandbuild.layer.toggle"}, {"Shift+L", "designandbuild.help.cut_axis"},
                    {"Ctrl+designandbuild.key.lmb", "designandbuild.help.cut_at"}, {"Shift+designandbuild.key.wheel", "designandbuild.help.layer_move"},
                    {"X", "designandbuild.layer.bubble"}, {"T", "designandbuild.help.tag_view"}, {"G", "designandbuild.help.outlines"}, {"Tab", "designandbuild.help.panel"}},
            {{"designandbuild.help.direct"}, {"designandbuild.key.lmb", "designandbuild.help.apply"}, {"designandbuild.key.lmb_drag", "designandbuild.help.paint"},
                    {"Alt+designandbuild.key.wheel", "designandbuild.help.size"}, {"Ctrl+designandbuild.key.wheel", "designandbuild.help.palette"},
                    {"designandbuild.key.mmb", "designandbuild.help.eyedropper"}, {"Shift+designandbuild.key.mmb", "designandbuild.help.eyedropper_palette"}},
            {{"designandbuild.help.structures"}, {"Alt+designandbuild.key.lmb", "designandbuild.help.alt_select"}, {"R", "designandbuild.help.rotate"}, {"F", "designandbuild.help.mirror"},
                    {"PgUp / PgDn", "designandbuild.help.updown"}, {"Del", "designandbuild.select.delete"}, {"designandbuild.key.rmb", "designandbuild.help.cancel_place"}},
            {{"designandbuild.help.general"}, {"Ctrl+Z", "designandbuild.undo"}, {"Ctrl+Y", "designandbuild.redo"}, {"H", "designandbuild.help.toggle"},
                    {"Esc", "designandbuild.help.escape"}},
    };

    private static String keyText(String raw) {
        if (raw.equals("@move")) {
            // 게임 설정의 이동 키 (기본 W A S D)
            var o = net.minecraft.client.Minecraft.getInstance().options;
            return o.keyUp.getTranslatedKeyMessage().getString() + " " + o.keyLeft.getTranslatedKeyMessage().getString() + " "
                    + o.keyDown.getTranslatedKeyMessage().getString() + " " + o.keyRight.getTranslatedKeyMessage().getString();
        }
        StringBuilder b = new StringBuilder();
        for (String part : raw.split("\\+")) {
            if (b.length() > 0) b.append('+');
            b.append(part.startsWith("designandbuild.") ? Component.translatable(part).getString() : part);
        }
        return b.toString();
    }

    /** 표가 차지하는 영역 (x, y, w, h). */
    public static int[] bounds(EditorScreen screen) {
        int[] r = new int[4];
        compute(screen, r, null);
        return r;
    }

    public static void render(EditorScreen screen, GuiGraphics g) {
        compute(screen, new int[4], g);
    }

    private static void compute(EditorScreen screen, int[] out, GuiGraphics g) {
        int colW = 170, gap = 10;
        int cols = Math.max(1, Math.min(2, (screen.vpW - 60) / (colW + gap)));
        // 묶음을 열에 나눠 담는다 (줄 수 균형)
        int[] colHeight = new int[cols];
        int[] colOf = new int[GROUPS.length];
        for (int i = 0; i < GROUPS.length; i++) {
            int best = 0;
            for (int c = 1; c < cols; c++) if (colHeight[c] < colHeight[best]) best = c;
            colOf[i] = best;
            colHeight[best] += 14 + (GROUPS[i].length - 1) * 11 + 6;
        }
        int maxH = 0;
        for (int hgt : colHeight) maxH = Math.max(maxH, hgt);
        int w = cols * colW + (cols - 1) * gap + 20;
        int h = maxH + 30;
        // 뷰포트 오른쪽 아래 (층 슬라이더와 팔레트를 피해서)
        int x = Math.max(screen.vpX + 4, screen.vpX + screen.vpW - w - 36);
        int y = Math.max(screen.vpY + 4, screen.paletteTop() - h - 6);
        out[0] = x;
        out[1] = y;
        out[2] = w;
        out[3] = h;
        if (g == null) return;
        g.pose().pushPose();
        g.pose().translate(0, 0, 250);
        g.fill(x, y, x + w, y + h, 0xEE0D1117);
        Ui.border(g, x, y, w, h, Ui.EDGE);
        Ui.text(g, Component.translatable("designandbuild.help.title"), x + 10, y + 8, Ui.TEXT);
        Component close = Component.translatable("designandbuild.help.close_hint");
        Ui.text(g, close, x + w - 10 - Ui.font().width(close), y + 8, Ui.DISABLED);
        int[] cy = new int[cols];
        for (int c = 0; c < cols; c++) cy[c] = y + 24;
        for (int i = 0; i < GROUPS.length; i++) {
            int c = colOf[i];
            int gx = x + 10 + c * (colW + gap);
            String[][] grp = GROUPS[i];
            Ui.text(g, Component.translatable(grp[0][0]), gx, cy[c], Ui.ACCENT);
            cy[c] += 14;
            for (int k = 1; k < grp.length; k++) {
                String key = keyText(grp[k][0]);
                int kw = Ui.font().width(key) + 6;
                g.fill(gx, cy[c] - 1, gx + kw, cy[c] + 9, 0xFF262E38);
                Ui.text(g, key, gx + 3, cy[c], Ui.TEXT);
                Ui.text(g, Ui.clip(Component.translatable(grp[k][1]).getString(), colW - kw - 8), gx + kw + 5, cy[c], Ui.DIM);
                cy[c] += 11;
            }
            cy[c] += 6;
        }
        g.pose().popPose();
    }
}
