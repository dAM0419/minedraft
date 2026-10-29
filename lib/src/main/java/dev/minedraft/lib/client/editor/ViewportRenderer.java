package dev.minedraft.lib.client.editor;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.minedraft.lib.client.LibConfig;
import dev.minedraft.lib.client.render.LibRenderTypes;
import dev.minedraft.lib.client.render.MeshDraw;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.util.Box;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 편집창 뷰포트 그리기: 섹션 메시(단면·투시), 다른 편집자(반짝임·커서·범위·이름), 단면 예외 칸, 굽는 중 표시, 알림, 커서 칩.
 * 모드의 표시는 편집창 훅으로 끼운다 ({@link AreaEditorScreen#renderWorldExtras} 등).
 */
public final class ViewportRenderer {
    private ViewportRenderer() {}

    private static final MultiBufferSource.BufferSource BUFFERS = MultiBufferSource.immediate(new ByteBufferBuilder(1 << 18));

    public static void render(AreaEditorScreen screen, GuiGraphics g, int mx, int my, float pt) {
        AreaEditorSession s = screen.session();
        if (s.renderer == null) return;
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
                int r = LibConfig.get().bubbleRadius;
                disc(s, mx, my, r, true);
                s.renderer.draw(EditorRenderer.Pass.UPPER, rot, proj);
                disc(s, mx, my, r, false);
                s.renderer.draw(EditorRenderer.Pass.LOWER, rot, proj);
            } else {
                s.renderer.draw(s.cutActive ? EditorRenderer.Pass.LOWER : EditorRenderer.Pass.FULL, rot, proj);
            }
            screen.renderWorldExtras(rot, proj);
            drawOverlays(screen, s, rot);
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
    private static void disc(AreaEditorSession s, double mx, double my, int radiusPx, boolean near) {
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

    private static void drawOverlays(AreaEditorScreen screen, AreaEditorSession s, Matrix4f rot) {
        double tx = s.camera.tx, ty = s.camera.ty, tz = s.camera.tz;
        PoseStack ps = new PoseStack();
        long now = System.currentTimeMillis();

        VertexConsumer quads = BUFFERS.getBuffer(LibRenderTypes.QUADS_SEE_THROUGH);
        Matrix4f m = ps.last().pose();
        screen.drawOverlayQuads(quads, m, tx, ty, tz);
        // 다른 편집자의 변경 반짝임
        for (AreaEditorSession.Flash f : s.flashes) {
            int a = (int) (120 * (1 - (now - f.time()) / 500.0));
            if (a <= 0) continue;
            fillBox(quads, m, BlockPos.getX(f.pos()) - tx - 0.01, BlockPos.getY(f.pos()) - ty - 0.01,
                    BlockPos.getZ(f.pos()) - tz - 0.01, 1.02, Ui.withAlpha(f.color(), a));
        }
        // 다른 편집자 커서 점
        for (AreaEditorSession.RemoteCursor c : s.cursors.values()) {
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
        screen.drawOverlayLines(lines, ps, tx, ty, tz);
        // 다른 편집자 범위 (점선)
        for (AreaEditorSession.RemoteCursor c : s.cursors.values()) {
            if (!c.has()) continue;
            dashedBox(lines, ps, c.region(), tx, ty, tz, Ui.withAlpha(EditorColors.editorColor(c.player()), 200));
        }
        BUFFERS.endBatch(LibRenderTypes.LINES_SEE_THROUGH);

        VertexConsumer solidLines = BUFFERS.getBuffer(LibRenderTypes.LINES);
        screen.drawSolidLines(solidLines, ps, tx, ty, tz);
        // 단면 예외 칸 (단면을 켠 뒤 놓아서 잘린 쪽인데도 보이는 칸): 옅은 청록 테두리
        int exemptColor = Ui.withAlpha(Ui.ACCENT, 110);
        s.forEachShownExempt(4096, p -> {
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            box(solidLines, ps, new Box(x, y, z, x, y, z), tx, ty, tz, exemptColor);
        });
        BUFFERS.endBatch(LibRenderTypes.LINES);
        mvs.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    // ------------------------------------------------------------------
    // 도형 도우미 (좌표는 카메라 목표점 기준)
    // ------------------------------------------------------------------

    public static void box(VertexConsumer vc, PoseStack ps, Box b, double tx, double ty, double tz, int color) {
        float r = ((color >> 16) & 0xFF) / 255f, g = ((color >> 8) & 0xFF) / 255f, bl = (color & 0xFF) / 255f,
                a = ((color >>> 24) & 0xFF) / 255f;
        LevelRenderer.renderLineBox(ps, vc, b.minX() - tx, b.minY() - ty, b.minZ() - tz, b.maxX() + 1 - tx,
                b.maxY() + 1 - ty, b.maxZ() + 1 - tz, r, g, bl, a);
    }

    public static void dashedBox(VertexConsumer vc, PoseStack ps, Box b, double tx, double ty, double tz, int color) {
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

    public static void fillBox(VertexConsumer vc, Matrix4f m, double x, double y, double z, double size, int color) {
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
    // 2D (다른 편집자 이름, 굽는 중, 알림, 커서 칩)
    // ------------------------------------------------------------------

    public static void render2D(AreaEditorScreen screen, GuiGraphics g, int mx, int my) {
        AreaEditorSession s = screen.session();
        // 다른 편집자 이름표
        for (Map.Entry<UUID, AreaEditorSession.RemoteCursor> e : s.cursors.entrySet()) {
            AreaEditorSession.RemoteCursor c = e.getValue();
            if (!c.has()) continue;
            String name = s.editors.getOrDefault(c.player(), "?");
            Vector3f p = s.camera.project(BlockPos.getX(c.pos()) + 0.5, BlockPos.getY(c.pos()) + 1.6, BlockPos.getZ(c.pos()) + 0.5);
            int w = Ui.font().width(name);
            int x = (int) p.x - w / 2, y = (int) p.y - 12;
            if (!screen.inViewport(p.x, p.y)) continue;
            g.fill(x - 2, y - 1, x + w + 2, y + 9, 0xB0000000);
            Ui.text(g, name, x, y, EditorColors.editorColor(c.player()));
        }
        screen.render2DExtras(g, mx, my);
        // 섹션 굽는 중
        if (s.renderer != null && s.renderer.isLoading()) {
            Component t = Component.translatable("minedraftlib.editor.meshing", (int) (s.renderer.progress() * 100));
            Ui.text(g, t, screen.vpX + 6, screen.vpY + 6, Ui.DIM);
        }
        // 알림 (뷰포트 왼쪽 아래)
        int bottom = screen.floatingBottom();
        int ty = bottom - 14;
        for (int i = s.toasts.size() - 1; i >= 0; i--) {
            AreaEditorSession.Toast t = s.toasts.get(i);
            Component text = t.count() > 1 ? Component.literal(t.text().getString() + " ×" + t.count()) : t.text();
            int w = Ui.font().width(text) + 10;
            g.fill(screen.vpX + 4, ty - 2, screen.vpX + 4 + w, ty + 10, 0xE0101418);
            g.fill(screen.vpX + 4, ty - 2, screen.vpX + 5, ty + 10, Ui.WARN);
            Ui.text(g, text, screen.vpX + 9, ty, Ui.TEXT);
            ty -= 14;
        }
        // 커서 칩
        if (screen.hover != null && screen.modal == null && screen.inViewport(mx, my) && !screen.isRightDragging()) {
            List<Component> lines = screen.cursorChip();
            if (!lines.isEmpty()) {
                int w = 0;
                for (Component c : lines) w = Math.max(w, Ui.font().width(c));
                w += 10;
                int h = lines.size() * 10 + 4;
                int x = mx + 14, y = my + 12;
                if (x + w > screen.vpX + screen.vpW) x = mx - w - 6;
                if (y + h > bottom) y = my - h - 6;
                g.fill(x, y, x + w, y + h, 0xE0101418);
                Ui.border(g, x, y, w, h, screen.cursorChipBorder());
                for (int i = 0; i < lines.size(); i++) Ui.text(g, lines.get(i), x + 5, y + 3 + i * 10, i == 0 ? Ui.TEXT : Ui.DIM);
            }
        }
    }
}
