package dev.designandbuild.core;

import dev.minedraft.lib.util.CellIO;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 불러온 구조물 조각 (.nbt). 좌표는 조각 안의 상대 좌표 [0, size).
 * 상태가 공기인 칸은 "공기 지정", 목록에 없는 칸은 구조물 공허(건드리지 않음).
 */
public final class TemplateData {
    public final String id;
    public final String name;
    public final int sizeX, sizeY, sizeZ;
    public final Direction mount;
    /** "source:vanilla", "kind:house", "terrain:plains" 형식. */
    public final List<String> tags;
    public final int[] xs, ys, zs;
    public final BlockState[] states;

    public TemplateData(String id, String name, int sizeX, int sizeY, int sizeZ, Direction mount, List<String> tags,
                        int[] xs, int[] ys, int[] zs, BlockState[] states) {
        this.id = id;
        this.name = name;
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.mount = mount;
        this.tags = tags;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.states = states;
    }

    public int count() {
        return states.length;
    }

    public int realBlockCount() {
        int n = 0;
        for (BlockState s : states) if (!s.isAir()) n++;
        return n;
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(name);
        buf.writeVarInt(sizeX);
        buf.writeVarInt(sizeY);
        buf.writeVarInt(sizeZ);
        CellIO.writeDirection(buf, mount);
        buf.writeVarInt(tags.size());
        for (String t : tags) buf.writeUtf(t);
        buf.writeVarInt(states.length);
        for (int i = 0; i < states.length; i++) {
            buf.writeVarInt(xs[i]);
            buf.writeVarInt(ys[i]);
            buf.writeVarInt(zs[i]);
            CellIO.writeState(buf, states[i]);
        }
    }

    public static TemplateData read(FriendlyByteBuf buf) {
        String id = buf.readUtf();
        String name = buf.readUtf();
        int sx = buf.readVarInt(), sy = buf.readVarInt(), sz = buf.readVarInt();
        Direction mount = CellIO.readDirection(buf);
        int tagCount = buf.readVarInt();
        List<String> tags = new ArrayList<>(tagCount);
        for (int i = 0; i < tagCount; i++) tags.add(buf.readUtf());
        int n = buf.readVarInt();
        if (n < 0 || n > ModelOps.MAX_CELLS) throw new IllegalArgumentException("Template too large");
        int[] xs = new int[n], ys = new int[n], zs = new int[n];
        BlockState[] states = new BlockState[n];
        for (int i = 0; i < n; i++) {
            xs[i] = buf.readVarInt();
            ys[i] = buf.readVarInt();
            zs[i] = buf.readVarInt();
            BlockState s = CellIO.readState(buf);
            states[i] = s == null ? net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() : s;
        }
        return new TemplateData(id, name, sx, sy, sz, mount, tags, xs, ys, zs, states);
    }
}
