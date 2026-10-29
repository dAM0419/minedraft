package dev.designandbuild.core;

import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 편집자가 서버로 보내는 편집 요청. 랜덤·방향 계산은 클라이언트가 끝내고 확정된 칸만 보낸다.
 * group 이 같고 0 이 아닌 연속 요청은 되돌리기 한 번으로 묶인다 (드래그 한 번).
 */
public final class Requests {
    private Requests() {}

    public sealed interface Req permits Terrain, StructCells, StructCreate, StructMove, StructDelete, StructReorder,
            StructRename, Rename, ChunkChange, Undo, Redo {
        void write(FriendlyByteBuf buf);
    }

    private static final int TERRAIN = 1, STRUCT_CELLS = 2, STRUCT_CREATE = 3, STRUCT_MOVE = 4, STRUCT_DELETE = 5,
            STRUCT_REORDER = 6, STRUCT_RENAME = 7, RENAME = 8, CHUNK_CHANGE = 9, UNDO = 11, REDO = 12;

    public static Req read(FriendlyByteBuf buf) {
        int kind = buf.readByte();
        return switch (kind) {
            case TERRAIN -> {
                int group = buf.readVarInt();
                long[] pos = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
                yield new Terrain(group, pos, CellIO.readStates(buf, pos.length), CellIO.readTags(buf, pos.length));
            }
            case STRUCT_CELLS -> {
                int group = buf.readVarInt();
                int id = buf.readVarInt();
                String name = buf.readUtf();
                long[] pos = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
                yield new StructCells(group, id, name, pos, CellIO.readStates(buf, pos.length));
            }
            case STRUCT_CREATE -> {
                int group = buf.readVarInt();
                String name = buf.readUtf();
                String source = buf.readUtf();
                Direction mount = CellIO.readDirection(buf);
                Rotation rot = CellIO.readRotation(buf);
                Mirror mirror = CellIO.readMirror(buf);
                long[] pos = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
                yield new StructCreate(group, name, source, mount, rot, mirror, pos, CellIO.readStates(buf, pos.length));
            }
            case STRUCT_MOVE -> new StructMove(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean());
            case STRUCT_DELETE -> new StructDelete(buf.readVarInt());
            case STRUCT_REORDER -> new StructReorder(buf.readVarInt(), buf.readVarInt());
            case STRUCT_RENAME -> new StructRename(buf.readVarInt(), buf.readUtf());
            case RENAME -> new Rename(buf.readUtf());
            case CHUNK_CHANGE -> new ChunkChange(readChunks(buf), readChunks(buf));
            case UNDO -> new Undo();
            case REDO -> new Redo();
            default -> throw new IllegalArgumentException("Unknown request " + kind);
        };
    }

    /** 지형 층에 쓰기. 태그는 FLOOR / PATH, 철거는 공기 + NONE. */
    public record Terrain(int group, long[] pos, BlockState[] states, DesignTag[] tags) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(TERRAIN);
            buf.writeVarInt(group);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
            CellIO.writeTags(buf, tags);
        }
    }

    /** 구조물 칸 쓰기. id 0 = 새 구조물을 name 으로 만든다. 상태 공기 = 공기 지정. */
    public record StructCells(int group, int id, String name, long[] pos, BlockState[] states) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_CELLS);
            buf.writeVarInt(group);
            buf.writeVarInt(id);
            buf.writeUtf(name);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
        }
    }

    /** 라이브러리 구조물 배치 (칸은 회전·반전·위치까지 적용된 절대 좌표). */
    public record StructCreate(int group, String name, String source, Direction mount, Rotation rotation, Mirror mirror,
                               long[] pos, BlockState[] states) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_CREATE);
            buf.writeVarInt(group);
            buf.writeUtf(name);
            buf.writeUtf(source);
            CellIO.writeDirection(buf, mount);
            CellIO.writeRotation(buf, rotation);
            CellIO.writeMirror(buf, mirror);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
        }
    }

    /** 이동(dx,dy,dz) 후 볼륨 기준 회전(시계 방향 90° × steps)·좌우 반전. */
    public record StructMove(int id, int dx, int dy, int dz, int rotSteps, boolean mirror) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_MOVE);
            buf.writeVarInt(id);
            buf.writeVarInt(dx);
            buf.writeVarInt(dy);
            buf.writeVarInt(dz);
            buf.writeVarInt(rotSteps);
            buf.writeBoolean(mirror);
        }
    }

    public record StructDelete(int id) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_DELETE);
            buf.writeVarInt(id);
        }
    }

    public record StructReorder(int id, int index) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_REORDER);
            buf.writeVarInt(id);
            buf.writeVarInt(index);
        }
    }

    public record StructRename(int id, String name) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(STRUCT_RENAME);
            buf.writeVarInt(id);
            buf.writeUtf(name);
        }
    }

    public record Rename(String name) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(RENAME);
            buf.writeUtf(name);
        }
    }

    /** 청크 여러 개를 한꺼번에 넣고 뺀다 (결과 영역 기준으로 검사). */
    public record ChunkChange(long[] add, long[] remove) implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(CHUNK_CHANGE);
            writeChunks(buf, add);
            writeChunks(buf, remove);
        }
    }

    private static void writeChunks(FriendlyByteBuf buf, long[] chunks) {
        buf.writeVarInt(chunks.length);
        for (long c : chunks) buf.writeLong(c);
    }

    private static long[] readChunks(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        if (n < 0 || n > 256) throw new IllegalArgumentException("Too many chunks: " + n);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) out[i] = buf.readLong();
        return out;
    }

    public record Undo() implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(UNDO);
        }
    }

    public record Redo() implements Req {
        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeByte(REDO);
        }
    }
}
