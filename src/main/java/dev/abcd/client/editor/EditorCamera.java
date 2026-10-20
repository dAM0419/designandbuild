package dev.abcd.client.editor;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

/**
 * 쿼터뷰 직교 카메라. 회전은 북동·남동·남서·북서 4방향.
 * 행렬은 카메라 목표점 기준 상대 좌표로 계산한다 (큰 좌표에서도 정밀도 유지).
 */
public final class EditorCamera {
    public static final float PITCH = 30.0F;
    /** 0 = 북동, 1 = 남동, 2 = 남서, 3 = 북서 (그 방향에서 내려다본다). */
    public int rotation;
    public double tx, ty, tz;
    /** 화면 픽셀(GUI) 당 블록. */
    public float scale = 8.0F;

    private float yaw = yawOf(0);
    private long animStart;
    private float animFrom = yaw;

    private int width = 1, height = 1;

    public static float yawOf(int rotation) {
        return 45.0F + 90.0F * rotation;
    }

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

    public void rotate(int delta) {
        animFrom = yaw;
        animStart = System.currentTimeMillis();
        rotation = Math.floorMod(rotation + delta, 4);
    }

    /** 애니메이션 중인 현재 방위각. */
    public float yaw() {
        float target = yawOf(rotation);
        long t = System.currentTimeMillis() - animStart;
        if (t >= 160) {
            yaw = target;
            return yaw;
        }
        float d = Mth.wrapDegrees(target - animFrom);
        float k = t / 160.0F;
        k = k * k * (3 - 2 * k);
        yaw = animFrom + d * k;
        return yaw;
    }

    /** 회전만 담은 뷰 행렬 (목표점 기준). */
    public Matrix4f rotationMatrix() {
        return new Matrix4f()
                .rotateX((float) Math.toRadians(PITCH))
                .rotateY((float) Math.toRadians(yaw() + 180.0F));
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

    /** 화면을 향하는 방향 (월드 기준 카메라 앞쪽). */
    public Vec3 forward() {
        return Vec3.directionFromRotation(PITCH, yaw());
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
        Ray r = ray(mx, my);
        if (Math.abs(r.dir.y) < 1e-6) return null;
        double t = (y - r.origin.y) / r.dir.y;
        return r.origin.add(r.dir.scale(t));
    }

    /** 화면을 끌어 이동 (끌기 전·후 커서가 가리키는 지면 점이 같도록). */
    public void pan(double fromX, double fromY, double toX, double toY) {
        Vec3 a = groundPoint(fromX, fromY, ty);
        Vec3 b = groundPoint(toX, toY, ty);
        if (a == null || b == null) return;
        tx += a.x - b.x;
        tz += a.z - b.z;
    }

    /** 커서 위치를 고정한 채 확대·축소. */
    public void zoom(double mx, double my, double amount) {
        Vec3 before = groundPoint(mx, my, ty);
        scale = Mth.clamp((float) (scale * Math.pow(1.15, amount)), 1.0F, 72.0F);
        Vec3 after = groundPoint(mx, my, ty);
        if (before != null && after != null) {
            tx += before.x - after.x;
            tz += before.z - after.z;
        }
    }

    public record Ray(Vec3 origin, Vec3 dir) {}

    /** 화면 위쪽이 가리키는 수평 방위 (0 = 북, 1 = 동, 2 = 남, 3 = 서) — 나침반·삼면도 회전용. */
    public float screenUpYawDegrees() {
        return yaw() + 180.0F;
    }
}
