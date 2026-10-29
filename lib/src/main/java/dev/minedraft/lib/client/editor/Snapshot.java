package dev.minedraft.lib.client.editor;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** 편집창을 열 때 받은 월드 블록 사본 (청크 단위, 섹션마다 팔레트 + 번호). */
public final class Snapshot {
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    private final Long2ObjectOpenHashMap<ChunkData> chunks = new Long2ObjectOpenHashMap<>();
    private long lastKey = Long.MIN_VALUE;
    private ChunkData lastChunk;

    static final class Section {
        final BlockState[] palette;
        final byte[] bytes;
        final short[] shorts;

        Section(BlockState[] palette, byte[] bytes, short[] shorts) {
            this.palette = palette;
            this.bytes = bytes;
            this.shorts = shorts;
        }

        BlockState get(int index) {
            if (shorts != null) return palette[shorts[index] & 0xFFFF];
            if (bytes != null) return palette[bytes[index] & 0xFF];
            return palette[0];
        }
    }

    static final class ChunkData {
        final int minSection;
        final Section[] sections;
        final Holder<Biome>[] biomePalette;
        final byte[][] biomes;
        /** 칸마다 가장 높은 공기 아닌 블록의 y (없으면 최저 높이 - 1). */
        final int[] heights = new int[256];

        ChunkData(int minSection, Section[] sections, Holder<Biome>[] biomePalette, byte[][] biomes) {
            this.minSection = minSection;
            this.sections = sections;
            this.biomePalette = biomePalette;
            this.biomes = biomes;
        }
    }

    public boolean has(long chunk) {
        return chunks.containsKey(chunk);
    }

    public int chunkCount() {
        return chunks.size();
    }

    public void remove(long chunk) {
        chunks.remove(chunk);
        lastKey = Long.MIN_VALUE;
        lastChunk = null;
    }

    /** S_SNAPSHOT 본문 (도면 UUID 는 이미 읽음). 받은 청크 좌표를 돌려준다. */
    @SuppressWarnings("unchecked")
    public long read(FriendlyByteBuf buf, RegistryAccess access) {
        long chunk = buf.readLong();
        int minSection = buf.readVarInt();
        int count = buf.readVarInt();
        Registry<Biome> registry = access.registryOrThrow(Registries.BIOME);
        int nb = buf.readVarInt();
        Holder<Biome>[] biomePalette = new Holder[nb];
        Holder<Biome> fallback = registry.getHolder(Biomes.PLAINS).map(h -> (Holder<Biome>) h).orElse(null);
        for (int i = 0; i < nb; i++) {
            ResourceLocation rl = ResourceLocation.tryParse(buf.readUtf());
            Holder<Biome> h = rl == null ? null
                    : registry.getHolder(ResourceKey.create(Registries.BIOME, rl)).map(x -> (Holder<Biome>) x).orElse(null);
            biomePalette[i] = h != null ? h : fallback;
        }
        Section[] sections = new Section[count];
        byte[][] biomes = new byte[count][];
        for (int i = 0; i < count; i++) {
            int flag = buf.readByte();
            if (flag != 0) {
                int n = buf.readVarInt();
                BlockState[] palette = new BlockState[Math.max(1, n)];
                for (int k = 0; k < n; k++) palette[k] = Block.stateById(buf.readVarInt());
                if (n == 0) palette[0] = AIR;
                byte[] bytes = null;
                short[] shorts = null;
                if (n > 256) {
                    shorts = new short[4096];
                    for (int k = 0; k < 4096; k++) shorts[k] = buf.readShort();
                } else if (n > 1) {
                    bytes = new byte[4096];
                    buf.readBytes(bytes);
                }
                sections[i] = new Section(palette, bytes, shorts);
            }
            byte[] b = new byte[64];
            buf.readBytes(b);
            biomes[i] = b;
        }
        ChunkData data = new ChunkData(minSection, sections, biomePalette, biomes);
        int minY = minSection << 4;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int top = minY - 1;
                for (int s = count - 1; s >= 0 && top < minY; s--) {
                    Section sec = sections[s];
                    if (sec == null) continue;
                    for (int y = 15; y >= 0; y--) {
                        if (!sec.get((y << 8) | (z << 4) | x).isAir()) {
                            top = ((minSection + s) << 4) + y;
                            break;
                        }
                    }
                }
                data.heights[(z << 4) | x] = top;
            }
        }
        chunks.put(chunk, data);
        lastKey = Long.MIN_VALUE;
        lastChunk = null;
        return chunk;
    }

    @Nullable
    private ChunkData chunk(int x, int z) {
        long key = ChunkPos.asLong(x >> 4, z >> 4);
        if (key == lastKey) return lastChunk;
        ChunkData c = chunks.get(key);
        lastKey = key;
        lastChunk = c;
        return c;
    }

    public BlockState get(int x, int y, int z) {
        ChunkData c = chunk(x, z);
        if (c == null) return AIR;
        int s = (y >> 4) - c.minSection;
        if (s < 0 || s >= c.sections.length) return AIR;
        Section sec = c.sections[s];
        if (sec == null) return AIR;
        return sec.get(((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
    }

    @Nullable
    public Holder<Biome> biome(int x, int y, int z) {
        ChunkData c = chunk(x, z);
        if (c == null || c.biomePalette.length == 0) return null;
        int s = Math.max(0, Math.min(c.sections.length - 1, (y >> 4) - c.minSection));
        int idx = c.biomes[s][(((y & 15) >> 2) << 4) | (((z & 15) >> 2) << 2) | ((x & 15) >> 2)] & 0xFF;
        return idx < c.biomePalette.length ? c.biomePalette[idx] : c.biomePalette[0];
    }

    /** 칸의 지표 높이 (가장 높은 블록). 청크가 없으면 Integer.MIN_VALUE. */
    public int surface(int x, int z) {
        ChunkData c = chunk(x, z);
        if (c == null) return Integer.MIN_VALUE;
        return c.heights[((z & 15) << 4) | (x & 15)];
    }
}
