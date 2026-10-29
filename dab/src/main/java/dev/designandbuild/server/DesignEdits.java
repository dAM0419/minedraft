package dev.designandbuild.server;

import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.DesignTag;
import dev.designandbuild.core.ModelOps;
import dev.designandbuild.core.Requests;
import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.StructureInstance;
import dev.designandbuild.core.TerrainCell;
import dev.minedraft.lib.edit.EditContext;
import dev.minedraft.lib.edit.EditRequests;
import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** DaB 설계 편집 요청을 Lib 에 등록한다: 요청을 확정 연산과 되돌리기 기록으로 바꾸는 서버 처리기. */
public final class DesignEdits {
    private DesignEdits() {}

    public static void register() {
        reg(Requests.TERRAIN, DesignEdits::terrain);
        reg(Requests.STRUCT_CELLS, DesignEdits::structCells);
        reg(Requests.STRUCT_CREATE, DesignEdits::structCreate);
        reg(Requests.STRUCT_MOVE, DesignEdits::structMove);
        reg(Requests.STRUCT_DELETE, DesignEdits::structDelete);
        reg(Requests.STRUCT_REORDER, DesignEdits::structReorder);
        reg(Requests.STRUCT_RENAME, DesignEdits::structRename);
    }

    @SuppressWarnings("unchecked")
    private static <R extends Requests.Req> void reg(ResourceLocation type, EditRequests.Handler<R> handler) {
        EditRequests.register(type, buf -> (R) Requests.read(type, buf), handler);
    }

    @Nullable
    private static String terrain(EditContext ctx, Requests.Terrain t) {
        BlueprintModel m = BlueprintModel.of(ctx.area);
        int n = Math.min(t.pos().length, Math.min(t.states().length, t.tags().length));
        List<Long> pos = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        List<DesignTag> tags = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long p = t.pos()[i];
            BlockState st = t.states()[i];
            if (st == null || !m.isInside(p)) continue;
            Resolved r = m.resolved(p);
            if (r != null && r.owner() != 0) continue; // 구조물 블록은 그 구조물을 선택해야 바꿀 수 있다
            DesignTag tag = st.isAir() ? DesignTag.NONE
                    : (t.tags()[i] == DesignTag.PATH ? DesignTag.PATH : DesignTag.FLOOR);
            TerrainCell before = m.terrainAt(p);
            TerrainCell after = new TerrainCell(st, tag);
            if (Objects.equals(before, after)) continue;
            pos.add(p);
            states.add(st);
            tags.add(tag);
            ctx.undo.add(new DesignUndo.TerrainChange(p, before, after));
        }
        if (!pos.isEmpty()) {
            ctx.ops.add(new ModelOps.Terrain(toLongs(pos), states.toArray(new BlockState[0]), tags.toArray(new DesignTag[0])));
        }
        return null;
    }

    @Nullable
    private static String structCells(EditContext ctx, Requests.StructCells t) {
        BlueprintModel m = BlueprintModel.of(ctx.area);
        boolean create = t.id() == 0;
        StructureInstance s = create ? null : m.structure(t.id());
        if (!create && s == null) return "designandbuild.msg.structure_gone";
        int id = create ? m.nextStructureId : t.id();
        int n = Math.min(t.pos().length, t.states().length);
        List<Long> pos = new ArrayList<>();
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            long p = t.pos()[i];
            BlockState st = t.states()[i];
            if (st != null && !m.isInside(p)) continue;
            Resolved r = m.resolved(p);
            if (r != null && r.owner() != 0 && r.owner() != id) continue; // 다른 구조물 칸은 보호
            BlockState before = s == null ? null : s.get(p);
            if (before == st) continue;
            pos.add(p);
            states.add(st);
            if (!create) ctx.undo.add(new DesignUndo.StructCellChange(id, p, before, st));
        }
        if (pos.isEmpty()) return null;
        if (create) {
            ctx.ops.add(new ModelOps.StructCreate(id, -1, EditRequests.sanitize(t.name(), 64), "", Direction.DOWN, Rotation.NONE,
                    Mirror.NONE, toLongs(pos), states.toArray(new BlockState[0])));
            ctx.undo.add(new DesignUndo.StructCreated(id, 0));
        } else {
            ctx.ops.add(new ModelOps.StructCells(id, toLongs(pos), states.toArray(new BlockState[0])));
        }
        return null;
    }

    @Nullable
    private static String structCreate(EditContext ctx, Requests.StructCreate t) {
        BlueprintModel m = BlueprintModel.of(ctx.area);
        int n = Math.min(t.pos().length, t.states().length);
        if (n == 0) return null;
        for (int i = 0; i < n; i++) {
            if (!m.isInside(t.pos()[i])) return "designandbuild.msg.outside_area";
        }
        int id = m.nextStructureId;
        ctx.ops.add(new ModelOps.StructCreate(id, -1, EditRequests.sanitize(t.name(), 64), EditRequests.sanitize(t.source(), 256),
                t.mount(), t.rotation(), t.mirror(), t.pos(), t.states()));
        ctx.undo.add(new DesignUndo.StructCreated(id, 0));
        return null;
    }

    @Nullable
    private static String structMove(EditContext ctx, Requests.StructMove t) {
        BlueprintModel m = BlueprintModel.of(ctx.area);
        StructureInstance s = m.structure(t.id());
        if (s == null) return "designandbuild.msg.structure_gone";
        Box b = s.bounds();
        if (b == null) return null;
        int steps = Math.floorMod(t.rotSteps(), 4);
        if (t.dx() == 0 && t.dy() == 0 && t.dz() == 0 && steps == 0 && !t.mirror()) return null;
        long[] pos = new long[s.size()];
        BlockState[] states = new BlockState[pos.length];
        long[] beforePos = new long[pos.length];
        BlockState[] beforeStates = new BlockState[pos.length];
        int i = 0;
        for (Long2ObjectMap.Entry<BlockState> e : s.blocks().long2ObjectEntrySet()) {
            long p = e.getLongKey();
            BlockState st = e.getValue();
            beforePos[i] = p;
            beforeStates[i] = st;
            int x = BlockPos.getX(p) - b.minX(), y = BlockPos.getY(p) - b.minY(), z = BlockPos.getZ(p) - b.minZ();
            int sx = b.sizeX(), sz = b.sizeZ();
            if (t.mirror()) {
                x = sx - 1 - x;
                st = st.mirror(Mirror.FRONT_BACK);
            }
            for (int k = 0; k < steps; k++) {
                int nx = sz - 1 - z;
                int nz = x;
                x = nx;
                z = nz;
                int tmp = sx;
                sx = sz;
                sz = tmp;
                st = st.rotate(Rotation.CLOCKWISE_90);
            }
            int wx = b.minX() + x + t.dx(), wy = b.minY() + y + t.dy(), wz = b.minZ() + z + t.dz();
            if (!m.isInside(wx, wy, wz)) return "designandbuild.msg.outside_area";
            pos[i] = BlockPos.asLong(wx, wy, wz);
            states[i] = st;
            i++;
        }
        Rotation rot = s.rotation;
        for (int k = 0; k < steps; k++) rot = rot.getRotated(Rotation.CLOCKWISE_90);
        Mirror mirror = t.mirror() ? (s.mirror == Mirror.NONE ? Mirror.FRONT_BACK : Mirror.NONE) : s.mirror;
        ctx.ops.add(new ModelOps.StructReplace(s.id, rot, mirror, pos, states));
        ctx.undo.add(new DesignUndo.StructReplaced(s.id, beforePos, beforeStates, s.rotation, s.mirror, 0));
        return null;
    }

    @Nullable
    private static String structDelete(EditContext ctx, Requests.StructDelete t) {
        BlueprintModel m = BlueprintModel.of(ctx.area);
        StructureInstance s = m.structure(t.id());
        if (s == null) return null;
        ctx.undo.add(new DesignUndo.StructDeleted(DesignUndo.Snapshot.of(m, s)));
        ctx.ops.add(new ModelOps.StructDelete(t.id()));
        return null;
    }

    @Nullable
    private static String structReorder(EditContext ctx, Requests.StructReorder t) {
        BlueprintModel m = BlueprintModel.of(ctx.area);
        if (m.structure(t.id()) == null) return null;
        int before = m.orderIndexOf(t.id());
        int after = Math.max(0, Math.min(t.index(), m.structureCount() - 1));
        if (before == after) return null;
        ctx.ops.add(new ModelOps.StructReorder(t.id(), after));
        ctx.undo.add(new DesignUndo.StructReordered(t.id(), before, after));
        return null;
    }

    @Nullable
    private static String structRename(EditContext ctx, Requests.StructRename t) {
        BlueprintModel m = BlueprintModel.of(ctx.area);
        StructureInstance s = m.structure(t.id());
        String name = EditRequests.sanitize(t.name(), 64);
        if (s == null || name.isEmpty() || name.equals(s.name)) return null;
        ctx.undo.add(new DesignUndo.StructRenamed(t.id(), s.name, name));
        ctx.ops.add(new ModelOps.StructRename(t.id(), name));
        return null;
    }

    private static long[] toLongs(List<Long> list) {
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
