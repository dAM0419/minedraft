package dev.minedraft.lib.client.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.minedraft.lib.util.Box;
import net.minecraft.client.renderer.LevelRenderer;
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

    /** 커서 근처(8픽셀)의 손잡이 면 번호 (-1 = 없음). */
    private int hovered(Box b, double mx, double my) {
        int best = -1;
        double bestD = 64;
        for (int i = 0; i < FACES.length; i++) {
            double[] c = faceCenter(b, FACES[i]);
            Vector3f p = cam().project(c[0], c[1], c[2]);
            double d = (p.x - mx) * (p.x - mx) + (p.y - my) * (p.y - my);
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

    void draw(VertexConsumer lines, PoseStack ps, double tx, double ty, double tz) {
        Box b = shown();
        if (b == null) return;
        ViewportRenderer.box(lines, ps, b, tx, ty, tz, screen.tagRangeColor());
        int hov = dragging() ? face : hovered(b, screen.mouseX(), screen.mouseY());
        for (int i = 0; i < FACES.length; i++) {
            double[] c = faceCenter(b, FACES[i]);
            double r = i == hov ? 0.3 : 0.18;
            float a = i == hov ? 1.0F : 0.75F;
            LevelRenderer.renderLineBox(ps, lines, c[0] - r - tx, c[1] - r - ty, c[2] - r - tz,
                    c[0] + r - tx, c[1] + r - ty, c[2] + r - tz, 1.0F, 1.0F, 1.0F, a);
        }
    }
}
