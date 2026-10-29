package dev.designandbuild.server;

import dev.designandbuild.net.Net;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 편집창 스냅샷: 청크 하나의 블록과 생물군계를 보낸다.
 *
 * <pre>
 * [도면 UUID][청크 long][최소 섹션][섹션 수]
 * [생물군계 이름 팔레트]
 * 섹션마다: [0 = 공기만] 또는 [1][블록 팔레트][4096칸 번호 (팔레트 크기에 따라 0/1/2바이트)][생물군계 64칸]
 * </pre>
 */
public final class SnapshotWriter {
    private SnapshotWriter() {}

    public static byte[] encode(ServerLevel level, UUID blueprint, long chunkLong) {
        int cx = ChunkPos.getX(chunkLong), cz = ChunkPos.getZ(chunkLong);
        LevelChunk chunk = level.getChunk(cx, cz);
        LevelChunkSection[] sections = chunk.getSections();

        // 생물군계 팔레트 (청크 단위)
        List<String> biomeNames = new ArrayList<>();
        Object2IntOpenHashMap<Holder<Biome>> biomeIds = new Object2IntOpenHashMap<>();
        biomeIds.defaultReturnValue(-1);
        byte[][] biomeData = new byte[sections.length][];
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection s = sections[i];
            byte[] b = new byte[64];
            for (int qy = 0; qy < 4; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    for (int qx = 0; qx < 4; qx++) {
                        Holder<Biome> h = s.getNoiseBiome(qx, qy, qz);
                        int id = biomeIds.getInt(h);
                        if (id < 0) {
                            id = biomeNames.size();
                            biomeIds.put(h, id);
                            biomeNames.add(h.unwrapKey().map(ResourceKey::location).map(Object::toString).orElse("minecraft:plains"));
                        }
                        b[(qy << 4) | (qz << 2) | qx] = (byte) Math.min(id, 255);
                    }
                }
            }
            biomeData[i] = b;
        }

        return Net.encode(buf -> {
            buf.writeUUID(blueprint);
            buf.writeLong(chunkLong);
            buf.writeVarInt(level.getMinSection());
            buf.writeVarInt(sections.length);
            buf.writeVarInt(biomeNames.size());
            for (String n : biomeNames) buf.writeUtf(n);

            Object2IntOpenHashMap<BlockState> ids = new Object2IntOpenHashMap<>();
            ids.defaultReturnValue(-1);
            IntArrayList palette = new IntArrayList();
            short[] data = new short[4096];
            for (int i = 0; i < sections.length; i++) {
                LevelChunkSection s = sections[i];
                if (s.hasOnlyAir()) {
                    buf.writeByte(0);
                    buf.writeBytes(biomeData[i]);
                    continue;
                }
                buf.writeByte(1);
                ids.clear();
                palette.clear();
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        for (int x = 0; x < 16; x++) {
                            BlockState st = s.getBlockState(x, y, z);
                            int id = ids.getInt(st);
                            if (id < 0) {
                                id = palette.size();
                                ids.put(st, id);
                                palette.add(Block.getId(st));
                            }
                            data[(y << 8) | (z << 4) | x] = (short) id;
                        }
                    }
                }
                buf.writeVarInt(palette.size());
                for (int k = 0; k < palette.size(); k++) buf.writeVarInt(palette.getInt(k));
                if (palette.size() > 256) {
                    for (short v : data) buf.writeShort(v);
                } else if (palette.size() > 1) {
                    for (short v : data) buf.writeByte(v);
                }
                buf.writeBytes(biomeData[i]);
            }
        });
    }
}
