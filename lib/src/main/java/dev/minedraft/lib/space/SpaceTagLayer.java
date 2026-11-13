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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 공간 태그 층: 칸마다 태그 하나. 태그가 있는 영역 종류에는 자동으로 첫 층으로 붙는다.
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
    private final Long2ObjectOpenHashMap<SpaceTag> cells = new Long2ObjectOpenHashMap<>();
    private final List<Consumer<LongCollection>> watchers = new ArrayList<>();

    public SpaceTagLayer(Area area) {
        this.area = area;
    }

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Nullable
    public SpaceTag tagAt(long pos) {
        return cells.get(pos);
    }

    public Long2ObjectMap<SpaceTag> cells() {
        return Long2ObjectMaps.unmodifiable(cells);
    }

    /** 바뀐 칸 묶음을 받는다 (태그 변경 연산 하나마다 한 번, 불러온 직후 전체 한 번). */
    public void watch(Consumer<LongCollection> watcher) {
        watchers.add(watcher);
    }

    /**
     * 칸들의 태그를 바꾼다 (null = 지움). 영역 변경 연산 안에서만 부른다.
     * 바뀐 칸을 지켜보는 층에 알리고 영역 구독자에게 flush 한다.
     */
    public void set(long[] pos, @Nullable SpaceTag[] tags) {
        LongArrayList changed = new LongArrayList();
        for (int i = 0; i < pos.length; i++) {
            if (putQuiet(pos[i], tags[i])) changed.add(pos[i]);
        }
        publish(changed);
    }

    /** 한 칸만 (다른 층이 자기 연산 안에서 같이 바꿀 때). 알림은 {@link #publish} 로 모아서. */
    public boolean putQuiet(long pos, @Nullable SpaceTag tag) {
        SpaceTag old = tag == null ? cells.remove(pos) : cells.put(pos, tag);
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
    // 통신·저장: 태그 ID 목록 + (칸, 목록 번호)
    // ------------------------------------------------------------------

    @Override
    public void writeNet(FriendlyByteBuf buf) {
        List<SpaceTag> palette = new ArrayList<>();
        Map<SpaceTag, Integer> index = new HashMap<>();
        for (SpaceTag t : cells.values()) if (index.putIfAbsent(t, palette.size()) == null) palette.add(t);
        buf.writeVarInt(palette.size());
        for (SpaceTag t : palette) buf.writeResourceLocation(t.id());
        buf.writeVarInt(cells.size());
        for (Long2ObjectMap.Entry<SpaceTag> e : cells.long2ObjectEntrySet()) {
            buf.writeLong(e.getLongKey());
            buf.writeVarInt(index.get(e.getValue()));
        }
    }

    @Override
    public void readNet(FriendlyByteBuf buf) {
        cells.clear();
        int np = buf.readVarInt();
        SpaceTag[] palette = new SpaceTag[np];
        for (int i = 0; i < np; i++) palette[i] = SpaceTag.get(buf.readResourceLocation());
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++) {
            long p = buf.readLong();
            int k = buf.readVarInt();
            SpaceTag t = k >= 0 && k < np ? palette[k] : null;
            if (t != null) cells.put(p, t);
        }
    }

    @Override
    public CompoundTag save() {
        List<SpaceTag> palette = new ArrayList<>();
        Map<SpaceTag, Integer> index = new HashMap<>();
        for (SpaceTag t : cells.values()) if (index.putIfAbsent(t, palette.size()) == null) palette.add(t);
        CompoundTag tag = new CompoundTag();
        ListTag pal = new ListTag();
        for (SpaceTag t : palette) pal.add(StringTag.valueOf(t.id().toString()));
        tag.put("palette", pal);
        long[] pos = new long[cells.size()];
        int[] idx = new int[pos.length];
        int i = 0;
        for (Long2ObjectMap.Entry<SpaceTag> e : cells.long2ObjectEntrySet()) {
            pos[i] = e.getLongKey();
            idx[i] = index.get(e.getValue());
            i++;
        }
        tag.putLongArray("pos", pos);
        tag.putIntArray("tag", idx);
        return tag;
    }

    @Override
    public void load(CompoundTag tag) {
        cells.clear();
        ListTag pal = tag.getList("palette", Tag.TAG_STRING);
        SpaceTag[] palette = new SpaceTag[pal.size()];
        for (int i = 0; i < palette.length; i++) {
            ResourceLocation id = ResourceLocation.tryParse(pal.getString(i));
            palette[i] = id == null ? null : SpaceTag.get(id); // 등록이 사라진 태그는 버린다
        }
        long[] pos = tag.getLongArray("pos");
        int[] idx = tag.getIntArray("tag");
        int n = Math.min(pos.length, idx.length);
        for (int i = 0; i < n; i++) {
            int k = idx[i];
            SpaceTag t = k >= 0 && k < palette.length ? palette[k] : null;
            if (t != null) cells.put(pos[i], t);
        }
    }

    @Override
    public void afterLoad() {
        // 지켜보는 층은 자기 afterLoad 에서 tagAt 으로 다시 읽으므로 여기서 따로 알리지 않는다
    }

    @Override
    public void onChunkRemoved(long chunk) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        LongArrayList removed = new LongArrayList();
        LongIterator it = cells.keySet().iterator();
        while (it.hasNext()) {
            long p = it.nextLong();
            if (BlockPos.getX(p) >> 4 == cx && BlockPos.getZ(p) >> 4 == cz) {
                it.remove();
                area.markChanged(p);
                removed.add(p);
            }
        }
        if (!removed.isEmpty()) for (Consumer<LongCollection> w : watchers) w.accept(removed);
    }

    @Override
    public void collectUsedChunks(LongSet out) {
        LongIterator it = cells.keySet().iterator();
        while (it.hasNext()) {
            long p = it.nextLong();
            out.add(ChunkPos.asLong(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4));
        }
    }
}
