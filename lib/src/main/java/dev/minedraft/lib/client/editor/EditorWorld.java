package dev.minedraft.lib.client.editor;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import org.jetbrains.annotations.Nullable;

/**
 * 편집창이 보는 가상 월드: 스냅샷 위에 설계(최종 목표)를 덮은 것.
 * hideCut 이면 층 자르기로 숨긴 블록을 공기로 본다 (잘린 단면이 그려지고, 커서가 통과한다).
 * 조명은 항상 최대 밝기, 면 음영은 바닐라와 같다.
 */
public final class EditorWorld implements BlockAndTintGetter {
    private final AreaEditorSession session;
    private final boolean hideCut;

    public EditorWorld(AreaEditorSession session, boolean hideCut) {
        this.session = session;
        this.hideCut = hideCut;
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return session.visibleAt(pos.getX(), pos.getY(), pos.getZ(), hideCut);
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
        return session.area.maxY - session.area.minY;
    }

    @Override
    public int getMinBuildHeight() {
        return session.area.minY;
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
        Holder<Biome> biome = session.snapshot.biome(pos.getX(), pos.getY(), pos.getZ());
        if (biome == null) {
            var level = Minecraft.getInstance().level;
            if (level == null) return 0xFFFFFF;
            biome = level.registryAccess().registryOrThrow(Registries.BIOME).getHolderOrThrow(Biomes.PLAINS);
        }
        return resolver.getColor(biome.value(), pos.getX(), pos.getZ());
    }
}
