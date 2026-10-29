package dev.designandbuild.core;

import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 도면에 배치된 구조물 하나. 블록은 월드 절대 좌표로 들고 있다.
 * 공기 블록은 "공기 지정"(이 칸을 비워라)이며, 다른 태그 블록은 지우지 못한다.
 */
public final class StructureInstance {
    public final int id;
    public String name;
    /** 라이브러리 출처 ("server:minecraft:village/...", "file:이름.nbt", 손으로 만든 구조물은 ""). */
    public String source;
    /** 설치면 (기본 아랫면). */
    public Direction mount;
    public Rotation rotation;
    public Mirror mirror;

    final Long2ObjectOpenHashMap<BlockState> blocks = new Long2ObjectOpenHashMap<>();

    private boolean boundsDirty = true;
    @Nullable
    private Box bounds;

    public StructureInstance(int id, String name, String source, Direction mount, Rotation rotation, Mirror mirror) {
        this.id = id;
        this.name = name;
        this.source = source;
        this.mount = mount;
        this.rotation = rotation;
        this.mirror = mirror;
    }

    @Nullable
    public BlockState get(long pos) {
        return blocks.get(pos);
    }

    public int size() {
        return blocks.size();
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /** 읽기 전용으로만 쓸 것. 변경은 BlueprintModel 을 거친다. */
    public Long2ObjectMap<BlockState> blocks() {
        return blocks;
    }

    void markBoundsDirty() {
        boundsDirty = true;
    }

    /** 소속 블록(공기 지정 포함)을 감싸는 최소 직육면체. 비었으면 null. */
    @Nullable
    public Box bounds() {
        if (boundsDirty) {
            boundsDirty = false;
            if (blocks.isEmpty()) {
                bounds = null;
            } else {
                int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
                int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
                for (long p : blocks.keySet()) {
                    int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (z < minZ) minZ = z;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                    if (z > maxZ) maxZ = z;
                }
                bounds = new Box(minX, minY, minZ, maxX, maxY, maxZ);
            }
        }
        return bounds;
    }

    /** 순서와 무관한 내용 해시 (되돌리기에서 "그 사이 바뀌었는가" 판정용). */
    public long contentHash() {
        long h = blocks.size();
        for (Long2ObjectMap.Entry<BlockState> e : blocks.long2ObjectEntrySet()) {
            long v = e.getLongKey() * 0x9E3779B97F4A7C15L + Block.getId(e.getValue());
            h += v ^ (v >>> 29);
        }
        return h;
    }

    public long[] positions() {
        return blocks.keySet().toLongArray();
    }

    public int realBlockCount() {
        int n = 0;
        for (BlockState s : blocks.values()) if (!s.isAir()) n++;
        return n;
    }
}
