package dev.designandbuild.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.designandbuild.client.library.MapWorld;
import dev.designandbuild.client.render.BlockMesher;
import dev.designandbuild.client.render.MeshDraw;
import dev.designandbuild.core.TemplateData;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 구조물 조각의 쿼터뷰 썸네일. 정점 버퍼로 한 번 구워 두고 GUI 안에 그린다. */
public final class Thumbnails {
    private Thumbnails() {}

    private static final int MAX_BLOCKS = 40_000;
    private static final Map<String, BlockMesher.Mesh> CACHE = new LinkedHashMap<>(64, 0.75f, true);
    private static BlockMesher mesher;
    private static int builtThisFrame;

    public static void frame() {
        builtThisFrame = 0;
    }

    public static void clear() {
        for (BlockMesher.Mesh m : CACHE.values()) m.close();
        CACHE.clear();
    }

    /** 썸네일을 그린다. 아직 굽지 못했으면 false. */
    public static boolean draw(GuiGraphics g, TemplateData t, int x, int y, int w, int h, float yaw, float z) {
        if (t.count() > MAX_BLOCKS) return false;
        BlockMesher.Mesh mesh = CACHE.get(t.id);
        if (mesh == null) {
            if (builtThisFrame >= 2) return false;
            builtThisFrame++;
            mesh = bake(t);
            CACHE.put(t.id, mesh);
            while (CACHE.size() > 96) {
                Iterator<Map.Entry<String, BlockMesher.Mesh>> it = CACHE.entrySet().iterator();
                it.next().getValue().close();
                it.remove();
            }
        }
        drawMesh(g, mesh, t.sizeX, t.sizeY, t.sizeZ, x, y, w, h, yaw, z);
        return true;
    }

    private static BlockMesher.Mesh bake(TemplateData t) {
        if (mesher == null) mesher = new BlockMesher();
        Long2ObjectOpenHashMap<BlockState> map = new Long2ObjectOpenHashMap<>();
        List<BlockPos> positions = new ArrayList<>();
        for (int i = 0; i < t.count(); i++) {
            if (t.states[i].isAir()) continue;
            BlockPos p = new BlockPos(t.xs[i], t.ys[i], t.zs[i]);
            map.put(p.asLong(), t.states[i]);
            positions.add(p);
        }
        return mesher.build(new MapWorld(map, -64, 512), positions.iterator(), 0, 0, 0, 1.0F, false);
    }

    /** 구운 메시를 GUI 사각형 안에 쿼터뷰로 맞춰 그린다. */
    public static void drawMesh(GuiGraphics g, BlockMesher.Mesh mesh, int sx, int sy, int sz, int x, int y, int w, int h,
                                float yaw, float z) {
        g.flush();
        float diag = (float) Math.sqrt((double) sx * sx + (double) sz * sz);
        float extentX = Math.max(1, diag);
        float extentY = Math.max(1, sy * 0.866F + diag * 0.5F);
        float scale = Math.min(w / (extentX * 1.08F), h / (extentY * 1.12F));
        PoseStack ps = g.pose();
        ps.pushPose();
        ps.translate(x + w / 2.0F, y + h / 2.0F, z);
        ps.scale(scale, -scale, scale);
        ps.mulPose(Axis.XP.rotationDegrees(30.0F));
        ps.mulPose(Axis.YP.rotationDegrees(yaw + 180.0F));
        ps.translate(-sx / 2.0F, -sy / 2.0F, -sz / 2.0F);
        Matrix4f mv = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(ps.last().pose());
        Matrix4f proj = new Matrix4f(RenderSystem.getProjectionMatrix());
        MeshDraw.beginNoFog();
        for (int layer = 0; layer < BlockMesher.LAYER_COUNT; layer++) {
            if (!mesh.hasLayer(layer)) continue;
            MeshDraw.beginLayer(BlockMesher.layerType(layer), true);
            RenderSystem.disableCull();
            MeshDraw.draw(mesh.buffers[layer], mv, proj);
            MeshDraw.endLayer(BlockMesher.layerType(layer));
            RenderSystem.enableCull();
        }
        MeshDraw.endNoFog();
        ps.popPose();
    }
}
