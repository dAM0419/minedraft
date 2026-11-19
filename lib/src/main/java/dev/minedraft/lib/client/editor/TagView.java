package dev.minedraft.lib.client.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.minedraft.lib.client.render.LibRenderTypes;
import dev.minedraft.lib.util.Box;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * 공간 태그 보기 (T): 태그가 붙은 칸을 태그 색 상자로 덮는다 (입체). 같은 색 칸끼리 맞닿은 면은 빼서
 * 한 덩어리로 보이게 하고, 한 칸에 여러 태그가 겹치면 색마다 조금씩 크게 덮어 함께 보이게 한다.
 * 칸 목록은 세션이 정한다 ({@link AreaEditorSession#forEachTagCell}). 영역 판·단면이 바뀔 때만 다시 굽는다.
 */
public final class TagView {
    private TagView() {}

    /** 한 번에 굽는 최대 칸 수, 색 수. */
    private static final int LIMIT = 150_000, MAX_COLORS = 32;

    private static void quad(BufferBuilder b, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int r, int g, int bl, int a) {
        b.addVertex(ax, ay, az).setColor(r, g, bl, a);
        b.addVertex(bx, by, bz).setColor(r, g, bl, a);
        b.addVertex(cx, cy, cz).setColor(r, g, bl, a);
        b.addVertex(dx, dy, dz).setColor(r, g, bl, a);
    }

    private static VertexBuffer buffer;
    private static long key = Long.MIN_VALUE;
    private static int ox, oy, oz;
    /** 칠하는 중처럼 잇달아 바뀔 때 다시 굽는 최소 간격 (밀리초). 그 사이엔 앞서 구운 것을 그린다. */
    private static final long REBAKE_MS = 150;
    private static long bakedAt;

    /** 편집창을 닫을 때: 버퍼를 버린다 (다음 편집창의 다른 영역과 섞이지 않게). */
    public static void release() {
        if (buffer != null) buffer.close();
        buffer = null;
        key = Long.MIN_VALUE;
    }

    static void draw(AreaEditorSession s, Matrix4f rot, Matrix4f proj) {
        long k = s.tagViewKey();
        long now = System.currentTimeMillis();
        if (buffer == null && key == k) return; // 덮을 칸이 없었다
        if (k != key && (buffer == null || now - bakedAt >= REBAKE_MS)) {
            key = k;
            bakedAt = now;
            if (buffer != null) buffer.close();
            buffer = null;
            Box a = s.area.areaBounds();
            ox = a.minX();
            oy = s.area.minY;
            oz = a.minZ();
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            // 색별 칸 모음 (겹친 태그는 색이 다르다)
            it.unimi.dsi.fastutil.ints.IntArrayList colors = new it.unimi.dsi.fastutil.ints.IntArrayList();
            java.util.List<it.unimi.dsi.fastutil.longs.LongOpenHashSet> sets = new java.util.ArrayList<>();
            int[] n = {0};
            s.forEachTagCell((x, y, z, color) -> {
                if (n[0] >= LIMIT) return false;
                int ci = colors.indexOf(color);
                if (ci < 0) {
                    if (colors.size() >= MAX_COLORS) return true;
                    ci = colors.size();
                    colors.add(color);
                    sets.add(new it.unimi.dsi.fastutil.longs.LongOpenHashSet());
                }
                if (sets.get(ci).add(net.minecraft.core.BlockPos.asLong(x, y, z))) n[0]++;
                return true;
            });
            for (int ci = 0; ci < colors.size(); ci++) {
                int color = colors.getInt(ci);
                int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF, ca = (color >>> 24) & 0xFF;
                float e = 0.006F + 0.004F * ci; // 색마다 조금씩 크게 (겹친 면이 깜빡이지 않게)
                it.unimi.dsi.fastutil.longs.LongOpenHashSet set = sets.get(ci);
                for (long p : set) {
                    int x = net.minecraft.core.BlockPos.getX(p), y = net.minecraft.core.BlockPos.getY(p), z = net.minecraft.core.BlockPos.getZ(p);
                    float x0 = x - ox - e, y0 = y - oy - e, z0 = z - oz - e;
                    float x1 = x - ox + 1 + e, y1 = y - oy + 1 + e, z1 = z - oz + 1 + e;
                    // 같은 색 이웃이 없는 면만
                    if (!set.contains(net.minecraft.core.BlockPos.asLong(x, y + 1, z))) quad(b, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, cr, cg, cb, ca);
                    if (!set.contains(net.minecraft.core.BlockPos.asLong(x, y - 1, z))) quad(b, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, cr, cg, cb, ca);
                    if (!set.contains(net.minecraft.core.BlockPos.asLong(x, y, z - 1))) quad(b, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, cr, cg, cb, ca);
                    if (!set.contains(net.minecraft.core.BlockPos.asLong(x, y, z + 1))) quad(b, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, cr, cg, cb, ca);
                    if (!set.contains(net.minecraft.core.BlockPos.asLong(x - 1, y, z))) quad(b, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, cr, cg, cb, ca);
                    if (!set.contains(net.minecraft.core.BlockPos.asLong(x + 1, y, z))) quad(b, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, cr, cg, cb, ca);
                }
            }
            var mesh = b.build();
            if (mesh != null) {
                buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                buffer.bind();
                buffer.upload(mesh);
                VertexBuffer.unbind();
            }
        }
        if (buffer == null) return;
        Matrix4f mv = new Matrix4f(rot).translate((float) (ox - s.camera.tx), (float) (oy - s.camera.ty), (float) (oz - s.camera.tz));
        LibRenderTypes.TAG_BOXES.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            buffer.bind();
            buffer.drawWithShader(mv, proj, shader);
            VertexBuffer.unbind();
        }
        LibRenderTypes.TAG_BOXES.clearRenderState();
    }
}
