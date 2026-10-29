package dev.minedraft.lib.util;

import net.minecraft.core.BlockPos;

/** 양 끝을 포함하는 정수 직육면체. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean contains(long pos) {
        return contains(BlockPos.getX(pos), BlockPos.getY(pos), BlockPos.getZ(pos));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public Box inflate(int xz, int y) {
        return new Box(minX - xz, minY - y, minZ - xz, maxX + xz, maxY + y, maxZ + xz);
    }

    public boolean intersects(Box o) {
        return minX <= o.maxX && maxX >= o.minX && minY <= o.maxY && maxY >= o.minY && minZ <= o.maxZ && maxZ >= o.minZ;
    }

    public static Box of(BlockPos a, BlockPos b) {
        return new Box(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
    }
}
