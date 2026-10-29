package dev.designandbuild.core;

import dev.designandbuild.DesignAndBuild;
import dev.minedraft.lib.edit.EditRequest;
import dev.minedraft.lib.util.CellIO;
import net.minecraft.resources.ResourceLocation;
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

    /** DaB 설계 편집 요청 (Lib 의 편집 요청으로 오간다). 이름 바꾸기·청크·되돌리기는 Lib 기본 요청을 쓴다. */
    public sealed interface Req extends EditRequest permits Terrain, StructCells, StructCreate, StructMove, StructDelete,
            StructReorder, StructRename {}

    public static final ResourceLocation TERRAIN = DesignAndBuild.rl("terrain"), STRUCT_CELLS = DesignAndBuild.rl("struct_cells"),
            STRUCT_CREATE = DesignAndBuild.rl("struct_create"), STRUCT_MOVE = DesignAndBuild.rl("struct_move"),
            STRUCT_DELETE = DesignAndBuild.rl("struct_delete"), STRUCT_REORDER = DesignAndBuild.rl("struct_reorder"),
            STRUCT_RENAME = DesignAndBuild.rl("struct_rename");

    public static Req read(ResourceLocation kind, FriendlyByteBuf buf) {
        return switch (kind.getPath()) {
            case "terrain" -> {
                int group = buf.readVarInt();
                long[] pos = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
                yield new Terrain(group, pos, CellIO.readStates(buf, pos.length), DesignTag.readAll(buf, pos.length));
            }
            case "struct_cells" -> {
                int group = buf.readVarInt();
                int id = buf.readVarInt();
                String name = buf.readUtf();
                long[] pos = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
                yield new StructCells(group, id, name, pos, CellIO.readStates(buf, pos.length));
            }
            case "struct_create" -> {
                int group = buf.readVarInt();
                String name = buf.readUtf();
                String source = buf.readUtf();
                Direction mount = CellIO.readDirection(buf);
                Rotation rot = CellIO.readRotation(buf);
                Mirror mirror = CellIO.readMirror(buf);
                long[] pos = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
                yield new StructCreate(group, name, source, mount, rot, mirror, pos, CellIO.readStates(buf, pos.length));
            }
            case "struct_move" -> new StructMove(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readBoolean());
            case "struct_delete" -> new StructDelete(buf.readVarInt());
            case "struct_reorder" -> new StructReorder(buf.readVarInt(), buf.readVarInt());
            case "struct_rename" -> new StructRename(buf.readVarInt(), buf.readUtf());
            default -> throw new IllegalArgumentException("Unknown request " + kind);
        };
    }

    /** 지형 층에 쓰기. 태그는 FLOOR / PATH, 철거는 공기 + NONE. */
    public record Terrain(int group, long[] pos, BlockState[] states, DesignTag[] tags) implements Req {
        @Override
        public ResourceLocation type() {
            return TERRAIN;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(group);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
            DesignTag.writeAll(buf, tags);
        }
    }

    /** 구조물 칸 쓰기. id 0 = 새 구조물을 name 으로 만든다. 상태 공기 = 공기 지정. */
    public record StructCells(int group, int id, String name, long[] pos, BlockState[] states) implements Req {
        @Override
        public ResourceLocation type() {
            return STRUCT_CELLS;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
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
        public ResourceLocation type() {
            return STRUCT_CREATE;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
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
        public ResourceLocation type() {
            return STRUCT_MOVE;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
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
        public ResourceLocation type() {
            return STRUCT_DELETE;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
        }
    }

    public record StructReorder(int id, int index) implements Req {
        @Override
        public ResourceLocation type() {
            return STRUCT_REORDER;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeVarInt(index);
        }
    }

    public record StructRename(int id, String name) implements Req {
        @Override
        public ResourceLocation type() {
            return STRUCT_RENAME;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeUtf(name);
        }
    }
}
