package dev.minedraft.lib.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;

/** 정점 위치를 옮기고 투명도를 곱하는 감싸개. */
public final class ShiftAlphaConsumer implements VertexConsumer {
    private VertexConsumer delegate;
    private float dx, dy, dz;
    private float alpha = 1.0F;

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
