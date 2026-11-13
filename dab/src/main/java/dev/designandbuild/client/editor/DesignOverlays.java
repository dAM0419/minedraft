package dev.designandbuild.client.editor;

import static dev.minedraft.lib.client.editor.ViewportRenderer.box;
import static dev.minedraft.lib.client.editor.ViewportRenderer.dashedBox;
import static dev.minedraft.lib.client.editor.ViewportRenderer.fillBox;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.designandbuild.client.ClientConfig;
import dev.minedraft.lib.client.render.LibRenderTypes;
import dev.minedraft.lib.client.render.BlockMesher;
import dev.minedraft.lib.client.render.MeshDraw;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.util.Box;
import dev.designandbuild.core.StructureInstance;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/** DaB 편집창 뷰포트 표시: 유령 블록, 지우기·충돌 표시, 구조물 상자, 태그 보기, 구조물 띠, 커서 칩. 공통 부분은 Lib 의 ViewportRenderer. */
final class DesignOverlays {
    private DesignOverlays() {}

    /** 블록 메시 뒤: 유령 블록. */
    static void world(EditorScreen screen, Matrix4f rot, Matrix4f proj) {
        EditorSession s = screen.session();
        double tx = s.camera.tx, ty = s.camera.ty, tz = s.camera.tz;
        // 유령 블록 (직접조작 미리보기·배치)
        EditorScreen.Preview pv = screen.preview;
        if (s.tool == EditorSession.Tool.DIRECT && pv.ghost != null && !screen.isStroking()) {
            drawGhost(pv.ghost, rot, proj, pv.ghostOx - tx, pv.ghostOy - ty, pv.ghostOz - tz);
        }
        ToolLogic.Placement pl = pv.placement;
        if (s.tool == EditorSession.Tool.PLACE && pl != null && pv.placementGhost != null) {
            drawGhost(pv.placementGhost, rot, proj, pl.ox() - tx, pl.oy() - ty, pl.oz() - tz);
        }

    }

    /** 반투명 상자: 지우기 대상, 조각 지우기, 겹침·떠 있는 칸. */
    static void quads(EditorScreen screen, VertexConsumer quads, Matrix4f m, double tx, double ty, double tz) {
        EditorSession s = screen.session();
        EditorScreen.Preview pv = screen.preview;
        ToolLogic.Placement pl = pv.placement;
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
    }

    /** 비쳐 보이는 선: 커서 아래 구조물, 순서 탭에서 가리킨 구조물, 선택 구조물과 옮길 자리. */
    static void lines(EditorScreen screen, VertexConsumer lines, PoseStack ps, double tx, double ty, double tz) {
        EditorSession s = screen.session();
        // 선택한 구조물·커서 아래 구조물
        if (s.tool == EditorSession.Tool.SELECT && screen.hover != null) {
            for (int id : s.structuresAround(screen.hover.pos())) {
                if (id == s.selected) continue;
                StructureInstance st = s.model.structure(id);
                if (st != null && st.bounds() != null) box(lines, ps, st.bounds(), tx, ty, tz, Ui.withAlpha(0xFFFFFF, 110));
            }
        }
        int orderHover = screen.sidePanel.shownTab() == screen.orderTab ? screen.orderTab.hovered : 0;
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
    }

    /** 가려지는 선: 선택 구조물, 직접조작 범위, 배치 상자, 칠하는 중인 칸. */
    static void solid(EditorScreen screen, VertexConsumer solidLines, PoseStack ps, double tx, double ty, double tz) {
        EditorSession s = screen.session();
        EditorScreen.Preview pv = screen.preview;
        ToolLogic.Placement pl = pv.placement;
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
        if (screen.isStroking() && screen.hover != null) {
            BlockPos p = screen.hover.pos();
            box(solidLines, ps, new Box(p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(), p.getZ()), tx, ty, tz, Ui.ACCENT);
        }
    }

    private static void drawGhost(BlockMesher.Mesh mesh, Matrix4f rot, Matrix4f proj, double ox, double oy, double oz) {
        if (!mesh.hasLayer(BlockMesher.TRANSLUCENT)) return;
        Matrix4f mv = new Matrix4f(rot).translate((float) ox, (float) oy, (float) oz);
        MeshDraw.beginLayer(LibRenderTypes.GHOST, true);
        MeshDraw.draw(mesh.buffers[BlockMesher.TRANSLUCENT], mv, proj);
        MeshDraw.endLayer(LibRenderTypes.GHOST);
    }

    /** 2D: 구조물 편집 중 띠. */
    static void band(EditorScreen screen, GuiGraphics g) {
        EditorSession s = screen.session();
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
    }

    static int chipBorder(EditorScreen screen) {
        EditorSession s = screen.session();
        boolean warn = s.tool == EditorSession.Tool.DIRECT && screen.preview.targets.length > ClientConfig.get().largeOpThreshold;
        boolean bad = s.tool == EditorSession.Tool.PLACE && screen.preview.placement != null && !screen.preview.placement.valid();
        return bad ? Ui.DEMOLISH : warn ? Ui.WARN : 0xFF4A5563;
    }

    static List<Component> chip(EditorScreen screen) {
        EditorSession s = screen.session();
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
