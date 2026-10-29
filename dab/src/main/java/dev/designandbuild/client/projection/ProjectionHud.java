package dev.designandbuild.client.projection;

import com.mojang.math.Axis;
import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.Keys;
import dev.designandbuild.client.ui.Icons;
import dev.designandbuild.client.ui.Ui;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.StateRules;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;

import java.util.List;

/**
 * 투영 HUD.
 * <pre>
 * 왼쪽 위   진행: 철거 ✓ › 바닥 ✓ › 길 64% › 구조물 0/7 (아래 줄에 현재 층·구조물)
 * 오른쪽 위 재료: 접힘 = 현재 층 상위 5개, 펼침 = 현재 층 / 현재 단계 전체
 * 위 가운데 현장 안내: 설계 영역 밖이면 방향 화살표와 거리
 * </pre>
 */
public final class ProjectionHud {
    private ProjectionHud() {}

    private static final String SEP = " › ";

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui || mc.player == null) return;
        Projection p = ProjectionManager.active();
        if (p == null) return;
        LocalPlayer player = mc.player;
        BlueprintModel m = p.model;
        if (!m.hasChunk(player.getBlockX() >> 4, player.getBlockZ() >> 4)) {
            renderSiteGuide(g, mc, m, player);
            return;
        }
        renderProgress(g, mc, p);
        renderMaterials(g, mc, p, player);
        renderHammerHint(g, mc);
    }

    /** 망치로 겨눈 칸을 못 놓으면 조준점 아래에 이유. */
    private static void renderHammerHint(GuiGraphics g, Minecraft mc) {
        Component hint = ProjectionManager.hammerHint;
        if (hint == null || mc.screen != null) return;
        Font font = mc.font;
        java.util.List<net.minecraft.util.FormattedCharSequence> lines = font.split(hint, Math.min(320, g.guiWidth() - 20));
        int y = g.guiHeight() / 2 + 12;
        for (net.minecraft.util.FormattedCharSequence line : lines) {
            int w = font.width(line);
            int x = (g.guiWidth() - w) / 2;
            g.fill(x - 3, y - 2, x + w + 3, y + 10, 0xA0000000);
            g.drawString(font, line, x, y, 0xFFFF7070, false);
            y += 12;
        }
    }

    // ------------------------------------------------------------------
    // 현장 안내
    // ------------------------------------------------------------------

    private static void renderSiteGuide(GuiGraphics g, Minecraft mc, BlueprintModel m, LocalPlayer player) {
        double px = player.getX(), pz = player.getZ();
        double best = Double.MAX_VALUE, bx = px, bz = pz;
        LongIterator it = m.chunks().iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            double x0 = ChunkPos.getX(c) << 4, z0 = ChunkPos.getZ(c) << 4;
            double nx = Mth.clamp(px, x0, x0 + 16), nz = Mth.clamp(pz, z0, z0 + 16);
            double d = (nx - px) * (nx - px) + (nz - pz) * (nz - pz);
            if (d < best) {
                best = d;
                bx = nx;
                bz = nz;
            }
        }
        double dx = bx - px, dz = bz - pz;
        int dist = (int) Math.round(Math.sqrt(best));
        Component text = Component.translatable("designandbuild.site.guide", Ui.compassName(dx, dz), dist);
        Font font = mc.font;
        int w = font.width(text) + 26;
        int x = (g.guiWidth() - w) / 2, y = 4;
        g.fill(x, y, x + w, y + 18, 0x90000000);
        Ui.text(g, text, x + 22, y + 5, Ui.TEXT);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float rel = Mth.wrapDegrees(targetYaw - player.getYRot());
        g.pose().pushPose();
        g.pose().rotateAround(Axis.ZP.rotationDegrees(rel), x + 11, y + 9, 0);
        Ui.icon(g, Icons.ARROW_UP, x + 3, y + 1, Ui.ACCENT);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------
    // 진행 표시
    // ------------------------------------------------------------------

    private static void renderProgress(GuiGraphics g, Minecraft mc, Projection p) {
        ClientConfig.Data cfg = ClientConfig.get();
        int x = 4 + cfg.progressOffsetX, y = 4 + cfg.progressOffsetY;
        Font font = mc.font;
        int cur = p.currentStage();
        if (!p.isReady()) {
            Component t = Component.translatable("designandbuild.projection.checking");
            g.fill(x - 2, y - 2, x + font.width(t) + 2, y + 10, 0x90000000);
            Ui.text(g, t, x, y, Ui.DIM);
            return;
        }
        // 1줄: 단계들
        MutableComponent[] parts = new MutableComponent[4];
        int[] colors = new int[4];
        for (int k = 0; k < 4; k++) {
            boolean structures = k == 3;
            boolean isCur = structures ? cur >= Projection.STAGE_STRUCTURES && cur < p.stageCount() : cur == k;
            boolean done;
            String value;
            if (structures) {
                int count = p.stageCount() - Projection.STAGE_STRUCTURES;
                int ok = p.completedStructures();
                done = count == 0 || ok == count;
                value = done ? "✓" : ok + "/" + count;
            } else if (k == Projection.STAGE_DEMOLISH) {
                done = p.isComplete(k);
                value = done ? (p.unknown(k) > 0 ? "✓?" : "✓") : String.valueOf(p.remaining(k));
            } else {
                done = p.isComplete(k);
                int total = p.total(k);
                value = done ? (p.unknown(k) > 0 ? "✓?" : "✓") : (total == 0 ? "✓" : p.percent(k) + "%");
            }
            String key = switch (k) {
                case 0 -> "designandbuild.stage.demolish";
                case 1 -> "designandbuild.stage.floor";
                case 2 -> "designandbuild.stage.path";
                default -> "designandbuild.stage.structures";
            };
            parts[k] = Component.translatable(key).append(" " + value);
            colors[k] = isCur ? Ui.ACCENT : done ? Ui.DIM : Ui.TEXT;
        }
        int w = 0;
        for (int k = 0; k < 4; k++) w += font.width(parts[k]) + (k < 3 ? font.width(SEP) : 0);
        Component line2 = secondLine(p);
        // 시야 밖 칸 안내 (현재 단계)
        if (cur < p.stageCount() && p.unknown(cur) > 0 && line2 != null) {
            line2 = Component.empty().append(line2).append(" · ")
                    .append(Component.translatable("designandbuild.projection.unknown", p.unknown(cur)));
        }
        int w2 = line2 == null ? 0 : font.width(line2);
        int h = line2 == null ? 12 : 23;
        g.fill(x - 3, y - 3, x + Math.max(w, w2) + 3, y + h - 1, 0x90000000);
        int cx = x;
        for (int k = 0; k < 4; k++) {
            g.drawString(font, parts[k], cx, y, colors[k], false);
            cx += font.width(parts[k]);
            if (k < 3) {
                g.drawString(font, SEP, cx, y, Ui.DISABLED, false);
                cx += font.width(SEP);
            }
        }
        if (line2 != null) g.drawString(font, line2, x, y + 11, Ui.DIM, false);
    }

    private static Component secondLine(Projection p) {
        int cur = p.currentStage();
        if (cur >= p.stageCount()) return Component.translatable("designandbuild.projection.all_done_short");
        int layer = p.currentLayer();
        Component mode = Component.translatable(p.viewModeKey());
        if (cur >= Projection.STAGE_STRUCTURES) {
            int[] floors = p.structureFloors();
            int pct = p.percent(cur);
            if (floors == null || layer == Projection.NO_LAYER) {
                return Component.translatable("designandbuild.projection.structure_line_nolayer", p.structureName(cur), pct);
            }
            int floor = layer - floors[0] + 1;
            return Component.translatable("designandbuild.projection.structure_line", p.structureName(cur), floor, floors[1], pct)
                    .append(" · ").append(mode);
        }
        if (layer == Projection.NO_LAYER) return Component.translatable("designandbuild.projection.layer_none");
        return Component.translatable("designandbuild.projection.layer_line", layer, p.layerRemaining(cur, layer)).append(" · ").append(mode);
    }

    // ------------------------------------------------------------------
    // 재료 목록
    // ------------------------------------------------------------------

    private static void renderMaterials(GuiGraphics g, Minecraft mc, Projection p, LocalPlayer player) {
        int cur = p.currentStage();
        if (cur <= Projection.STAGE_DEMOLISH || cur >= p.stageCount()) return;
        ClientConfig.Data cfg = ClientConfig.get();
        int mode = ProjectionManager.materialsMode;
        List<Projection.Material> list = mode == 2 ? ProjectionManager.stageMaterials() : ProjectionManager.layerMaterials();
        Font font = mc.font;
        int right = g.guiWidth() - 4 + cfg.materialsOffsetX;
        int y = 4 + cfg.materialsOffsetY;
        // 상태 효과 아이콘이 오른쪽 위를 쓰면 그 아래로
        if (!player.getActiveEffects().isEmpty()) y += 52;
        if (mode == 0) {
            if (list.isEmpty()) return;
            int rows = Math.min(5, list.size());
            int w = 0;
            for (int i = 0; i < rows; i++) {
                Projection.Material m = list.get(i);
                String c = m.isTool() ? Component.translatable("designandbuild.materials.uses", m.count()).getString() : String.valueOf(m.count());
                w = Math.max(w, font.width(c));
            }
            w += 22 + (hasHint(list, rows) ? 12 : 0);
            int h = rows * 18 + (list.size() > rows ? 10 : 0) + 4;
            int x = right - w;
            g.fill(x - 2, y - 2, right + 2, y + h - 2, 0x90000000);
            for (int i = 0; i < rows; i++) drawRow(g, font, player, list.get(i), x, y + i * 18, w, false);
            if (list.size() > rows) {
                String more = "+" + (list.size() - rows) + " · " + Keys.MATERIALS.getTranslatedKeyMessage().getString();
                Ui.text(g, more, right - font.width(more), y + rows * 18, Ui.DISABLED);
            }
            return;
        }
        // 펼침: 탭 두 개 + 이름까지
        int rows = Math.min(14, list.size());
        int w = 170;
        int x = right - w;
        int h = 16 + Math.max(1, rows) * 18 + (list.size() > rows ? 10 : 0) + 4;
        g.fill(x - 2, y - 2, right + 2, y + h - 2, 0xB0000000);
        Component t1 = Component.translatable("designandbuild.materials.layer");
        Component t2 = Component.translatable("designandbuild.materials.stage");
        Ui.text(g, t1, x + 2, y + 2, mode == 1 ? Ui.ACCENT : Ui.DIM);
        if (mode == 1) g.fill(x + 2, y + 11, x + 2 + font.width(t1), y + 12, Ui.ACCENT);
        int t2x = x + 10 + font.width(t1);
        Ui.text(g, t2, t2x, y + 2, mode == 2 ? Ui.ACCENT : Ui.DIM);
        if (mode == 2) g.fill(t2x, y + 11, t2x + font.width(t2), y + 12, Ui.ACCENT);
        String key = Keys.MATERIALS.getTranslatedKeyMessage().getString();
        Ui.text(g, key, right - font.width(key), y + 2, Ui.DISABLED);
        int ry = y + 16;
        if (list.isEmpty()) {
            Ui.text(g, Component.translatable("designandbuild.materials.none"), x + 2, ry + 4, Ui.DIM);
            return;
        }
        for (int i = 0; i < rows; i++) drawRow(g, font, player, list.get(i), x, ry + i * 18, w, true);
        if (list.size() > rows) Ui.text(g, "+" + (list.size() - rows), x + 2, ry + rows * 18, Ui.DISABLED);
    }

    private static boolean hasHint(List<Projection.Material> list, int rows) {
        for (int i = 0; i < rows; i++) if (list.get(i).hint() != StateRules.HINT_NONE) return true;
        return false;
    }

    private static void drawRow(GuiGraphics g, Font font, LocalPlayer player, Projection.Material mat, int x, int y, int w,
                                boolean withName) {
        if (mat.isTool()) {
            drawToolRow(g, font, player, mat, x, y, w, withName);
            return;
        }
        ItemStack stack = new ItemStack(mat.item());
        int have = player.getInventory().countItem(mat.item());
        boolean enough = have >= mat.count();
        int right = x + w;
        g.renderItem(stack, x, y);
        String count = String.valueOf(mat.count());
        int color = enough ? Ui.GOOD : Ui.TEXT;
        if (withName) {
            String name = Ui.clip(stack.getHoverName().getString(), w - 18 - 40 - (mat.hint() != 0 ? 12 : 0));
            Ui.text(g, name, x + 18, y + 4, Ui.DIM);
            String have2 = have + "/" + count;
            Ui.text(g, have2, right - font.width(have2), y + 4, color);
        } else {
            Ui.text(g, count, right - font.width(count), y + 4, color);
        }
        if (mat.hint() != StateRules.HINT_NONE) {
            int icon = switch (mat.hint()) {
                case StateRules.HINT_SHOVEL -> Icons.SHOVEL;
                case StateRules.HINT_HOE -> Icons.HOE;
                default -> Icons.SILK;
            };
            g.pose().pushPose();
            g.pose().translate(x + 13, y + 8, 200);
            g.pose().scale(0.6F, 0.6F, 1);
            Ui.icon(g, icon, 0, 0, Ui.WARN);
            g.pose().popPose();
        }
    }

    /** 가공 줄: 도구 그림 · "도끼로 가공" · 사용 횟수. 그 도구(꿀벌집은 개수)를 가지고 있으면 초록. */
    private static void drawToolRow(GuiGraphics g, Font font, LocalPlayer player, Projection.Material mat, int x, int y, int w,
                                    boolean withName) {
        boolean ok;
        if (mat.tool() == StateRules.TOOL_HONEYCOMB) {
            ok = player.getInventory().countItem(mat.item()) >= mat.count();
        } else {
            ok = false;
            var ability = StateRules.abilityOf(mat.tool());
            var inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize() && !ok; i++) {
                ItemStack st = inv.getItem(i);
                if (!st.isEmpty() && ability != null && st.canPerformAction(ability)) ok = true;
            }
        }
        int right = x + w;
        g.renderItem(new ItemStack(mat.item()), x, y);
        String count = Component.translatable("designandbuild.materials.uses", mat.count()).getString();
        int color = ok ? Ui.GOOD : Ui.TEXT;
        if (withName) {
            String name = Ui.clip(Component.translatable("designandbuild.materials.tool." + mat.tool()).getString(), w - 18 - 40);
            Ui.text(g, name, x + 18, y + 4, Ui.ACCENT);
        }
        Ui.text(g, count, right - font.width(count), y + 4, color);
    }
}
