package dev.minedraft.lib.client.render;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * 칸에 더해 그릴 것 (Off-grid 등): 기준 블록의 칸 안 이동값과, 같은 칸에 겹친 블록들.
 * 메싱하는 월드가 이 인터페이스를 구현하면 {@link BlockMesher} 가 함께 그린다.
 */
public interface CellExtras {
    /**
     * @param hx      기준 블록 이동값 (블록 단위)
     * @param states  겹친 블록
     * @param offsets 겹친 블록마다 이동값 3개 (x, y, z)
     * @param tags    겹친 블록의 블록 엔티티 데이터 (없으면 null)
     */
    record Extras(float hx, float hy, float hz, BlockState[] states, float[] offsets, CompoundTag[] tags) {
        public boolean hostMoved() {
            return hx != 0 || hy != 0 || hz != 0;
        }
    }

    /** 이 칸에 더해 그릴 것 (없으면 null). */
    @Nullable
    Extras extras(BlockPos pos);
}
