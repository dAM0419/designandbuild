package dev.designandbuild.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.designandbuild.DesignAndBuild;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.Direction;

/**
 * 정점 위치를 옮기고 투명도를 곱하는 감싸개.
 * 또 블록 면 음영(위 1.0 / 남북 0.8 / 동서 0.6 / 아래 0.5)이 구워지다 빠진 면을 되살린다:
 * 다른 모드가 모델 렌더러의 밝기 값을 가로채 음영이 사라진 경우에도 편집창에서 면이 구분되게 한다.
 */
public final class ShiftAlphaConsumer implements VertexConsumer {
    private VertexConsumer delegate;
    private float dx, dy, dz;
    private float alpha = 1.0F;
    private static boolean loggedRepair;

    private static float faceShade(Direction d) {
        return switch (d) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }

    @Override
    public void putBulkData(PoseStack.Pose pose, BakedQuad quad, float[] brightness, float red, float green,
                            float blue, float a, int[] lightmap, int overlay, boolean readAlpha) {
        if (quad.isShade()) {
            float ds = faceShade(quad.getDirection());
            if (ds < 1.0F) {
                // 음영이 제대로 적용됐다면 어떤 정점도 면 음영보다 밝을 수 없다
                boolean lost = false;
                for (float b : brightness) if (b > ds + 0.02F) lost = true;
                if (lost) {
                    float[] fixed = new float[brightness.length];
                    for (int i = 0; i < fixed.length; i++) fixed[i] = brightness[i] * ds;
                    brightness = fixed;
                    if (!loggedRepair) {
                        loggedRepair = true;
                        DesignAndBuild.LOGGER.info("[DaB] 구워진 면 음영이 빠져 있어 되살렸습니다 (셰이더/렌더 모드 영향 추정).");
                    }
                }
            }
        }
        VertexConsumer.super.putBulkData(pose, quad, brightness, red, green, blue, a, lightmap, overlay, readAlpha);
    }

    public ShiftAlphaConsumer set(VertexConsumer delegate, float dx, float dy, float dz, float alpha) {
        this.delegate = delegate;
        this.dx = dx;
        this.dy = dy;
        this.dz = dz;
        this.alpha = alpha;
        return this;
    }

    @Override
    public VertexConsumer addVertex(float x, float y, float z) {
        delegate.addVertex(x + dx, y + dy, z + dz);
        return this;
    }

    @Override
    public VertexConsumer setColor(int red, int green, int blue, int a) {
        delegate.setColor(red, green, blue, alpha >= 1.0F ? a : (int) (a * alpha));
        return this;
    }

    @Override
    public VertexConsumer setUv(float u, float v) {
        delegate.setUv(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv1(int u, int v) {
        delegate.setUv1(u, v);
        return this;
    }

    @Override
    public VertexConsumer setUv2(int u, int v) {
        delegate.setUv2(u, v);
        return this;
    }

    @Override
    public VertexConsumer setNormal(float x, float y, float z) {
        delegate.setNormal(x, y, z);
        return this;
    }
}
