package dev.minedraft.lib.client.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.minedraft.lib.util.Box;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.Direction;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

/**
 * 공간 태그 범위 손잡이 (공통). 모드가 범위 상자를 정해 주면, 여섯 면의 손잡이를 끌어 늘리고 줄인다.
 * 손잡이는 쿼터뷰에서 그 면의 법선 방향을 화면에 투영해 끈다. 놓으면 {@link AreaEditorScreen#applyTagRange} 로 넘긴다.
 */
final class RangeHandles {
    private static final Direction[] FACES = Direction.values();

    private final AreaEditorScreen screen;
    private int face = -1;
    @Nullable
    private Box start;
    private double startX, startY;
    private int delta;

    RangeHandles(AreaEditorScreen screen) {
        this.screen = screen;
    }

    @Nullable
    private Box box() {
        return screen.immersive.active ? null : screen.tagRangeBox();
    }

    boolean dragging() {
        return face >= 0 && start != null;
    }

    private EditorCamera cam() {
        return screen.base.camera;
    }

    private static double[] faceCenter(Box b, Direction d) {
        double cx = (b.minX() + b.maxX() + 1) / 2.0, cy = (b.minY() + b.maxY() + 1) / 2.0, cz = (b.minZ() + b.maxZ() + 1) / 2.0;
        switch (d) {
            case EAST -> cx = b.maxX() + 1;
            case WEST -> cx = b.minX();
            case UP -> cy = b.maxY() + 1;
            case DOWN -> cy = b.minY();
            case SOUTH -> cz = b.maxZ() + 1;
            default -> cz = b.minZ();
        }
        return new double[]{cx, cy, cz};
    }

    /** 면 d 를 바깥으로 delta 칸 (음수면 안쪽으로). 두께는 1칸 아래로 줄지 않는다. */
    static Box resized(Box b, Direction d, int delta) {
        int x0 = b.minX(), y0 = b.minY(), z0 = b.minZ(), x1 = b.maxX(), y1 = b.maxY(), z1 = b.maxZ();
        switch (d) {
            case EAST -> x1 = Math.max(x0, x1 + delta);
            case WEST -> x0 = Math.min(x1, x0 - delta);
            case UP -> y1 = Math.max(y0, y1 + delta);
            case DOWN -> y0 = Math.min(y1, y0 - delta);
            case SOUTH -> z1 = Math.max(z0, z1 + delta);
            default -> z0 = Math.min(z1, z0 - delta);
        }
        return new Box(x0, y0, z0, x1, y1, z1);
    }

    /** 화살표 시작점이 면 가운데에서 바깥으로 떨어진 거리, 화살표 길이, 누를 수 있는 반지름 (화면 픽셀). */
    private static final float GAP = 6, LEN = 18, HIT = 9;

    /**
     * 면 d 의 화살표: {밑점 x, y, 방향 x, y}. 방향은 면 법선을 화면에 투영한 단위 벡터
     * (법선이 화면을 향하면 방향 0 — 점으로 그리고 끌 수 없다).
     */
    private float[] arrow(Box b, Direction d) {
        double[] c = faceCenter(b, d);
        Vector3f p0 = cam().project(c[0], c[1], c[2]);
        Vector3f p1 = cam().project(c[0] + d.getStepX(), c[1] + d.getStepY(), c[2] + d.getStepZ());
        float dx = p1.x - p0.x, dy = p1.y - p0.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5F) return new float[]{p0.x, p0.y, 0, 0};
        dx /= len;
        dy /= len;
        return new float[]{p0.x + dx * GAP, p0.y + dy * GAP, dx, dy};
    }

    /** 커서 아래의 화살표 면 번호 (-1 = 없음). 화살표 몸통 가운데에서 가장 가까운 것. */
    private int hovered(Box b, double mx, double my) {
        int best = -1;
        double bestD = HIT * HIT;
        for (int i = 0; i < FACES.length; i++) {
            float[] a = arrow(b, FACES[i]);
            if (a[2] == 0 && a[3] == 0) continue;
            double cx = a[0] + a[2] * LEN / 2, cy = a[1] + a[3] * LEN / 2;
            // 몸통 선분까지의 거리
            double t = Math.max(-LEN / 2, Math.min(LEN / 2, (mx - cx) * a[2] + (my - cy) * a[3]));
            double px = cx + a[2] * t - mx, py = cy + a[3] * t - my;
            double d = px * px + py * py;
            if (d < bestD) {
                bestD = d;
                best = i;
            }
        }
        return best;
    }

    /** 지금 보여 줄 범위 (끄는 중이면 바뀐 범위). */
    @Nullable
    Box shown() {
        if (dragging()) return resized(start, FACES[face], delta);
        return box();
    }

    boolean overHandle() {
        Box b = box();
        return b != null && hovered(b, screen.mouseX(), screen.mouseY()) >= 0;
    }

    boolean pressed(double mx, double my, int button) {
        Box b = box();
        if (b == null || button != 0) return false;
        int f = hovered(b, mx, my);
        if (f < 0) return false;
        face = f;
        start = b;
        startX = mx;
        startY = my;
        delta = 0;
        return true;
    }

    boolean dragged(double mx, double my) {
        if (!dragging()) return false;
        Direction d = FACES[face];
        double[] c = faceCenter(start, d);
        Vector3f p0 = cam().project(c[0], c[1], c[2]);
        Vector3f p1 = cam().project(c[0] + d.getStepX(), c[1] + d.getStepY(), c[2] + d.getStepZ());
        double dx = p1.x - p0.x, dy = p1.y - p0.y, len2 = dx * dx + dy * dy;
        if (len2 < 1) return true; // 이 축이 화면을 향해 있다: 옆에서 보고 끌어야 한다
        delta = (int) Math.round(((mx - startX) * dx + (my - startY) * dy) / len2);
        return true;
    }

    /** 놓았다: 바뀐 범위를 돌려준다 (끌던 중이 아니면 null). */
    @Nullable
    Box released() {
        if (!dragging()) return null;
        Box nb = resized(start, FACES[face], delta);
        face = -1;
        start = null;
        delta = 0;
        return nb;
    }

    /** 3D: 범위 상자 (가리는 블록 뒤는 see-through 선으로 옅게, 앞은 진하게). */
    void drawBox(VertexConsumer lines, PoseStack ps, double tx, double ty, double tz, int alpha) {
        Box b = shown();
        if (b == null) return;
        int c = screen.tagRangeColor();
        ViewportRenderer.box(lines, ps, b, tx, ty, tz, (c & 0xFFFFFF) | (alpha << 24));
    }

    /** 2D: 여섯 면의 화살표. 블록 위에 그리므로 가려지지 않는다. */
    void drawArrows(GuiGraphics g) {
        Box b = shown();
        if (b == null) return;
        int hov = dragging() ? face : hovered(b, screen.mouseX(), screen.mouseY());
        int accent = 0xFF000000 | screen.tagRangeColor();
        for (int i = 0; i < FACES.length; i++) {
            float[] a = arrow(b, FACES[i]);
            boolean h = i == hov;
            if (a[2] == 0 && a[3] == 0) {
                // 화면을 향한 면: 끌 수 없다는 표시로 작은 점
                g.fill((int) a[0] - 2, (int) a[1] - 2, (int) a[0] + 2, (int) a[1] + 2, 0xA0000000);
                g.fill((int) a[0] - 1, (int) a[1] - 1, (int) a[0] + 1, (int) a[1] + 1, 0xC0FFFFFF);
                continue;
            }
            float ang = (float) Math.atan2(a[3], a[2]);
            float scale = h ? 1.25F : 1.0F;
            g.pose().pushPose();
            g.pose().translate(a[0], a[1], 0);
            g.pose().mulPose(com.mojang.math.Axis.ZP.rotation(ang));
            g.pose().scale(scale, scale, 1);
            arrowShape(g, 1, 0xC0000000);        // 테두리
            arrowShape(g, 0, h ? accent : 0xFFFFFFFF);
            g.pose().popPose();
        }
    }

    /** x 축 방향 화살표 (밑점 0, 끝 LEN). grow 만큼 두껍게 (테두리). */
    private static void arrowShape(GuiGraphics g, int grow, int color) {
        int len = (int) LEN, head = 7;
        // 몸통
        g.fill(-grow, -1 - grow, len - head + 1, 2 + grow, color);
        // 머리: 세로 줄을 끝으로 갈수록 좁게
        for (int i = 0; i <= head + grow; i++) {
            int x = len - head + i - grow;
            int hw = head - i + grow;
            if (hw < 0) break;
            g.fill(x, -hw, x + 1, hw + 1, color);
        }
    }
}
