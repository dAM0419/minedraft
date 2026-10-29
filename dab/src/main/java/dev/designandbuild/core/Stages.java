package dev.designandbuild.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jetbrains.annotations.Nullable;

/**
 * 투영 단계 판정 (클라이언트 투영·서버 즉시 반영·건설망치 공용).
 * 단계는 (종류, 구조물 ID) 로 가리킨다. 구조물 단계의 번호는 순서에 따라 바뀌므로 ID 로 주고받는다.
 */
public final class Stages {
    private Stages() {}

    public static final int NONE = -1, DEMOLISH = 0, FLOOR = 1, PATH = 2, STRUCTURE = 3;

    /** 즉시 반영 확인 창에서 주황 경고를 띄우는 칸 수. */
    public static final int LARGE_APPLY = 100_000;

    /** 층 보기: 끔(아무것도 안 보임) / 이 층만 / 이 층 이하 / 단계 전체(모든 층). */
    public static final int LAYER_NONE = 0, LAYER_ONLY = 1, LAYER_BELOW = 2, LAYER_ALL = 3;

    /** 이 설계 칸이 속한 단계 종류. 공기 지정은 철거. */
    public static int kindOf(BlueprintModel m, Resolved r) {
        if (r.isAir()) return DEMOLISH;
        if (r.tag() == DesignTag.PATH) return PATH;
        if (r.tag() == DesignTag.STRUCTURE && r.owner() != 0 && m.structure(r.owner()) != null) return STRUCTURE;
        return FLOOR;
    }

    /** 설치 단계(바닥·길·구조물)에서 이 칸이 그 단계에 속하는가. 철거 단계는 월드 비교로 정하므로 여기서 다루지 않는다. */
    public static boolean inBuildStage(BlueprintModel m, Resolved r, int kind, int structureId) {
        if (kind <= DEMOLISH) return false;
        int k = kindOf(m, r);
        if (k != kind) return false;
        return kind != STRUCTURE || r.owner() == structureId;
    }

    /**
     * 위에 매달려 받침이 위 칸에 있는 블록: 매단 랜턴·맹그로브 번식체, 천장 종, 아래로 자라는 점적석,
     * 천장 버튼·레버, 천장 매다는 간판, 포자 꽃, 매달린 뿌리, 늘어진 덩굴·발광 열매 덩굴.
     */
    public static boolean hangsFromAbove(BlockState s) {
        Block b = s.getBlock();
        if (s.hasProperty(BlockStateProperties.HANGING) && s.getValue(BlockStateProperties.HANGING)) return true;
        if (s.hasProperty(BlockStateProperties.BELL_ATTACHMENT)
                && s.getValue(BlockStateProperties.BELL_ATTACHMENT) == net.minecraft.world.level.block.state.properties.BellAttachType.CEILING) return true;
        if (b instanceof net.minecraft.world.level.block.PointedDripstoneBlock && s.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)
                && s.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN) return true;
        if (s.hasProperty(BlockStateProperties.ATTACH_FACE)
                && s.getValue(BlockStateProperties.ATTACH_FACE) == net.minecraft.world.level.block.state.properties.AttachFace.CEILING) return true;
        return b instanceof net.minecraft.world.level.block.CeilingHangingSignBlock
                || b instanceof net.minecraft.world.level.block.SporeBlossomBlock
                || b instanceof net.minecraft.world.level.block.HangingRootsBlock
                || b instanceof net.minecraft.world.level.block.WeepingVinesBlock
                || b instanceof net.minecraft.world.level.block.WeepingVinesPlantBlock
                || b instanceof net.minecraft.world.level.block.CaveVinesBlock
                || b instanceof net.minecraft.world.level.block.CaveVinesPlantBlock;
    }

    /** 설치 단계에서 이 블록을 놓는 층: 받침이 위에 있는 블록은 받침 층(한 칸 위)에서 놓는다. */
    public static int buildLayerY(BlockState s, int y) {
        return hangsFromAbove(s) ? y + 1 : y;
    }

    /** 층 보기 조건. 2칸 블록은 반쪽이 이 층에 걸리면 함께 보인다. 설치 단계에서 매다는 블록은 받침 층에 보인다. */
    public static boolean layerOk(BlockState target, int y, int mode, int layerY, boolean topDown) {
        if (mode == LAYER_NONE) return false;
        if (mode == LAYER_ALL) return true;
        if (!topDown) y = buildLayerY(target, y);
        if (layerOk(y, mode, layerY, topDown)) return true;
        if (target.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            boolean upper = target.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER;
            return layerOk(upper ? y - 1 : y + 1, mode, layerY, topDown);
        }
        return false;
    }

    private static boolean layerOk(int y, int mode, int layerY, boolean topDown) {
        if (mode == LAYER_ONLY) return y == layerY;
        return topDown ? y >= layerY : y <= layerY;
    }

    /** 2칸 블록의 짝 칸 (문·큰 꽃의 위아래, 침대의 머리·발). 없으면 null. */
    @Nullable
    public static BlockPos partner(BlockState s, BlockPos pos) {
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.above() : pos.below();
        }
        if (s.getBlock() instanceof BedBlock && s.hasProperty(BlockStateProperties.BED_PART)) {
            Direction f = s.getValue(BlockStateProperties.HORIZONTAL_FACING);
            return s.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT ? pos.relative(f) : pos.relative(f.getOpposite());
        }
        return null;
    }

    /** 2칸 블록에서 아이템을 쓰는 쪽(아래쪽·발)인가. 한 칸 블록은 언제나 true. */
    public static boolean isPrimaryPart(BlockState s) {
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            return s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
        }
        if (s.getBlock() instanceof BedBlock && s.hasProperty(BlockStateProperties.BED_PART)) {
            return s.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT;
        }
        return true;
    }
}
