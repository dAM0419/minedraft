package dev.designandbuild.block;

import com.mojang.serialization.MapCodec;
import dev.designandbuild.item.BlueprintItem;
import dev.designandbuild.server.BlueprintManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * 도면 테이블 (제도대). 옆 2칸 × 위 2칸. 설치한 아래 칸(MAIN, 아래)이 블록 엔티티를 갖고, 그 칸의 청크가 기준 청크가 된다.
 * 바라보는 사람 쪽이 앞이고, 옆 칸(SIDE)은 설치한 사람의 오른쪽에 생긴다. 위 칸(UPPER)은 기울어진 상판 자리를 차지할 뿐 그리는 것은 없다.
 * 모델은 아래 칸이 위 칸 높이까지 한꺼번에 그린다. 네 칸 중 하나가 없어지면 나머지도 사라진다.
 */
public class BlueprintTableBlock extends HorizontalDirectionalBlock implements EntityBlock {
    public static final MapCodec<BlueprintTableBlock> CODEC = simpleCodec(BlueprintTableBlock::new);
    public static final EnumProperty<Part> PART = EnumProperty.create("part", Part.class);
    public static final BooleanProperty HAS_BLUEPRINT = BooleanProperty.create("blueprint");
    public static final BooleanProperty UPPER = BooleanProperty.create("upper");

    public enum Part implements StringRepresentable {
        MAIN, SIDE;

        @Override
        public String getSerializedName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** 아래 칸: 바깥쪽 옆 틀(기둥·받침)만. 다리 사이는 지나갈 수 있다. 키는 바깥쪽 방향. */
    private static final Map<Direction, VoxelShape> LOWER = new EnumMap<>(Direction.class);
    /** 위 칸: 기울어진 상판(앞 낮음)을 덮는 판. */
    private static final VoxelShape UPPER_SHAPE = Block.box(0, 0, 0, 16, 7, 16);

    static {
        LOWER.put(Direction.EAST, Block.box(11.5, 0, 1, 14.5, 16, 15));
        LOWER.put(Direction.WEST, Block.box(1.5, 0, 1, 4.5, 16, 15));
        LOWER.put(Direction.SOUTH, Block.box(1, 0, 11.5, 15, 16, 14.5));
        LOWER.put(Direction.NORTH, Block.box(1, 0, 1.5, 15, 16, 4.5));
    }

    public BlueprintTableBlock(Properties properties) {
        super(properties.pushReaction(PushReaction.BLOCK));
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(PART, Part.MAIN)
                .setValue(HAS_BLUEPRINT, false)
                .setValue(UPPER, false));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART, HAS_BLUEPRINT, UPPER);
    }

    /** 이 칸에서 짝 칸으로 가는 방향. */
    public static Direction partnerDirection(BlockState state) {
        Direction facing = state.getValue(FACING);
        return state.getValue(PART) == Part.MAIN ? facing.getCounterClockWise() : facing.getClockWise();
    }

    /** 블록 엔티티가 있는 칸 (아래 MAIN). */
    public static BlockPos mainPos(BlockPos pos, BlockState state) {
        BlockPos lower = state.getValue(UPPER) ? pos.below() : pos;
        return state.getValue(PART) == Part.MAIN ? lower : lower.relative(partnerDirection(state));
    }

    private static boolean isMainLower(BlockState state) {
        return state.getValue(PART) == Part.MAIN && !state.getValue(UPPER);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        BlockPos pos = context.getClickedPos();
        BlockPos side = pos.relative(facing.getCounterClockWise());
        Level level = context.getLevel();
        if (pos.getY() >= level.getMaxBuildHeight() - 1) return null;
        for (BlockPos q : new BlockPos[]{side, pos.above(), side.above()}) {
            if (!level.getBlockState(q).canBeReplaced(context) || !level.getWorldBorder().isWithinBounds(q)) return null;
        }
        return defaultBlockState().setValue(FACING, facing);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide) {
            BlockPos side = pos.relative(partnerDirection(state));
            BlockState sideState = state.setValue(PART, Part.SIDE).setValue(HAS_BLUEPRINT, false);
            level.setBlock(side, sideState, 3);
            level.setBlock(pos.above(), state.setValue(HAS_BLUEPRINT, false).setValue(UPPER, true), 3);
            level.setBlock(side.above(), sideState.setValue(UPPER, true), 3);
            level.blockUpdated(pos, Blocks.AIR);
            state.updateNeighbourShapes(level, pos, 3);
        }
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor, LevelAccessor level,
                                     BlockPos pos, BlockPos neighborPos) {
        boolean upper = state.getValue(UPPER);
        if (direction == partnerDirection(state)) {
            boolean paired = neighbor.is(this) && neighbor.getValue(PART) != state.getValue(PART)
                    && neighbor.getValue(FACING) == state.getValue(FACING) && neighbor.getValue(UPPER) == upper;
            return paired ? state : Blocks.AIR.defaultBlockState();
        }
        if (direction == (upper ? Direction.DOWN : Direction.UP)) {
            boolean paired = neighbor.is(this) && neighbor.getValue(PART) == state.getValue(PART)
                    && neighbor.getValue(FACING) == state.getValue(FACING) && neighbor.getValue(UPPER) != upper;
            return paired ? state : Blocks.AIR.defaultBlockState();
        }
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        // 창작 모드에서 다른 칸을 부숴도 테이블 아이템이 떨어지지 않게 한다 (아이템은 아래 MAIN 칸에서만 나온다)
        if (!level.isClientSide && player.isCreative() && !isMainLower(state)) {
            BlockPos main = mainPos(pos, state);
            BlockState mainState = level.getBlockState(main);
            if (mainState.is(this) && isMainLower(mainState)) {
                level.setBlock(main, Blocks.AIR.defaultBlockState(), 35);
                level.levelEvent(player, 2001, main, Block.getId(mainState));
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (isMainLower(state) && level.getBlockEntity(pos) instanceof BlueprintTableBlockEntity table) {
                ItemStack stack = table.takeForRemoval();
                if (!stack.isEmpty()) Containers.dropItemStack(level, pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5, stack);
            }
            if (!level.isClientSide && isMainLower(state)) {
                BlueprintManager manager = BlueprintManager.get();
                if (manager != null) manager.onTableChanged(level, pos, null);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (stack.getItem() instanceof BlueprintItem) {
            BlueprintTableBlockEntity table = BlueprintTableBlockEntity.at(level, pos);
            if (table != null && table.blueprint().isEmpty()) {
                if (!level.isClientSide) {
                    ItemStack one = player.hasInfiniteMaterials() ? stack.copyWithCount(1) : stack.split(1);
                    table.setBlueprint(one);
                    level.playSound(null, pos, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1.0F, 1.0F);
                }
                return ItemInteractionResult.sidedSuccess(level.isClientSide);
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            BlueprintTableBlockEntity table = BlueprintTableBlockEntity.at(level, pos);
            if (table != null) {
                BlockPos main = table.getBlockPos();
                serverPlayer.openMenu(table, buf -> buf.writeBlockPos(main));
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        if (state.getValue(UPPER)) return UPPER_SHAPE;
        return LOWER.get(partnerDirection(state).getOpposite());
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return isMainLower(state) ? new BlueprintTableBlockEntity(pos, state) : null;
    }
}
