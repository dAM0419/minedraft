package dev.designandbuild.client.projection;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import org.lwjgl.opengl.GL30;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.designandbuild.client.ClientConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import org.joml.Matrix4fStack;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.minedraft.lib.client.render.MapWorld;
import dev.minedraft.lib.client.render.LibRenderTypes;
import dev.minedraft.lib.client.render.BlockMesher;
import dev.minedraft.lib.client.render.MeshDraw;
import dev.minedraft.lib.client.ui.Ui;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * 월드 투영 그리기. 구역(16³)마다 유령 블록·외곽선·필터를 구워 두고, 바뀐 구역만 다시 굽는다.
 * <pre>
 * 놓아야 할 블록   실제 텍스처 색 그대로. 투영 전체를 별도 버퍼에 불투명하게 그린 뒤 통째로 반투명하게 덮는다
 *                 (블록마다 반투명이면 겹친 면이 비쳐 지저분하다). 셰이더 팩은 지원하지 않는다.
 * 철거할 블록      희미한 빨간 필터 (액체 원천은 청색 "메우기")
 * 방향이 틀림      주황 외곽선
 * 다른 블록이 놓임  빨간 필터 (철거와 같다)
 * 가공할 블록      청록 외곽선
 * 놓을 수 없음      유령 + 보라 외곽선 (받침이 생길 수 없는 설계)
 * 망치 조준        흰 외곽선 (못 놓는 칸이면 빨강)
 * </pre>
 * 상태 표시는 합성 뒤에 따로 그려 반투명 덮개에 섞이지 않게 한다.
 */
public final class ProjectionRenderer {
    private ProjectionRenderer() {}

    /** 이보다 먼 구역은 그리지 않는다. */
    private static final double MAX_DISTANCE = 192;
    /** 한 틱에 구울 칸 수 상한 (프레임이 튀지 않게 나눠 굽는다). */
    private static final int BUILD_BUDGET = 3000;
    /** 도구로 가공할 칸 외곽선 (청록). */
    private static final int CONVERT_COLOR = 0xF05FD0C0;
    /** 놓을 수 없는 칸 (받침이 생길 수 없는 설계). */
    private static final int INVALID_COLOR = 0xF0B57CF0;

    private static final class SectionVisual implements AutoCloseable {
        final int ox, oy, oz;
        @Nullable
        BlockMesher.Mesh ghost;
        @Nullable
        VertexBuffer lines;
        @Nullable
        VertexBuffer quads;

        final AABB box;
        /** 이 프레임의 모델뷰 (구역 원점 기준). */
        final Matrix4f mv = new Matrix4f();

        SectionVisual(int ox, int oy, int oz) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.box = new AABB(ox, oy, oz, ox + 16, oy + 16, oz + 16);
        }

        boolean isEmpty() {
            return (ghost == null || ghost.isEmpty()) && lines == null && quads == null;
        }

        @Override
        public void close() {
            if (ghost != null) ghost.close();
            if (lines != null) lines.close();
            if (quads != null) quads.close();
            ghost = null;
            lines = null;
            quads = null;
        }
    }

    private static final ByteBufferBuilder LINE_MEMORY = new ByteBufferBuilder(1 << 16);
    private static final ByteBufferBuilder QUAD_MEMORY = new ByteBufferBuilder(1 << 16);
    private static final Long2ObjectOpenHashMap<SectionVisual> VISUALS = new Long2ObjectOpenHashMap<>();
    private static final LongArrayList QUEUE = new LongArrayList();
    /** QUEUE 중복 확인용. */
    private static final it.unimi.dsi.fastutil.longs.LongOpenHashSet QUEUED = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
    @Nullable
    private static BlockMesher mesher;
    @Nullable
    private static Projection owner;

    public static void clear() {
        for (SectionVisual v : VISUALS.values()) v.close();
        VISUALS.clear();
        QUEUE.clear();
        QUEUED.clear();
        owner = null;
        // 화면 크기 합성 버퍼는 투영을 다시 그릴 때 만든다 (들고 있지 않을 때 그래픽 메모리를 쥐고 있지 않게)
        if (ghostTarget != null) {
            ghostTarget.destroyBuffers();
            ghostTarget = null;
        }
    }

    // ------------------------------------------------------------------
    // 굽기 (클라이언트 틱)
    // ------------------------------------------------------------------

    static void update(Projection p) {
        if (owner != p) {
            clear();
            owner = p;
            p.allDirty = true;
        }
        if (p.allDirty) {
            p.allDirty = false;
            p.dirtySections.clear();
            QUEUE.clear();
            QUEUED.clear();
            // 지금 보이는 구역 + 이미 구운 구역 모두 다시
            for (long sec : p.sections()) enqueue(sec);
            LongIterator it = VISUALS.keySet().iterator();
            while (it.hasNext()) {
                long sec = it.nextLong();
                if (p.cellsIn(sec) == null) enqueue(sec);
            }
        } else if (!p.dirtySections.isEmpty()) {
            LongIterator it = p.dirtySections.iterator();
            while (it.hasNext()) {
                long sec = it.nextLong();
                // 칸도 그림도 없는 구역(이웃 표시로 들어온 빈 구역)은 건너뛴다
                if (p.cellsIn(sec) != null || VISUALS.containsKey(sec)) enqueue(sec);
            }
            p.dirtySections.clear();
        }
        int budget = BUILD_BUDGET;
        while (!QUEUE.isEmpty() && budget > 0) {
            long sec = QUEUE.removeLong(QUEUE.size() - 1);
            QUEUED.remove(sec);
            budget -= buildSection(p, sec);
        }
    }

    private static void enqueue(long sec) {
        if (QUEUED.add(sec)) QUEUE.add(sec);
    }

    private static int buildSection(Projection p, long sec) {
        SectionVisual old = VISUALS.remove(sec);
        if (old != null) old.close();
        it.unimi.dsi.fastutil.ints.IntArrayList cells = p.cellsIn(sec);
        if (cells == null) return 1;
        int ox = SectionPos.x(sec) << 4, oy = SectionPos.y(sec) << 4, oz = SectionPos.z(sec) << 4;
        List<BlockPos> ghostList = new ArrayList<>();
        BufferBuilder lines = null, quads = null;
        int work = 0;
        for (int k = 0; k < cells.size(); k++) {
            int i = cells.getInt(k);
            if (!p.inView(i)) continue;
            work++;
            long pos = p.pos[i];
            float x = BlockPos.getX(pos) - ox, y = BlockPos.getY(pos) - oy, z = BlockPos.getZ(pos) - oz;
            switch (p.status[i]) {
                case Projection.PLACE -> ghostList.add(BlockPos.of(pos));
                case Projection.INVALID -> {
                    // 놓을 수 없는 칸: 유령 + 보라 외곽선 (받침이 생길 수 없는 설계)
                    ghostList.add(BlockPos.of(pos));
                    if (lines == null) lines = new BufferBuilder(LINE_MEMORY, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
                    lineBox(lines, x - 0.004F, y - 0.004F, z - 0.004F, x + 1.004F, y + 1.004F, z + 1.004F, INVALID_COLOR);
                }
                case Projection.WRONG, Projection.CONVERT -> {
                    if (lines == null) lines = new BufferBuilder(LINE_MEMORY, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
                    // 주황 = 방향 틀림, 청록 = 도구로 가공할 칸
                    int color = p.status[i] == Projection.CONVERT ? CONVERT_COLOR : Ui.WARN;
                    lineBox(lines, x - 0.004F, y - 0.004F, z - 0.004F, x + 1.004F, y + 1.004F, z + 1.004F, color);
                }
                case Projection.DEMOLISH, Projection.FILL -> {
                    if (quads == null) quads = new BufferBuilder(QUAD_MEMORY, VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
                    int color = p.status[i] == Projection.FILL ? Ui.withAlpha(Ui.FILL_WATER, 90) : Ui.withAlpha(Ui.DEMOLISH, 80);
                    filledBox(quads, x - 0.006F, y - 0.006F, z - 0.006F, x + 1.006F, y + 1.006F, z + 1.006F, color);
                }
                default -> {
                }
            }
        }
        SectionVisual v = new SectionVisual(ox, oy, oz);
        if (lines != null) v.lines = upload(lines.build());
        if (quads != null) v.quads = upload(quads.build());
        if (!ghostList.isEmpty()) {
            if (mesher == null) mesher = new BlockMesher();
            // 불투명하게 굽는다 (반투명은 합성 단계에서 한 번에).
            // 이웃 구역의 유령도 보이게 투영 전체를 조회한다: 구역 경계에서 맞닿은 면이 지워지고 그늘이 이어진다
            v.ghost = mesher.build(new MapWorld(p::ghostAt, oy - 16, 48, true), ghostList.iterator(), ox, oy, oz, 1.0F, false);
        }
        if (!v.isEmpty()) VISUALS.put(sec, v);
        return Math.max(1, work);
    }

    @Nullable
    private static VertexBuffer upload(@Nullable MeshData data) {
        if (data == null) return null;
        VertexBuffer vb = new VertexBuffer(VertexBuffer.Usage.STATIC);
        vb.bind();
        vb.upload(data);
        VertexBuffer.unbind();
        return vb;
    }

    private static void lineBox(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, bl = color & 0xFF, a = (color >>> 24) & 0xFF;
        line(b, x0, y0, z0, x1, y0, z0, r, g, bl, a);
        line(b, x0, y1, z0, x1, y1, z0, r, g, bl, a);
        line(b, x0, y0, z1, x1, y0, z1, r, g, bl, a);
        line(b, x0, y1, z1, x1, y1, z1, r, g, bl, a);
        line(b, x0, y0, z0, x0, y1, z0, r, g, bl, a);
        line(b, x1, y0, z0, x1, y1, z0, r, g, bl, a);
        line(b, x0, y0, z1, x0, y1, z1, r, g, bl, a);
        line(b, x1, y0, z1, x1, y1, z1, r, g, bl, a);
        line(b, x0, y0, z0, x0, y0, z1, r, g, bl, a);
        line(b, x1, y0, z0, x1, y0, z1, r, g, bl, a);
        line(b, x0, y1, z0, x0, y1, z1, r, g, bl, a);
        line(b, x1, y1, z0, x1, y1, z1, r, g, bl, a);
    }

    private static void line(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1,
                             int r, int g, int bl, int a) {
        float dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 0) return;
        dx /= len;
        dy /= len;
        dz /= len;
        b.addVertex(x0, y0, z0).setColor(r, g, bl, a).setNormal(dx, dy, dz);
        b.addVertex(x1, y1, z1).setColor(r, g, bl, a).setNormal(dx, dy, dz);
    }

    private static void filledBox(BufferBuilder b, float x0, float y0, float z0, float x1, float y1, float z1, int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, bl = color & 0xFF, a = (color >>> 24) & 0xFF;
        // 아래
        quad(b, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, bl, a);
        // 위
        quad(b, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, bl, a);
        // 북
        quad(b, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, bl, a);
        // 남
        quad(b, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, bl, a);
        // 서
        quad(b, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, bl, a);
        // 동
        quad(b, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, bl, a);
    }

    private static void quad(BufferBuilder b, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int r, int g, int bl, int a) {
        b.addVertex(ax, ay, az).setColor(r, g, bl, a);
        b.addVertex(bx, by, bz).setColor(r, g, bl, a);
        b.addVertex(cx, cy, cz).setColor(r, g, bl, a);
        b.addVertex(dx, dy, dz).setColor(r, g, bl, a);
    }

    // ------------------------------------------------------------------
    // 그리기 (프레임)
    // ------------------------------------------------------------------

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        if (VISUALS.isEmpty() || owner == null || ProjectionManager.active() != owner) return;
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        Frustum frustum = event.getFrustum();
        Matrix4f view = event.getModelViewMatrix();
        Matrix4f proj = event.getProjectionMatrix();
        List<SectionVisual> visible = new ArrayList<>();
        for (Long2ObjectMap.Entry<SectionVisual> e : VISUALS.long2ObjectEntrySet()) {
            SectionVisual v = e.getValue();
            double cx = v.ox + 8 - cam.x, cy = v.oy + 8 - cam.y, cz = v.oz + 8 - cam.z;
            if (cx * cx + cy * cy + cz * cz > MAX_DISTANCE * MAX_DISTANCE) continue;
            if (frustum != null && !frustum.isVisible(v.box)) continue;
            // 이 프레임의 구역 변환 (여러 번 그리므로 한 번만 만든다)
            v.mv.set(view).translate((float) (v.ox - cam.x), (float) (v.oy - cam.y), (float) (v.oz - cam.z));
            visible.add(v);
        }
        BlockPos target = ProjectionManager.hammerTarget;
        if (visible.isEmpty() && target == null) return;

        // 유령 블록: 한 장으로 그려 통째로 반투명하게
        float opacity = Mth.clamp(ClientConfig.get().projectionOpacity, 30, 80) / 100.0F;
        drawGhostsComposited(visible, view, proj, cam, opacity);
        // 빨간·청색 필터
        drawBuffers(LibRenderTypes.QUADS, visible, view, proj, cam, 1);
        // 외곽선 (방향 틀림)
        drawBuffers(LibRenderTypes.LINES, visible, view, proj, cam, 0);
        // 망치 조준
        if (target != null) drawTargetBox(target, view, proj, cam);
    }

    // ------------------------------------------------------------------
    // 유령 블록 합성
    // ------------------------------------------------------------------

    @Nullable
    private static TextureTarget ghostTarget;
    @Nullable
    private static Boolean irisPresent;

    /** Iris 셰이더 팩이 켜져 있는가 (비지원 안내용). */
    public static boolean shaderPackInUse() {
        try {
            if (irisPresent == null) {
                irisPresent = net.neoforged.fml.ModList.get().isLoaded("iris")
                        || net.neoforged.fml.ModList.get().isLoaded("oculus");
            }
            if (!irisPresent) return false;
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            Object inst = api.getMethod("getInstance").invoke(null);
            return (Boolean) api.getMethod("isShaderPackInUse").invoke(inst);
        } catch (Throwable t) {
            return false;
        }
    }

    private static void drawGhostLayers(List<SectionVisual> visible, Matrix4f view, Matrix4f proj, Vec3 cam) {
        for (int layer = 0; layer < BlockMesher.LAYER_COUNT; layer++) {
            RenderType type = BlockMesher.layerType(layer);
            boolean begun = false;
            for (SectionVisual v : visible) {
                if (v.ghost == null || !v.ghost.hasLayer(layer)) continue;
                if (!begun) {
                    MeshDraw.beginLayer(type, true);
                    begun = true;
                }
                MeshDraw.draw(v.ghost.buffers[layer], v.mv, proj);
            }
            if (begun) MeshDraw.endLayer(type);
        }
    }

    /** 주 화면 깊이를 복사한 별도 버퍼에 불투명하게 그리고 (겹친 면은 깊이로 가려짐), 결과를 반투명하게 덮는다. */
    private static void drawGhostsComposited(List<SectionVisual> visible, Matrix4f view, Matrix4f proj, Vec3 cam, float opacity) {
        boolean any = false;
        for (SectionVisual v : visible) if (v.ghost != null && !v.ghost.isEmpty()) any = true;
        if (!any) return;
        Minecraft mc = Minecraft.getInstance();
        RenderTarget main = mc.getMainRenderTarget();
        if (ghostTarget == null) {
            ghostTarget = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
            ghostTarget.setClearColor(0, 0, 0, 0);
        } else if (ghostTarget.width != main.width || ghostTarget.height != main.height) {
            ghostTarget.resize(main.width, main.height, Minecraft.ON_OSX);
            ghostTarget.setClearColor(0, 0, 0, 0);
        }
        TextureTarget gt = ghostTarget;
        // 지금 묶인 버퍼 (그래픽 설정에 따라 주 화면이 아닐 수 있다) — 합성은 거기에 하고 끝나면 되돌린다
        int prevFbo = GlStateManager._getInteger(GL30.GL_FRAMEBUFFER_BINDING);
        gt.clear(Minecraft.ON_OSX);
        gt.copyDepthFrom(main);
        gt.bindWrite(false);
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        drawGhostLayers(visible, view, proj, cam);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        GlStateManager._viewport(0, 0, main.viewWidth, main.viewHeight);

        // 합성: 화면 전체 사각형에 유령 버퍼를 불투명도만큼
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, gt.getColorTextureId());
        Matrix4f ortho = new Matrix4f().setOrtho(0, main.width, main.height, 0, -1000, 1000);
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
        int a = Math.round(opacity * 255);
        float w = main.width, h = main.height;
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        b.addVertex(0, h, 0).setUv(0, 0).setColor(255, 255, 255, a);
        b.addVertex(w, h, 0).setUv(1, 0).setColor(255, 255, 255, a);
        b.addVertex(w, 0, 0).setUv(1, 1).setColor(255, 255, 255, a);
        b.addVertex(0, 0, 0).setUv(0, 1).setColor(255, 255, 255, a);
        BufferUploader.drawWithShader(b.buildOrThrow());
        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    // ------------------------------------------------------------------
    // 망치 조준 상자
    // ------------------------------------------------------------------

    /** 조준 상자 (흰색 = 놓을 수 있음, 빨강 = 못 놓음). 모양이 늘 같아 한 번 구워 둔다. */
    private static final VertexBuffer[] TARGET_BOX = new VertexBuffer[2];

    private static void drawTargetBox(BlockPos p, Matrix4f view, Matrix4f proj, Vec3 cam) {
        int which = ProjectionManager.hammerHint != null ? 1 : 0;
        VertexBuffer vb = TARGET_BOX[which];
        if (vb == null) {
            BufferBuilder b = new BufferBuilder(LINE_MEMORY, VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);
            lineBox(b, -0.002F, -0.002F, -0.002F, 1.002F, 1.002F, 1.002F, which == 1 ? 0xF0FF5050 : 0xF0FFFFFF);
            vb = upload(b.build());
            if (vb == null) return;
            TARGET_BOX[which] = vb;
        }
        LibRenderTypes.LINES.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            Matrix4f mv = new Matrix4f(view).translate((float) (p.getX() - cam.x), (float) (p.getY() - cam.y), (float) (p.getZ() - cam.z));
            vb.bind();
            vb.drawWithShader(mv, proj, shader);
            VertexBuffer.unbind();
        }
        LibRenderTypes.LINES.clearRenderState();
    }

    private static void drawBuffers(RenderType type, List<SectionVisual> visible, Matrix4f view, Matrix4f proj, Vec3 cam,
                                    int which) {
        type.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            for (SectionVisual v : visible) {
                VertexBuffer vb = which == 0 ? v.lines : v.quads;
                if (vb == null) continue;
                vb.bind();
                vb.drawWithShader(v.mv, proj, shader);
            }
            VertexBuffer.unbind();
        }
        type.clearRenderState();
    }
}
