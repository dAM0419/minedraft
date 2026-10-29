package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.client.editor.ui.UiPart;
import dev.minedraft.lib.client.editor.EditorColors;
import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.editor.AreaEditorSession;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.PlayerFaceRenderer;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 상단 바: 도면 이름·저장 상태 | 편집 중인 사람 | 나침반·회전·닫기. */
public final class TopBar extends UiPart<AreaEditorScreen> {
    private final List<int[]> faceBoxes = new ArrayList<>();
    private final List<UUID> faceIds = new ArrayList<>();

    public TopBar(AreaEditorScreen screen) {
        super(screen);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        AreaEditorSession s = s();
        g.fill(x, y, x + w, y + h, Ui.BLUEPRINT);
        for (int gx = x + 8; gx < x + w; gx += 16) g.fill(gx, y, gx + 1, y + h, 0x14FFFFFF);
        g.fill(x, y + h - 1, x + w, y + h, Ui.EDGE);

        // 왼쪽: 영역 이름 + 저장 상태
        Ui.icon(g, screen.titleIcon(), x + 3, y, Ui.TEXT);
        String status = s.pending > 0 ? Component.translatable("minedraftlib.editor.saving").getString()
                : Component.translatable("minedraftlib.editor.saved").getString();
        int statusColor = s.pending > 0 ? Ui.WARN : Ui.DIM;
        String name = Ui.clip(s.area.name(), Math.max(40, w / 3 - 80));
        Ui.text(g, name, x + 21, y + 4, Ui.TEXT);
        Ui.text(g, status, x + 25 + Ui.font().width(name), y + 4, statusColor);

        // 가운데: 편집 중인 사람
        faceBoxes.clear();
        faceIds.clear();
        int count = s.editors.size();
        int fx = x + w / 2 - count * 6;
        Minecraft mc = Minecraft.getInstance();
        for (Map.Entry<UUID, String> e : s.editors.entrySet()) {
            PlayerSkin skin = DefaultPlayerSkin.get(e.getKey());
            if (mc.getConnection() != null) {
                PlayerInfo info = mc.getConnection().getPlayerInfo(e.getKey());
                if (info != null) skin = info.getSkin();
            }
            g.fill(fx - 1, y + 2, fx + 11, y + 14, EditorColors.editorColor(e.getKey()));
            PlayerFaceRenderer.draw(g, skin, fx, y + 3, 10);
            faceBoxes.add(new int[]{fx - 1, y + 2, 12, 12});
            faceIds.add(e.getKey());
            if (Ui.inside(mx, my, fx - 1, y + 2, 12, 12)) {
                screen.tooltip = List.of(Component.literal(e.getValue()), Component.translatable("minedraftlib.editor.goto_editor"));
            }
            fx += 12;
        }

        // 오른쪽: 닫기, 회전, 나침반
        int bx = x + w - 18;
        boolean hClose = Ui.inside(mx, my, bx, y, 16, 16);
        Ui.iconButton(g, bx, y, 16, 16, Icons.CLOSE, false, hClose, true);
        if (hClose) screen.tooltip = List.of(Component.translatable("minedraftlib.editor.close"));
        bx -= 18;
        boolean hR = Ui.inside(mx, my, bx, y, 16, 16);
        Ui.iconButton(g, bx, y, 16, 16, Icons.ROTATE_R, false, hR, true);
        if (hR) screen.tooltip = List.of(Component.translatable("minedraftlib.editor.rotate_right"));
        bx -= 18;
        boolean hL = Ui.inside(mx, my, bx, y, 16, 16);
        Ui.iconButton(g, bx, y, 16, 16, Icons.ROTATE_L, false, hL, true);
        if (hL) screen.tooltip = List.of(Component.translatable("minedraftlib.editor.rotate_left"));
        bx -= 22;
        drawCompass(g, bx + 8, y + 8, s);
        if (Ui.inside(mx, my, bx, y, 16, 16)) {
            screen.tooltip = List.of(Component.translatable("minedraftlib.editor.view_name", s.camera.viewName()));
        }
    }

    /** 나침반: 화면에서 동서남북이 가리키는 방향에 글자를 둔다. */
    private void drawCompass(GuiGraphics g, int cx, int cy, AreaEditorSession s) {
        g.fill(cx - 7, cy - 7, cx + 8, cy + 8, 0x30000000);
        double tx = s.camera.tx, ty = s.camera.ty, tz = s.camera.tz;
        Vector3f c = s.camera.project(tx, ty, tz);
        for (Direction d : Direction.Plane.HORIZONTAL) {
            Vector3f p = s.camera.project(tx + d.getStepX() * 10, ty, tz + d.getStepZ() * 10);
            float dx = p.x - c.x, dy = p.y - c.y;
            float len = (float) Math.sqrt(dx * dx + dy * dy);
            if (len < 1e-3) continue;
            int lx = cx + Math.round(dx / len * 6), ly = cy + Math.round(dy / len * 6);
            String letter = Component.translatable("minedraftlib.compass.letter." + d.getName()).getString();
            int color = d == Direction.NORTH ? Ui.DEMOLISH : Ui.DIM;
            g.drawString(Ui.font(), letter, lx - Ui.font().width(letter) / 2, ly - 4, color, false);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        AreaEditorSession s = s();
        int bx = x + w - 18;
        if (Ui.inside(mx, my, bx, y, 16, 16)) {
            screen.onClose();
            return true;
        }
        bx -= 18;
        if (Ui.inside(mx, my, bx, y, 16, 16)) {
            s.camera.rotate(1);
            return true;
        }
        bx -= 18;
        if (Ui.inside(mx, my, bx, y, 16, 16)) {
            s.camera.rotate(-1);
            return true;
        }
        for (int i = 0; i < faceBoxes.size(); i++) {
            int[] b = faceBoxes.get(i);
            if (Ui.inside(mx, my, b[0], b[1], b[2], b[3])) {
                AreaEditorSession.RemoteCursor c = s.cursors.get(faceIds.get(i));
                if (c != null && c.has()) {
                    screen.focusCamera(BlockPos.getX(c.pos()) + 0.5, BlockPos.getY(c.pos()) + 1, BlockPos.getZ(c.pos()) + 0.5);
                }
                return true;
            }
        }
        return true;
    }
}
