package dev.designandbuild.client.editor.ui;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import dev.minedraft.lib.client.editor.EditorCamera;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.designandbuild.client.editor.PlacementStates;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 설치 미리보기: 지금 설정으로 설치될 실제 블록을 인벤토리 아이템처럼 비스듬히 본 3D로 크게 보여 주고,
 * 그 위에서 블록을 설치하는 가상 플레이어의 입력 세 가지를 고른다.
 * <pre>
 * 바라보는 방향: 미리보기 둘레의 방위 단추 4개(바닥 축 방향에 놓임) + 위 보기 / 아래 보기
 * 붙일 면:       미리보기 육면체의 보이는 면을 누름. 안 보이는 면은 "반대면" (뒤쪽 면은 점선으로 비쳐 보이고, 고른 뒤쪽 면은 채워 보인다)
 * 위·아래 절반:  옆면을 누른 위치(면의 위 절반 / 아래 절반)
 * </pre>
 * 미리보기는 편집창 시점 방향으로 그리고, 끌면 돌려 볼 수 있다(설정은 그대로, 놓으면 시점 방향으로 돌아온다).
 * 팝업 안에서 R = 바라보는 방향 90° 회전, F = 위·아래 뒤집기.
 */
public final class OrientationPopup {
    private static final int W = 244, H = 158;
    private static final int PV = 116; // 미리보기 칸 크기
    private static final float CUBE = 36.0F; // 미리보기 블록 크기 (픽셀)
    private static final float PITCH = EditorCamera.PITCH;

    private final EditorScreen screen;
    private final int x, y;
    private float orbit; // 끌어서 돌린 각도
    private boolean pressed, dragged;
    private double pressX;

    public OrientationPopup(EditorScreen screen, int anchorX, int barY) {
        this.screen = screen;
        this.x = Math.max(4, Math.min(anchorX - W + 20, screen.width - W - 4));
        this.y = Math.max(4, barY - H - 4);
    }

    private EditorSession s() {
        return screen.session();
    }

    public boolean contains(double mx, double my) {
        return Ui.inside(mx, my, x, y, W, H);
    }

    // ------------------------------------------------------------------
    // 기하
    // ------------------------------------------------------------------

    private int pvX() {
        return x + 6;
    }

    private int pvY() {
        return y + 30;
    }

    private float cx() {
        return pvX() + PV / 2.0F;
    }

    private float cy() {
        return pvY() + PV / 2.0F + 4;
    }

    private float viewYaw() {
        return s().camera.yaw() + orbit;
    }

    /** 블록 그리기와 같은 회전 (화면 기준 y 위쪽). */
    private static Matrix4f rotation(float yaw) {
        return new Matrix4f().rotateX((float) Math.toRadians(PITCH)).rotateY((float) Math.toRadians(yaw + 180.0F));
    }

    /** 블록 좌표(0~1)를 화면 좌표로. */
    private float[] project(Matrix4f m, float bx, float by, float bz) {
        Vector3f v = m.transformPosition(new Vector3f(bx - 0.5F, by - 0.5F, bz - 0.5F));
        return new float[]{cx() + v.x * CUBE, cy() - v.y * CUBE};
    }

    /** 면의 네 꼭짓점 (블록 좌표): 아래-왼, 아래-오른, 위-오른, 위-왼 (옆면은 y 0→1 순으로). */
    private static float[][] faceCorners(Direction d) {
        return switch (d) {
            case UP -> new float[][]{{0, 1, 1}, {1, 1, 1}, {1, 1, 0}, {0, 1, 0}};
            case DOWN -> new float[][]{{0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1}};
            case NORTH -> new float[][]{{1, 0, 0}, {0, 0, 0}, {0, 1, 0}, {1, 1, 0}};
            case SOUTH -> new float[][]{{0, 0, 1}, {1, 0, 1}, {1, 1, 1}, {0, 1, 1}};
            case WEST -> new float[][]{{0, 0, 0}, {0, 0, 1}, {0, 1, 1}, {0, 1, 0}};
            case EAST -> new float[][]{{1, 0, 1}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}};
        };
    }

    private boolean visible(Matrix4f m, Direction d) {
        Vector3f n = m.transformDirection(new Vector3f(d.getStepX(), d.getStepY(), d.getStepZ()));
        return n.z > 0.05F;
    }

    private float[][] screenFace(Matrix4f m, Direction d) {
        float[][] c = faceCorners(d);
        float[][] out = new float[4][];
        for (int i = 0; i < 4; i++) out[i] = project(m, c[i][0], c[i][1], c[i][2]);
        return out;
    }

    private static boolean insideQuad(float[][] q, double px, double py) {
        boolean pos = false, neg = false;
        for (int i = 0; i < 4; i++) {
            float[] a = q[i], b = q[(i + 1) % 4];
            double cr = (b[0] - a[0]) * (py - a[1]) - (b[1] - a[1]) * (px - a[0]);
            if (cr > 0) pos = true;
            if (cr < 0) neg = true;
        }
        return !(pos && neg);
    }

    /** 옆면에서 위 절반을 눌렀는가: 면의 가운데 가로줄 기준으로 위쪽 꼭짓점과 같은 편인가. */
    private static boolean upperHalfOf(float[][] q, double px, double py) {
        float[] l = mid(q[0], q[3]), r = mid(q[1], q[2]);
        double side = (r[0] - l[0]) * (py - l[1]) - (r[1] - l[1]) * (px - l[0]);
        double top = (r[0] - l[0]) * (q[3][1] - l[1]) - (r[1] - l[1]) * (q[3][0] - l[0]);
        return Math.signum(side) == Math.signum(top);
    }

    private static float[] mid(float[] a, float[] b) {
        return new float[]{(a[0] + b[0]) / 2, (a[1] + b[1]) / 2};
    }

    /** 바라보는 방향 단추 위치: 바닥 평면에서 그 방위로 뻗은 축이 화면에 놓이는 쪽. */
    private float[] arrowPos(Matrix4f m, Direction d) {
        Vector3f v = m.transformDirection(new Vector3f(d.getStepX(), 0, d.getStepZ()));
        float k = 66.0F;
        float ax = cx() + v.x * k, ay = cy() + 8 - v.y * k;
        float lo = 8, hiX = PV - 8, hiY = PV - 8;
        ax = Math.max(pvX() + lo, Math.min(pvX() + hiX, ax));
        ay = Math.max(pvY() + lo, Math.min(pvY() + hiY, ay));
        return new float[]{ax, ay};
    }

    @Nullable
    private Direction faceAt(Matrix4f m, double mx, double my) {
        Direction best = null;
        for (Direction d : Direction.values()) {
            if (!visible(m, d)) continue;
            if (insideQuad(screenFace(m, d), mx, my)) best = d;
        }
        return best;
    }

    // ------------------------------------------------------------------
    // 그리기
    // ------------------------------------------------------------------

    public void render(GuiGraphics g, int mx, int my, float pt) {
        EditorSession s = s();
        if (!pressed && orbit != 0) orbit *= 0.7F; // 놓으면 시점 방향으로 돌아온다
        if (Math.abs(orbit) < 0.5F) orbit = 0;
        Ui.solidPanel(g, x, y, W, H);
        Ui.text(g, Component.translatable("designandbuild.orientation"), x + 6, y + 5, Ui.TEXT);
        Component look = Component.translatable(s.lookV > 0 ? "designandbuild.look.up" : s.lookV < 0 ? "designandbuild.look.down" : "designandbuild.look.level");
        Component summary = Component.translatable("designandbuild.orientation.summary", Ui.dirName(s.lookH), look, Ui.dirName(s.face));
        String half = s.face.getAxis().isHorizontal()
                ? " · " + Component.translatable(s.upperHalf ? "designandbuild.orientation.upper" : "designandbuild.orientation.lower").getString() : "";
        Ui.text(g, Ui.clip(summary.getString() + half, W - 12), x + 6, y + 17, Ui.DIM);

        int px = pvX(), py = pvY();
        g.fill(px, py, px + PV, py + PV, 0xFF0B0E12);
        Ui.border(g, px, py, PV, PV, Ui.EDGE);

        Matrix4f m = rotation(viewYaw());
        List<BlockState> states = states(s);
        // 실제 블록 (첫 재료)
        if (!states.isEmpty()) drawBlock(g, states.get(0), cx(), cy(), CUBE, viewYaw());
        // 면 겹침: 고른 면 채움 + 커서 면 강조 + 육면체 외곽
        Direction hoverFace = Ui.inside(mx, my, px, py, PV, PV) && !dragged ? faceAt(m, mx, my) : null;
        // 뒤쪽 면 (투시): 블록 너머로 비쳐 보이게 점선 외곽, 고른 면이면 옅게 채움
        for (Direction d : Direction.values()) {
            if (visible(m, d)) continue;
            float[][] q = screenFace(m, d);
            if (d == s.face) {
                if (d.getAxis().isHorizontal()) {
                    float[] l = mid(q[0], q[3]), r = mid(q[1], q[2]);
                    quad(g, s.upperHalf ? new float[][]{l, r, q[2], q[3]} : new float[][]{q[0], q[1], r, l}, 0x582EC4B6);
                } else {
                    quad(g, q, 0x582EC4B6);
                }
                for (int i = 0; i < 4; i++) dashed(g, q[i], q[(i + 1) % 4], 0xF02EC4B6);
            } else {
                for (int i = 0; i < 4; i++) dashed(g, q[i], q[(i + 1) % 4], 0x908A8F96);
            }
        }
        for (Direction d : Direction.values()) {
            if (!visible(m, d)) continue;
            float[][] q = screenFace(m, d);
            if (d == s.face) {
                if (d.getAxis().isHorizontal()) {
                    float[] l = mid(q[0], q[3]), r = mid(q[1], q[2]);
                    float[][] h = s.upperHalf ? new float[][]{l, r, q[2], q[3]} : new float[][]{q[0], q[1], r, l};
                    quad(g, h, 0x702EC4B6);
                } else {
                    quad(g, q, 0x702EC4B6);
                }
            }
            if (d == hoverFace) {
                if (d.getAxis().isHorizontal()) {
                    boolean up = upperHalfOf(q, mx, my);
                    float[] l = mid(q[0], q[3]), r = mid(q[1], q[2]);
                    quad(g, up ? new float[][]{l, r, q[2], q[3]} : new float[][]{q[0], q[1], r, l}, 0x38FFFFFF);
                } else {
                    quad(g, q, 0x38FFFFFF);
                }
            }
            for (int i = 0; i < 4; i++) line(g, q[i], q[(i + 1) % 4], 0xC08A8F96);
            if (d.getAxis().isHorizontal()) line(g, mid(q[0], q[3]), mid(q[1], q[2]), 0x408A8F96);
        }
        // 고른 면이 뒤쪽이면 그 사실을 글자로
        if (!visible(m, s.face)) {
            Ui.centered(g, Component.translatable("designandbuild.orientation.hidden_face", Ui.dirName(s.face)), (int) cx(), py + PV - 12, Ui.ACCENT);
        }
        // 바라보는 방향 단추
        for (Direction d : Direction.Plane.HORIZONTAL) {
            float[] a = arrowPos(m, d);
            int bx = Math.round(a[0]) - 6, by = Math.round(a[1]) - 6;
            boolean on = s.lookH == d;
            boolean hv = Ui.inside(mx, my, bx, by, 12, 12);
            g.fill(bx, by, bx + 12, by + 12, on ? 0xFF1F6F68 : hv ? 0xFF2A323D : 0xFF161B22);
            Ui.border(g, bx, by, 12, 12, on ? Ui.ACCENT : Ui.EDGE);
            String letter = Component.translatable("minedraftlib.compass.letter." + d.getName()).getString();
            Ui.text(g, letter, bx + 6 - Ui.font().width(letter) / 2 + 1, by + 2, d == Direction.NORTH ? 0xFFFF7A7A : Ui.TEXT);
            if (hv) screen.tooltip = List.of(Component.translatable("designandbuild.orientation.look_dir", Ui.dirName(d)));
        }

        // ---- 오른쪽: 단추들 ----
        int bx = x + PV + 14, bw = W - PV - 20, by = y + 30;
        button(g, bx, by, bw, Component.translatable("designandbuild.look.up"), s.lookV > 0, mx, my);
        button(g, bx, by + 16, bw, Component.translatable("designandbuild.look.down"), s.lookV < 0, mx, my);
        button(g, bx, by + 36, bw, Component.translatable("designandbuild.orientation.flip"), false, mx, my);
        button(g, bx, by + 52, bw, Component.translatable("designandbuild.orientation.rotate"), false, mx, my);
        button(g, bx, by + 68, bw, Component.translatable("designandbuild.orientation.flip_half"), false, mx, my);
        // 섞기 재료: 나머지 슬롯 결과를 작게
        if (states.size() > 1) {
            for (int i = 1; i < states.size(); i++) {
                int tx = bx + (i - 1) * 30, ty = by + 90;
                g.fill(tx, ty, tx + 26, ty + 26, 0xFF0B0E12);
                Ui.border(g, tx, ty, 26, 26, Ui.EDGE);
                drawBlock(g, states.get(i), tx + 13, ty + 14, 9.0F, viewYaw());
            }
        }
        if (states.isEmpty()) {
            Ui.centered(g, Component.translatable("designandbuild.orientation.no_material"), (int) cx(), py + 8, Ui.DIM);
        }
        Ui.text(g, Ui.clip(Component.translatable("designandbuild.orientation.hint").getString(), W - 12), x + 6, y + H - 12, Ui.DIM);
    }

    private void button(GuiGraphics g, int bx, int by, int bw, Component label, boolean on, int mx, int my) {
        boolean hv = Ui.inside(mx, my, bx, by, bw, 14);
        g.fill(bx, by, bx + bw, by + 14, on ? 0xFF1F6F68 : hv ? 0x40FFFFFF : 0x20FFFFFF);
        Ui.border(g, bx, by, bw, 14, on ? Ui.ACCENT : Ui.EDGE);
        Ui.text(g, Ui.clip(label.getString(), bw - 6), bx + 4, by + 3, Ui.TEXT);
    }

    private static List<BlockState> states(EditorSession s) {
        List<BlockState> out = new ArrayList<>();
        for (ItemStack st : s.materials) {
            BlockState b = PlacementStates.stateFor(st, s.lookH, s.lookV, s.face, s.upperHalf);
            if (b != null) out.add(b);
        }
        return out;
    }

    private static void quad(GuiGraphics g, float[][] q, int color) {
        Matrix4f pose = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < 4; i++) vc.addVertex(pose, q[i][0], q[i][1], 200).setColor(color);
        // 뒤집힌 순서도 그려 컬링과 무관하게
        for (int i = 3; i >= 0; i--) vc.addVertex(pose, q[i][0], q[i][1], 200).setColor(color);
        g.flush();
    }

    private static void line(GuiGraphics g, float[] a, float[] b, int color) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3F) return;
        float nx = -dy / len * 0.5F, ny = dx / len * 0.5F;
        quad(g, new float[][]{{a[0] + nx, a[1] + ny}, {b[0] + nx, b[1] + ny}, {b[0] - nx, b[1] - ny}, {a[0] - nx, a[1] - ny}}, color);
    }

    /** 점선 (3px 그리고 2px 비움). */
    private static void dashed(GuiGraphics g, float[] a, float[] b, int color) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3F) return;
        for (float t = 0; t < len; t += 5.0F) {
            float t1 = Math.min(len, t + 3.0F);
            line(g, new float[]{a[0] + dx * t / len, a[1] + dy * t / len}, new float[]{a[0] + dx * t1 / len, a[1] + dy * t1 / len}, color);
        }
    }

    /** 블록 상태를 비스듬히 본 3D로 그린다 (편집창 시점과 같은 기울기). */
    public static void drawBlock(GuiGraphics g, BlockState state, float cx, float cy, float size, float yaw) {
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(cx, cy, 150);
        ps.scale(size, -size, size);
        ps.mulPose(Axis.XP.rotationDegrees(PITCH));
        ps.mulPose(Axis.YP.rotationDegrees(yaw + 180));
        ps.translate(-0.5, -0.5, -0.5);
        try {
            Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, ps, g.bufferSource(), LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY, ModelData.EMPTY, null);
        } catch (Exception ignored) {
        }
        g.flush();
        ps.popPose();
    }

    // ------------------------------------------------------------------
    // 방향 속성 유무 (옵션 바 아이콘용)
    // ------------------------------------------------------------------

    private static final Map<Item, Boolean> ORIENTED = new HashMap<>();

    /** 이 재료가 입력에 따라 다른 상태로 놓이는가. 재료가 없으면 true (미리 정해 둘 수 있게). */
    public static boolean oriented(EditorSession s) {
        boolean any = false;
        for (ItemStack st : s.materials) {
            if (st.isEmpty()) continue;
            any = true;
            if (ORIENTED.computeIfAbsent(st.getItem(), it -> probe(st))) return true;
        }
        return !any;
    }

    private static boolean probe(ItemStack st) {
        BlockState a = PlacementStates.stateFor(st, Direction.NORTH, 0, Direction.UP, false);
        if (a == null) return false;
        Object[][] variants = {{Direction.EAST, 0, Direction.UP, false}, {Direction.NORTH, 1, Direction.UP, false},
                {Direction.NORTH, 0, Direction.EAST, false}, {Direction.NORTH, 0, Direction.EAST, true},
                {Direction.NORTH, 0, Direction.DOWN, false}, {Direction.SOUTH, -1, Direction.NORTH, true}};
        for (Object[] v : variants) {
            BlockState b = PlacementStates.stateFor(st, (Direction) v[0], (Integer) v[1], (Direction) v[2], (Boolean) v[3]);
            if (b != null && b != a) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 입력
    // ------------------------------------------------------------------

    public boolean mouseClicked(double mx, double my, int button) {
        EditorSession s = s();
        Matrix4f m = rotation(viewYaw());
        for (Direction d : Direction.Plane.HORIZONTAL) {
            float[] a = arrowPos(m, d);
            if (Ui.inside(mx, my, Math.round(a[0]) - 7, Math.round(a[1]) - 7, 14, 14)) {
                s.lookH = d;
                if (s.lookV != 0) s.lookV = 0;
                return true;
            }
        }
        int bx = x + PV + 14, bw = W - PV - 20, by = y + 30;
        if (Ui.inside(mx, my, bx, by, bw, 14)) {
            s.lookV = s.lookV > 0 ? 0 : 1;
            return true;
        }
        if (Ui.inside(mx, my, bx, by + 16, bw, 14)) {
            s.lookV = s.lookV < 0 ? 0 : -1;
            return true;
        }
        if (Ui.inside(mx, my, bx, by + 36, bw, 14)) {
            s.face = s.face.getOpposite();
            return true;
        }
        if (Ui.inside(mx, my, bx, by + 52, bw, 14)) {
            rotate();
            return true;
        }
        if (Ui.inside(mx, my, bx, by + 68, bw, 14)) {
            flipHalf();
            return true;
        }
        if (Ui.inside(mx, my, pvX(), pvY(), PV, PV)) {
            pressed = true;
            dragged = false;
            pressX = mx;
        }
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (pressed) {
            pressed = false;
            if (!dragged) {
                Matrix4f m = rotation(viewYaw());
                Direction d = faceAt(m, mx, my);
                if (d != null) {
                    EditorSession s = s();
                    s.face = d;
                    s.upperHalf = d.getAxis().isHorizontal() && upperHalfOf(screenFace(m, d), mx, my);
                }
            }
            dragged = false;
            return true;
        }
        return contains(mx, my);
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (pressed) {
            if (Math.abs(mx - pressX) > 2) dragged = true;
            if (dragged) orbit += (float) dx * 2.0F;
            return true;
        }
        return contains(mx, my);
    }

    /** R: 바라보는 방향을 시계 방향 90°. */
    public void rotate() {
        EditorSession s = s();
        s.lookH = s.lookH.getClockWise();
    }

    /** F: 옆면이면 위·아래 절반을, 윗면·아랫면이면 면을 뒤집는다 (위쪽 반블록 ↔ 아래쪽 반블록). */
    public void flipHalf() {
        EditorSession s = s();
        if (s.face.getAxis().isHorizontal()) s.upperHalf = !s.upperHalf;
        else s.face = s.face.getOpposite();
    }
}
