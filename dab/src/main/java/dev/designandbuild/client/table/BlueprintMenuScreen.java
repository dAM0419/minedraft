package dev.designandbuild.client.table;

import dev.designandbuild.Registration;
import dev.designandbuild.client.ClientConfig;
import dev.designandbuild.client.Keys;
import dev.designandbuild.client.projection.Projection;
import dev.designandbuild.client.projection.ProjectionManager;
import dev.designandbuild.client.ui.Icons;
import dev.designandbuild.client.ui.Ui;
import dev.designandbuild.item.BlueprintRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * 도면 아이템 우클릭 메뉴: 층 표시 방식(이 층만 / 이 층 이하), 투영 켜기·끄기,
 * 단계 고르기 (이 클라이언트에서만, 앞뒤 어디로든), 키 안내.
 * 명령어 권한 2 이상이면 단계 줄마다 즉시 반영 단추와, 마지막 즉시 반영 되돌리기 줄이 붙는다.
 */
public final class BlueprintMenuScreen extends Screen {
    private static final int W = 240, ROW = 13, MAX_ROWS = 8, LIST_Y = 100;
    private static final int BG = 0xF21E3F66, LINE = 0xCCE8F0FF;

    private final BlueprintRef ref;
    private int H = 132;
    private int scroll;
    // 마지막으로 그린 배치 (누르기 판정용)
    private int listRows, autoX, autoW;
    /** 즉시 반영 확인 중인 단계 (-1 = 없음). */
    private int confirmStage = -1;
    private static final int INSTANT_ROW = 22;

    private BlueprintMenuScreen(BlueprintRef ref) {
        super(Component.translatable("designandbuild.menu.title"));
        this.ref = ref;
    }

    public static void open(ItemStack stack) {
        BlueprintRef ref = stack.get(Registration.BLUEPRINT_REF.get());
        if (ref == null) return;
        Minecraft.getInstance().setScreen(new BlueprintMenuScreen(ref));
    }

    @Override
    protected void init() {
        super.init();
        java.util.UUID id = ref.id();
        dev.designandbuild.net.Net.toServer(dev.designandbuild.net.Msg.C_INSTANT_QUERY, b -> b.writeUUID(id));
    }

    @Nullable
    private ProjectionManager.InstantState instant() {
        ProjectionManager.InstantState st = ProjectionManager.INSTANT.get(ref.id());
        return st != null && st.allowed() ? st : null;
    }

    /** 즉시 반영 줄(확인·되돌리기·진행 중)이 필요한가. */
    private boolean instantRow() {
        ProjectionManager.InstantState st = instant();
        return st != null && projection() != null && (confirmStage >= 0 || st.hasUndo() || st.running());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private String id() {
        return ref.id().toString();
    }

    /** 층 표시 방식: 0 = 이 층만, 1 = 이 층까지, 2 = 단계 전체. */
    private int viewMode() {
        if (ClientConfig.get().layerAll.contains(id())) return 2;
        return Boolean.TRUE.equals(ClientConfig.get().layerBelow.get(id())) ? 1 : 0;
    }

    private static Component[] viewLabels() {
        return new Component[]{
                Component.translatable("designandbuild.menu.view_only"),
                Component.translatable("designandbuild.menu.view_below"),
                Component.translatable("designandbuild.menu.view_all")};
    }

    private boolean projectionOn() {
        return !ClientConfig.get().projectionOff.contains(id());
    }

    private int x() {
        return (width - W) / 2;
    }

    private int y() {
        return (height - H) / 2;
    }

    /** 이 도면의 투영 (들고 있는 도면이 이것이고 불러왔을 때). */
    @Nullable
    private Projection projection() {
        Projection p = ProjectionManager.active();
        return p != null && p.model != null && p.model.id.equals(ref.id()) && p.isReady() ? p : null;
    }

    private List<FormattedCharSequence> keyLines() {
        String keys = Component.translatable("designandbuild.menu.keys",
                Keys.LAYER_UP.getTranslatedKeyMessage().getString() + "/" + Keys.LAYER_DOWN.getTranslatedKeyMessage().getString(),
                Keys.STAGE_PREV.getTranslatedKeyMessage().getString() + "/" + Keys.STAGE_NEXT.getTranslatedKeyMessage().getString(),
                Keys.MATERIALS.getTranslatedKeyMessage().getString()).getString();
        return font.split(Component.literal(keys), W - 20);
    }

    private void layout() {
        Projection p = projection();
        int count = p == null ? 0 : p.stageCount();
        listRows = p == null ? 1 : Math.min(MAX_ROWS, count);
        scroll = Math.max(0, Math.min(scroll, count - listRows));
        if (p == null || confirmStage >= count) confirmStage = -1;
        H = LIST_Y + listRows * ROW + 4 + (instantRow() ? INSTANT_ROW : 0) + 8 + keyLines().size() * 10 + 6 + 22;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        layout();
        int x = x(), y = y();
        g.fill(x, y, x + W, y + H, BG);
        Ui.border(g, x, y, W, H, LINE);
        Ui.icon(g, Icons.BLUEPRINT, x + 6, y + 4, 0xFFFFFFFF);
        g.drawString(font, Ui.clip(ref.name(), W - 40), x + 26, y + 8, 0xFFFFFFFF, false);
        g.drawString(font, Component.translatable("designandbuild.table.base_chunk", ref.baseX(), ref.baseZ()), x + 26, y + 19, 0xAAE8F0FF, false);

        int ry = y + 36;
        // 층 표시
        g.drawString(font, Component.translatable("designandbuild.menu.layer_mode"), x + 10, ry + 4, 0xFFE8F0FF, false);
        Component[] vl = viewLabels();
        int vx = x + 100;
        for (int k = 0; k < vl.length; k++) {
            int w = font.width(vl[k]) + 12;
            seg(g, vx, ry, w, vl[k], viewMode() == k, mx, my);
            vx += w;
        }
        ry += 22;
        g.drawString(font, Component.translatable("designandbuild.menu.projection"), x + 10, ry + 4, 0xFFE8F0FF, false);
        segmented(g, x + 100, ry, Component.translatable("designandbuild.menu.on"), projectionOn(),
                Component.translatable("designandbuild.menu.off"), !projectionOn(), mx, my);
        ry += 26;
        renderStages(g, x, y, mx, my);
        ry = y + LIST_Y + listRows * ROW + 4;
        if (instantRow()) {
            renderInstantRow(g, x, ry + 4, mx, my);
            ry += INSTANT_ROW;
        }
        ry += 8;
        // 키 안내
        for (var line : keyLines()) {
            g.drawString(font, line, x + 10, ry, 0x99E8F0FF, false);
            ry += 10;
        }
        Component close = Component.translatable("designandbuild.dialog.close");
        int bw = font.width(close) + 20;
        int bx = x + W - 10 - bw, by = y + H - 22;
        boolean hover = Ui.inside(mx, my, bx, by, bw, 16);
        g.fill(bx, by, bx + bw, by + 16, hover ? 0xFF2F6FA8 : 0xFF245A8C);
        Ui.border(g, bx, by, bw, 16, 0xFFFFFFFF);
        g.drawString(font, close, bx + 10, by + 4, 0xFFFFFFFF, false);
    }

    /** 단계 목록: 이름, 진행(✓ / % / 남은 수), 지금 보는 단계 강조. 누르면 그 단계로. */
    private void renderStages(GuiGraphics g, int x, int y, int mx, int my) {
        g.drawString(font, Component.translatable("designandbuild.menu.stage"), x + 10, y + LIST_Y - 12, 0xFFE8F0FF, false);
        Projection p = projection();
        int lx = x + 10, ly = y + LIST_Y, lw = W - 20, lh = listRows * ROW + 4;
        g.fill(lx, ly, lx + lw, ly + lh, 0x40000000);
        Ui.border(g, lx, ly, lw, lh, 0x60E8F0FF);
        if (p == null) {
            autoW = 0;
            Component msg = Component.translatable(projectionOn() ? "designandbuild.menu.stage_unavailable" : "designandbuild.menu.stage_off");
            g.drawString(font, Ui.clip(msg.getString(), lw - 8), lx + 4, ly + 4, 0x99E8F0FF, false);
            return;
        }
        // 첫 미완료 단계로
        Component auto = Component.translatable("designandbuild.menu.stage_auto");
        autoW = font.width(auto) + 10;
        autoX = x + W - 10 - autoW;
        boolean autoHover = Ui.inside(mx, my, autoX, y + LIST_Y - 15, autoW, 12);
        g.fill(autoX, y + LIST_Y - 15, autoX + autoW, y + LIST_Y - 3, autoHover ? 0x40FFFFFF : 0x20FFFFFF);
        Ui.border(g, autoX, y + LIST_Y - 15, autoW, 12, 0x60E8F0FF);
        g.drawString(font, auto, autoX + 5, y + LIST_Y - 13, 0xFFE8F0FF, false);

        int cur = p.currentStage();
        for (int i = 0; i < listRows; i++) {
            int st = scroll + i;
            if (st >= p.stageCount()) break;
            int ry = ly + 2 + i * ROW;
            boolean hover = Ui.inside(mx, my, lx + 1, ry, lw - 2, ROW);
            if (st == cur) g.fill(lx + 1, ry, lx + lw - 1, ry + ROW, 0xFF2F6FA8);
            else if (hover) g.fill(lx + 1, ry, lx + lw - 1, ry + ROW, 0x30FFFFFF);
            String prefix = st < Projection.STAGE_STRUCTURES ? "" : (st - Projection.STAGE_STRUCTURES + 1) + ". ";
            Component progress = progress(p, st);
            int pw = font.width(progress);
            int boltW = 0;
            if (instant() != null && (!p.isComplete(st) || p.unknown(st) > 0)) {
                // 즉시 반영 단추
                boltW = 14;
                int bx = lx + lw - 6 - pw - boltW, by = ry + 1;
                boolean bh = Ui.inside(mx, my, bx, by, 12, ROW - 2);
                g.fill(bx, by, bx + 12, by + ROW - 2, bh || confirmStage == st ? 0xFFD9962F : 0x40FFFFFF);
                g.drawString(font, "⚡", bx + 3, ry + 3, 0xFFFFFFFF, false);
                if (bh) setTooltipForNextRenderPass(Component.translatable("designandbuild.instant.button"));
            }
            String name = Ui.clip(prefix + ProjectionManager.stageName(p, st).getString(), lw - 16 - pw - boltW);
            boolean done = p.isComplete(st);
            g.drawString(font, (st == cur ? "▶ " : "  ") + name, lx + 3, ry + 3, done && st != cur ? 0x99E8F0FF : 0xFFFFFFFF, false);
            g.drawString(font, progress, lx + lw - 4 - pw, ry + 3, done ? 0xFF7BE08A : 0xFFE8F0FF, false);
        }
        // 스크롤 막대
        if (p.stageCount() > listRows) {
            int trackH = lh - 4;
            int barH = Math.max(6, trackH * listRows / p.stageCount());
            int barY = ly + 2 + (trackH - barH) * scroll / Math.max(1, p.stageCount() - listRows);
            g.fill(lx + lw - 3, barY, lx + lw - 1, barY + barH, 0x80E8F0FF);
        }
    }

    /** 확인 줄 / 되돌리기 줄 / 진행 중 표시. */
    private void renderInstantRow(GuiGraphics g, int x, int ry, int mx, int my) {
        Projection p = projection();
        ProjectionManager.InstantState st = instant();
        if (p == null || st == null) return;
        int lx = x + 10, lw = W - 20;
        if (st.running()) {
            g.drawString(font, Component.translatable("designandbuild.instant.running"), lx, ry + 4, 0xFFFFD27A, false);
            return;
        }
        if (confirmStage >= 0) {
            // 시야 밖 칸은 아직 모르므로 설치 쪽에 함께 센다 (실제 수는 서버가 월드를 보고 정한다)
            int place = confirmStage == Projection.STAGE_DEMOLISH ? 0 : p.remaining(confirmStage) + p.unknown(confirmStage);
            int demolish = confirmStage == Projection.STAGE_DEMOLISH ? p.remaining(confirmStage) : 0;
            boolean large = place + demolish > dev.designandbuild.core.Stages.LARGE_APPLY;
            Component q = Component.translatable("designandbuild.instant.confirm", ProjectionManager.stageName(p, confirmStage), place, demolish);
            Component ok = Component.translatable("designandbuild.instant.apply"), no = Component.translatable("designandbuild.dialog.cancel");
            int okW = font.width(ok) + 12, noW = font.width(no) + 12;
            int okX = lx + lw - okW, noX = okX - 4 - noW;
            g.drawString(font, Ui.clip(q.getString(), noX - lx - 4), lx, ry + 4, large ? 0xFFFFB347 : 0xFFE8F0FF, false);
            if (large && Ui.inside(mx, my, lx, ry, noX - lx, 16)) {
                setTooltipForNextRenderPass(Component.translatable("designandbuild.instant.large"));
            }
            button(g, noX, ry, noW, no, false, mx, my);
            button(g, okX, ry, okW, ok, true, mx, my);
            return;
        }
        if (st.hasUndo()) {
            Component label = Component.translatable("designandbuild.instant.undo", undoName(p, st));
            int w = Math.min(lw, font.width(label) + 12);
            button(g, lx, ry, w, Component.literal(Ui.clip(label.getString(), w - 12)), false, mx, my);
        }
    }

    private Component undoName(Projection p, ProjectionManager.InstantState st) {
        if (st.undoKind() == dev.designandbuild.core.Stages.STRUCTURE) {
            var s = p.model.structure(st.undoStructure());
            return Component.literal(s == null ? "?" : s.name);
        }
        return ProjectionManager.stageName(p, st.undoKind());
    }

    private void button(GuiGraphics g, int x, int y, int w, Component label, boolean primary, int mx, int my) {
        boolean hover = Ui.inside(mx, my, x, y, w, 16);
        g.fill(x, y, x + w, y + 16, primary ? (hover ? 0xFFE0A440 : 0xFFD9962F) : (hover ? 0x40FFFFFF : 0x20FFFFFF));
        Ui.border(g, x, y, w, 16, primary ? 0xFFFFFFFF : 0x60E8F0FF);
        g.drawString(font, label, x + 6, y + 4, 0xFFFFFFFF, false);
    }

    private static Component progress(Projection p, int s) {
        if (p.isComplete(s)) {
            int u = p.unknown(s);
            return u > 0 ? Component.translatable("designandbuild.menu.stage_unknown", u) : Component.literal("✓");
        }
        if (s == Projection.STAGE_DEMOLISH) return Component.translatable("designandbuild.menu.stage_left", p.remaining(s));
        if (p.total(s) <= 0) return Component.literal("✓");
        return Component.literal(p.percent(s) + "%");
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        Projection p = projection();
        if (p != null && p.stageCount() > listRows) {
            scroll = Math.max(0, Math.min(p.stageCount() - listRows, scroll - (int) Math.signum(dy)));
        }
        return true;
    }

    private void segmented(GuiGraphics g, int x, int y, Component a, boolean aOn, Component b, boolean bOn, int mx, int my) {
        int wa = font.width(a) + 12, wb = font.width(b) + 12;
        seg(g, x, y, wa, a, aOn, mx, my);
        seg(g, x + wa, y, wb, b, bOn, mx, my);
    }

    private void seg(GuiGraphics g, int x, int y, int w, Component label, boolean on, int mx, int my) {
        boolean hover = Ui.inside(mx, my, x, y, w, 16);
        g.fill(x, y, x + w, y + 16, on ? 0xFF2F6FA8 : hover ? 0x40FFFFFF : 0x20FFFFFF);
        Ui.border(g, x, y, w, 16, on ? 0xFFFFFFFF : 0x60E8F0FF);
        g.drawString(font, label, x + 6, y + 4, on ? 0xFFFFFFFF : 0xAAE8F0FF, false);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        int x = x(), y = y();
        int ry = y + 36;
        Component[] vl = viewLabels();
        int vx = x + 100;
        for (int k = 0; k < vl.length; k++) {
            int w = font.width(vl[k]) + 12;
            if (Ui.inside(mx, my, vx, ry, w, 16)) {
                setViewMode(k);
                return true;
            }
            vx += w;
        }
        ry += 22;
        Component on = Component.translatable("designandbuild.menu.on");
        Component off = Component.translatable("designandbuild.menu.off");
        int wo = font.width(on) + 12, wf = font.width(off) + 12;
        if (Ui.inside(mx, my, x + 100, ry, wo, 16)) {
            setProjection(true);
            return true;
        }
        if (Ui.inside(mx, my, x + 100 + wo, ry, wf, 16)) {
            setProjection(false);
            return true;
        }
        Projection p = projection();
        if (p != null) {
            if (autoW > 0 && Ui.inside(mx, my, autoX, y + LIST_Y - 15, autoW, 12)) {
                p.jumpToFirstIncomplete();
                return true;
            }
            int lx = x + 10, ly = y + LIST_Y, lw = W - 20;
            // 즉시 반영 줄
            ProjectionManager.InstantState ist = instant();
            if (ist != null && instantRow() && !ist.running()) {
                int iy = ly + listRows * ROW + 4 + 4;
                if (confirmStage >= 0) {
                    Component ok = Component.translatable("designandbuild.instant.apply"), no = Component.translatable("designandbuild.dialog.cancel");
                    int okW = font.width(ok) + 12, noW = font.width(no) + 12;
                    int okX = lx + lw - okW, noX = okX - 4 - noW;
                    if (Ui.inside(mx, my, okX, iy, okW, 16)) {
                        int[] key = p.stageKey(confirmStage);
                        java.util.UUID id = ref.id();
                        dev.designandbuild.net.Net.toServer(dev.designandbuild.net.Msg.C_INSTANT_APPLY, b -> {
                            b.writeUUID(id);
                            b.writeVarInt(key[0]);
                            b.writeVarInt(key[1]);
                        });
                        confirmStage = -1;
                        return true;
                    }
                    if (Ui.inside(mx, my, noX, iy, noW, 16)) {
                        confirmStage = -1;
                        return true;
                    }
                } else if (ist.hasUndo()) {
                    Component label = Component.translatable("designandbuild.instant.undo", undoName(p, ist));
                    int w = Math.min(lw, font.width(label) + 12);
                    if (Ui.inside(mx, my, lx, iy, w, 16)) {
                        java.util.UUID id = ref.id();
                        dev.designandbuild.net.Net.toServer(dev.designandbuild.net.Msg.C_INSTANT_UNDO, b -> b.writeUUID(id));
                        return true;
                    }
                }
            }
            for (int i = 0; i < listRows; i++) {
                int st = scroll + i;
                if (st < p.stageCount() && ist != null && (!p.isComplete(st) || p.unknown(st) > 0)) {
                    int pw = font.width(progress(p, st));
                    int bx = lx + lw - 6 - pw - 14, by = ly + 2 + i * ROW + 1;
                    if (Ui.inside(mx, my, bx, by, 12, ROW - 2)) {
                        confirmStage = confirmStage == st ? -1 : st;
                        return true;
                    }
                }
                if (st < p.stageCount() && Ui.inside(mx, my, lx + 1, ly + 2 + i * ROW, lw - 2, ROW)) {
                    p.jumpToStage(st);
                    ProjectionManager.markAllDirty();
                    return true;
                }
            }
        }
        Component close = Component.translatable("designandbuild.dialog.close");
        int bw = font.width(close) + 20;
        if (Ui.inside(mx, my, x + W - 10 - bw, y + H - 22, bw, 16) || !Ui.inside(mx, my, x, y, W, H)) {
            onClose();
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    private void setViewMode(int mode) {
        ClientConfig.get().layerBelow.remove(id());
        ClientConfig.get().layerAll.remove(id());
        if (mode == 1) ClientConfig.get().layerBelow.put(id(), true);
        else if (mode == 2) ClientConfig.get().layerAll.add(id());
        ClientConfig.save();
        ProjectionManager.markAllDirty();
    }

    private void setProjection(boolean on) {
        if (on) ClientConfig.get().projectionOff.remove(id());
        else ClientConfig.get().projectionOff.add(id());
        ClientConfig.save();
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE || Minecraft.getInstance().options.keyInventory.matches(key, scan)) {
            onClose();
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
