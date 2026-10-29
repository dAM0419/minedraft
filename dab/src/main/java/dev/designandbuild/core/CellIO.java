package dev.designandbuild.core;

import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** 네트워크 인코딩 도우미. 블록 상태는 번호(양쪽 레지스트리가 동기화되어 같다)로 보낸다. */
public final class CellIO {
    private CellIO() {}

    /** null 은 0, 그 밖은 상태 번호 + 1. */
    public static void writeState(FriendlyByteBuf buf, @Nullable BlockState state) {
        buf.writeVarInt(state == null ? 0 : Block.getId(state) + 1);
    }

    @Nullable
    public static BlockState readState(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        return id == 0 ? null : Block.stateById(id - 1);
    }

    public static void writePositions(FriendlyByteBuf buf, long[] pos) {
        buf.writeVarInt(pos.length);
        for (long p : pos) buf.writeLong(p);
    }

    public static long[] readPositions(FriendlyByteBuf buf, int max) {
        int n = buf.readVarInt();
        if (n < 0 || n > max) throw new IllegalArgumentException("Too many cells: " + n);
        long[] pos = new long[n];
        for (int i = 0; i < n; i++) pos[i] = buf.readLong();
        return pos;
    }

    public static void writeStates(FriendlyByteBuf buf, BlockState[] states) {
        for (BlockState s : states) writeState(buf, s);
    }

    public static BlockState[] readStates(FriendlyByteBuf buf, int n) {
        BlockState[] states = new BlockState[n];
        for (int i = 0; i < n; i++) states[i] = readState(buf);
        return states;
    }

    public static void writeTags(FriendlyByteBuf buf, DesignTag[] tags) {
        for (DesignTag t : tags) buf.writeByte(t.ordinal());
    }

    public static DesignTag[] readTags(FriendlyByteBuf buf, int n) {
        DesignTag[] tags = new DesignTag[n];
        for (int i = 0; i < n; i++) tags[i] = DesignTag.byId(buf.readByte());
        return tags;
    }

    public static void writeDirection(FriendlyByteBuf buf, Direction d) {
        buf.writeByte(d.get3DDataValue());
    }

    public static Direction readDirection(FriendlyByteBuf buf) {
        return Direction.from3DDataValue(buf.readByte());
    }

    public static void writeRotation(FriendlyByteBuf buf, Rotation r) {
        buf.writeByte(r.ordinal());
    }

    public static Rotation readRotation(FriendlyByteBuf buf) {
        Rotation[] v = Rotation.values();
        int i = buf.readByte();
        return i >= 0 && i < v.length ? v[i] : Rotation.NONE;
    }

    public static void writeMirror(FriendlyByteBuf buf, Mirror m) {
        buf.writeByte(m.ordinal());
    }

    public static Mirror readMirror(FriendlyByteBuf buf) {
        Mirror[] v = Mirror.values();
        int i = buf.readByte();
        return i >= 0 && i < v.length ? v[i] : Mirror.NONE;
    }
}
