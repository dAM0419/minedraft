package dev.designandbuild.client.editor;

import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 편집창 직교 카메라. 방위각(yaw)과 내려다보는 각(pitch, 0 = 정면, 90 = 바로 위)으로 시점을 정한다.
 * 기본은 북동쪽에서 30° 내려다보는 쿼터뷰. 시점 큐브로 면·모서리·꼭짓점 방향으로 바꾼다.
 * 행렬은 카메라 목표점 기준 상대 좌표로 계산한다 (큰 좌표에서도 정밀도 유지).
 */
public final class EditorCamera {
    /** 기본 쿼터뷰의 내려다보는 각. */
    public static final float PITCH = 30.0F;
    public static final float HOME_YAW = 45.0F;
    private static final long ANIM_MS = 200;

    public double tx, ty, tz;
    /** 화면 픽셀(GUI) 당 블록. */
    public float scale = 8.0F;

    /** 목표 시점 (애니메이션이 끝나면 이 값). yaw 는 감지 않고 누적한다 (회전 방향이 자연스럽게). */
    private float targetYaw = HOME_YAW, targetPitch = PITCH;
    private float fromYaw = HOME_YAW, fromPitch = PITCH;
    private long animStart;

    private int width = 1, height = 1;

    public void setViewport(int width, int height) {
        this.width = Math.max(1, width);
        this.height = Math.max(1, height);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    /** 수평으로 90° 단위 회전 (Q/E). 내려다보는 각은 그대로. */
    public void rotate(int delta) {
        startAnim();
        targetYaw += 90.0F * delta;
    }

    /** 이 시점으로 부드럽게 옮긴다. yaw 는 가까운 쪽으로 돈다. */
    public void setView(float yaw, float pitch) {
        startAnim();
        targetYaw = fromYaw + Mth.wrapDegrees(yaw - fromYaw);
        targetPitch = Mth.clamp(pitch, 0.0F, 90.0F);
    }

    /** 기본 쿼터뷰 (북동 30°). */
    public void home() {
        setView(HOME_YAW, PITCH);
    }

    private void startAnim() {
        fromYaw = yaw();
        fromPitch = pitch();
        targetYaw = fromYaw + Mth.wrapDegrees(targetYaw - fromYaw);
        animStart = System.currentTimeMillis();
    }

    private float progress() {
        long t = System.currentTimeMillis() - animStart;
        if (t >= ANIM_MS) return 1.0F;
        float k = t / (float) ANIM_MS;
        return k * k * (3 - 2 * k);
    }

    /** 애니메이션 중인 현재 방위각. */
    public float yaw() {
        return Mth.lerp(progress(), fromYaw, targetYaw);
    }

    /** 애니메이션 중인 현재 내려다보는 각. */
    public float pitch() {
        return Mth.lerp(progress(), fromPitch, targetPitch);
    }

    public float targetYaw() {
        return targetYaw;
    }

    public float targetPitch() {
        return targetPitch;
    }

    /** 방위각 yaw 에서 내려다볼 때, 목표점에서 카메라 쪽을 가리키는 단위 벡터. */
    public static Vector3f toCamera(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vector3f((float) (Math.cos(p) * Math.sin(y)), (float) Math.sin(p), (float) (-Math.cos(p) * Math.cos(y)));
    }

    /** 지금 카메라가 있는 쪽 (목표점 기준 단위 벡터). */
    public Vector3f toCamera() {
        return toCamera(yaw(), pitch());
    }

    /** 수평 방향 (dx, dz) 쪽에서 보는 방위각. */
    public static float yawToward(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(dx, -dz));
    }

    private static final String[] OCTANTS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    /** 지금(목표) 시점 이름: "TOP", "N", "NE · 30°" 처럼. */
    public String viewName() {
        float p = targetPitch;
        if (p >= 89.5F) return "TOP";
        int oct = Math.floorMod(Math.round(Mth.wrapDegrees(targetYaw) / 45.0F), 8);
        String dir = OCTANTS[oct];
        return p <= 0.5F ? dir : dir + " · " + Math.round(p) + "°";
    }

    /** 회전만 담은 뷰 행렬 (목표점 기준). */
    public Matrix4f rotationMatrix() {
        return new Matrix4f()
                .rotateX((float) Math.toRadians(pitch()))
                .rotateY((float) Math.toRadians(yaw() + 180.0F));
    }

    /**
     * 끌기·칠하기·이동의 기준 평면 축. 충분히 내려다보면 수평면(Y), 거의 정면이면 카메라와 마주 보는 세로면(X 또는 Z).
     */
    public Direction.Axis planeAxis() {
        Vector3f c = toCamera();
        if (Math.abs(c.y) >= 0.45F) return Direction.Axis.Y;
        return Math.abs(c.x) >= Math.abs(c.z) ? Direction.Axis.X : Direction.Axis.Z;
    }

    /** 실제 화면 픽셀 당 블록 (GUI 배율 반영). */
    public float realScale() {
        return scale * (float) net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale();
    }

    public Matrix4f projection() {
        float hw = width / 2.0F / scale;
        float hh = height / 2.0F / scale;
        return new Matrix4f().setOrtho(-hw, hw, -hh, hh, -4000.0F, 4000.0F);
    }

    /** 화면 좌표(GUI) 의 시선. origin 은 월드 좌표, direction 은 단위 벡터. */
    public Ray ray(double mx, double my) {
        Matrix4f inv = projection().mul(rotationMatrix()).invert();
        float nx = (float) (mx / width * 2.0 - 1.0);
        float ny = (float) (1.0 - my / height * 2.0);
        Vector4f near = inv.transform(new Vector4f(nx, ny, -1.0F, 1.0F));
        Vector4f far = inv.transform(new Vector4f(nx, ny, 1.0F, 1.0F));
        Vec3 a = new Vec3(near.x / near.w + tx, near.y / near.w + ty, near.z / near.w + tz);
        Vec3 b = new Vec3(far.x / far.w + tx, far.y / far.w + ty, far.z / far.w + tz);
        return new Ray(a, b.subtract(a).normalize());
    }

    /** 월드 좌표를 화면 좌표(GUI)로. 화면 밖이어도 값은 돌려준다. */
    public Vector3f project(double x, double y, double z) {
        Matrix4f m = projection().mul(rotationMatrix());
        Vector4f v = m.transform(new Vector4f((float) (x - tx), (float) (y - ty), (float) (z - tz), 1.0F));
        float sx = (v.x / v.w + 1.0F) * 0.5F * width;
        float sy = (1.0F - v.y / v.w) * 0.5F * height;
        return new Vector3f(sx, sy, v.z / v.w);
    }

    /** 시선과 높이 y 인 수평면의 교점. */
    @Nullable
    public Vec3 groundPoint(double mx, double my, double y) {
        return planePoint(mx, my, Direction.Axis.Y, y);
    }

    /** 시선과 축 평면(axis = value)의 교점. 시선이 평면과 나란하면 null. */
    @Nullable
    public Vec3 planePoint(double mx, double my, Direction.Axis axis, double value) {
        Ray r = ray(mx, my);
        double d = r.dir.get(axis), o = r.origin.get(axis);
        if (Math.abs(d) < 1e-4) return null;
        return r.origin.add(r.dir.scale((value - o) / d));
    }

    /** 화면 위의 점이 목표점 깊이에서 가리키는 월드 점. */
    private Vec3 screenPoint(double mx, double my) {
        Matrix4f inv = projection().mul(rotationMatrix()).invert();
        float nx = (float) (mx / width * 2.0 - 1.0);
        float ny = (float) (1.0 - my / height * 2.0);
        Vector4f v = inv.transform(new Vector4f(nx, ny, 0.0F, 1.0F));
        return new Vec3(v.x / v.w + tx, v.y / v.w + ty, v.z / v.w + tz);
    }

    /**
     * 커서 아래의 기준점. 내려다볼 때는 목표점 높이의 지면 (지도처럼 높이가 바뀌지 않는다),
     * 거의 정면일 때는 화면 평면 (위아래로도 움직인다).
     */
    private Vec3 anchor(double mx, double my) {
        if (pitch() >= 15.0F) {
            Vec3 g = groundPoint(mx, my, ty);
            if (g != null) return g;
        }
        return screenPoint(mx, my);
    }

    /** 화면을 끌어 이동 (끌기 전·후 커서 아래의 점이 같도록). */
    public void pan(double fromX, double fromY, double toX, double toY) {
        Vec3 a = anchor(fromX, fromY);
        Vec3 b = anchor(toX, toY);
        tx += a.x - b.x;
        ty += a.y - b.y;
        tz += a.z - b.z;
    }

    /** 커서 위치를 고정한 채 확대·축소. */
    public void zoom(double mx, double my, double amount) {
        Vec3 before = anchor(mx, my);
        scale = Mth.clamp((float) (scale * Math.pow(1.15, amount)), 1.0F, 72.0F);
        Vec3 after = anchor(mx, my);
        tx += before.x - after.x;
        ty += before.y - after.y;
        tz += before.z - after.z;
    }

    public record Ray(Vec3 origin, Vec3 dir) {}

}
