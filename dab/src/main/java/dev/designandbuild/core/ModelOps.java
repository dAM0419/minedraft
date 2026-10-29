package dev.designandbuild.core;

import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 서버가 확정해 모든 열람자에게 뿌리는 변경. 서버와 클라이언트가 같은 순서로 적용하므로 결과가 같다.
 * 상태 배열의 null 은 "칸을 비운다(설계 삭제)" 를 뜻한다.
 */
public final class ModelOps {
    private ModelOps() {}

    public static final int MAX_CELLS = 4_000_000;

    public sealed interface Op permits Terrain, StructCreate, StructCells, StructReplace, StructDelete,
            StructReorder, StructRename, Rename, ChunkAdd, ChunkRemove {
        void apply(BlueprintModel m);

        void write(FriendlyByteBuf buf);
    }

    private static final int TERRAIN = 1, STRUCT_CREATE = 2, STRUCT_CELLS = 3, STRUCT_REPLACE = 4, STRUCT_DELETE = 5,
            STRUCT_REORDER = 6, STRUCT_RENAME = 7, RENAME = 8, CHUNK_ADD = 9, CHUNK_REMOVE = 10;

    public static Op read(FriendlyByteBuf buf) {
        int kind = buf.readByte();
        return switch (kind) {
            case TERRAIN -> {
                long[] pos = CellIO.readPositions(buf, MAX_CELLS);
                yield new Terrain(pos, CellIO.readStates(buf, pos.length), CellIO.readTags(buf, pos.length));
            }
            case STRUCT_CREATE -> {
                int id = buf.readVarInt();
                int index = buf.readVarInt();
                String name = buf.readUtf();
                String source = buf.readUtf();
                Direction mount = CellIO.readDirection(buf);
                Rotation rot = CellIO.readRotation(buf);
                Mirror mirror = CellIO.readMirror(buf);
                long[] pos = CellIO.readPositions(buf, MAX_CELLS);
                yield new StructCreate(id, index, name, source, mount, rot, mirror, pos, CellIO.readStates(buf, pos.length));
            }
            case STRUCT_CELLS -> {
                int id = buf.readVarInt();
                long[] pos = CellIO.readPositions(buf, MAX_CELLS);
                yield new StructCells(id, pos, CellIO.readStates(buf, pos.length));
            }
            case STRUCT_REPLACE -> {
                int id = buf.readVarInt();
                Rotation rot = CellIO.readRotation(buf);
                Mirror mirror = CellIO.readMirror(buf);
                long[] pos = CellIO.readPositions(buf, MAX_CELLS);
                yield new StructReplace(id, rot, mirror, pos, CellIO.readStates(buf, pos.length));
            }
            case STRUCT_DELETE -> new StructDelete(buf.readVarInt());
            case STRUCT_REORDER -> new StructReorder(buf.readVarInt(), buf.readVarInt());
            case STRUCT_RENAME -> new StructRename(buf.readVarInt(), buf.readUtf());
            case RENAME -> new Rename(buf.readUtf());
            case CHUNK_ADD -> new ChunkAdd(buf.readLong());
            case CHUNK_REMOVE -> new ChunkRemove(buf.readLong());
            default -> throw new IllegalArgumentException("Unknown op " + kind);
        };
    }

    /** 지형 층 칸 쓰기 (상태 null = 지형 설계 삭제). */
    public record Terrain(long[] pos, BlockState[] states, DesignTag[] tags) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            for (int i = 0; i < pos.length; i++) {
                BlockState s = states[i];
                if (s != null && !m.isInside(pos[i])) continue;
                m.putTerrain(pos[i], s == null ? null : new TerrainCell(s, tags[i]));
            }
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(TERRAIN);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
            CellIO.writeTags(buf, tags);
        }
    }

    /** 구조물 생성 (index 위치에 끼워 넣음, -1 = 맨 뒤). */
    public record StructCreate(int id, int index, String name, String source, Direction mount, Rotation rotation,
                               Mirror mirror, long[] pos, BlockState[] states) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            StructureInstance s = m.createStructure(id, index, name, source, mount, rotation, mirror);
            for (int i = 0; i < pos.length; i++) {
                if (states[i] == null || !m.isInside(pos[i])) continue;
                m.putStructureBlock(s, pos[i], states[i]);
            }
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_CREATE);
            buf.writeVarInt(id);
            buf.writeVarInt(index);
            buf.writeUtf(name);
            buf.writeUtf(source);
            CellIO.writeDirection(buf, mount);
            CellIO.writeRotation(buf, rotation);
            CellIO.writeMirror(buf, mirror);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
        }
    }

    /** 구조물 칸 쓰기 (상태 null = 그 칸을 구조물에서 뺌). */
    public record StructCells(int id, long[] pos, BlockState[] states) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            StructureInstance s = m.structure(id);
            if (s == null) return;
            for (int i = 0; i < pos.length; i++) {
                if (states[i] != null && !m.isInside(pos[i])) continue;
                m.putStructureBlock(s, pos[i], states[i]);
            }
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_CELLS);
            buf.writeVarInt(id);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
        }
    }

    /** 구조물 블록 전체 교체 (이동·회전·반전, 되돌리기). */
    public record StructReplace(int id, Rotation rotation, Mirror mirror, long[] pos, BlockState[] states) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            StructureInstance s = m.structure(id);
            if (s == null) return;
            m.clearStructureBlocks(s);
            for (int i = 0; i < pos.length; i++) {
                if (states[i] == null || !m.isInside(pos[i])) continue;
                m.putStructureBlock(s, pos[i], states[i]);
            }
            s.rotation = rotation;
            s.mirror = mirror;
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_REPLACE);
            buf.writeVarInt(id);
            CellIO.writeRotation(buf, rotation);
            CellIO.writeMirror(buf, mirror);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
        }
    }

    public record StructDelete(int id) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            m.deleteStructure(id);
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_DELETE);
            buf.writeVarInt(id);
        }
    }

    public record StructReorder(int id, int index) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            m.moveInOrder(id, index);
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_REORDER);
            buf.writeVarInt(id);
            buf.writeVarInt(index);
        }
    }

    public record StructRename(int id, String name) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            m.renameStructure(id, name);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_RENAME);
            buf.writeVarInt(id);
            buf.writeUtf(name);
        }
    }

    public record Rename(String name) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            m.rename(name);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(RENAME);
            buf.writeUtf(name);
        }
    }

    public record ChunkAdd(long chunk) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            m.addChunk(chunk);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(CHUNK_ADD);
            buf.writeLong(chunk);
        }
    }

    public record ChunkRemove(long chunk) implements Op {
        @Override
        public void apply(BlueprintModel m) {
            m.removeChunk(chunk);
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(CHUNK_REMOVE);
            buf.writeLong(chunk);
        }
    }
}
