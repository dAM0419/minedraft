package dev.designandbuild.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;

/**
 * 건설망치 설치 판정 (서버 설치·클라이언트 조준 표시 공용). 못 놓으면 그 이유를 돌려준다.
 * 순서: 설계·단계·층 → 자리(월드의 그 칸) → 2칸 블록 → 아이템 → 받침. 재료·보호 구역은 서버에서 따로 본다.
 */
public final class PlaceCheck {
    private PlaceCheck() {}

    public static final int OK = 0, NOT_DESIGN = 1, OTHER_STAGE = 2, OTHER_LAYER = 3, LIQUID = 4, DONE = 5,
            OCCUPIED = 6, WRONG_STATE = 7, CONVERT = 8, LOWER_FIRST = 9, NO_ITEM = 10, SUPPORT_PENDING = 11,
            SUPPORT_SHAPE = 12, SUPPORT_DESIGN = 13, PARTNER_BLOCKED = 14, UNLOADED = 15, TOO_FAR = 16,
            PROTECTED = 17, MISSING = 18, FAILED = 19;
    public static final int COUNT = 20;
    /** 알림 문구 키 이름 (designandbuild.hammer.fail.<이름>, 일괄 집계는 designandbuild.hammer.count.<이름>). */
    public static final String[] NAMES = {"ok", "not_design", "other_stage", "other_layer", "liquid", "done",
            "occupied", "wrong_state", "convert", "lower_first", "no_item", "support_pending",
            "support_shape", "support_design", "partner_blocked", "unloaded", "too_far",
            "protected", "missing", "failed"};

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * 판정 결과. target = 놓을 상태(설계 상태, 물에 잠김은 그 자리에 맞춤), dir = 받침·짝 칸의 방향,
     * other = 자리에 있는 블록 또는 받침 칸의 블록, a·b = 단계(종류, 구조물 ID) 또는 층(칸의 층, 보는 층).
     */
    public record Result(int code, BlockState target, @Nullable Direction dir, BlockState other, int a, int b) {
        public boolean ok() {
            return code == OK;
        }

        /** 받침 쪽 문제 (일괄 설치에서 끝에 한 번 더 시도). */
        public boolean supportIssue() {
            return code == SUPPORT_PENDING || code == SUPPORT_SHAPE || code == SUPPORT_DESIGN || code == PARTNER_BLOCKED;
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(code);
            buf.writeVarInt(Block.getId(target));
            buf.writeByte(dir == null ? -1 : dir.ordinal());
            buf.writeVarInt(Block.getId(other));
            buf.writeVarInt(a);
            buf.writeVarInt(b);
        }

        public static Result read(FriendlyByteBuf b) {
            int code = b.readVarInt();
            BlockState t = Block.stateById(b.readVarInt());
            int d = b.readByte();
            BlockState o = Block.stateById(b.readVarInt());
            int a = b.readVarInt(), bb = b.readVarInt();
            return new Result(code, t, d < 0 ? null : Direction.values()[d], o, a, bb);
        }
    }

    public static Result of(int code, BlockState target) {
        return new Result(code, target, null, AIR, 0, 0);
    }

    /** 설치할 수 있는 칸인가 (거리·재료·보호 구역 빼고 전부). */
    public static Result check(BlueprintModel m, Level level, BlockPos p, int kind, int sid, int mode, int layerY, boolean topDown) {
        Result r = design(m, p, kind, sid, mode, layerY, topDown);
        if (!r.ok()) return r;
        r = spot(level, p, r.target());
        if (!r.ok()) return r;
        BlockState target = r.target();
        // 2칸 블록의 위쪽·머리: 아래쪽·발이 먼저 (그쪽에서 함께 놓는다)
        if (!Stages.isPrimaryPart(target)) {
            BlockPos q = Stages.partner(target, p);
            if (q == null || !level.getBlockState(q).is(target.getBlock())) {
                return new Result(LOWER_FIRST, target, q == null ? null : direction(p, q), AIR, 0, 0);
            }
            return r;
        }
        if (noItem(target)) return of(NO_ITEM, target);
        // 물에 잠김은 그 자리 물 원천일 때만
        BlockState state = target;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            boolean water = level.getFluidState(p).is(Fluids.WATER) && level.getFluidState(p).isSource();
            state = state.setValue(BlockStateProperties.WATERLOGGED, water);
        }
        BlockPos partner = Stages.partner(state, p);
        if (partner != null) {
            BlockState wp = level.getBlockState(partner);
            if (!level.isLoaded(partner)) return of(UNLOADED, state);
            if (!(wp.isAir() || wp.canBeReplaced())) return new Result(PARTNER_BLOCKED, state, direction(p, partner), wp, 0, 0);
        }
        return support(m, level, p, state);
    }

    /** 설계·단계·층 조건. 통과하면 target = 설계 상태. */
    public static Result design(BlueprintModel m, BlockPos p, int kind, int sid, int mode, int layerY, boolean topDown) {
        Resolved r = m.resolved(p.asLong());
        if (r == null || r.isAir()) return of(NOT_DESIGN, AIR);
        BlockState t = r.state();
        if (!Stages.inBuildStage(m, r, kind, sid)) {
            int k = Stages.kindOf(m, r);
            return new Result(OTHER_STAGE, t, null, AIR, k, k == Stages.STRUCTURE ? r.owner() : 0);
        }
        if (t.getBlock() instanceof LiquidBlock) return of(LIQUID, t);
        if (!Stages.layerOk(t, p.getY(), mode, layerY, topDown)) {
            return new Result(OTHER_LAYER, t, null, AIR, Stages.buildLayerY(t, p.getY()), layerY);
        }
        return of(OK, t);
    }

    /** 자리 조건: 월드의 그 칸이 비었는가 (덮어 놓을 수 있는 풀·물 포함). */
    public static Result spot(Level level, BlockPos p, BlockState t) {
        if (!level.isLoaded(p)) return of(UNLOADED, t);
        BlockState w = level.getBlockState(p);
        if (StateRules.matches(w, t)) return of(DONE, t);
        if (w.isAir() || w.canBeReplaced()) return of(OK, t);
        if (StateRules.sameBlockWrongState(w, t)) return new Result(WRONG_STATE, t, null, w, 0, 0);
        if (StateRules.conversion(level, w, t) != null) return new Result(CONVERT, t, null, w, 0, 0);
        return new Result(OCCUPIED, t, null, w, 0, 0);
    }

    private static boolean noItem(BlockState t) {
        boolean[] none = {false};
        StateRules.requirement(t, (item, c) -> {
            if (item instanceof BucketItem || item == Items.BUCKET) return;
            if (item == Items.AIR) none[0] = true;
        });
        return none[0];
    }

    /**
     * 받침 판정: 그 자리(비었다고 보고)에 state 를 놓으면 버티는가.
     * 받침 방향은 블록 자신의 이웃 갱신 규칙으로 찾는다 (그 방향 이웃 때문에 공기로 바뀌면 그쪽이 받침).
     * 이 규칙으로 안 잡히면 버팀 검사가 실패할 때 블록 모양으로 받침 쪽을 짐작한다.
     * 받침이 없는 이유: 받침 칸이 아직 안 지은 설계 칸(대기) / 블록은 있는데 받칠 수 없는 모양 / 비어 있고 설계에도 없음(설계 오류).
     */
    public static Result support(@Nullable BlueprintModel m, Level level, BlockPos p, BlockState state) {
        Direction d = null;
        if (Stages.partner(state, p) == null) {
            // 2칸 블록은 짝 칸을 함께 놓으므로 짝이 없을 때의 모양 갱신으로 판정하지 않는다
            for (Direction dd : Direction.values()) {
                BlockPos q = p.relative(dd);
                BlockState after;
                try {
                    after = state.updateShape(dd, level.getBlockState(q), level, p, q);
                } catch (Exception e) {
                    continue;
                }
                if (after.isAir()) {
                    d = dd;
                    break;
                }
            }
        }
        if (d == null) {
            boolean ok;
            try {
                ok = state.canSurvive(level, p);
            } catch (Exception e) {
                ok = true;
            }
            if (ok) return of(OK, state);
            d = guessSupport(state);
        }
        BlockPos q = p.relative(d);
        BlockState wq = level.getBlockState(q);
        Resolved rq = m == null ? null : m.resolved(q.asLong());
        if (rq != null && !rq.isAir() && !StateRules.matches(wq, rq.state())) {
            return new Result(SUPPORT_PENDING, state, d, rq.state(), 0, 0);
        }
        if (wq.isAir() || wq.canBeReplaced()) return new Result(SUPPORT_DESIGN, state, d, wq, 0, 0);
        return new Result(SUPPORT_SHAPE, state, d, wq, 0, 0);
    }

    /** 이웃 갱신 규칙으로 안 잡힐 때의 받침 쪽 짐작. */
    private static Direction guessSupport(BlockState s) {
        if (Stages.hangsFromAbove(s)) return Direction.UP;
        if (s.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            AttachFace f = s.getValue(BlockStateProperties.ATTACH_FACE);
            if (f == AttachFace.FLOOR) return Direction.DOWN;
            if (f == AttachFace.CEILING) return Direction.UP;
            if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) return s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        Block b = s.getBlock();
        if (b instanceof CocoaBlock && s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_FACING);
        }
        if ((b instanceof WallTorchBlock || b instanceof WallSignBlock || b instanceof LadderBlock || b instanceof WallBannerBlock
                || b instanceof TripWireHookBlock) && s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            return s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite();
        }
        if (s.hasProperty(BlockStateProperties.FACING)) return s.getValue(BlockStateProperties.FACING).getOpposite();
        return Direction.DOWN;
    }

    @Nullable
    private static Direction direction(BlockPos from, BlockPos to) {
        return Direction.fromDelta(to.getX() - from.getX(), to.getY() - from.getY(), to.getZ() - from.getZ());
    }
}
