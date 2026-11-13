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
 * 공간 태그 보기 (T): 태그가 붙은 칸 윗면을 태그 색으로 덮는다. 칸 목록은 세션이 정한다
 * ({@link AreaEditorSession#forEachTagCell}). 영역 판·단면이 바뀔 때만 다시 굽는다.
 */
public final class TagView {
    private TagView() {}

    /** 한 번에 굽는 최대 칸 수. */
    private static final int LIMIT = 150_000;

    private static VertexBuffer buffer;
    private static long key = Long.MIN_VALUE;
    private static int ox, oy, oz;

    /** 편집창을 닫을 때: 버퍼를 버린다 (다음 편집창의 다른 영역과 섞이지 않게). */
    public static void release() {
        if (buffer != null) buffer.close();
        buffer = null;
        key = Long.MIN_VALUE;
    }

    static void draw(AreaEditorSession s, Matrix4f rot, Matrix4f proj) {
        long k = s.tagViewKey();
        if (k != key || buffer == null) {
            key = k;
            if (buffer != null) buffer.close();
            buffer = null;
            Box a = s.area.areaBounds();
            ox = a.minX();
            oy = s.area.minY;
            oz = a.minZ();
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int[] n = {0};
            s.forEachTagCell((x, y, z, color) -> {
                if (n[0] >= LIMIT) return false;
                float fx = x - ox, fy = y + 1.01F - oy, fz = z - oz;
                int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF, ca = (color >>> 24) & 0xFF;
                b.addVertex(fx, fy, fz).setColor(cr, cg, cb, ca);
                b.addVertex(fx, fy, fz + 1).setColor(cr, cg, cb, ca);
                b.addVertex(fx + 1, fy, fz + 1).setColor(cr, cg, cb, ca);
                b.addVertex(fx + 1, fy, fz).setColor(cr, cg, cb, ca);
                n[0]++;
                return true;
            });
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
        LibRenderTypes.QUADS.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            buffer.bind();
            buffer.drawWithShader(mv, proj, shader);
            VertexBuffer.unbind();
        }
        LibRenderTypes.QUADS.clearRenderState();
    }
}
