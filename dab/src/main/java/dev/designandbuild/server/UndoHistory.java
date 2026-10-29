package dev.designandbuild.server;

import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.DesignTag;
import dev.designandbuild.core.ModelOps;
import dev.designandbuild.core.StructureInstance;
import dev.designandbuild.core.TerrainCell;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * 플레이어 한 명이 도면 하나에 한 편집 기록. 되돌리기는 "내가 바꾼 뒤 아무도 건드리지 않은 칸"만 되돌린다.
 */
public final class UndoHistory {
    private static final int LIMIT = 100;

    private final Deque<Entry> undo = new ArrayDeque<>();
    private final Deque<Entry> redo = new ArrayDeque<>();

    public static final class Entry {
        final int group;
        final List<Change> changes = new ArrayList<>();

        Entry(int group) {
            this.group = group;
        }

        public boolean isEmpty() {
            return changes.isEmpty();
        }
    }

    public Entry newEntry(int group) {
        return new Entry(group);
    }

    public void push(Entry entry) {
        if (entry.isEmpty()) return;
        redo.clear();
        Entry last = undo.peekLast();
        if (last != null && entry.group != 0 && last.group == entry.group) {
            last.changes.addAll(entry.changes);
            return;
        }
        undo.addLast(entry);
        while (undo.size() > LIMIT) undo.removeFirst();
    }

    /** 되돌리기. 적용할 연산을 out 에 담는다. 되돌릴 게 없으면 false. */
    public boolean undo(BlueprintModel m, List<ModelOps.Op> out) {
        Entry e = undo.pollLast();
        if (e == null) return false;
        Entry inverse = invert(m, e, out);
        if (!inverse.isEmpty()) redo.addLast(inverse);
        return true;
    }

    public boolean redo(BlueprintModel m, List<ModelOps.Op> out) {
        Entry e = redo.pollLast();
        if (e == null) return false;
        Entry inverse = invert(m, e, out);
        if (!inverse.isEmpty()) undo.addLast(inverse);
        return true;
    }

    // ------------------------------------------------------------------

    sealed interface Change permits TerrainChange, StructCellChange, StructCreated, StructDeleted, StructReplaced,
            StructReordered, StructRenamed, BlueprintRenamed {}

    record TerrainChange(long pos, @Nullable TerrainCell before, @Nullable TerrainCell after) implements Change {}

    record StructCellChange(int id, long pos, @Nullable BlockState before, @Nullable BlockState after) implements Change {}

    /** 생성 직후 내용 해시. 그 사이 누가 바꿨으면 되돌리지 않는다. */
    record StructCreated(int id, long hashAfter) implements Change {}

    record StructDeleted(Snapshot snapshot) implements Change {}

    record StructReplaced(int id, long[] beforePos, BlockState[] beforeStates, Rotation beforeRot, Mirror beforeMirror,
                          long hashAfter) implements Change {}

    record StructReordered(int id, int before, int after) implements Change {}

    record StructRenamed(int id, String before, String after) implements Change {}

    record BlueprintRenamed(String before, String after) implements Change {}

    /** 구조물 전체 사본 (삭제 되돌리기용). */
    record Snapshot(int id, int index, String name, String source, Direction mount, Rotation rotation, Mirror mirror,
                    long[] pos, BlockState[] states) {
        static Snapshot of(BlueprintModel m, StructureInstance s) {
            long[] pos = new long[s.size()];
            BlockState[] states = new BlockState[pos.length];
            int i = 0;
            for (Long2ObjectMap.Entry<BlockState> e : s.blocks().long2ObjectEntrySet()) {
                pos[i] = e.getLongKey();
                states[i] = e.getValue();
                i++;
            }
            return new Snapshot(s.id, m.orderIndexOf(s.id), s.name, s.source, s.mount, s.rotation, s.mirror, pos, states);
        }
    }

    public static long hashOf(long[] pos, BlockState[] states) {
        long h = 0;
        int n = 0;
        for (int i = 0; i < pos.length; i++) {
            if (states[i] == null) continue;
            long v = pos[i] * 0x9E3779B97F4A7C15L + Block.getId(states[i]);
            h += v ^ (v >>> 29);
            n++;
        }
        return h + n;
    }

    // ------------------------------------------------------------------

    /** 기록을 거꾸로 적용할 연산을 만들고, 그 반대 기록(다시 하기용)을 돌려준다. */
    /** 되돌리는 중 아직 적용 전인 칸 변경 (같은 칸이 한 기록에 여러 번 나올 때 순서대로 맞추려고). */
    private static final Object REMOVED = new Object();

    private static final class Virtual {
        final BlueprintModel m;
        final Long2ObjectOpenHashMap<Object> terrain = new Long2ObjectOpenHashMap<>();
        final Int2ObjectOpenHashMap<Long2ObjectOpenHashMap<Object>> struct = new Int2ObjectOpenHashMap<>();

        Virtual(BlueprintModel m) {
            this.m = m;
        }

        @Nullable
        TerrainCell terrainAt(long pos) {
            Object v = terrain.get(pos);
            if (v == null) return m.terrainAt(pos);
            return v == REMOVED ? null : (TerrainCell) v;
        }

        void setTerrain(long pos, @Nullable TerrainCell cell) {
            terrain.put(pos, cell == null ? REMOVED : cell);
        }

        @Nullable
        BlockState structAt(StructureInstance st, long pos) {
            Long2ObjectOpenHashMap<Object> map = struct.get(st.id);
            Object v = map == null ? null : map.get(pos);
            if (v == null) return st.get(pos);
            return v == REMOVED ? null : (BlockState) v;
        }

        void setStruct(int id, long pos, @Nullable BlockState state) {
            struct.computeIfAbsent(id, k -> new Long2ObjectOpenHashMap<>()).put(pos, state == null ? REMOVED : state);
        }

        /** 아직 적용 전인 칸 변경까지 반영한 구조물 사본. */
        Snapshot snapshot(StructureInstance st) {
            Snapshot real = Snapshot.of(m, st);
            Long2ObjectOpenHashMap<Object> over = struct.get(st.id);
            if (over == null || over.isEmpty()) return real;
            Long2ObjectOpenHashMap<BlockState> copy = new Long2ObjectOpenHashMap<>(st.blocks());
            for (Long2ObjectMap.Entry<Object> e : over.long2ObjectEntrySet()) {
                if (e.getValue() == REMOVED) copy.remove(e.getLongKey());
                else copy.put(e.getLongKey(), (BlockState) e.getValue());
            }
            long[] pos = copy.keySet().toLongArray();
            BlockState[] states = new BlockState[pos.length];
            for (int i = 0; i < pos.length; i++) states[i] = copy.get(pos[i]);
            return new Snapshot(real.id(), real.index(), real.name(), real.source(), real.mount(), real.rotation(),
                    real.mirror(), pos, states);
        }

        /** 아직 적용 전인 칸 변경까지 반영한 내용 해시. */
        long contentHash(StructureInstance st) {
            Long2ObjectOpenHashMap<Object> over = struct.get(st.id);
            if (over == null || over.isEmpty()) return st.contentHash();
            Long2ObjectOpenHashMap<BlockState> copy = new Long2ObjectOpenHashMap<>(st.blocks());
            for (Long2ObjectMap.Entry<Object> e : over.long2ObjectEntrySet()) {
                if (e.getValue() == REMOVED) copy.remove(e.getLongKey());
                else copy.put(e.getLongKey(), (BlockState) e.getValue());
            }
            long h = copy.size();
            for (Long2ObjectMap.Entry<BlockState> e : copy.long2ObjectEntrySet()) {
                long v = e.getLongKey() * 0x9E3779B97F4A7C15L + Block.getId(e.getValue());
                h += v ^ (v >>> 29);
            }
            return h;
        }
    }

    private static Entry invert(BlueprintModel m, Entry e, List<ModelOps.Op> out) {
        Entry inverse = new Entry(0);
        OpBuilder ops = new OpBuilder(out);
        Virtual view = new Virtual(m);
        for (int i = e.changes.size() - 1; i >= 0; i--) {
            Change c = e.changes.get(i);
            switch (c) {
                case TerrainChange t -> {
                    if (Objects.equals(view.terrainAt(t.pos), t.after)) {
                        ops.terrain(t.pos, t.before);
                        view.setTerrain(t.pos, t.before);
                        inverse.changes.add(new TerrainChange(t.pos, t.after, t.before));
                    }
                }
                case StructCellChange s -> {
                    StructureInstance st = m.structure(s.id);
                    if (st != null && view.structAt(st, s.pos) == s.after) {
                        ops.structCell(s.id, s.pos, s.before);
                        view.setStruct(s.id, s.pos, s.before);
                        inverse.changes.add(new StructCellChange(s.id, s.pos, s.after, s.before));
                    }
                }
                case StructCreated s -> {
                    StructureInstance st = m.structure(s.id);
                    if (st != null && view.contentHash(st) == s.hashAfter) {
                        Snapshot snap = view.snapshot(st);
                        ops.flush();
                        out.add(new ModelOps.StructDelete(s.id));
                        inverse.changes.add(new StructDeleted(snap));
                    }
                }
                case StructDeleted s -> {
                    Snapshot snap = s.snapshot;
                    if (m.structure(snap.id()) == null) {
                        ops.flush();
                        out.add(new ModelOps.StructCreate(snap.id(), snap.index(), snap.name(), snap.source(), snap.mount(),
                                snap.rotation(), snap.mirror(), snap.pos(), snap.states()));
                        inverse.changes.add(new StructCreated(snap.id(), hashOf(snap.pos(), snap.states())));
                    }
                }
                case StructReplaced s -> {
                    StructureInstance st = m.structure(s.id);
                    if (st != null && view.contentHash(st) == s.hashAfter) {
                        Snapshot now = view.snapshot(st);
                        ops.flush();
                        out.add(new ModelOps.StructReplace(s.id, s.beforeRot, s.beforeMirror, s.beforePos, s.beforeStates));
                        inverse.changes.add(new StructReplaced(s.id, now.pos(), now.states(), now.rotation(), now.mirror(),
                                hashOf(s.beforePos, s.beforeStates)));
                    }
                }
                case StructReordered s -> {
                    if (m.structure(s.id) != null && m.orderIndexOf(s.id) == s.after) {
                        ops.flush();
                        out.add(new ModelOps.StructReorder(s.id, s.before));
                        inverse.changes.add(new StructReordered(s.id, s.after, s.before));
                    }
                }
                case StructRenamed s -> {
                    StructureInstance st = m.structure(s.id);
                    if (st != null && st.name.equals(s.after)) {
                        ops.flush();
                        out.add(new ModelOps.StructRename(s.id, s.before));
                        inverse.changes.add(new StructRenamed(s.id, s.after, s.before));
                    }
                }
                case BlueprintRenamed s -> {
                    if (m.name.equals(s.after)) {
                        ops.flush();
                        out.add(new ModelOps.Rename(s.before));
                        inverse.changes.add(new BlueprintRenamed(s.after, s.before));
                    }
                }
            }
        }
        ops.flush();
        // 다시 하기는 원래 순서대로 적용되어야 하므로 뒤집는다
        java.util.Collections.reverse(inverse.changes);
        return inverse;
    }

    /** 연속된 같은 종류의 칸 변경을 연산 하나로 묶는다. */
    private static final class OpBuilder {
        private final List<ModelOps.Op> out;
        private final List<Long> tPos = new ArrayList<>();
        private final List<BlockState> tStates = new ArrayList<>();
        private final List<DesignTag> tTags = new ArrayList<>();
        private int sId = -1;
        private final List<Long> sPos = new ArrayList<>();
        private final List<BlockState> sStates = new ArrayList<>();

        OpBuilder(List<ModelOps.Op> out) {
            this.out = out;
        }

        void terrain(long pos, @Nullable TerrainCell cell) {
            flushStruct();
            tPos.add(pos);
            tStates.add(cell == null ? null : cell.state());
            tTags.add(cell == null ? DesignTag.NONE : cell.tag());
        }

        void structCell(int id, long pos, @Nullable BlockState state) {
            flushTerrain();
            if (sId != id) flushStruct();
            sId = id;
            sPos.add(pos);
            sStates.add(state);
        }

        void flush() {
            flushTerrain();
            flushStruct();
        }

        private void flushTerrain() {
            if (tPos.isEmpty()) return;
            long[] pos = new long[tPos.size()];
            for (int i = 0; i < pos.length; i++) pos[i] = tPos.get(i);
            out.add(new ModelOps.Terrain(pos, tStates.toArray(new BlockState[0]), tTags.toArray(new DesignTag[0])));
            tPos.clear();
            tStates.clear();
            tTags.clear();
        }

        private void flushStruct() {
            if (sPos.isEmpty()) return;
            long[] pos = new long[sPos.size()];
            for (int i = 0; i < pos.length; i++) pos[i] = sPos.get(i);
            out.add(new ModelOps.StructCells(sId, pos, sStates.toArray(new BlockState[0])));
            sPos.clear();
            sStates.clear();
            sId = -1;
        }
    }
}
