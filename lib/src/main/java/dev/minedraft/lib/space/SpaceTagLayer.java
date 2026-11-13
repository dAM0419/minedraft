package dev.minedraft.lib.space;

import dev.minedraft.lib.MinedraftLib;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaLayer;
import dev.minedraft.lib.area.LayerKey;
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
        LongArrayList changed = new LongArrayList();
        for (int i = 0; i < pos.length; i++) {
            if (putQuiet(pos[i], group, tags[i])) changed.add(pos[i]);
        }
        publish(changed);
    }

    /** 한 칸만 (다른 층이 자기 연산 안에서 같이 바꿀 때). 지켜보는 층 알림은 {@link #publish} 로 모아서. */
    public boolean putQuiet(long pos, ResourceLocation group, @Nullable SpaceTag tag) {
        if (tag != null && !tag.group().equals(group)) throw new IllegalArgumentException(tag.id() + " is not in " + group);
        SpaceTag old;
        if (tag == null) {
            Long2ObjectOpenHashMap<SpaceTag> m = groups.get(group);
            old = m == null ? null : m.remove(pos);
        } else {
            old = groups.computeIfAbsent(group, k -> new Long2ObjectOpenHashMap<>()).put(pos, tag);
        }
        if (old == tag) return false;
        area.markChanged(pos);
        return true;
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
            }
        }
    }

    @Override
    public void readNet(FriendlyByteBuf buf) {
        groups.clear();
        int np = buf.readVarInt();
        SpaceTag[] palette = new SpaceTag[np];
        for (int i = 0; i < np; i++) palette[i] = SpaceTag.get(buf.readResourceLocation());
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++) {
            long p = buf.readLong();
            int k = buf.readVarInt();
            SpaceTag t = k >= 0 && k < np ? palette[k] : null;
            if (t != null) groups.computeIfAbsent(t.group(), g -> new Long2ObjectOpenHashMap<>()).put(p, t);
        }
    }

    @Override
    public CompoundTag save() {
        List<SpaceTag> palette = new ArrayList<>();
        Map<SpaceTag, Integer> index = new HashMap<>();
        LongArrayList pos = new LongArrayList();
        List<Integer> idx = new ArrayList<>();
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
            }
        }
        CompoundTag tag = new CompoundTag();
        ListTag pal = new ListTag();
        for (SpaceTag t : palette) pal.add(StringTag.valueOf(t.id().toString()));
        tag.put("palette", pal);
        tag.putLongArray("pos", pos.toLongArray());
        tag.putIntArray("tag", idx.stream().mapToInt(Integer::intValue).toArray());
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        groups.clear();
        ListTag pal = tag.getList("palette", Tag.TAG_STRING);
        SpaceTag[] palette = new SpaceTag[pal.size()];
        for (int i = 0; i < palette.length; i++) {
            ResourceLocation id = ResourceLocation.tryParse(pal.getString(i));
            palette[i] = id == null ? null : SpaceTag.get(id); // 등록이 사라진 태그(모드를 뺐다)는 버린다
        }
        long[] pos = tag.getLongArray("pos");
        int[] idx = tag.getIntArray("tag");
        int n = Math.min(pos.length, idx.length);
        for (int i = 0; i < n; i++) {
            int k = idx[i];
            SpaceTag t = k >= 0 && k < palette.length ? palette[k] : null;
            if (t != null) groups.computeIfAbsent(t.group(), g -> new Long2ObjectOpenHashMap<>()).put(pos[i], t);
        }
    }

    @Override
    public void onChunkRemoved(long chunk) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        LongArrayList removed = new LongArrayList();
        for (Long2ObjectOpenHashMap<SpaceTag> m : groups.values()) {
            LongIterator it = m.keySet().iterator();
            while (it.hasNext()) {
                long p = it.nextLong();
                if (BlockPos.getX(p) >> 4 == cx && BlockPos.getZ(p) >> 4 == cz) {
                    it.remove();
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
