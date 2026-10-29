package dev.designandbuild.core;

import net.minecraft.world.level.block.state.BlockState;

/**
 * 지형 층과 모든 구조물을 순서대로 합친 최종 목표 한 칸.
 *
 * @param state    목표 블록 (공기 = 철거 / 비워 두기)
 * @param tag      이 칸이 지어지는 단계의 태그
 * @param owner    이 칸을 차지한 구조물 ID (0 = 지형 층)
 * @param conflict 앞 순서 구조물의 다른 실제 블록을 덮어썼는가
 */
public record Resolved(BlockState state, DesignTag tag, int owner, boolean conflict) {
    public boolean isAir() {
        return state.isAir();
    }
}
