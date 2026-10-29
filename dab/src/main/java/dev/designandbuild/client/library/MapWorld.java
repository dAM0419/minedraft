package dev.designandbuild.client.library;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

import java.util.function.LongFunction;

/** 블록 목록만 있는 작은 가상 월드 (썸네일·유령 블록 굽기용). */
public final class MapWorld implements BlockAndTintGetter {
    private final LongFunction<BlockState> blocks;
    private final int minY, height;
    /** 실제 월드의 생물군계 색을 쓴다 (월드 투영). 아니면 평원 색. */
    private final boolean worldTint;

    public MapWorld(Long2ObjectMap<BlockState> blocks, int minY, int height) {
        this(blocks::get, minY, height, false);
    }

    /** 조회 함수로 만든다: 없는 칸은 null. */
    public MapWorld(LongFunction<BlockState> blocks, int minY, int height, boolean worldTint) {
        this.blocks = blocks;
        this.minY = minY;
        this.height = height;
        this.worldTint = worldTint;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        BlockState s = blocks.apply(pos.asLong());
        return s == null ? Blocks.AIR.defaultBlockState() : s;
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Nullable
    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinBuildHeight() {
        return minY;
    }

    @Override
    public float getShade(Direction direction, boolean shade) {
        if (!shade) return 1.0F;
        return switch (direction) {
            case DOWN -> 0.5F;
            case UP -> 1.0F;
            case NORTH, SOUTH -> 0.8F;
            case WEST, EAST -> 0.6F;
        };
    }

    @Override
    public LevelLightEngine getLightEngine() {
        return Minecraft.getInstance().level.getLightEngine();
    }

    @Override
    public int getBrightness(LightLayer layer, BlockPos pos) {
        return 15;
    }

    @Override
    public int getRawBrightness(BlockPos pos, int amount) {
        return 15;
    }

    @Override
    public int getBlockTint(BlockPos pos, ColorResolver resolver) {
        var level = Minecraft.getInstance().level;
        if (level == null) return 0xFFFFFF;
        if (worldTint) return level.getBlockTint(pos, resolver);
        var plains = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        return resolver.getColor(plains.value(), pos.getX(), pos.getZ());
    }
}
