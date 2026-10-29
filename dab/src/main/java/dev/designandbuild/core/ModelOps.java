package dev.designandbuild.core;

import dev.designandbuild.DesignAndBuild;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;
import dev.minedraft.lib.area.AreaOps;
import dev.minedraft.lib.util.CellIO;
import net.minecraft.resources.ResourceLocation;
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

    /** DaB 설계층 변경. Lib 의 영역 변경(AreaOp)으로 오가고, 적용은 그 영역의 설계층에 한다. */
    public sealed interface Op extends AreaOp permits Terrain, StructCreate, StructCells, StructReplace, StructDelete,
            StructReorder, StructRename {
        void apply(BlueprintModel m);

        @Override
        default void apply(Area area) {
            apply(BlueprintModel.of(area));
        }
    }

    public static final ResourceLocation TERRAIN = DesignAndBuild.rl("terrain"), STRUCT_CREATE = DesignAndBuild.rl("struct_create"),
            STRUCT_CELLS = DesignAndBuild.rl("struct_cells"), STRUCT_REPLACE = DesignAndBuild.rl("struct_replace"),
            STRUCT_DELETE = DesignAndBuild.rl("struct_delete"), STRUCT_REORDER = DesignAndBuild.rl("struct_reorder"),
            STRUCT_RENAME = DesignAndBuild.rl("struct_rename");

    /** 읽기 함수를 Lib 에 등록한다 (모드 생성자). */
    public static void register() {
        for (ResourceLocation k : new ResourceLocation[]{TERRAIN, STRUCT_CREATE, STRUCT_CELLS, STRUCT_REPLACE, STRUCT_DELETE,
                STRUCT_REORDER, STRUCT_RENAME}) {
            AreaOps.register(k, buf -> read(k, buf));
        }
    }

    private static Op read(ResourceLocation kind, FriendlyByteBuf buf) {
        return switch (kind.getPath()) {
            case "terrain" -> {
                long[] pos = CellIO.readPositions(buf, MAX_CELLS);
                yield new Terrain(pos, CellIO.readStates(buf, pos.length), DesignTag.readAll(buf, pos.length));
            }
            case "struct_create" -> {
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
            case "struct_cells" -> {
                int id = buf.readVarInt();
                long[] pos = CellIO.readPositions(buf, MAX_CELLS);
                yield new StructCells(id, pos, CellIO.readStates(buf, pos.length));
            }
            case "struct_replace" -> {
                int id = buf.readVarInt();
                Rotation rot = CellIO.readRotation(buf);
                Mirror mirror = CellIO.readMirror(buf);
                long[] pos = CellIO.readPositions(buf, MAX_CELLS);
                yield new StructReplace(id, rot, mirror, pos, CellIO.readStates(buf, pos.length));
            }
            case "struct_delete" -> new StructDelete(buf.readVarInt());
            case "struct_reorder" -> new StructReorder(buf.readVarInt(), buf.readVarInt());
            case "struct_rename" -> new StructRename(buf.readVarInt(), buf.readUtf());
            default -> throw new IllegalArgumentException("Unknown op " + kind);
        };
    }

    /** 지형 층 칸 쓰기 (상태 null = 지형 설계 삭제). */
    public record Terrain(long[] pos, BlockState[] states, DesignTag[] tags) implements Op {
        @Override
        public ResourceLocation type() {
            return TERRAIN;
        }

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
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
            DesignTag.writeAll(buf, tags);
        }
    }

    /** 구조물 생성 (index 위치에 끼워 넣음, -1 = 맨 뒤). */
    public record StructCreate(int id, int index, String name, String source, Direction mount, Rotation rotation,
                               Mirror mirror, long[] pos, BlockState[] states) implements Op {
        @Override
        public ResourceLocation type() {
            return STRUCT_CREATE;
        }

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
        public ResourceLocation type() {
            return STRUCT_CELLS;
        }

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
            buf.writeVarInt(id);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
        }
    }

    /** 구조물 블록 전체 교체 (이동·회전·반전, 되돌리기). */
    public record StructReplace(int id, Rotation rotation, Mirror mirror, long[] pos, BlockState[] states) implements Op {
        @Override
        public ResourceLocation type() {
            return STRUCT_REPLACE;
        }

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
            buf.writeVarInt(id);
            CellIO.writeRotation(buf, rotation);
            CellIO.writeMirror(buf, mirror);
            CellIO.writePositions(buf, pos);
            CellIO.writeStates(buf, states);
        }
    }

    public record StructDelete(int id) implements Op {
        @Override
        public ResourceLocation type() {
            return STRUCT_DELETE;
        }

        @Override
        public void apply(BlueprintModel m) {
            m.deleteStructure(id);
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
        }
    }

    public record StructReorder(int id, int index) implements Op {
        @Override
        public ResourceLocation type() {
            return STRUCT_REORDER;
        }

        @Override
        public void apply(BlueprintModel m) {
            m.moveInOrder(id, index);
            m.flush();
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeVarInt(index);
        }
    }

    public record StructRename(int id, String name) implements Op {
        @Override
        public ResourceLocation type() {
            return STRUCT_RENAME;
        }

        @Override
        public void apply(BlueprintModel m) {
            m.renameStructure(id, name);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(id);
            buf.writeUtf(name);
        }
    }

}
