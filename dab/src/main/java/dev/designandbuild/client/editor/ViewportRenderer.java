package dev.designandbuild.client.editor;

import dev.minedraft.lib.client.editor.EditorColors;
import dev.minedraft.lib.client.editor.EditorCamera;
import dev.designandbuild.client.ui.TagColors;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.designandbuild.client.ClientConfig;
import dev.minedraft.lib.client.render.LibRenderTypes;
import dev.minedraft.lib.client.render.BlockMesher;
import dev.minedraft.lib.client.render.MeshDraw;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.util.Box;
import dev.designandbuild.core.DesignTag;
import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.StructureInstance;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 편집창 뷰포트 그리기: 섹션 메시, 미리보기, 선택 표시, 다른 편집자, 커서 칩. */
public final class ViewportRenderer {
    private ViewportRenderer() {}

    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));

    // 태그 보기 캐시
    private static VertexBuffer tagBuffer;
    private static long tagKey = Long.MIN_VALUE;
    private static int tagOx, tagOy, tagOz;

    /** 편집창을 닫을 때: 태그 보기 버퍼를 버린다 (다음 편집창의 다른 도면과 섞이지 않게). */
    public static void release() {
        if (tagBuffer != null) tagBuffer.close();
        tagBuffer = null;
        tagKey = Long.MIN_VALUE;
    }

    public static void render(EditorScreen screen, GuiGraphics g, int mx, int my, float pt) {
        EditorSession s = screen.session();
        if (s.renderer == null) return;
        screen.preview.update();
        screen.preview.updatePlacement();
        g.flush();
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

        Matrix4f proj = s.camera.projection();
        Matrix4f rot = s.camera.rotationMatrix();
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(proj, VertexSorting.ORTHOGRAPHIC_Z);
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.set(rot);
        RenderSystem.applyModelViewMatrix();
        MeshDraw.beginNoFog();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        try {
            boolean bubble = s.cutActive && s.bubble && screen.inViewport(mx, my);
            if (bubble) {
                int r = ClientConfig.get().bubbleRadius;
                disc(s, mx, my, r, true);
                s.renderer.draw(EditorRenderer.Pass.UPPER, rot, proj);
                disc(s, mx, my, r, false);
                s.renderer.draw(EditorRenderer.Pass.LOWER, rot, proj);
            } else {
                s.renderer.draw(s.cutActive ? EditorRenderer.Pass.LOWER : EditorRenderer.Pass.FULL, rot, proj);
            }
            if (screen.tagView) drawTagView(s, rot, proj);
            drawOverlays(screen, s, rot, proj);
        } finally {
            MeshDraw.endNoFog();
            mvs.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.restoreProjectionMatrix();
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.enableCull();
        }
    }

    /** 커서 버블: 화면 원 안의 깊이를 가장 앞(near) 또는 가장 뒤(far)로 채운다. */
    private static void disc(EditorSession s, double mx, double my, int radiusPx, boolean near) {
        EditorCamera c = s.camera;
        float cx = (float) ((mx - c.width() / 2.0) / c.scale);
        float cy = (float) ((c.height() / 2.0 - my) / c.scale);
        float r = radiusPx / c.scale;
        float z = near ? 3990.0F : -3990.0F;
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.identity();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.depthMask(true);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionShader);
        BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION);
        b.addVertex(cx, cy, z);
        for (int i = 0; i <= 40; i++) {
            double a = -Math.PI * 2 * i / 40.0;
            b.addVertex(cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, z);
        }
        BufferUploader.drawWithShader(b.buildOrThrow());
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.depthFunc(GL11.GL_LEQUAL);
        RenderSystem.enableCull();
        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    // ------------------------------------------------------------------
    // 겹쳐 그리기
    // ------------------------------------------------------------------

    private static void drawOverlays(EditorScreen screen, EditorSession s, Matrix4f rot, Matrix4f proj) {
        double tx = s.camera.tx, ty = s.camera.ty, tz = s.camera.tz;
        PoseStack ps = new PoseStack();
        long now = System.currentTimeMillis();

        // 유령 블록 (직접조작 미리보기·배치)
        EditorScreen.Preview pv = screen.preview;
        if (s.tool == EditorSession.Tool.DIRECT && pv.ghost != null && !screen.isStroking()) {
            drawGhost(pv.ghost, rot, proj, pv.ghostOx - tx, pv.ghostOy - ty, pv.ghostOz - tz);
        }
        ToolLogic.Placement pl = pv.placement;
        if (s.tool == EditorSession.Tool.PLACE && pl != null && pv.placementGhost != null) {
            drawGhost(pv.placementGhost, rot, proj, pl.ox() - tx, pl.oy() - ty, pl.oz() - tz);
        }

        VertexConsumer quads = BUFFERS.getBuffer(LibRenderTypes.QUADS_SEE_THROUGH);
        Matrix4f m = ps.last().pose();
        // 지우기 대상
        if (s.tool == EditorSession.Tool.DIRECT && s.op == EditorSession.Op.ERASE && pv.targets.length <= ToolLogic.GHOST_LIMIT) {
            for (long p : pv.targets) {
                int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                if (s.blockAt(x, y, z, true).isAir()) continue;
                fillBox(quads, m, x - tx - 0.01, y - ty - 0.01, z - tz - 0.01, 1.02, Ui.withAlpha(Ui.DEMOLISH, 70));
            }
        }
        if (s.tool == EditorSession.Tool.PLACE && pl != null && s.placeOp == EditorSession.Op.ERASE && pl.kept() != null
                && pl.kept().length <= ToolLogic.GHOST_LIMIT) {
            // 조각 모양 지우기: 지워질 칸
            for (int i : pl.kept()) {
                long p = pl.pos()[i];
                fillBox(quads, m, BlockPos.getX(p) - tx - 0.01, BlockPos.getY(p) - ty - 0.01, BlockPos.getZ(p) - tz - 0.01, 1.02,
                        Ui.withAlpha(Ui.DEMOLISH, 70));
            }
        }
        if (s.tool == EditorSession.Tool.PLACE && pl != null) {
            for (long p : pl.conflicts()) {
                fillBox(quads, m, BlockPos.getX(p) - tx - 0.02, BlockPos.getY(p) - ty - 0.02, BlockPos.getZ(p) - tz - 0.02, 1.04,
                        Ui.withAlpha(Ui.WARN, 90));
            }
            for (long p : pl.floating()) {
                fillBox(quads, m, BlockPos.getX(p) - tx + 0.35, BlockPos.getY(p) - ty + 0.8, BlockPos.getZ(p) - tz + 0.35, 0.3,
                        Ui.withAlpha(Ui.DEMOLISH, 220));
            }
        }
        // 다른 편집자의 변경 반짝임
        for (EditorSession.Flash f : s.flashes) {
            int a = (int) (120 * (1 - (now - f.time()) / 500.0));
            if (a <= 0) continue;
            fillBox(quads, m, BlockPos.getX(f.pos()) - tx - 0.01, BlockPos.getY(f.pos()) - ty - 0.01,
                    BlockPos.getZ(f.pos()) - tz - 0.01, 1.02, Ui.withAlpha(f.color(), a));
        }
        // 다른 편집자 커서 점
        for (EditorSession.RemoteCursor c : s.cursors.values()) {
            if (!c.has()) continue;
            int color = EditorColors.editorColor(c.player());
            fillBox(quads, m, BlockPos.getX(c.pos()) - tx + 0.3, BlockPos.getY(c.pos()) - ty + 1.05, BlockPos.getZ(c.pos()) - tz + 0.3,
                    0.4, Ui.withAlpha(color, 230));
        }
        BUFFERS.endBatch(LibRenderTypes.QUADS_SEE_THROUGH);

        // 선은 선 셰이더의 축소 보정을 되돌리고 화면 쪽으로 살짝 당겨 그린다 (멀리 있는 상자가 어긋나지 않게)
        Matrix4fStack mvs = RenderSystem.getModelViewStack();
        mvs.pushMatrix();
        mvs.set(EditorRenderer.lineView(rot, s.camera.realScale()));
        RenderSystem.applyModelViewMatrix();
        VertexConsumer lines = BUFFERS.getBuffer(LibRenderTypes.LINES_SEE_THROUGH);
        // 선택한 구조물·커서 아래 구조물
        if (s.tool == EditorSession.Tool.SELECT && screen.hover != null) {
            for (int id : s.structuresAround(screen.hover.pos())) {
                if (id == s.selected) continue;
                StructureInstance st = s.model.structure(id);
                if (st != null && st.bounds() != null) box(lines, ps, st.bounds(), tx, ty, tz, Ui.withAlpha(0xFFFFFF, 110));
            }
        }
        int orderHover = screen.sidePanel.hoveredStructure();
        if (orderHover != 0) {
            StructureInstance st = s.model.structure(orderHover);
            if (st != null && st.bounds() != null) box(lines, ps, st.bounds(), tx, ty, tz, Ui.withAlpha(Ui.ACCENT, 230));
        }
        if (s.selected != 0) {
            StructureInstance st = s.model.structure(s.selected);
            if (st != null && st.bounds() != null) {
                Box b = st.bounds();
                box(lines, ps, b, tx, ty, tz, Ui.withAlpha(0xFFFFFF, 90));
                if (screen.moving && (screen.moveDX != 0 || screen.moveDZ != 0 || screen.moveDY != 0)) {
                    Box moved = new Box(b.minX() + screen.moveDX, b.minY() + screen.moveDY, b.minZ() + screen.moveDZ,
                            b.maxX() + screen.moveDX, b.maxY() + screen.moveDY, b.maxZ() + screen.moveDZ);
                    dashedBox(lines, ps, moved, tx, ty, tz, Ui.withAlpha(Ui.ACCENT, 230));
                }
            }
        }
        // 다른 편집자 범위 (점선)
        for (EditorSession.RemoteCursor c : s.cursors.values()) {
            if (!c.has()) continue;
            dashedBox(lines, ps, c.region(), tx, ty, tz, Ui.withAlpha(EditorColors.editorColor(c.player()), 200));
        }
        BUFFERS.endBatch(LibRenderTypes.LINES_SEE_THROUGH);

        VertexConsumer solidLines = BUFFERS.getBuffer(LibRenderTypes.LINES);
        if (s.selected != 0) {
            StructureInstance st = s.model.structure(s.selected);
            if (st != null && st.bounds() != null) box(solidLines, ps, st.bounds(), tx, ty, tz, 0xFFFFFFFF);
        }
        if (s.tool == EditorSession.Tool.DIRECT && pv.region != null && screen.hover != null) {
            boolean large = pv.targets.length > ClientConfig.get().largeOpThreshold;
            int color = s.op == EditorSession.Op.ERASE ? Ui.DEMOLISH : large ? Ui.WARN : Ui.ACCENT;
            box(solidLines, ps, pv.region, tx, ty, tz, color);
        }
        if (s.tool == EditorSession.Tool.PLACE && pl != null) {
            Box b = new Box(pl.ox(), pl.oy(), pl.oz(), pl.ox() + pl.shape().sx() - 1, pl.oy() + pl.shape().sy() - 1,
                    pl.oz() + pl.shape().sz() - 1);
            box(solidLines, ps, b, tx, ty, tz, pl.valid() ? 0xFFFFFFFF : Ui.DEMOLISH);
        }
        // 단면 예외 칸 (단면을 켠 뒤 놓아서 잘린 쪽인데도 보이는 칸): 옅은 청록 테두리
        int exemptColor = Ui.withAlpha(Ui.ACCENT, 110);
        s.forEachShownExempt(4096, p -> {
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            box(solidLines, ps, new Box(x, y, z, x, y, z), tx, ty, tz, exemptColor);
        });
        if (screen.isStroking() && screen.hover != null) {
            BlockPos p = screen.hover.pos();
            box(solidLines, ps, new Box(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()), tx, ty, tz, Ui.ACCENT);
        }
        BUFFERS.endBatch(LibRenderTypes.LINES);
        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    private static void drawGhost(BlockMesher.Mesh mesh, Matrix4f rot, Matrix4f proj, double ox, double oy, double oz) {
        if (!mesh.hasLayer(BlockMesher.TRANSLUCENT)) return;
        Matrix4f mv = new Matrix4f(rot).translate((float) ox, (float) oy, (float) oz);
        MeshDraw.beginLayer(LibRenderTypes.GHOST, true);
        MeshDraw.draw(mesh.buffers[BlockMesher.TRANSLUCENT], mv, proj);
        MeshDraw.endLayer(LibRenderTypes.GHOST);
    }

    /** 태그 보기: 설계 칸 윗면을 태그 색으로 덮는다 (도면 판이 바뀔 때만 다시 굽는다). */
    private static void drawTagView(EditorSession s, Matrix4f rot, Matrix4f proj) {
        long key = s.model.revision() * 31 + s.cutStamp;
        if (key != tagKey || tagBuffer == null) {
            tagKey = key;
            if (tagBuffer != null) tagBuffer.close();
            tagBuffer = null;
            Box a = s.model.areaBounds();
            tagOx = a.minX();
            tagOy = s.model.minY;
            tagOz = a.minZ();
            BufferBuilder b = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
            int n = 0;
            for (Long2ObjectMap.Entry<Resolved> e : s.model.resolvedMap().long2ObjectEntrySet()) {
                if (n > 150_000) break;
                Resolved r = e.getValue();
                long p = e.getLongKey();
                int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                if (s.cutActive && s.isCut(x, y, z)) continue;
                int color;
                float top;
                if (r.isAir()) {
                    if (s.worldAt(x, y, z).isAir()) continue;
                    color = Ui.withAlpha(Ui.DEMOLISH, 90);
                    top = y + 1.01F;
                } else {
                    Resolved above = s.model.resolved(x, y + 1, z);
                    if (above != null && !above.isAir() && !(s.cutActive && s.isCut(x, y + 1, z))) continue;
                    color = Ui.withAlpha(TagColors.tagColor(r.tag()), r.tag() == DesignTag.STRUCTURE ? 80 : 110);
                    top = y + 1.01F;
                }
                float fx = x - tagOx, fy = top - tagOy, fz = z - tagOz;
                int cr = (color >> 16) & 0xFF, cg = (color >> 8) & 0xFF, cb = color & 0xFF, ca = (color >>> 24) & 0xFF;
                b.addVertex(fx, fy, fz).setColor(cr, cg, cb, ca);
                b.addVertex(fx, fy, fz + 1).setColor(cr, cg, cb, ca);
                b.addVertex(fx + 1, fy, fz + 1).setColor(cr, cg, cb, ca);
                b.addVertex(fx + 1, fy, fz).setColor(cr, cg, cb, ca);
                n++;
            }
            var mesh = b.build();
            if (mesh != null) {
                tagBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
                tagBuffer.bind();
                tagBuffer.upload(mesh);
                VertexBuffer.unbind();
            }
        }
        if (tagBuffer == null) return;
        Matrix4f mv = new Matrix4f(rot).translate((float) (tagOx - s.camera.tx), (float) (tagOy - s.camera.ty),
                (float) (tagOz - s.camera.tz));
        LibRenderTypes.QUADS.setupRenderState();
        ShaderInstance shader = RenderSystem.getShader();
        if (shader != null) {
            tagBuffer.bind();
            tagBuffer.drawWithShader(mv, proj, shader);
            VertexBuffer.unbind();
        }
        LibRenderTypes.QUADS.clearRenderState();
    }

    // ------------------------------------------------------------------
    // 도형 도우미 (좌표는 카메라 목표점 기준)
    // ------------------------------------------------------------------

    static void box(VertexConsumer vc, PoseStack ps, Box b, double tx, double ty, double tz, int color) {
        float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, bl = (color & 0xFF) / 255f,
                a = ((color >>> 24) & 0xFF) / 255f;
        LevelRenderer.renderLineBox(ps, vc, b.minX() - tx, b.minY() - ty, b.minZ() - tz, b.maxX() + 1 - tx,
                b.maxY() + 1 - ty, b.maxZ() + 1 - tz, r, g, bl, a);
    }

    static void dashedBox(VertexConsumer vc, PoseStack ps, Box b, double tx, double ty, double tz, int color) {
        double x0 = b.minX() - tx, y0 = b.minY() - ty, z0 = b.minZ() - tz;
        double x1 = b.maxX() + 1 - tx, y1 = b.maxY() + 1 - ty, z1 = b.maxZ() + 1 - tz;
        double[][] edges = {
                {x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1}};
        PoseStack.Pose pose = ps.last();
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, bl = color & 0xFF, a = (color >>> 24) & 0xFF;
        for (double[] e : edges) {
            double dx = e[3] - e[0], dy = e[4] - e[1], dz = e[5] - e[2];
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len <= 0) continue;
            float nx = (float) (dx / len), ny = (float) (dy / len), nz = (float) (dz / len);
            for (double t = 0; t < len; t += 0.5) {
                double t2 = Math.min(len, t + 0.3);
                vc.addVertex(pose, (float) (e[0] + dx * t / len), (float) (e[1] + dy * t / len), (float) (e[2] + dz * t / len))
                        .setColor(r, g, bl, a).setNormal(pose, nx, ny, nz);
                vc.addVertex(pose, (float) (e[0] + dx * t2 / len), (float) (e[1] + dy * t2 / len), (float) (e[2] + dz * t2 / len))
                        .setColor(r, g, bl, a).setNormal(pose, nx, ny, nz);
            }
        }
    }

    static void fillBox(VertexConsumer vc, Matrix4f m, double x, double y, double z, double size, int color) {
        float x0 = (float) x, y0 = (float) y, z0 = (float) z;
        float x1 = (float) (x + size), y1 = (float) (y + size), z1 = (float) (z + size);
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF, a = (color >>> 24) & 0xFF;
        // 위
        quad(vc, m, x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0, r, g, b, a);
        // 아래
        quad(vc, m, x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, r, g, b, a);
        // 북·남
        quad(vc, m, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1, y0, z0, r, g, b, a);
        quad(vc, m, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, r, g, b, a);
        // 서·동
        quad(vc, m, x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, r, g, b, a);
        quad(vc, m, x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, r, g, b, a);
    }

    private static void quad(VertexConsumer vc, Matrix4f m, float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz, int r, int g, int b, int a) {
        vc.addVertex(m, ax, ay, az).setColor(r, g, b, a);
        vc.addVertex(m, bx, by, bz).setColor(r, g, b, a);
        vc.addVertex(m, cx, cy, cz).setColor(r, g, b, a);
        vc.addVertex(m, dx, dy, dz).setColor(r, g, b, a);
    }

    // ------------------------------------------------------------------
    // 2D (커서 칩, 띠, 알림, 다른 편집자 이름)
    // ------------------------------------------------------------------

    public static void render2D(EditorScreen screen, GuiGraphics g, int mx, int my) {
        EditorSession s = screen.session();
        // 다른 편집자 이름표
        for (Map.Entry<UUID, EditorSession.RemoteCursor> e : s.cursors.entrySet()) {
            EditorSession.RemoteCursor c = e.getValue();
            if (!c.has()) continue;
            String name = s.editors.getOrDefault(c.player(), "?");
            Vector3f p = s.camera.project(BlockPos.getX(c.pos()) + 0.5, BlockPos.getY(c.pos()) + 1.6, BlockPos.getZ(c.pos()) + 0.5);
            int w = Ui.font().width(name);
            int x = (int) p.x - w / 2, y = (int) p.y - 12;
            if (!screen.inViewport(p.x, p.y)) continue;
            g.fill(x - 2, y - 1, x + w + 2, y + 9, 0xB0000000);
            Ui.text(g, name, x, y, EditorColors.editorColor(c.player()));
        }
        // 구조물 편집 중 띠
        if (s.selected != 0) {
            StructureInstance st = s.model.structure(s.selected);
            if (st != null) {
                Component band = Component.translatable("designandbuild.editor.editing_structure", st.name);
                int w = Ui.font().width(band) + 12;
                int x = screen.vpX + (screen.vpW - w) / 2, y = screen.vpY + 4;
                g.fill(x, y, x + w, y + 13, 0xD0101418);
                Ui.border(g, x, y, w, 13, 0xFFFFFFFF);
                Ui.text(g, band, x + 6, y + 3, Ui.TEXT);
            }
        }
        // 섹션 굽는 중
        if (s.renderer != null && s.renderer.isLoading()) {
            Component t = Component.translatable("designandbuild.editor.meshing", (int) (s.renderer.progress() * 100));
            Ui.text(g, t, screen.vpX + 6, screen.vpY + 6, Ui.DIM);
        }
        // 알림 (뷰포트 왼쪽 아래)
        int ty = screen.paletteTop() - 14;
        for (int i = s.toasts.size() - 1; i >= 0; i--) {
            EditorSession.Toast t = s.toasts.get(i);
            Component text = t.count() > 1 ? Component.literal(t.text().getString() + " ×" + t.count()) : t.text();
            int w = Ui.font().width(text) + 10;
            g.fill(screen.vpX + 4, ty - 2, screen.vpX + 4 + w, ty + 10, 0xE0101418);
            g.fill(screen.vpX + 4, ty - 2, screen.vpX + 5, ty + 10, Ui.WARN);
            Ui.text(g, text, screen.vpX + 9, ty, Ui.TEXT);
            ty -= 14;
        }
        // 커서 칩
        if (screen.hover != null && screen.modal == null && screen.inViewport(mx, my) && !screen.isRightDragging()) {
            List<Component> lines = chip(screen, s);
            if (!lines.isEmpty()) {
                int w = 0;
                for (Component c : lines) w = Math.max(w, Ui.font().width(c));
                w += 10;
                int h = lines.size() * 10 + 4;
                int x = mx + 14, y = my + 12;
                if (x + w > screen.vpX + screen.vpW) x = mx - w - 6;
                if (y + h > screen.paletteTop()) y = my - h - 6;
                boolean warn = s.tool == EditorSession.Tool.DIRECT && screen.preview.targets.length > ClientConfig.get().largeOpThreshold;
                boolean bad = s.tool == EditorSession.Tool.PLACE && screen.preview.placement != null && !screen.preview.placement.valid();
                g.fill(x, y, x + w, y + h, 0xE0101418);
                Ui.border(g, x, y, w, h, bad ? Ui.DEMOLISH : warn ? Ui.WARN : 0xFF4A5563);
                for (int i = 0; i < lines.size(); i++) Ui.text(g, lines.get(i), x + 5, y + 3 + i * 10, i == 0 ? Ui.TEXT : Ui.DIM);
            }
        }
    }

    private static List<Component> chip(EditorScreen screen, EditorSession s) {
        List<Component> lines = new ArrayList<>();
        switch (s.tool) {
            case DIRECT -> {
                String op = Component.translatable("designandbuild.op." + s.op.name().toLowerCase()).getString();
                String shape = Component.translatable("designandbuild.shape." + s.shape.name().toLowerCase()).getString();
                String size = s.sizeX + "×" + s.sizeY + "×" + s.sizeZ;
                StringBuilder b = new StringBuilder(op).append(" · ").append(shape).append(' ').append(size);
                StructureInstance editing = s.selected != 0 ? s.model.structure(s.selected) : null;
                if (editing != null) {
                    b.append(" · ").append(Ui.clip(editing.name, 90));
                    if (s.op != EditorSession.Op.ERASE && s.percent < 100) b.append(" · ").append(s.percent).append('%');
                } else if (s.op != EditorSession.Op.ERASE) {
                    b.append(" · ").append(Component.translatable("designandbuild.tag." + s.tag.key()).getString());
                    if (s.percent < 100) b.append(" · ").append(s.percent).append('%');
                }
                b.append(" · ").append(Component.translatable("designandbuild.editor.blocks", screen.preview.targets.length).getString());
                lines.add(Component.literal(b.toString()));
                if (ToolLogic.structureLayer(s) && s.selected == 0) lines.add(Component.translatable("designandbuild.editor.new_structure"));
                if (screen.preview.skippedForeign > 0) {
                    lines.add(Component.translatable("designandbuild.editor.skipped_foreign", screen.preview.skippedForeign)
                            .withStyle(st -> st.withColor(Ui.WARN & 0xFFFFFF)));
                }
                if (screen.preview.targets.length == 0 && screen.preview.skippedOccupied > 0 && screen.preview.skippedForeign == 0) {
                    lines.add(Component.translatable(s.anchor == EditorSession.Anchor.OUTSIDE ? "designandbuild.editor.no_empty"
                            : "designandbuild.editor.no_empty_anchor").withStyle(st -> st.withColor(Ui.WARN & 0xFFFFFF)));
                }
                if (screen.preview.targets.length > ClientConfig.get().largeOpThreshold) {
                    lines.add(Component.translatable("designandbuild.editor.large_op").withStyle(st -> st.withColor(Ui.WARN & 0xFFFFFF)));
                }
            }
            case PLACE -> {
                if (s.placing == null) {
                    lines.add(Component.translatable("designandbuild.msg.pick_structure"));
                } else {
                    String name = s.placing.name();
                    ToolLogic.Placement p = screen.preview.placement;
                    if (p == null) {
                        lines.add(Component.literal(name + " · ").append(Component.translatable("designandbuild.editor.loading_structure")));
                    } else {
                        lines.add(Component.literal(name + " · " + (p.shape().rotation() * 90) + "°" + (p.shape().mirror() ? " · ⇋" : "")));
                        if (!p.valid()) lines.add(Component.translatable(p.error()));
                        else if (p.conflicts().length > 0) lines.add(Component.translatable("designandbuild.editor.overwrites", p.conflicts().length));
                        if (p.valid() && p.replaced() > 0) lines.add(Component.translatable("designandbuild.editor.replaces_floor", p.replaced()));
                        if (p.shape().mount().getAxis().isHorizontal()) lines.add(Component.translatable("designandbuild.editor.rotation_locked"));
                    }
                }
            }
            case SELECT -> {
                for (int id : s.structuresAround(screen.hover.pos())) {
                    StructureInstance st = s.model.structure(id);
                    if (st == null) continue;
                    lines.add(Component.literal((s.model.orderIndexOf(id) + 1) + ". " + st.name));
                }
            }
        }
        return lines;
    }
}
