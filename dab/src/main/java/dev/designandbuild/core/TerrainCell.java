package dev.designandbuild.core;

import net.minecraft.world.level.block.state.BlockState;

/** 지형 층(바닥·길·철거)의 한 칸. 상태는 공기일 수 있다(태그 NONE = 철거). */
public record TerrainCell(BlockState state, DesignTag tag) {
    public boolean isRealBlock() {
        return !state.isAir();
    }
}
