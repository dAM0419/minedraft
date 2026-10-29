package dev.minedraft.lib.client.editor.ui;

import dev.minedraft.lib.client.editor.ui.UiPart;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.minedraft.lib.client.editor.EditorCamera;
import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.ui.Icons;
import dev.minedraft.lib.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * 시점 큐브 (인벤터 ViewCube 식). 뷰포트 오른쪽 위에 지금 시점대로 돌아간 정육면체를 그린다.
 * 면·모서리·꼭짓점 칸을 누르면 그쪽에서 보는 시점으로 부드럽게 바뀐다. 아래쪽 절반(밑에서 올려다보기)은 쓰지 않는다.
 *
 * 칸은 방향 벡터 (sx, sy, sz) ∈ {-1, 0, 1}³ 로 구분한다. 면 하나를 3×3 으로 나눠, 가운데 = 면, 가장자리 = 모서리, 귀퉁이 = 꼭짓점.
 * 시점: 윗면 = 바로 위, 옆면·세로 모서리 = 정면(0°), 위 모서리 = 45°, 위 꼭짓점 = 30° (기본 쿼터뷰와 같은 기울기).
 */
public final class ViewCube extends UiPart<AreaEditorScreen> {
    /** 정육면체 반 변 길이 (화면 픽셀). */
    private static final float HALF = 17.0F;
    /** 모서리·꼭짓점 띠의 두께 (반 변 대비). */
    private static final float BAND = 0.32F;
    private static final int CUBE_H = 62, BTN = 14;

    private static final int FACE = 0xFFD4DAE2, BORDER = 0xFFB4BCC8, DISABLED = 0xFF5A626E, LINE = 0xFF1C222A;

    /** 면: 법선 축(0 = x, 1 = y, 2 = z), 부호, 이름. 아래 면은 그리지 않는다 (아래에서 보는 일이 없다). */
    private static final int[][] FACES = {{1, 1}, {2, -1}, {0, 1}, {2, 1}, {0, -1}};
    private static final String[] LABELS = {"TOP", "N", "E", "S", "W"};

    @Nullable
    private int[] hovered;

    public ViewCube(AreaEditorScreen screen) {
        super(screen);
    }

    private float cx() {
        return x + w / 2.0F;
    }

    private float cy() {
        return y + CUBE_H / 2.0F + 2;
    }

    private record Cell(int[] region, float[][] quad, float light) {}

    /** 지금 보이는 칸들을 화면 좌표로. */
    private List<Cell> cells() {
        EditorCamera cam = s().camera;
        Matrix4f m = cam.rotationMatrix();
        Vector3f toCam = cam.toCamera();
        List<Cell> out = new ArrayList<>();
        float[] cuts = {-1.0F, -1.0F + BAND, 1.0F - BAND, 1.0F};
        for (int[] f : FACES) {
            int axis = f[0], sign = f[1];
            float[] n = new float[3];
            n[axis] = sign;
            float facing = n[0] * toCam.x + n[1] * toCam.y + n[2] * toCam.z;
            if (facing <= 0.02F) continue;
            int ua = (axis + 1) % 3, va = (axis + 2) % 3;
            float light = 0.62F + 0.38F * facing;
            for (int i = 0; i < 3; i++) {
                for (int j = 0; j < 3; j++) {
                    int[] region = new int[3];
                    region[axis] = sign;
                    region[ua] = i - 1;
                    region[va] = j - 1;
                    float[][] q = new float[4][];
                    float[][] uv = {{cuts[i], cuts[j]}, {cuts[i + 1], cuts[j]}, {cuts[i + 1], cuts[j + 1]}, {cuts[i], cuts[j + 1]}};
                    for (int k = 0; k < 4; k++) {
                        float[] p = new float[3];
                        p[axis] = sign;
                        p[ua] = uv[k][0];
                        p[va] = uv[k][1];
                        Vector3f v = m.transformPosition(new Vector3f(p[0], p[1], p[2]));
                        q[k] = new float[]{cx() + v.x * HALF, cy() - v.y * HALF};
                    }
                    out.add(new Cell(region, q, light));
                }
            }
        }
        return out;
    }

    private static boolean enabled(int[] region) {
        return region[1] >= 0;
    }

    private static boolean same(@Nullable int[] a, int[] b) {
        return a != null && a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        List<Cell> cells = cells();
        hovered = null;
        if (Ui.inside(mx, my, x, y, w, CUBE_H + 4)) {
            for (Cell c : cells) {
                if (enabled(c.region) && inside(c.quad, mx, my)) {
                    hovered = c.region;
                    break;
                }
            }
        }
        for (Cell c : cells) {
            boolean center = Math.abs(c.region[0]) + Math.abs(c.region[1]) + Math.abs(c.region[2]) == 1;
            int base = !enabled(c.region) ? DISABLED : center ? FACE : BORDER;
            int col = same(hovered, c.region) ? Ui.ACCENT : shade(base, c.light);
            quad(g, c.quad, col);
        }
        // 칸 경계선
        for (Cell c : cells) {
            for (int k = 0; k < 4; k++) line(g, c.quad[k], c.quad[(k + 1) % 4], 0x50000000);
        }
        drawLabels(g);
        // 아래 버튼: 왼쪽 회전 · 기본 시점 · 오른쪽 회전
        int by = y + CUBE_H + 4;
        int bx = (int) cx() - (BTN * 3 + 4) / 2;
        int[] icons = {Icons.ROTATE_L, Icons.COMPASS, Icons.ROTATE_R};
        String[] tips = {"minedraftlib.viewcube.rotate_left", "minedraftlib.viewcube.home", "minedraftlib.viewcube.rotate_right"};
        for (int i = 0; i < 3; i++) {
            int ix = bx + i * (BTN + 2);
            boolean h = Ui.inside(mx, my, ix, by, BTN, BTN);
            Ui.iconButton(g, ix, by, BTN, BTN, icons[i], false, h, true);
            if (h) screen.tooltip = List.of(Component.translatable(tips[i]));
        }
        if (hovered != null) {
            screen.tooltip = List.of(Component.translatable("minedraftlib.editor.view_name", nameOf(hovered)),
                    Component.translatable("minedraftlib.viewcube.tip").withStyle(st -> st.withColor(Ui.DIM & 0xFFFFFF)));
        }
    }

    private void drawLabels(GuiGraphics g) {
        EditorCamera cam = s().camera;
        Matrix4f m = cam.rotationMatrix();
        Vector3f toCam = cam.toCamera();
        for (int i = 0; i < FACES.length; i++) {
            float[] n = new float[3];
            n[FACES[i][0]] = FACES[i][1];
            float facing = n[0] * toCam.x + n[1] * toCam.y + n[2] * toCam.z;
            if (facing < 0.3F) continue;
            Vector3f v = m.transformPosition(new Vector3f(n[0], n[1], n[2]));
            String s = LABELS[i];
            int tx = Math.round(cx() + v.x * HALF - Ui.font().width(s) / 2.0F);
            int ty = Math.round(cy() - v.y * HALF - 4);
            g.pose().pushPose();
            g.pose().translate(0, 0, 210);
            g.drawString(Ui.font(), s, tx, ty, i == 1 ? 0xFFB03030 : LINE, false);
            g.pose().popPose();
        }
    }

    /** 칸이 가리키는 시점 이름 (툴팁용). */
    private static String nameOf(int[] r) {
        if (r[0] == 0 && r[2] == 0) return "TOP";
        String ns = r[2] < 0 ? "N" : r[2] > 0 ? "S" : "";
        String ew = r[0] > 0 ? "E" : r[0] < 0 ? "W" : "";
        String dir = ns + ew;
        if (r[1] <= 0) return dir;
        return dir + " · " + (r[0] != 0 && r[2] != 0 ? 30 : 45) + "°";
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        EditorCamera cam = s().camera;
        int by = y + CUBE_H + 4;
        int bx = (int) cx() - (BTN * 3 + 4) / 2;
        if (Ui.inside(mx, my, bx, by, BTN, BTN)) {
            cam.rotate(-1);
            return true;
        }
        if (Ui.inside(mx, my, bx + BTN + 2, by, BTN, BTN)) {
            cam.home();
            return true;
        }
        if (Ui.inside(mx, my, bx + 2 * (BTN + 2), by, BTN, BTN)) {
            cam.rotate(1);
            return true;
        }
        for (Cell c : cells()) {
            if (!inside(c.quad, mx, my)) continue;
            if (enabled(c.region)) go(cam, c.region);
            return true;
        }
        return Ui.inside(mx, my, x, y, w, h);
    }

    /** 칸 방향에서 보는 시점으로. */
    private static void go(EditorCamera cam, int[] r) {
        if (r[0] == 0 && r[2] == 0) {
            // 바로 위: 지금 방위를 90° 단위로 맞춰 평면도가 축에 나란하게
            float yaw = Math.round(cam.targetYaw() / 90.0F) * 90.0F;
            cam.setView(yaw, 90.0F);
            return;
        }
        float yaw = EditorCamera.yawToward(r[0], r[2]);
        float pitch = r[1] <= 0 ? 0.0F : (r[0] != 0 && r[2] != 0 ? EditorCamera.PITCH : 45.0F);
        cam.setView(yaw, pitch);
    }

    // ------------------------------------------------------------------

    private static int shade(int color, float k) {
        int r = Mth.clamp(Math.round(((color >> 16) & 0xFF) * k), 0, 255);
        int gg = Mth.clamp(Math.round(((color >> 8) & 0xFF) * k), 0, 255);
        int b = Mth.clamp(Math.round((color & 0xFF) * k), 0, 255);
        return (color & 0xFF000000) | (r << 16) | (gg << 8) | b;
    }

    /** 볼록 사각형 안인가 (두 삼각형). */
    private static boolean inside(float[][] q, double px, double py) {
        return inTri(q[0], q[1], q[2], px, py) || inTri(q[0], q[2], q[3], px, py);
    }

    private static boolean inTri(float[] a, float[] b, float[] c, double px, double py) {
        double d1 = (px - b[0]) * (a[1] - b[1]) - (a[0] - b[0]) * (py - b[1]);
        double d2 = (px - c[0]) * (b[1] - c[1]) - (b[0] - c[0]) * (py - c[1]);
        double d3 = (px - a[0]) * (c[1] - a[1]) - (c[0] - a[0]) * (py - a[1]);
        boolean neg = d1 < 0 || d2 < 0 || d3 < 0, pos = d1 > 0 || d2 > 0 || d3 > 0;
        return !(neg && pos);
    }

    private static void quad(GuiGraphics g, float[][] q, int color) {
        Matrix4f pose = g.pose().last().pose();
        VertexConsumer vc = g.bufferSource().getBuffer(RenderType.gui());
        for (int i = 0; i < 4; i++) vc.addVertex(pose, q[i][0], q[i][1], 200).setColor(color);
        for (int i = 3; i >= 0; i--) vc.addVertex(pose, q[i][0], q[i][1], 200).setColor(color);
        g.flush();
    }

    private static void line(GuiGraphics g, float[] a, float[] b, int color) {
        float dx = b[0] - a[0], dy = b[1] - a[1];
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 1e-3F) return;
        float nx = -dy / len * 0.4F, ny = dx / len * 0.4F;
        quad(g, new float[][]{{a[0] + nx, a[1] + ny}, {b[0] + nx, b[1] + ny}, {b[0] - nx, b[1] - ny}, {a[0] - nx, a[1] - ny}}, color);
    }
}
