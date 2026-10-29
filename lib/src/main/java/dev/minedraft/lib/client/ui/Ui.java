package dev.minedraft.lib.client.ui;

import dev.minedraft.lib.MinedraftLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * 편집창 시각 언어: 반투명 어두운 패널, 1px 테두리, 단색 픽셀 아이콘.
 * 색은 의미 하나에만 쓴다 (태그·철거·경고·선택·편집자).
 */
public final class Ui {
    private Ui() {}

    public static final ResourceLocation ICONS = MinedraftLib.rl("textures/gui/icons.png");
    public static final int ICON_TEX_W = 256, ICON_TEX_H = 64;

    // ---- 패널 ----
    public static final int BG_TOP = 0xFF1A2230, BG_BOTTOM = 0xFF0D1118;
    public static final int PANEL = 0xD9101418;
    public static final int PANEL_SOLID = 0xF2101418;
    public static final int EDGE = 0xFF39424E;
    public static final int HOVER = 0x28FFFFFF;
    public static final int TEXT = 0xFFE6E9EE;
    public static final int DIM = 0xFF96A0AB;
    public static final int DISABLED = 0xFF5A626C;

    // ---- 의미 색 ----
    public static final int ACCENT = 0xFF2EC4B6;
    public static final int FLOOR = 0xFFC8963E;
    public static final int PATH = 0xFF6E8BA8;
    public static final int STRUCTURE = 0xFF9B6BD6;
    public static final int DEMOLISH = 0xFFE04848;
    public static final int WARN = 0xFFF08C2E;
    public static final int SELECT = 0xFFFFFFFF;
    public static final int GOOD = 0xFF5BC26A;
    public static final int FILL_WATER = 0xFF4FA3E0;
    /** 도면 테마 (상단 바·테이블). */
    public static final int BLUEPRINT = 0xF01E3F66;

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    public static int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0xFFFFFF);
    }

    public static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static void panel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL);
        border(g, x, y, w, h, EDGE);
    }

    public static void solidPanel(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_SOLID);
        border(g, x, y, w, h, EDGE);
    }

    public static void border(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /** 아이콘 (흰 그림을 색으로 칠함). */
    public static void icon(GuiGraphics g, int icon, int x, int y, int color) {
        if (icon < 0) return; // 아이콘 없이 배경만 (위에 따로 그리는 단추)
        float a = ((color >>> 24) & 0xFF) / 255f, r = ((color >> 16) & 0xFF) / 255f, gr = ((color >> 8) & 0xFF) / 255f,
                b = (color & 0xFF) / 255f;
        g.setColor(r, gr, b, a == 0 ? 1 : a);
        g.blit(ICONS, x, y, (icon % 16) * 16, (icon / 16) * 16, 16, 16, ICON_TEX_W, ICON_TEX_H);
        g.setColor(1, 1, 1, 1);
    }

    /** 아이콘 버튼. active = 켜진 상태(강조색 + 밑줄). */
    public static void iconButton(GuiGraphics g, int x, int y, int w, int h, int icon, boolean active, boolean hover,
                                  boolean enabled) {
        if (hover && enabled) g.fill(x, y, x + w, y + h, HOVER);
        if (active) {
            g.fill(x, y, x + w, y + h, 0x302EC4B6);
            g.fill(x + 2, y + h - 2, x + w - 2, y + h - 1, ACCENT);
        }
        int color = !enabled ? DISABLED : active ? ACCENT : TEXT;
        icon(g, icon, x + (w - 16) / 2, y + (h - 16) / 2, color);
    }

    public static void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font(), s, x, y, color, false);
    }

    public static void text(GuiGraphics g, Component s, int x, int y, int color) {
        g.drawString(font(), s, x, y, color, false);
    }

    public static void centered(GuiGraphics g, Component s, int cx, int y, int color) {
        g.drawString(font(), s, cx - font().width(s) / 2, y, color, false);
    }

    public static void separator(GuiGraphics g, int x, int y, int h) {
        g.fill(x, y + 3, x + 1, y + h - 3, EDGE);
    }

    /** 글자가 너비를 넘으면 … 으로 줄인다. */
    public static String clip(String s, int width) {
        Font f = font();
        if (f.width(s) <= width) return s;
        String dots = "…";
        int w = width - f.width(dots);
        return f.plainSubstrByWidth(s, Math.max(0, w)) + dots;
    }

    public static List<FormattedCharSequence> wrap(Component text, int width) {
        return font().split(text, width);
    }

    public static Component dirName(Direction d) {
        return Component.translatable("designandbuild.dir." + d.getName());
    }

    /** 방위 8방향 이름 (북·북동…). dx·dz 는 월드 기준. */
    public static Component compassName(double dx, double dz) {
        double angle = Math.toDegrees(Math.atan2(dx, -dz));
        int idx = (int) Math.floor(((angle + 360 + 22.5) % 360) / 45.0);
        String[] keys = {"n", "ne", "e", "se", "s", "sw", "w", "nw"};
        return Component.translatable("designandbuild.compass." + keys[idx]);
    }
}
