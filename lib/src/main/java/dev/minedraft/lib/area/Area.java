package dev.minedraft.lib.area;

import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongConsumer;

/**
 * 영역 하나: 기준 청크에서 이어진 청크 묶음과 그 위의 층들. 서버(원본)와 클라이언트(사본)에서 같은 코드로 쓴다.
 *
 * <pre>
 * version  : 서버가 변경을 적용할 때마다 1씩 오른다 (사본이 빠뜨린 변경을 알아챈다)
 * revision : 이 사본의 내용이 바뀔 때마다 오른다 (그림·계산 캐시 무효화용)
 * </pre>
 * 모든 변경은 {@link AreaOp} 를 거친다. 층은 바뀐 칸을 {@link #markChanged} 로 알리고, 연산 끝에 {@link #flush} 한다.
 */
public final class Area {
    public final UUID id;
    public final AreaType type;
    public final ResourceKey<Level> dimension;
    public final int baseX, baseZ;
    /** 쓸 수 있는 높이 [minY, maxY) */
    public final int minY, maxY;
    /** 서버가 변경을 적용할 때마다 1씩 오른다. */
    public long version;

    private String name;
    private final LongOpenHashSet chunks = new LongOpenHashSet();
    private final Map<ResourceLocation, AreaLayer> layers = new LinkedHashMap<>();
    private final LongOpenHashSet pending = new LongOpenHashSet();
    private final List<LongConsumer> listeners = new ArrayList<>(2);
    private long revision;

    public Area(UUID id, AreaType type, String name, ResourceKey<Level> dimension, int baseX, int baseZ, int minY, int maxY) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.dimension = dimension;
        this.baseX = baseX;
        this.baseZ = baseZ;
        this.minY = minY;
        this.maxY = maxY;
        for (var factory : type.layers()) {
            AreaLayer l = factory.apply(this);
            layers.put(l.id(), l);
        }
    }

    /** 기준 청크를 가운데로 종류가 정한 크기의 새 영역. */
    public static Area createNew(UUID id, AreaType type, String name, ResourceKey<Level> dimension, int baseX, int baseZ,
                                 int minY, int maxY) {
        Area a = new Area(id, type, name, dimension, baseX, baseZ, minY, maxY);
        int r = Math.max(0, type.initialRadius());
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) a.chunks.add(ChunkPos.asLong(baseX + dx, baseZ + dz));
        }
        return a;
    }

    // ------------------------------------------------------------------
    // 층
    // ------------------------------------------------------------------

    public <T extends AreaLayer> T layer(LayerKey<T> key) {
        AreaLayer l = layers.get(key.id());
        if (l == null) throw new IllegalStateException("Area " + type.id() + " has no layer " + key.id());
        return key.type().cast(l);
    }

    @Nullable
    public <T extends AreaLayer> T layerOrNull(LayerKey<T> key) {
        AreaLayer l = layers.get(key.id());
        return key.type().isInstance(l) ? key.type().cast(l) : null;
    }

    public Collection<AreaLayer> layers() {
        return layers.values();
    }

    // ------------------------------------------------------------------
    // 읽기
    // ------------------------------------------------------------------

    public String name() {
        return name;
    }

    public long revision() {
        return revision;
    }

    public long baseChunk() {
        return ChunkPos.asLong(baseX, baseZ);
    }

    public LongSet chunks() {
        return LongSets.unmodifiable(chunks);
    }

    public boolean hasChunk(int chunkX, int chunkZ) {
        return chunks.contains(ChunkPos.asLong(chunkX, chunkZ));
    }

    public boolean isInside(int x, int y, int z) {
        return y >= minY && y < maxY && chunks.contains(ChunkPos.asLong(x >> 4, z >> 4));
    }

    public boolean isInside(long pos) {
        return isInside(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
    }

    public boolean isInsideColumn(int x, int z) {
        return chunks.contains(ChunkPos.asLong(x >> 4, z >> 4));
    }

    /** 청크 영역의 블록 좌표 범위 (높이는 쓸 수 있는 높이). */
    public Box areaBounds() {
        int minCX = Integer.MAX_VALUE, minCZ = Integer.MAX_VALUE, maxCX = Integer.MIN_VALUE, maxCZ = Integer.MIN_VALUE;
        for (long c : chunks) {
            int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
            minCX = Math.min(minCX, cx);
            minCZ = Math.min(minCZ, cz);
            maxCX = Math.max(maxCX, cx);
            maxCZ = Math.max(maxCZ, cz);
        }
        if (minCX == Integer.MAX_VALUE) {
            minCX = maxCX = baseX;
            minCZ = maxCZ = baseZ;
        }
        return new Box(minCX << 4, minY, minCZ << 4, (maxCX << 4) + 15, maxY - 1, (maxCZ << 4) + 15);
    }

    /**
     * 청크 여러 개를 한꺼번에 넣고 뺄 때의 검사. 결과 영역 기준으로: 기준 청크는 남아야 하고,
     * 최대 청크 수 이하이며, 기준 청크에서 이어진 한 덩어리여야 한다.
     * @return 문제가 없으면 null, 있으면 번역 키
     */
    @Nullable
    public String checkChunkChange(LongSet add, LongSet remove) {
        LongOpenHashSet next = new LongOpenHashSet(chunks);
        LongIterator it = remove.iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            if (c == baseChunk() || !next.remove(c)) return "minedraftlib.msg.chunk_cannot_remove";
        }
        it = add.iterator();
        while (it.hasNext()) next.add(it.nextLong());
        if (next.size() > type.maxChunks()) return "minedraftlib.msg.chunk_limit";
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(baseChunk());
        seen.add(baseChunk());
        while (!queue.isEmpty()) {
            long c = queue.dequeueLong();
            int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
            long[] around = {ChunkPos.asLong(cx + 1, cz), ChunkPos.asLong(cx - 1, cz), ChunkPos.asLong(cx, cz + 1), ChunkPos.asLong(cx, cz - 1)};
            for (long n : around) {
                if (next.contains(n) && seen.add(n)) queue.enqueue(n);
            }
        }
        if (seen.size() != next.size()) return add.isEmpty() ? "minedraftlib.msg.chunk_split" : "minedraftlib.msg.chunk_not_adjacent";
        return null;
    }

    // ------------------------------------------------------------------
    // 변경 알림
    // ------------------------------------------------------------------

    /** 칸 변경 알림 (편집창 그림, 투영). 보통 0~2개. */
    public void addListener(LongConsumer listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(LongConsumer listener) {
        listeners.remove(listener);
    }

    /** 층이 칸이 바뀌었다고 알린다 ({@link #flush} 때 구독자에게 전해진다). */
    public void markChanged(long pos) {
        pending.add(pos);
    }

    /** 칸과 무관한 변경 (이름, 순서 …): 캐시만 무효화한다. */
    public void bump() {
        revision++;
    }

    /** 모아 둔 칸 변경을 구독자에게 알린다. */
    public void flush() {
        if (pending.isEmpty()) return;
        for (LongConsumer listener : listeners) {
            LongIterator it = pending.iterator();
            while (it.hasNext()) listener.accept(it.nextLong());
        }
        pending.clear();
        revision++;
    }

    // ------------------------------------------------------------------
    // 변경 (AreaOp 에서만 부른다)
    // ------------------------------------------------------------------

    public void rename(String newName) {
        this.name = newName;
        revision++;
    }

    public void addChunk(long chunk) {
        if (chunks.add(chunk)) revision++;
    }

    /** 청크를 빼고 그 안의 층 데이터를 지운다. */
    public void removeChunk(long chunk) {
        if (!chunks.remove(chunk)) return;
        for (AreaLayer l : layers.values()) l.onChunkRemoved(chunk);
        revision++;
        flush();
    }

    /** 불러오기 전용. */
    void loadChunk(long chunk) {
        chunks.add(chunk);
    }

    /** 불러온 직후: 층의 파생 데이터를 만든다. */
    void afterLoad() {
        for (AreaLayer l : layers.values()) l.afterLoad();
        flush();
        revision++;
    }
}
