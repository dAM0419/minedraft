package dev.minedraft.lib.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;


/**
 * 몰입형 설계: 편집창 뷰포트 안에서 1인칭으로 영역 안을 돌아다니는 시점 (편집창 화면은 그대로).
 * 마우스로 고개를 돌리고 이동 키로 움직인다. 막힘 없는 비행만 (벽 통과, 중력 없음).
 * 각도는 바닐라와 같은 규칙: yaw 0 = 남쪽(+Z), pitch 양수 = 아래.
 */
public final class ImmersiveView {
    /** 블록을 고를 수 있는 거리. */
    public static final double REACH = 6.0;
    private static final float FOV = 70.0F;

    private final AreaEditorScreen screen;
    public boolean active;
    /** 눈 위치. */
    public double x, y, z;
    public float yaw, pitch;

    private boolean grabbed;
    private double lastMouseX, lastMouseY;
    private long lastNanos;

    ImmersiveView(AreaEditorScreen screen) {
        this.screen = screen;
    }

    private AreaEditorSession s() {
        return screen.session();
    }

    // ------------------------------------------------------------------
    // 들어가기·나오기
    // ------------------------------------------------------------------

    /** 지금 쿼터뷰가 보던 곳(모드가 정한 대상이 있으면 그 가운데)을 같은 방향에서 바라보는 자리로 들어간다. */
    void enter() {
        EditorCamera c = s().camera;
        double cx = c.tx, cy = c.ty, cz = c.tz, dist = 12;
        var focus = s().focusBounds();
        if (focus != null) {
            cx = (focus.minX() + focus.maxX() + 1) / 2.0;
            cy = (focus.minY() + focus.maxY() + 1) / 2.0;
            cz = (focus.minZ() + focus.maxZ() + 1) / 2.0;
            dist = Math.max(4, Math.max(focus.sizeX(), Math.max(focus.sizeY(), focus.sizeZ())) * 0.9 + 3);
        }
        yaw = c.yaw();
        pitch = Mth.clamp(c.pitch(), -89.9F, 89.9F);
        Vec3 look = look();
        x = cx - look.x * dist;
        y = cy - look.y * dist;
        z = cz - look.z * dist;
        active = true;
        lastNanos = System.nanoTime();
    }

    /** 나오면 쿼터뷰가 지금 보던 곳을 가운데로 둔다 (방향·확대는 들어가기 전 그대로). */
    void exit() {
        if (!active) return;
        active = false;
        release();
        Vec3 look = look();
        EditorCamera c = s().camera;
        c.tx = x + look.x * 8;
        c.ty = y + look.y * 8;
        c.tz = z + look.z * 8;
    }

    // ------------------------------------------------------------------
    // 마우스 잡기
    // ------------------------------------------------------------------

    /** 창(모달)이 없고 창이 활성이면 마우스를 잡고(커서 숨김), 아니면 놓는다. 매 프레임. */
    void updateGrab(boolean wantGrab) {
        Minecraft mc = Minecraft.getInstance();
        long win = mc.getWindow().getWindow();
        boolean want = active && wantGrab && mc.isWindowActive();
        // 창을 닫은 클릭이 아직 눌려 있으면 다 뗄 때까지 기다린다 (그 클릭이 몰입형 클릭으로 넘어오지 않게)
        if (want && !grabbed && anyMouseDown(win)) return;
        if (want && !grabbed) {
            GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_DISABLED);
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(win, mx, my);
            lastMouseX = mx[0];
            lastMouseY = my[0];
            grabbed = true;
        } else if (!want && grabbed) {
            release();
        }
    }

    private static boolean anyMouseDown(long win) {
        for (int b = GLFW.GLFW_MOUSE_BUTTON_1; b <= GLFW.GLFW_MOUSE_BUTTON_3; b++) {
            if (GLFW.glfwGetMouseButton(win, b) == GLFW.GLFW_PRESS) return true;
        }
        return false;
    }

    /**
     * 지금 움직일 수 없는 까닭 (번역 키, 없으면 null). 몰입형 화면 가운데에 띄워 멈춘 까닭을 알 수 있게 한다.
     */
    @org.jetbrains.annotations.Nullable
    String blockedReason() {
        if (!active) return null;
        if (screen.modal != null) return null; // 창이 떠 있는 건 눈에 보인다
        if (screen.focusedBox() != null) return "minedraftlib.immersive.blocked_focus";
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isWindowActive()) return "minedraftlib.immersive.blocked_window";
        if (!grabbed) return "minedraftlib.immersive.blocked_mouse";
        return null;
    }

    void release() {
        if (!grabbed) return;
        grabbed = false;
        Minecraft mc = Minecraft.getInstance();
        long win = mc.getWindow().getWindow();
        GLFW.glfwSetInputMode(win, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        // 커서는 뷰포트 가운데로 (조준점이 있던 자리)
        double scale = mc.getWindow().getGuiScale();
        GLFW.glfwSetCursorPos(win, (screen.vpX + screen.vpW / 2.0) * scale, (screen.vpY + screen.vpH / 2.0) * scale);
    }

    public boolean grabbed() {
        return grabbed;
    }

    // ------------------------------------------------------------------
    // 매 프레임: 고개·이동
    // ------------------------------------------------------------------

    void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.1, (now - lastNanos) / 1.0E9);
        lastNanos = now;
        Minecraft mc = Minecraft.getInstance();
        if (grabbed) {
            double[] mx = new double[1], my = new double[1];
            GLFW.glfwGetCursorPos(mc.getWindow().getWindow(), mx, my);
            double dx = mx[0] - lastMouseX, dy = my[0] - lastMouseY;
            lastMouseX = mx[0];
            lastMouseY = my[0];
            // 바닐라 감도 계산
            double sens = mc.options.sensitivity().get() * 0.6 + 0.2;
            double k = sens * sens * sens * 8.0 * 0.15;
            yaw += (float) (dx * k);
            pitch = Mth.clamp(pitch + (float) (dy * k * (mc.options.invertYMouse().get() ? -1 : 1)), -90.0F, 90.0F);
        }
        if (screen.modal != null || screen.focusedBox() != null) return;
        var o = mc.options;
        int fwd = 0, side = 0;
        if (held(o.keyUp)) fwd++;
        if (held(o.keyDown)) fwd--;
        if (held(o.keyLeft)) side++;
        if (held(o.keyRight)) side--;
        boolean sprint = held(o.keySprint);
        double fx = 0, fz = 0;
        if (fwd != 0 || side != 0) {
            double r = Math.toRadians(yaw);
            double sin = Math.sin(r), cos = Math.cos(r);
            // 앞 = (-sin, cos), 왼쪽 = (cos, sin)
            double len = Math.sqrt(fwd * fwd + side * side);
            fx = (-sin * fwd + cos * side) / len;
            fz = (cos * fwd + sin * side) / len;
        }
        double speed = (sprint ? 22.0 : 11.0) * dt;
        int up = (held(o.keyJump) ? 1 : 0) - (held(o.keyShift) ? 1 : 0);
        x += fx * speed;
        z += fz * speed;
        y += up * speed;
        double minY = s().area.minY - 32, maxY = s().area.maxY + 64;
        y = Mth.clamp(y, minY, maxY);
    }

    private static boolean held(KeyMapping key) {
        InputConstants.Key k = key.getKey();
        if (k.getType() != InputConstants.Type.KEYSYM) return false;
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), k.getValue());
    }

    // ------------------------------------------------------------------
    // 시선·행렬
    // ------------------------------------------------------------------

    public Vec3 look() {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        return new Vec3(-Math.sin(yr) * Math.cos(pr), -Math.sin(pr), Math.cos(yr) * Math.cos(pr));
    }

    public Matrix4f rotationMatrix() {
        return new Matrix4f().rotateX((float) Math.toRadians(pitch)).rotateY((float) Math.toRadians(yaw + 180.0F));
    }

    /** 원근 투영: 화면 전체에 그리되 시선 중심이 뷰포트 가운데에 오도록 비대칭으로. */
    public Matrix4f projection() {
        float w = screen.width, h = screen.height;
        float near = 0.05F, far = 1024.0F;
        float top = near * (float) Math.tan(Math.toRadians(FOV / 2.0));
        float perPx = top / Math.max(1.0F, screen.vpH / 2.0F);
        float cx = screen.vpX + screen.vpW / 2.0F, cy = screen.vpY + screen.vpH / 2.0F;
        return new Matrix4f().setFrustum(-cx * perPx, (w - cx) * perPx, -(h - cy) * perPx, cy * perPx, near, far);
    }

    /** 바닐라 설치 판정용 가로 방향. */
    public Direction horizontalFacing() {
        return Direction.fromYRot(yaw);
    }
}
