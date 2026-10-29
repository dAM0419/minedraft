package dev.designandbuild.client.table;

import dev.designandbuild.Registration;
import dev.designandbuild.client.ClientNet;
import dev.designandbuild.client.ui.Icons;
import dev.designandbuild.client.ui.Ui;
import dev.designandbuild.item.BlueprintRef;
import dev.designandbuild.menu.BlueprintTableMenu;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

/**
 * 도면 테이블 화면 (청사진 테마).
 * <pre>
 * [도면 칸] [이름 입력          ]
 * 요약: 기준 청크, 청크 수, 구조물 수, 진척도, 편집 중인 사람
 * [        편집창 열기 / 설계 시작        ]
 * 연동 사본: [빈 도면] → [사본]
 * 인벤토리
 * </pre>
 */
public final class BlueprintTableScreen extends AbstractContainerScreen<BlueprintTableMenu> {
    private static final int BG = 0xF21E3F66, GRID = 0x22FFFFFF, LINE = 0xCCE8F0FF;
    private static final int BTN_X = 12, BTN_Y = 86, BTN_W = 172, BTN_H = 20;

    private EditBox name;
    @Nullable
    private ClientNet.TableInfo info;
    private ItemStack lastBlueprint = ItemStack.EMPTY;
    private int poll;
    private String committedName = "";
    @Nullable
    private int[] mapColors;
    private boolean confirming;
    /** 셰이더 팩이 켜진 채 편집창을 열려고 함: 경고를 보고 확인해야 들어간다. */
    private boolean shaderConfirm;

    public BlueprintTableScreen(BlueprintTableMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = BlueprintTableMenu.WIDTH;
        this.imageHeight = BlueprintTableMenu.HEIGHT;
        this.inventoryLabelX = BlueprintTableMenu.INV_X;
        this.inventoryLabelY = BlueprintTableMenu.INV_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        name = new EditBox(font, leftPos + 38, topPos + 25, 146, 12, Component.translatable("designandbuild.table.name"));
        name.setBordered(false);
        name.setMaxLength(48);
        name.setTextColor(0xFFFFFFFF);
        name.setHint(Component.translatable("designandbuild.table.name.hint"));
        addWidget(name);
        ClientNet.tableInfo = null;
        requestInfo();
    }

    private void requestInfo() {
        BlockPos pos = menu.tablePos();
        Net.toServer(Msg.C_TABLE_INFO_REQ, b -> b.writeBlockPos(pos));
    }

    public void onInfo(ClientNet.TableInfo info) {
        if (!info.pos().equals(menu.tablePos())) return;
        this.info = info;
        if (!name.isFocused() && info.state() == ClientNet.TableInfo.BOUND) {
            name.setValue(info.name());
            committedName = info.name();
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ItemStack bp = menu.blueprint();
        if (!ItemStack.matches(bp, lastBlueprint)) {
            lastBlueprint = bp.copy();
            info = null;
            if (!name.isFocused()) {
                BlueprintRef ref = bp.get(Registration.BLUEPRINT_REF.get());
                name.setValue(ref != null ? ref.name() : "");
                committedName = name.getValue();
            }
            requestInfo();
            poll = 0;
        }
        if (++poll >= 40) {
            poll = 0;
            requestInfo();
        }
    }

    private int state() {
        if (info != null) return info.state();
        ItemStack bp = menu.blueprint();
        if (bp.isEmpty()) return ClientNet.TableInfo.EMPTY_TABLE;
        return bp.get(Registration.BLUEPRINT_REF.get()) == null ? ClientNet.TableInfo.EMPTY_BLUEPRINT : ClientNet.TableInfo.BOUND;
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    @Override
    protected void renderBg(GuiGraphics g, float pt, int mx, int my) {
        int x = leftPos, y = topPos, w = imageWidth, h = imageHeight;
        g.fill(x, y, x + w, y + h, BG);
        for (int gx = x + 8; gx < x + w; gx += 8) g.fill(gx, y + 1, gx + 1, y + h - 1, GRID);
        for (int gy = y + 8; gy < y + h; gy += 8) g.fill(x + 1, gy, x + w - 1, gy + 1, GRID);
        Ui.border(g, x, y, w, h, LINE);
        Ui.border(g, x + 2, y + 2, w - 4, h - 4, 0x55E8F0FF);
        // 칸 테두리
        slotFrame(g, x + BlueprintTableMenu.BLUEPRINT_X - 1, y + BlueprintTableMenu.BLUEPRINT_Y - 1);
        slotFrame(g, x + BlueprintTableMenu.COPY_IN_X - 1, y + BlueprintTableMenu.COPY_IN_Y - 1);
        slotFrame(g, x + BlueprintTableMenu.COPY_OUT_X - 1, y + BlueprintTableMenu.COPY_OUT_Y - 1);
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) slotFrame(g, x + BlueprintTableMenu.INV_X + c * 18 - 1, y + BlueprintTableMenu.INV_Y + r * 18 - 1);
        }
        for (int c = 0; c < 9; c++) slotFrame(g, x + BlueprintTableMenu.INV_X + c * 18 - 1, y + BlueprintTableMenu.INV_Y + 58 - 1);
        if (menu.blueprint().isEmpty()) {
            Ui.icon(g, Icons.BLUEPRINT, x + BlueprintTableMenu.BLUEPRINT_X, y + BlueprintTableMenu.BLUEPRINT_Y, 0x60FFFFFF);
        }
        // 이름 칸
        int st = state();
        boolean nameOn = st == ClientNet.TableInfo.BOUND || st == ClientNet.TableInfo.EMPTY_BLUEPRINT;
        name.visible = nameOn;
        if (nameOn) {
            g.fill(x + 34, y + 22, x + 186, y + 39, name.isFocused() ? 0xFF0F2540 : 0x60102A48);
            Ui.border(g, x + 34, y + 22, 152, 17, name.isFocused() ? 0xFFFFFFFF : 0x80E8F0FF);
        }
        // 복사 화살표
        int ay = y + BlueprintTableMenu.COPY_IN_Y;
        Ui.icon(g, Icons.ARROW_RIGHT, x + BlueprintTableMenu.COPY_IN_X + 22, ay, 0xCCFFFFFF);
    }

    private static void slotFrame(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + 18, y + 18, 0x700A1A30);
        Ui.border(g, x, y, 18, 18, 0x90E8F0FF);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mx, int my) {
        g.drawString(font, title, 8, 8, 0xFFFFFFFF, false);
        // 인벤토리 이름은 생략한다 (연동 사본 줄과 겹치지 않게)
        int st = state();
        int ty = 46;
        if (st == ClientNet.TableInfo.EMPTY_TABLE) {
            Ui.text(g, Component.translatable("designandbuild.table.place_blueprint"), 36, 28, 0xCCE8F0FF);
        } else if (st == ClientNet.TableInfo.EMPTY_BLUEPRINT) {
            Ui.text(g, Component.translatable("designandbuild.table.empty_blueprint"), 12, ty, 0xFFFFFFFF);
            Ui.text(g, Component.translatable("designandbuild.table.empty_blueprint.tip"), 12, ty + 11, 0xAAE8F0FF);
            BlockPos pos = menu.tablePos();
            Ui.text(g, Component.translatable("designandbuild.table.base_chunk", pos.getX() >> 4, pos.getZ() >> 4), 12, ty + 22, 0xAAE8F0FF);
        } else if (st == ClientNet.TableInfo.MISSING) {
            Ui.text(g, Component.translatable("designandbuild.table.missing"), 12, ty, 0xFFFF8A8A);
        } else if (info != null) {
            Ui.text(g, Component.translatable("designandbuild.table.summary.base", info.baseX(), info.baseZ(), info.chunks(), dev.designandbuild.core.BlueprintModel.MAX_CHUNKS), 12, ty, 0xFFFFFFFF);
            String progress = info.progress() < 0 ? "–" : info.progress() + "%";
            Ui.text(g, Component.translatable("designandbuild.table.summary.content", info.structures(), progress), 12, ty + 11, 0xFFFFFFFF);
            Ui.text(g, Component.translatable("designandbuild.table.summary.editors", info.editors()), 12, ty + 22,
                    info.editors() > 0 ? 0xFF7FE0D6 : 0xAAE8F0FF);
        } else {
            Ui.text(g, "…", 12, ty, 0xAAE8F0FF);
        }
        Ui.text(g, Component.translatable("designandbuild.table.copy"), 12, BlueprintTableMenu.COPY_IN_Y, 0xFFFFFFFF);
        Ui.text(g, Ui.clip(Component.translatable("designandbuild.table.copy.tip").getString(), BlueprintTableMenu.COPY_IN_X - 16),
                12, BlueprintTableMenu.COPY_IN_Y + 10, 0x99E8F0FF);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        super.render(g, mx, my, pt);
        if (name.visible) name.render(g, mx, my, pt);
        renderButton(g, mx, my);
        if (confirming) {
            renderConfirm(g, mx, my);
        } else if (shaderConfirm) {
            renderShaderConfirm(g, mx, my);
        } else {
            renderTooltip(g, mx, my);
        }
    }

    private void renderButton(GuiGraphics g, int mx, int my) {
        int st = state();
        int x = leftPos + BTN_X, y = topPos + BTN_Y;
        boolean enabled = st == ClientNet.TableInfo.BOUND || st == ClientNet.TableInfo.EMPTY_BLUEPRINT;
        boolean hover = enabled && !confirming && Ui.inside(mx, my, x, y, BTN_W, BTN_H);
        int bg = !enabled ? 0x40102A48 : hover ? 0xFF2F6FA8 : 0xFF245A8C;
        g.fill(x, y, x + BTN_W, y + BTN_H, bg);
        Ui.border(g, x, y, BTN_W, BTN_H, enabled ? 0xFFFFFFFF : 0x60E8F0FF);
        Component label = Component.translatable(st == ClientNet.TableInfo.EMPTY_BLUEPRINT ? "designandbuild.table.start" : "designandbuild.table.open");
        int lw = font.width(label) + 20;
        int lx = x + (BTN_W - lw) / 2;
        Ui.icon(g, st == ClientNet.TableInfo.EMPTY_BLUEPRINT ? Icons.PLUS : Icons.BLUEPRINT, lx, y + 2, enabled ? 0xFFFFFFFF : 0x60FFFFFF);
        g.drawString(font, label, lx + 20, y + 6, enabled ? 0xFFFFFFFF : 0x80E8F0FF, false);
    }

    // ------------------------------------------------------------------
    // 설계 대상 지정 확인
    // ------------------------------------------------------------------

    private static final int CW = 228, CH = 196, MAP = 96;

    private void buildMap() {
        ClientLevel level = Minecraft.getInstance().level;
        mapColors = new int[48 * 48];
        if (level == null) return;
        BlockPos table = menu.tablePos();
        int x0 = ((table.getX() >> 4) - 1) << 4, z0 = ((table.getZ() >> 4) - 1) << 4;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dz = 0; dz < 48; dz++) {
            for (int dx = 0; dx < 48; dx++) {
                int wx = x0 + dx, wz = z0 + dz;
                int h = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz) - 1;
                m.set(wx, h, wz);
                BlockState s = level.getBlockState(m);
                MapColor mc = s.getMapColor(level, m);
                int col = mc == MapColor.NONE ? 0x203040 : mc.col;
                // 높이에 따라 살짝 명암
                int hn = level.getHeight(Heightmap.Types.WORLD_SURFACE, wx, wz - 1) - 1;
                float shade = h > hn ? 1.1F : h < hn ? 0.85F : 1.0F;
                int r = Math.min(255, (int) (((col >> 16) & 0xFF) * shade));
                int gr = Math.min(255, (int) (((col >> 8) & 0xFF) * shade));
                int b = Math.min(255, (int) ((col & 0xFF) * shade));
                mapColors[dz * 48 + dx] = 0xFF000000 | r << 16 | gr << 8 | b;
            }
        }
    }

    private int cx() {
        return (width - CW) / 2;
    }

    private int cy() {
        return (height - CH) / 2;
    }

    private void renderConfirm(GuiGraphics g, int mx, int my) {
        if (mapColors == null) buildMap();
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, width, height, 0x90000000);
        int x = cx(), y = cy();
        g.fill(x, y, x + CW, y + CH, BG);
        Ui.border(g, x, y, CW, CH, LINE);
        g.drawString(font, Component.translatable("designandbuild.table.confirm.title"), x + 10, y + 8, 0xFFFFFFFF, false);
        int ty = y + 22;
        for (var line : font.split(Component.translatable("designandbuild.table.confirm.text"), CW - 20)) {
            g.drawString(font, line, x + 10, ty, 0xFFE8F0FF, false);
            ty += 10;
        }
        int mx0 = x + (CW - MAP) / 2, my0 = ty + 4;
        if (mapColors != null) {
            for (int dz = 0; dz < 48; dz++) {
                for (int dx = 0; dx < 48; dx++) {
                    int px = mx0 + dx * 2, py = my0 + dz * 2;
                    g.fill(px, py, px + 2, py + 2, mapColors[dz * 48 + dx]);
                }
            }
        }
        // 청크 경계 + 기준 청크
        for (int k = 0; k <= 3; k++) {
            g.fill(mx0 + k * 32, my0, mx0 + k * 32 + 1, my0 + MAP, 0xAAFFFFFF);
            g.fill(mx0, my0 + k * 32, mx0 + MAP, my0 + k * 32 + 1, 0xAAFFFFFF);
        }
        Ui.border(g, mx0 + 32, my0 + 32, 33, 33, 0xFF2EC4B6);
        BlockPos table = menu.tablePos();
        int tx = mx0 + ((table.getX() & 15) + 16) * 2, tz = my0 + ((table.getZ() & 15) + 16) * 2;
        g.fill(tx - 1, tz - 1, tx + 3, tz + 3, 0xFFFFFFFF);
        g.drawString(font, "N", mx0 + MAP / 2 - 2, my0 - 9, 0xAAFFFFFF, false);
        int by = my0 + MAP + 6;
        Component base = Component.translatable("designandbuild.table.base_chunk", table.getX() >> 4, table.getZ() >> 4);
        g.drawString(font, base, x + (CW - font.width(base)) / 2, by, 0xFFE8F0FF, false);
        // 버튼
        Component ok = Component.translatable("designandbuild.table.confirm.ok");
        Component cancel = Component.translatable("designandbuild.dialog.cancel");
        int bw1 = font.width(ok) + 20, bw2 = font.width(cancel) + 20;
        int bY = y + CH - 24;
        int okX = x + CW - 10 - bw1, cancelX = okX - 6 - bw2;
        confirmButton(g, okX, bY, bw1, ok, true, mx, my);
        confirmButton(g, cancelX, bY, bw2, cancel, false, mx, my);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------
    // 셰이더 팩 경고
    // ------------------------------------------------------------------

    private static final int SW = 228, SH = 104;

    private int[] shaderButtons() {
        int x = (width - SW) / 2, y = (height - SH) / 2;
        Component ok = Component.translatable("designandbuild.shader.open_anyway");
        Component cancel = Component.translatable("designandbuild.dialog.cancel");
        int bw1 = font.width(ok) + 20, bw2 = font.width(cancel) + 20;
        int bY = y + SH - 24;
        int okX = x + SW - 10 - bw1, cancelX = okX - 6 - bw2;
        return new int[]{x, y, okX, bw1, cancelX, bw2, bY};
    }

    private void renderShaderConfirm(GuiGraphics g, int mx, int my) {
        int[] b = shaderButtons();
        int x = b[0], y = b[1];
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(0, 0, width, height, 0x90000000);
        g.fill(x, y, x + SW, y + SH, BG);
        Ui.border(g, x, y, SW, SH, 0xFFE0A030);
        Ui.icon(g, Icons.WARN, x + 8, y + 6, 0xFFE0A030);
        g.drawString(font, Component.translatable("designandbuild.shader.title"), x + 28, y + 10, 0xFFFFFFFF, false);
        int ty = y + 28;
        for (var line : font.split(Component.translatable("designandbuild.msg.shader_warning"), SW - 20)) {
            g.drawString(font, line, x + 10, ty, 0xFFE8F0FF, false);
            ty += 10;
        }
        confirmButton(g, b[2], b[6], b[3], Component.translatable("designandbuild.shader.open_anyway"), true, mx, my);
        confirmButton(g, b[4], b[6], b[5], Component.translatable("designandbuild.dialog.cancel"), false, mx, my);
        g.pose().popPose();
    }

    private boolean shaderConfirmClick(double mx, double my) {
        int[] b = shaderButtons();
        if (Ui.inside(mx, my, b[2], b[6], b[3], 16)) {
            shaderConfirm = false;
            openEditor();
        } else if (Ui.inside(mx, my, b[4], b[6], b[5], 16) || !Ui.inside(mx, my, b[0], b[1], SW, SH)) {
            shaderConfirm = false;
        }
        return true;
    }

    private void openEditor() {
        BlockPos pos = menu.tablePos();
        Net.toServer(Msg.C_OPEN_EDITOR, b -> b.writeBlockPos(pos));
    }

    private void confirmButton(GuiGraphics g, int x, int y, int w, Component label, boolean primary, int mx, int my) {
        boolean hover = Ui.inside(mx, my, x, y, w, 16);
        int bg = primary ? (hover ? 0xFF2F6FA8 : 0xFF245A8C) : (hover ? 0x60FFFFFF : 0x30FFFFFF);
        g.fill(x, y, x + w, y + 16, bg);
        Ui.border(g, x, y, w, 16, primary ? 0xFFFFFFFF : 0x90E8F0FF);
        g.drawString(font, label, x + 10, y + 4, 0xFFFFFFFF, false);
    }

    private boolean confirmClick(double mx, double my) {
        int x = cx(), y = cy();
        Component ok = Component.translatable("designandbuild.table.confirm.ok");
        Component cancel = Component.translatable("designandbuild.dialog.cancel");
        int bw1 = font.width(ok) + 20, bw2 = font.width(cancel) + 20;
        int bY = y + CH - 24;
        int okX = x + CW - 10 - bw1, cancelX = okX - 6 - bw2;
        if (Ui.inside(mx, my, okX, bY, bw1, 16)) {
            startDesign();
        } else if (Ui.inside(mx, my, cancelX, bY, bw2, 16) || !Ui.inside(mx, my, x, y, CW, CH)) {
            confirming = false;
        }
        return true;
    }

    private void startDesign() {
        confirming = false;
        BlockPos pos = menu.tablePos();
        String n = name.getValue().trim();
        if (n.isEmpty()) n = Component.translatable("designandbuild.blueprint.default_name").getString();
        String finalName = n;
        Net.toServer(Msg.C_START_DESIGN, b -> {
            b.writeBlockPos(pos);
            b.writeUtf(finalName, 64);
        });
    }

    // ------------------------------------------------------------------
    // 입력
    // ------------------------------------------------------------------

    private void commitName() {
        String v = name.getValue().trim();
        if (state() != ClientNet.TableInfo.BOUND || v.isEmpty() || v.equals(committedName)) return;
        committedName = v;
        BlockPos pos = menu.tablePos();
        Net.toServer(Msg.C_TABLE_RENAME, b -> {
            b.writeBlockPos(pos);
            b.writeUtf(v, 64);
        });
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (confirming) return confirmClick(mx, my);
        if (shaderConfirm) return shaderConfirmClick(mx, my);
        boolean wasFocused = name.isFocused();
        if (name.visible && name.isMouseOver(mx, my)) {
            name.setFocused(true);
            setFocused(name);
            if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) name.setValue("");
            name.mouseClicked(mx, my, GLFW.GLFW_MOUSE_BUTTON_LEFT);
            return true;
        }
        if (wasFocused) {
            name.setFocused(false);
            setFocused(null);
            commitName();
        }
        int st = state();
        if (Ui.inside(mx, my, leftPos + BTN_X, topPos + BTN_Y, BTN_W, BTN_H)) {
            if (st == ClientNet.TableInfo.BOUND) {
                // 셰이더 팩이 켜져 있으면 그래픽 오류 경고를 먼저 보여 주고, 확인해야 들어간다
                if (dev.designandbuild.client.projection.ProjectionRenderer.shaderPackInUse()) {
                    shaderConfirm = true;
                } else {
                    openEditor();
                }
                return true;
            }
            if (st == ClientNet.TableInfo.EMPTY_BLUEPRINT) {
                mapColors = null;
                confirming = true;
                return true;
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (confirming) {
            if (key == GLFW.GLFW_KEY_ESCAPE) confirming = false;
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) startDesign();
            return true;
        }
        if (shaderConfirm) {
            if (key == GLFW.GLFW_KEY_ESCAPE) shaderConfirm = false;
            else if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                shaderConfirm = false;
                openEditor();
            }
            return true;
        }
        if (name.isFocused()) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                name.setValue(committedName);
                name.setFocused(false);
                setFocused(null);
                return true;
            }
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER || key == GLFW.GLFW_KEY_TAB) {
                name.setFocused(false);
                setFocused(null);
                commitName();
                return true;
            }
            name.keyPressed(key, scan, mods);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (confirming || shaderConfirm) return true;
        if (name.isFocused()) return name.charTyped(c, mods);
        return super.charTyped(c, mods);
    }

    @Override
    public void removed() {
        if (name != null && name.isFocused()) commitName();
        super.removed();
    }
}
