package dev.designandbuild.core;

import dev.designandbuild.DesignAndBuild;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaLayer;
import dev.minedraft.lib.area.AreaType;
import dev.minedraft.lib.area.LayerKey;
import dev.minedraft.lib.util.Box;
import dev.minedraft.lib.util.CellIO;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * 도면 하나의 설계 데이터 = Minedraft Lib 영역의 DaB 설계층. 서버(원본)와 클라이언트(사본) 양쪽에서 같은 코드로 쓴다.
 * 영역 정보(청크, 이름, 버전, 변경 알림)는 Lib 의 {@link Area} 가 갖고, 여기서는 그대로 넘겨 준다.
 *
 * <pre>
 * 지형 층   : 칸마다 (블록, 바닥/길/철거 태그)
 * 구조물    : 인스턴스마다 자기 블록 (공기 = 공기 지정), 건축 순서 목록
 * 최종 목표 : 지형 층 위에 구조물을 순서대로 덮어 계산 (resolved)
 *   - 구조물의 실제 블록은 덮어쓴다 (앞 구조물의 다른 실제 블록을 덮으면 conflict)
 *   - 구조물의 공기는 태그 블록(바닥, 길, 앞 구조물의 실제 블록)을 지우지 못한다
 * </pre>
 *
 * 모든 변경은 ModelOps 를 거치고, 끝나면 {@link #flush()} 로 최종 목표를 다시 계산한다.
 */
public final class BlueprintModel implements AreaLayer {
    public static final ResourceLocation ID = DesignAndBuild.rl("design");
    public static final LayerKey<BlueprintModel> KEY = new LayerKey<>(ID, BlueprintModel.class);
    public static final int MAX_CHUNKS = 100;
    /** DaB 도면 영역: 처음 3×3 청크, 최대 100 청크, 설계층 하나. */
    public static final AreaType AREA_TYPE = new AreaType(DesignAndBuild.rl("blueprint"), MAX_CHUNKS, 1,
            List.of(BlueprintModel::new));

    public static BlueprintModel of(Area area) {
        return area.layer(KEY);
    }

    @Nullable
    public static BlueprintModel ofNullable(@Nullable Area area) {
        return area == null ? null : area.layerOrNull(KEY);
    }

    public final Area area;
    public final UUID id;
    public final ResourceKey<Level> dimension;
    public final int baseX, baseZ;
    /** 건축 가능 높이 [minY, maxY) */
    public final int minY, maxY;
    public int nextStructureId = 1;

    private final Long2ObjectOpenHashMap<TerrainCell> terrain = new Long2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<StructureInstance> structures = new Int2ObjectOpenHashMap<>();
    private final IntArrayList order = new IntArrayList();

    // ---- 파생 데이터 ----
    private final Long2ObjectOpenHashMap<IntArrayList> posIndex = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<Resolved> resolved = new Long2ObjectOpenHashMap<>();
    private final Int2IntOpenHashMap orderIndex = new Int2IntOpenHashMap();
    private final LongOpenHashSet pending = new LongOpenHashSet();

    private long conflictCacheRevision = -1;
    private final Int2IntOpenHashMap conflictCache = new Int2IntOpenHashMap();

    private BlueprintModel(Area area) {
        this.area = area;
        this.id = area.id;
        this.dimension = area.dimension;
        this.baseX = area.baseX;
        this.baseZ = area.baseZ;
        this.minY = area.minY;
        this.maxY = area.maxY;
        this.orderIndex.defaultReturnValue(Integer.MAX_VALUE);
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    // ---- 영역 정보 (Lib) ----

    public String name() {
        return area.name();
    }

    public long version() {
        return area.version;
    }

    public long revision() {
        return area.revision();
    }

    public void addListener(LongConsumer listener) {
        area.addListener(listener);
    }

    public void removeListener(LongConsumer listener) {
        area.removeListener(listener);
    }

    public long baseChunk() {
        return area.baseChunk();
    }

    public LongSet chunks() {
        return area.chunks();
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        return area.hasChunk(chunkX, chunkZ);
    }

    public boolean isInside(int x, int y, int z) {
        return area.isInside(x, y, z);
    }

    public boolean isInside(long pos) {
        return area.isInside(pos);
    }

    public boolean isInsideColumn(int x, int z) {
        return area.isInsideColumn(x, z);
    }

    public Box areaBounds() {
        return area.areaBounds();
    }

    @Nullable
    public String checkChunkChange(LongSet add, LongSet remove) {
        return area.checkChunkChange(add, remove);
    }

    // ------------------------------------------------------------------
    // 읽기
    // ------------------------------------------------------------------

    public Long2ObjectMap<TerrainCell> terrain() {
        return Long2ObjectMaps.unmodifiable(terrain);
    }

    @Nullable
    public TerrainCell terrainAt(long pos) {
        return terrain.get(pos);
    }

    @Nullable
    public Resolved resolved(long pos) {
        return resolved.get(pos);
    }

    @Nullable
    public Resolved resolved(int x, int y, int z) {
        return resolved.get(BlockPos.asLong(x, y, z));
    }

    public Long2ObjectMap<Resolved> resolvedMap() {
        return Long2ObjectMaps.unmodifiable(resolved);
    }

    @Nullable
    public StructureInstance structure(int id) {
        return structures.get(id);
    }

    public int structureCount() {
        return structures.size();
    }

    public IntList order() {
        return IntLists.unmodifiable(order);
    }

    public int orderIndexOf(int id) {
        return orderIndex.get(id);
    }

    public List<StructureInstance> orderedStructures() {
        List<StructureInstance> list = new ArrayList<>(order.size());
        for (int i = 0; i < order.size(); i++) {
            StructureInstance s = structures.get(order.getInt(i));
            if (s != null) list.add(s);
        }
        return list;
    }

    /** 구조물별 덮어쓰기 충돌 칸 수 (덮어쓴 쪽 기준). */
    public int conflictsOf(int structureId) {
        if (conflictCacheRevision != area.revision()) {
            conflictCache.clear();
            for (Resolved r : resolved.values()) {
                if (r.conflict()) conflictCache.addTo(r.owner(), 1);
            }
            conflictCacheRevision = area.revision();
        }
        return conflictCache.get(structureId);
    }

    /** 충돌이 있는 칸 하나 (카메라 이동용). 없으면 Long.MIN_VALUE. */
    public long firstConflictOf(int structureId) {
        for (Long2ObjectMap.Entry<Resolved> e : resolved.long2ObjectEntrySet()) {
            if (e.getValue().conflict() && e.getValue().owner() == structureId) return e.getLongKey();
        }
        return Long.MIN_VALUE;
    }

    /** 청크 안에 기록된 설계 칸 수와 걸쳐 있는 구조물 (제거 경고용). */
    public int designCellsInChunk(long chunk, List<StructureInstance> touched) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        int count = 0;
        for (long p : terrain.keySet()) {
            if ((BlockPos.getX(p) >> 4) == cx && (BlockPos.getZ(p) >> 4) == cz) count++;
        }
        for (StructureInstance s : orderedStructures()) {
            boolean hit = false;
            for (long p : s.blocks.keySet()) {
                if ((BlockPos.getX(p) >> 4) == cx && (BlockPos.getZ(p) >> 4) == cz) {
                    count++;
                    hit = true;
                }
            }
            if (hit) touched.add(s);
        }
        return count;
    }

    // ------------------------------------------------------------------
    // 변경 (ModelOps 에서만 부른다)
    // ------------------------------------------------------------------

    public void putTerrain(long pos, @Nullable TerrainCell cell) {
        if (cell == null) {
            if (terrain.remove(pos) != null) pending.add(pos);
        } else {
            TerrainCell old = terrain.put(pos, cell);
            if (!cell.equals(old)) pending.add(pos);
        }
    }

    public void putStructureBlock(StructureInstance s, long pos, @Nullable BlockState state) {
        if (state == null) {
            if (s.blocks.remove(pos) != null) {
                removeIndex(pos, s.id);
                s.markBoundsDirty();
                pending.add(pos);
            }
        } else {
            BlockState old = s.blocks.put(pos, state);
            if (old == null) {
                addIndex(pos, s.id);
                s.markBoundsDirty();
            }
            if (old != state) pending.add(pos);
        }
    }

    public void clearStructureBlocks(StructureInstance s) {
        for (long p : s.blocks.keySet()) {
            removeIndex(p, s.id);
            pending.add(p);
        }
        s.blocks.clear();
        s.markBoundsDirty();
    }

    public StructureInstance createStructure(int id, int index, String name, String source, Direction mount,
                                             Rotation rotation, Mirror mirror) {
        StructureInstance old = structures.get(id);
        if (old != null) deleteStructure(id);
        StructureInstance s = new StructureInstance(id, name, source, mount, rotation, mirror);
        structures.put(id, s);
        int idx = index < 0 || index > order.size() ? order.size() : index;
        order.add(idx, id);
        rebuildOrderIndex();
        if (id >= nextStructureId) nextStructureId = id + 1;
        area.bump();
        return s;
    }

    public void deleteStructure(int id) {
        StructureInstance s = structures.remove(id);
        if (s == null) return;
        for (long p : s.blocks.keySet()) {
            removeIndex(p, id);
            pending.add(p);
        }
        order.rem(id);
        rebuildOrderIndex();
        area.bump();
    }

    public void moveInOrder(int id, int index) {
        StructureInstance s = structures.get(id);
        if (s == null) return;
        order.rem(id);
        int idx = Math.max(0, Math.min(index, order.size()));
        order.add(idx, id);
        rebuildOrderIndex();
        pending.addAll(s.blocks.keySet());
        area.bump();
    }

    public void renameStructure(int id, String newName) {
        StructureInstance s = structures.get(id);
        if (s != null) {
            s.name = newName;
            area.bump();
        }
    }

    @Override
    public void collectUsedChunks(it.unimi.dsi.fastutil.longs.LongSet out) {
        // 설계 칸·구조물 블록이 하나라도 있는 청크
        it.unimi.dsi.fastutil.longs.LongIterator it = resolved.keySet().iterator();
        while (it.hasNext()) {
            long p = it.nextLong();
            out.add(ChunkPos.asLong(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4));
        }
    }

    /** 청크가 빠졌다: 그 안의 설계를 모두 지운다. 구조물은 남은 블록만 남는다. */
    @Override
    public void onChunkRemoved(long chunk) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        LongIterator it = terrain.keySet().iterator();
        while (it.hasNext()) {
            long p = it.nextLong();
            if ((BlockPos.getX(p) >> 4) == cx && (BlockPos.getZ(p) >> 4) == cz) {
                it.remove();
                pending.add(p);
            }
        }
        List<Integer> emptied = new ArrayList<>();
        for (StructureInstance s : structures.values()) {
            LongIterator sit = s.blocks.keySet().iterator();
            boolean changed = false;
            while (sit.hasNext()) {
                long p = sit.nextLong();
                if ((BlockPos.getX(p) >> 4) == cx && (BlockPos.getZ(p) >> 4) == cz) {
                    sit.remove();
                    removeIndex(p, s.id);
                    pending.add(p);
                    changed = true;
                }
            }
            if (changed) {
                s.markBoundsDirty();
                if (s.blocks.isEmpty()) emptied.add(s.id);
            }
        }
        for (int id : emptied) deleteStructure(id);
        flush();
    }

    /** 대기 중인 칸들의 최종 목표를 다시 계산하고 영역 구독자에게 변경을 알린다. */
    public void flush() {
        if (pending.isEmpty()) return;
        LongIterator it = pending.iterator();
        while (it.hasNext()) {
            long p = it.nextLong();
            resolveAt(p);
            area.markChanged(p);
        }
        pending.clear();
        area.flush();
    }

    /** 불러온 직후 파생 데이터를 처음부터 만든다. */
    @Override
    public void afterLoad() {
        posIndex.clear();
        resolved.clear();
        rebuildOrderIndex();
        for (StructureInstance s : structures.values()) {
            for (long p : s.blocks.keySet()) addIndex(p, s.id);
            s.markBoundsDirty();
        }
        pending.addAll(terrain.keySet());
        pending.addAll(posIndex.keySet());
        flush();
    }

    // ---- 불러오기 전용 (코덱) ----

    void loadTerrain(long pos, TerrainCell cell) {
        terrain.put(pos, cell);
    }

    void loadStructure(StructureInstance s) {
        structures.put(s.id, s);
        order.add(s.id);
        if (s.id >= nextStructureId) nextStructureId = s.id + 1;
    }

    // ------------------------------------------------------------------

    private void resolveAt(long pos) {
        TerrainCell t = terrain.get(pos);
        BlockState state = t == null ? null : t.state();
        DesignTag tag = t == null ? null : t.tag();
        int owner = 0;
        boolean conflict = false;
        IntArrayList ids = posIndex.get(pos);
        if (ids != null && !ids.isEmpty()) {
            int[] sorted = ids.toIntArray();
            // 건축 순서대로 (삽입 정렬, 보통 1~2개)
            for (int i = 1; i < sorted.length; i++) {
                int v = sorted[i];
                int key = orderIndex.get(v);
                int j = i - 1;
                while (j >= 0 && orderIndex.get(sorted[j]) > key) {
                    sorted[j + 1] = sorted[j];
                    j--;
                }
                sorted[j + 1] = v;
            }
            for (int sid : sorted) {
                StructureInstance s = structures.get(sid);
                if (s == null) continue;
                BlockState b = s.blocks.get(pos);
                if (b == null) continue;
                if (!b.isAir()) {
                    if (owner != 0 && state != null && !state.isAir() && state != b) conflict = true;
                    state = b;
                    tag = DesignTag.STRUCTURE;
                    owner = sid;
                } else {
                    // 공기 지정은 태그가 붙은 실제 블록을 지우지 못한다
                    boolean protectedCell = state != null && !state.isAir() && tag != DesignTag.NONE;
                    if (!protectedCell) {
                        state = b;
                        tag = DesignTag.STRUCTURE;
                        owner = sid;
                    }
                }
            }
        }
        if (state == null) {
            resolved.remove(pos);
        } else {
            resolved.put(pos, new Resolved(state, tag == null ? DesignTag.NONE : tag, owner, conflict));
        }
    }

    private void addIndex(long pos, int sid) {
        IntArrayList l = posIndex.get(pos);
        if (l == null) {
            l = new IntArrayList(2);
            posIndex.put(pos, l);
        }
        if (!l.contains(sid)) l.add(sid);
    }

    private void removeIndex(long pos, int sid) {
        IntArrayList l = posIndex.get(pos);
        if (l != null) {
            l.rem(sid);
            if (l.isEmpty()) posIndex.remove(pos);
        }
    }

    private void rebuildOrderIndex() {
        orderIndex.clear();
        for (int i = 0; i < order.size(); i++) orderIndex.put(order.getInt(i), i);
    }

    // ------------------------------------------------------------------
    // 통신·저장 (설계층 내용만, 영역 정보는 Lib)
    // ------------------------------------------------------------------

    @Override
    public void writeNet(FriendlyByteBuf buf) {
        buf.writeVarInt(nextStructureId);
        long[] pos = new long[terrain.size()];
        BlockState[] states = new BlockState[pos.length];
        DesignTag[] tags = new DesignTag[pos.length];
        int i = 0;
        for (Long2ObjectMap.Entry<TerrainCell> e : terrain.long2ObjectEntrySet()) {
            pos[i] = e.getLongKey();
            states[i] = e.getValue().state();
            tags[i] = e.getValue().tag();
            i++;
        }
        CellIO.writePositions(buf, pos);
        CellIO.writeStates(buf, states);
        DesignTag.writeAll(buf, tags);
        List<StructureInstance> list = orderedStructures();
        buf.writeVarInt(list.size());
        for (StructureInstance st : list) {
            buf.writeVarInt(st.id);
            buf.writeUtf(st.name);
            buf.writeUtf(st.source);
            CellIO.writeDirection(buf, st.mount);
            CellIO.writeRotation(buf, st.rotation);
            CellIO.writeMirror(buf, st.mirror);
            long[] sp = new long[st.blocks.size()];
            BlockState[] ss = new BlockState[sp.length];
            int j = 0;
            for (Long2ObjectMap.Entry<BlockState> e : st.blocks.long2ObjectEntrySet()) {
                sp[j] = e.getLongKey();
                ss[j] = e.getValue();
                j++;
            }
            CellIO.writePositions(buf, sp);
            CellIO.writeStates(buf, ss);
        }
    }

    @Override
    public void readNet(FriendlyByteBuf buf) {
        nextStructureId = buf.readVarInt();
        long[] pos = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
        BlockState[] states = CellIO.readStates(buf, pos.length);
        DesignTag[] tags = DesignTag.readAll(buf, pos.length);
        for (int i = 0; i < pos.length; i++) {
            if (states[i] != null) loadTerrain(pos[i], new TerrainCell(states[i], tags[i]));
        }
        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            int sid = buf.readVarInt();
            String sname = buf.readUtf();
            String source = buf.readUtf();
            Direction mount = CellIO.readDirection(buf);
            Rotation rot = CellIO.readRotation(buf);
            Mirror mirror = CellIO.readMirror(buf);
            StructureInstance st = new StructureInstance(sid, sname, source, mount, rot, mirror);
            long[] sp = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
            BlockState[] ss = CellIO.readStates(buf, sp.length);
            for (int j = 0; j < sp.length; j++) {
                if (ss[j] != null) st.blocks.put(sp[j], ss[j]);
            }
            loadStructure(st);
        }
    }

    @Override
    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("nextId", nextStructureId);
        Palette palette = new Palette();
        long[] pos = new long[terrain.size()];
        int[] st = new int[pos.length];
        byte[] tg = new byte[pos.length];
        int i = 0;
        for (Long2ObjectMap.Entry<TerrainCell> e : terrain.long2ObjectEntrySet()) {
            pos[i] = e.getLongKey();
            st[i] = palette.id(e.getValue().state());
            tg[i] = (byte) e.getValue().tag().ordinal();
            i++;
        }
        CompoundTag t = new CompoundTag();
        t.putLongArray("pos", pos);
        t.putIntArray("state", st);
        t.putByteArray("tag", tg);
        tag.put("terrain", t);
        ListTag structs = new ListTag();
        for (StructureInstance s : orderedStructures()) {
            CompoundTag c = new CompoundTag();
            c.putInt("id", s.id);
            c.putString("name", s.name);
            c.putString("source", s.source);
            c.putInt("mount", s.mount.get3DDataValue());
            c.putInt("rot", s.rotation.ordinal());
            c.putInt("mirror", s.mirror.ordinal());
            long[] sp = new long[s.blocks.size()];
            int[] ss = new int[sp.length];
            int j = 0;
            for (Long2ObjectMap.Entry<BlockState> e : s.blocks.long2ObjectEntrySet()) {
                sp[j] = e.getLongKey();
                ss[j] = palette.id(e.getValue());
                j++;
            }
            c.putLongArray("pos", sp);
            c.putIntArray("state", ss);
            structs.add(c);
        }
        tag.put("structures", structs);
        ListTag pal = new ListTag();
        for (BlockState s : palette.states) pal.add(NbtUtils.writeBlockState(s));
        tag.put("palette", pal);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        nextStructureId = Math.max(1, tag.getInt("nextId"));
        ListTag pal = tag.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[pal.size()];
        for (int i = 0; i < pal.size(); i++) {
            palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), pal.getCompound(i));
        }
        CompoundTag t = tag.getCompound("terrain");
        long[] pos = t.getLongArray("pos");
        int[] st = t.getIntArray("state");
        byte[] tg = t.getByteArray("tag");
        int n = Math.min(pos.length, Math.min(st.length, tg.length));
        for (int i = 0; i < n; i++) loadTerrain(pos[i], new TerrainCell(stateAt(palette, st[i]), DesignTag.byId(tg[i])));
        ListTag structs = tag.getList("structures", Tag.TAG_COMPOUND);
        Rotation[] rots = Rotation.values();
        Mirror[] mirrors = Mirror.values();
        for (int i = 0; i < structs.size(); i++) {
            CompoundTag c = structs.getCompound(i);
            int r = c.getInt("rot"), mi = c.getInt("mirror");
            StructureInstance s = new StructureInstance(c.getInt("id"), c.getString("name"), c.getString("source"),
                    Direction.from3DDataValue(c.getInt("mount")),
                    r >= 0 && r < rots.length ? rots[r] : Rotation.NONE,
                    mi >= 0 && mi < mirrors.length ? mirrors[mi] : Mirror.NONE);
            long[] sp = c.getLongArray("pos");
            int[] ss = c.getIntArray("state");
            int sn = Math.min(sp.length, ss.length);
            for (int j = 0; j < sn; j++) s.blocks.put(sp[j], stateAt(palette, ss[j]));
            loadStructure(s);
        }
    }

    private static BlockState stateAt(BlockState[] palette, int index) {
        return index >= 0 && index < palette.length ? palette[index] : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
    }

    private static final class Palette {
        final List<BlockState> states = new ArrayList<>();
        final it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<BlockState> ids = new it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap<>();

        Palette() {
            ids.defaultReturnValue(-1);
        }

        int id(BlockState s) {
            int i = ids.getInt(s);
            if (i < 0) {
                i = states.size();
                states.add(s);
                ids.put(s, i);
            }
            return i;
        }
    }
}
