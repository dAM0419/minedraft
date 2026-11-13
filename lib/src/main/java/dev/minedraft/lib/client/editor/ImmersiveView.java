package dev.minedraft.lib.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * 몰입형 설계: 편집창 뷰포트 안에서 1인칭으로 영역 안을 돌아다니는 시점 (편집창 화면은 그대로).
 * 마우스로 고개를 돌리고 이동 키로 움직인다. 비행(벽 통과)과 걷기(중력·충돌)를 오간다.
 * 각도는 바닐라와 같은 규칙: yaw 0 = 남쪽(+Z), pitch 양수 = 아래.
 */
public final class ImmersiveView {
    /** 블록을 고를 수 있는 거리. */
    public static final double REACH = 6.0;
    private static final float FOV = 70.0F;
    private static final double EYE = 1.62, HALF_W = 0.3, HEIGHT = 1.8, STEP = 0.6;

    private final AreaEditorScreen screen;
    public boolean active;
    /** 비행(벽 통과) / 걷기(중력·충돌). */
    public boolean flying = true;
    /** 눈 위치. */
    public double x, y, z;
    public float yaw, pitch;

    private double vy;
    private boolean onGround;
    private boolean grabbed;
    private double lastMouseX, lastMouseY;
    private long lastNanos;
    private long lastJumpPress;

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
        vy = 0;
        flying = true;
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
        double dt = Math.min(0.05, (now - lastNanos) / 1.0E9);
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
        if (flying) {
            double speed = (sprint ? 22.0 : 11.0) * dt;
            int up = (held(o.keyJump) ? 1 : 0) - (held(o.keyShift) ? 1 : 0);
            x += fx * speed;
            z += fz * speed;
            y += up * speed;
        } else {
            double speed = (sprint ? 5.6 : held(o.keyShift) ? 1.3 : 4.3) * dt;
            if (onGround && held(o.keyJump)) vy = 8.4;
            vy = Math.max(-60, vy - 32.0 * dt);
            move(fx * speed, vy * dt, fz * speed);
        }
        double minY = s().area.minY - 32, maxY = s().area.maxY + 64;
        y = Mth.clamp(y, minY, maxY);
    }

    /** 스페이스 두 번 누르기 = 비행 켜고 끄기 (바닐라 크리에이티브처럼). */
    void jumpPressed() {
        long now = System.currentTimeMillis();
        if (now - lastJumpPress < 300) {
            toggleFlying();
            lastJumpPress = 0;
        } else {
            lastJumpPress = now;
        }
    }

    public void toggleFlying() {
        flying = !flying;
        vy = 0;
    }

    private static boolean held(KeyMapping key) {
        InputConstants.Key k = key.getKey();
        if (k.getType() != InputConstants.Type.KEYSYM) return false;
        return InputConstants.isKeyDown(Minecraft.getInstance().getWindow().getWindow(), k.getValue());
    }

    // ------------------------------------------------------------------
    // 걷기: 충돌 (숨긴 블록도 딛는다 — 구조물만 보여도 땅 위를 걷는다)
    // ------------------------------------------------------------------

    private AABB body() {
        double feet = y - EYE;
        return new AABB(x - HALF_W, feet, z - HALF_W, x + HALF_W, feet + HEIGHT, z + HALF_W);
    }

    private List<VoxelShape> shapesAround(AABB box) {
        List<VoxelShape> out = new ArrayList<>();
        AreaEditorSession ses = s();
        EditorLevel lv = ses.blockEntities.levelOrNull();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int x0 = Mth.floor(box.minX) - 1, x1 = Mth.floor(box.maxX) + 1;
        int y0 = Mth.floor(box.minY) - 1, y1 = Mth.floor(box.maxY) + 1;
        int z0 = Mth.floor(box.minZ) - 1, z1 = Mth.floor(box.maxZ) + 1;
        for (int bx = x0; bx <= x1; bx++)
            for (int by = y0; by <= y1; by++)
                for (int bz = z0; bz <= z1; bz++) {
                    BlockState st = ses.blockAt(bx, by, bz, false);
                    if (st.isAir()) continue;
                    mp.set(bx, by, bz);
                    VoxelShape sh;
                    try {
                        sh = lv != null ? st.getCollisionShape(lv, mp, CollisionContext.empty()) : st.getCollisionShape(ses.world, mp);
                    } catch (Throwable t) {
                        sh = Shapes.block();
                    }
                    if (!sh.isEmpty()) out.add(sh.move(bx, by, bz));
                }
        return out;
    }

    private void move(double dx, double dy, double dz) {
        AABB box = body();
        List<VoxelShape> shapes = shapesAround(box.expandTowards(dx, dy, dz).inflate(STEP));
        double my = Shapes.collide(Direction.Axis.Y, box, shapes, dy);
        box = box.move(0, my, 0);
        boolean landed = dy < 0 && my != dy;
        if (my != dy) vy = 0;
        double mx = Shapes.collide(Direction.Axis.X, box, shapes, dx);
        box = box.move(mx, 0, 0);
        double mz = Shapes.collide(Direction.Axis.Z, box, shapes, dz);
        box = box.move(0, 0, mz);
        // 반 블록·계단 오르기
        if ((landed || onGround) && (mx != dx || mz != dz)) {
            AABB up = body().move(0, my, 0);
            double sy = Shapes.collide(Direction.Axis.Y, up, shapes, STEP);
            AABB stepped = up.move(0, sy, 0);
            double sx = Shapes.collide(Direction.Axis.X, stepped, shapes, dx);
            stepped = stepped.move(sx, 0, 0);
            double sz = Shapes.collide(Direction.Axis.Z, stepped, shapes, dz);
            stepped = stepped.move(0, 0, sz);
            double down = Shapes.collide(Direction.Axis.Y, stepped, shapes, -sy);
            stepped = stepped.move(0, down, 0);
            if (sx * sx + sz * sz > mx * mx + mz * mz) box = stepped;
        }
        onGround = landed;
        x = (box.minX + box.maxX) / 2;
        z = (box.minZ + box.maxZ) / 2;
        y = box.minY + EYE;
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
