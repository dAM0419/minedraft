package dev.minedraft.lib.space;

import dev.minedraft.lib.MinedraftLib;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaLayer;
import dev.minedraft.lib.area.LayerKey;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 공간 태그 층: 칸마다, 태그 묶음마다 태그 하나 (다른 묶음끼리는 겹친다). 모든 영역 종류에 첫 층으로 붙는다.
 * 다른 층(DaB 설계층 등)은 {@link #watch} 로 바뀐 칸을 받아 자기 파생 데이터를 다시 만든다.
 * <p>
 * 개체 묶음({@link TagGroup#instanced})의 칸은 개체 번호도 갖는다 (1 이상, 없으면 0). 어느 칸이 어느 개체인지는 이 층이
 * 원본이다: 모드는 번호별 설정(이름 등)만 자기 층에 두고, 칸 소속은 여기서 읽는다 ({@link #instanceAt}, {@link #instances}).
 */
public final class SpaceTagLayer implements AreaLayer {
    public static final ResourceLocation ID = MinedraftLib.rl("space_tags");
    public static final LayerKey<SpaceTagLayer> KEY = new LayerKey<>(ID, SpaceTagLayer.class);

    @Nullable
    public static SpaceTagLayer of(Area area) {
        return area.layerOrNull(KEY);
    }

    private final Area area;
    /** 묶음 ID → (칸 → 태그). */
    private final Map<ResourceLocation, Long2ObjectOpenHashMap<SpaceTag>> groups = new LinkedHashMap<>();
    /** 묶음 ID → (칸 → 개체 번호). 번호가 있는 칸만. */
    private final Map<ResourceLocation, Long2IntOpenHashMap> instanceOf = new HashMap<>();
    /** 묶음 ID → (개체 번호 → 개체). 칸에서 파생 (저장하지 않는다). */
    private final Map<ResourceLocation, Int2ObjectOpenHashMap<Instance>> instances = new HashMap<>();
    /** 묶음 ID → 다음에 줄 개체 번호 (서버가 매긴다. 지운 번호를 다시 쓰지 않도록 저장한다). */
    private final Map<ResourceLocation, Integer> nextInstance = new HashMap<>();
    private final List<Consumer<LongCollection>> watchers = new ArrayList<>();

    public SpaceTagLayer(Area area) {
        this.area = area;
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    /** 이 칸의 이 묶음 태그. */
    @Nullable
    public SpaceTag tagAt(long pos, ResourceLocation group) {
        Long2ObjectOpenHashMap<SpaceTag> m = groups.get(group);
        return m == null ? null : m.get(pos);
    }

    /** 이 칸의 이 묶음 개체 번호 (없으면 0). */
    public int instanceAt(long pos, ResourceLocation group) {
        Long2IntOpenHashMap m = instanceOf.get(group);
        return m == null ? 0 : m.get(pos);
    }

    /** 개체 하나: 태그와 칸 수. 칸이 하나도 남지 않으면 사라진다. */
    public static final class Instance {
        public final int id;
        public final SpaceTag tag;
        private int cells;

        Instance(int id, SpaceTag tag) {
            this.id = id;
            this.tag = tag;
        }

        public int cells() {
            return cells;
        }
    }

    /** 이 묶음의 개체 (없으면 null). */
    @Nullable
    public Instance instance(ResourceLocation group, int id) {
        Int2ObjectOpenHashMap<Instance> m = instances.get(group);
        return m == null ? null : m.get(id);
    }

    /** 이 묶음의 개체들 (읽기 전용, 순서 없음). */
    public java.util.Collection<Instance> instances(ResourceLocation group) {
        Int2ObjectOpenHashMap<Instance> m = instances.get(group);
        return m == null ? List.of() : Collections.unmodifiableCollection(m.values());
    }

    /** 개체의 칸들 (묶음 전체를 훑는다: 가끔만 부를 것). */
    public long[] instanceCells(ResourceLocation group, int id) {
        Long2IntOpenHashMap m = instanceOf.get(group);
        if (m == null || id <= 0) return new long[0];
        LongArrayList out = new LongArrayList();
        for (it.unimi.dsi.fastutil.longs.Long2IntMap.Entry e : m.long2IntEntrySet()) if (e.getIntValue() == id) out.add(e.getLongKey());
        return out.toLongArray();
    }

    /** 새 개체 번호 (서버 전용). 번호를 다시 쓰지 않는다. */
    public int allocateInstance(ResourceLocation group) {
        int id = nextInstance.getOrDefault(group, 1);
        nextInstance.put(group, id + 1);
        return id;
    }

    /** 이 칸에 태그가 하나라도 있는가 (목록을 만들지 않는다: 시선 고르기처럼 자주 묻는 곳용). */
    public boolean hasAny(long pos) {
        for (Long2ObjectOpenHashMap<SpaceTag> m : groups.values()) if (m.containsKey(pos)) return true;
        return false;
    }

    /** 이 칸의 모든 태그 (묶음 순서). */
    public List<SpaceTag> tagsAt(long pos) {
        List<SpaceTag> out = new ArrayList<>(2);
        for (Long2ObjectOpenHashMap<SpaceTag> m : groups.values()) {
            SpaceTag t = m.get(pos);
            if (t != null) out.add(t);
        }
        return out;
    }

    /** 한 묶음의 칸들 (읽기 전용). */
    public Long2ObjectMap<SpaceTag> cells(ResourceLocation group) {
        Long2ObjectOpenHashMap<SpaceTag> m = groups.get(group);
        return m == null ? Long2ObjectMaps.emptyMap() : Long2ObjectMaps.unmodifiable(m);
    }

    /** 칸이 있는 묶음 ID. */
    public java.util.Set<ResourceLocation> groupIds() {
        return Collections.unmodifiableSet(groups.keySet());
    }

    /** 바뀐 칸 묶음을 받는다 (태그 변경 연산 하나마다 한 번). */
    public void watch(Consumer<LongCollection> watcher) {
        watchers.add(watcher);
    }

    /**
     * 한 묶음에서 칸들의 태그를 바꾼다 (null = 지움). 영역 변경 연산 안에서만 부른다.
     * 바뀐 칸을 지켜보는 층에 알리고 영역 구독자에게 flush 한다.
     */
    public void set(ResourceLocation group, long[] pos, @Nullable SpaceTag[] tags) {
        set(group, pos, tags, null);
    }

    /** 개체 번호와 함께 (inst null = 모두 0). 태그를 지우는 칸의 번호는 0 이어야 한다. */
    public void set(ResourceLocation group, long[] pos, @Nullable SpaceTag[] tags, @Nullable int[] inst) {
        LongArrayList changed = new LongArrayList();
        for (int i = 0; i < pos.length; i++) {
            if (putQuiet(pos[i], group, tags[i], inst == null ? 0 : inst[i])) changed.add(pos[i]);
        }
        publish(changed);
    }

    /** 한 칸만 (다른 층이 자기 연산 안에서 같이 바꿀 때). 지켜보는 층 알림은 {@link #publish} 로 모아서. */
    public boolean putQuiet(long pos, ResourceLocation group, @Nullable SpaceTag tag) {
        return putQuiet(pos, group, tag, 0);
    }

    /** 개체 번호와 함께 (0 = 번호 없음). 태그가 null 이면 번호도 지워진다. */
    public boolean putQuiet(long pos, ResourceLocation group, @Nullable SpaceTag tag, int inst) {
        if (tag != null && !tag.group().equals(group)) throw new IllegalArgumentException(tag.id() + " is not in " + group);
        if (tag == null || inst < 0) inst = 0;
        SpaceTag old;
        if (tag == null) {
            Long2ObjectOpenHashMap<SpaceTag> m = groups.get(group);
            old = m == null ? null : m.remove(pos);
        } else {
            old = groups.computeIfAbsent(group, k -> new Long2ObjectOpenHashMap<>()).put(pos, tag);
        }
        int oldInst = instanceAt(pos, group);
        if (old == tag && oldInst == inst) return false;
        if (oldInst != inst || (inst != 0 && old != tag)) {
            if (oldInst != 0) unindex(group, pos, oldInst);
            if (inst != 0) index(group, pos, inst, tag);
        }
        area.markChanged(pos);
        return true;
    }

    private void index(ResourceLocation group, long pos, int inst, SpaceTag tag) {
        Long2IntOpenHashMap m = instanceOf.computeIfAbsent(group, k -> new Long2IntOpenHashMap());
        m.put(pos, inst);
        Int2ObjectOpenHashMap<Instance> im = instances.computeIfAbsent(group, k -> new Int2ObjectOpenHashMap<>());
        Instance in = im.get(inst);
        if (in == null || in.tag != tag) {
            // 개체의 태그는 하나: 처음 넣는 칸이 정한다 (서버가 섞이지 않게 거른다. 섞여 들어오면 마지막 것을 따른다)
            Instance fresh = new Instance(inst, tag);
            if (in != null) fresh.cells = in.cells;
            im.put(inst, in = fresh);
        }
        in.cells++;
        if (nextInstance.getOrDefault(group, 1) <= inst) nextInstance.put(group, inst + 1);
    }

    private void unindex(ResourceLocation group, long pos, int inst) {
        Long2IntOpenHashMap m = instanceOf.get(group);
        if (m != null) m.remove(pos);
        Int2ObjectOpenHashMap<Instance> im = instances.get(group);
        Instance in = im == null ? null : im.get(inst);
        if (in != null && --in.cells <= 0) im.remove(inst);
    }

    private void clearAll() {
        groups.clear();
        instanceOf.clear();
        instances.clear();
        nextInstance.clear();
    }

    /** 불러올 때: 칸 하나를 넣는다 (알림 없음). */
    private void loadCell(long pos, SpaceTag t, int inst) {
        groups.computeIfAbsent(t.group(), g -> new Long2ObjectOpenHashMap<>()).put(pos, t);
        if (inst > 0) index(t.group(), pos, inst, t);
    }

    /** 바뀐 칸을 지켜보는 층에 알리고 flush. */
    public void publish(LongCollection changed) {
        if (changed.isEmpty()) return;
        for (Consumer<LongCollection> w : watchers) w.accept(changed);
        area.flush();
    }

    // ------------------------------------------------------------------
    // 통신·저장: 태그 ID 목록 + 묶음마다 (칸, 목록 번호)
    // ------------------------------------------------------------------

    @Override
    public void writeNet(FriendlyByteBuf buf) {
        List<SpaceTag> palette = new ArrayList<>();
        Map<SpaceTag, Integer> index = new HashMap<>();
        for (Long2ObjectOpenHashMap<SpaceTag> m : groups.values())
            for (SpaceTag t : m.values()) if (index.putIfAbsent(t, palette.size()) == null) palette.add(t);
        buf.writeVarInt(palette.size());
        for (SpaceTag t : palette) buf.writeResourceLocation(t.id());
        int total = 0;
        for (Long2ObjectOpenHashMap<SpaceTag> m : groups.values()) total += m.size();
        buf.writeVarInt(total);
        for (Long2ObjectOpenHashMap<SpaceTag> m : groups.values()) {
            for (Long2ObjectMap.Entry<SpaceTag> e : m.long2ObjectEntrySet()) {
                buf.writeLong(e.getLongKey());
                buf.writeVarInt(index.get(e.getValue()));
                buf.writeVarInt(instanceAt(e.getLongKey(), e.getValue().group()));
            }
        }
    }

    @Override
    public void readNet(FriendlyByteBuf buf) {
        clearAll();
        int np = buf.readVarInt();
        SpaceTag[] palette = new SpaceTag[np];
        for (int i = 0; i < np; i++) palette[i] = SpaceTag.get(buf.readResourceLocation());
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++) {
            long p = buf.readLong();
            int k = buf.readVarInt();
            int inst = buf.readVarInt();
            SpaceTag t = k >= 0 && k < np ? palette[k] : null;
            if (t != null) loadCell(p, t, inst);
        }
    }

    @Override
    public CompoundTag save() {
        List<SpaceTag> palette = new ArrayList<>();
        Map<SpaceTag, Integer> index = new HashMap<>();
        LongArrayList pos = new LongArrayList();
        List<Integer> idx = new ArrayList<>();
        IntList inst = new IntArrayList();
        boolean anyInst = false;
        for (Long2ObjectOpenHashMap<SpaceTag> m : groups.values()) {
            for (Long2ObjectMap.Entry<SpaceTag> e : m.long2ObjectEntrySet()) {
                Integer k = index.get(e.getValue());
                if (k == null) {
                    k = palette.size();
                    index.put(e.getValue(), k);
                    palette.add(e.getValue());
                }
                pos.add(e.getLongKey());
                idx.add(k);
                int in = instanceAt(e.getLongKey(), e.getValue().group());
                inst.add(in);
                anyInst |= in != 0;
            }
        }
        CompoundTag tag = new CompoundTag();
        ListTag pal = new ListTag();
        for (SpaceTag t : palette) pal.add(StringTag.valueOf(t.id().toString()));
        tag.put("palette", pal);
        tag.putLongArray("pos", pos.toLongArray());
        tag.putIntArray("tag", idx.stream().mapToInt(Integer::intValue).toArray());
        if (anyInst) tag.putIntArray("inst", inst.toIntArray());
        if (!nextInstance.isEmpty()) {
            CompoundTag next = new CompoundTag();
            for (Map.Entry<ResourceLocation, Integer> e : nextInstance.entrySet()) next.putInt(e.getKey().toString(), e.getValue());
            tag.put("next_inst", next);
        }
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        clearAll();
        ListTag pal = tag.getList("palette", Tag.TAG_STRING);
        SpaceTag[] palette = new SpaceTag[pal.size()];
        for (int i = 0; i < palette.length; i++) {
            ResourceLocation id = ResourceLocation.tryParse(pal.getString(i));
            palette[i] = id == null ? null : SpaceTag.get(id); // 등록이 사라진 태그(모드를 뺐다)는 버린다
        }
        long[] pos = tag.getLongArray("pos");
        int[] idx = tag.getIntArray("tag");
        int[] inst = tag.getIntArray("inst"); // 없으면 빈 배열 (개체 번호가 생기기 전 저장)
        int n = Math.min(pos.length, idx.length);
        for (int i = 0; i < n; i++) {
            int k = idx[i];
            SpaceTag t = k >= 0 && k < palette.length ? palette[k] : null;
            if (t != null) loadCell(pos[i], t, i < inst.length ? inst[i] : 0);
        }
        CompoundTag next = tag.getCompound("next_inst");
        for (String key : next.getAllKeys()) {
            ResourceLocation g = ResourceLocation.tryParse(key);
            if (g != null && nextInstance.getOrDefault(g, 1) < next.getInt(key)) nextInstance.put(g, next.getInt(key));
        }
    }

    @Override
    public void onChunkRemoved(long chunk) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        LongArrayList removed = new LongArrayList();
        for (Map.Entry<ResourceLocation, Long2ObjectOpenHashMap<SpaceTag>> ge : groups.entrySet()) {
            Long2ObjectOpenHashMap<SpaceTag> m = ge.getValue();
            LongIterator it = m.keySet().iterator();
            while (it.hasNext()) {
                long p = it.nextLong();
                if (BlockPos.getX(p) >> 4 == cx && BlockPos.getZ(p) >> 4 == cz) {
                    it.remove();
                    int in = instanceAt(p, ge.getKey());
                    if (in != 0) unindex(ge.getKey(), p, in);
                    area.markChanged(p);
                    removed.add(p);
                }
            }
        }
        if (!removed.isEmpty()) for (Consumer<LongCollection> w : watchers) w.accept(removed);
    }

    @Override
    public void collectUsedChunks(LongSet out) {
        for (Long2ObjectOpenHashMap<SpaceTag> m : groups.values()) {
            LongIterator it = m.keySet().iterator();
            while (it.hasNext()) {
                long p = it.nextLong();
                out.add(ChunkPos.asLong(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4));
            }
        }
    }
}
