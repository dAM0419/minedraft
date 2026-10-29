package dev.designandbuild.client.editor;

import dev.designandbuild.core.Resolved;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.StandingAndWallBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

/**
 * 직접조작에서 놓을 블록 상태 정하기.
 *
 * 방향 삼면도의 세 입력(바라보는 방향, 클릭할 면, 클릭 높이)으로 가상의 설치 상황을 만들어
 * 바닐라 설치 로직(getStateForPlacement)에 그대로 넘긴다. 그래서 다른 모드 블록도 같은 규칙으로 방향을 받는다.
 * 설치는 주변이 비어 있는 하늘 높이에서 계산하고, 연결 모양(울타리·벽 등)은 설계 위에서 다시 계산한다.
 */
public final class PlacementStates {
    private PlacementStates() {}

    /** 재료 아이템을 이 방향 입력으로 놓았을 때의 상태. 블록이 아니면 null. */
    @Nullable
    public static BlockState stateFor(ItemStack stack, Direction lookH, int lookV, Direction face, boolean upper) {
        if (stack.isEmpty()) return null;
        if (stack.getItem() instanceof BucketItem) {
            var contained = net.neoforged.neoforge.fluids.FluidUtil.getFluidContained(stack);
            if (contained.isEmpty()) return null;
            Fluid fluid = contained.get().getFluid();
            if (fluid == Fluids.EMPTY) return null;
            return fluid.defaultFluidState().createLegacyBlock();
        }
        if (!(stack.getItem() instanceof BlockItem blockItem)) return null;
        Block block = blockItem.getBlock();
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return block.defaultBlockState();
        // 같은 재료·입력이면 결과도 같다 (빈 하늘 높이에서 계산하므로 자리와 무관). 화면마다 여러 번 묻는다
        if (level != cacheLevel || CACHE.size() > 4096) {
            CACHE.clear();
            cacheLevel = level;
        }
        Key key = new Key(blockItem, stack.getComponentsPatch(), lookH, lookV, face, upper);
        BlockState cached = CACHE.get(key);
        if (cached != null) return cached;
        BlockState computed = compute(stack, blockItem, block, level, mc, lookH, lookV, face, upper);
        CACHE.put(key, computed);
        return computed;
    }

    private record Key(BlockItem item, net.minecraft.core.component.DataComponentPatch components, Direction lookH, int lookV,
                       Direction face, boolean upper) {}

    private static final java.util.Map<Key, BlockState> CACHE = new java.util.HashMap<>();
    @Nullable
    private static Level cacheLevel;

    private static BlockState compute(ItemStack stack, BlockItem blockItem, Block block, Level level, Minecraft mc,
                                      Direction lookH, int lookV, Direction face, boolean upper) {
        int px = mc.player == null ? 0 : mc.player.getBlockX();
        int pz = mc.player == null ? 0 : mc.player.getBlockZ();
        BlockPos target = new BlockPos(px, level.getMaxBuildHeight() - 3, pz);
        Direction look = lookV > 0 ? Direction.UP : lookV < 0 ? Direction.DOWN : lookH;

        double hx = target.getX() + 0.5, hy = target.getY() + 0.5, hz = target.getZ() + 0.5;
        switch (face) {
            case UP -> hy = target.getY();
            case DOWN -> hy = target.getY() + 1;
            case EAST -> hx = target.getX();
            case WEST -> hx = target.getX() + 1;
            case SOUTH -> hz = target.getZ();
            case NORTH -> hz = target.getZ() + 1;
        }
        if (face.getAxis().isHorizontal()) hy = target.getY() + (upper ? 0.75 : 0.25);
        BlockHitResult hit = new BlockHitResult(new Vec3(hx, hy, hz), face, target.relative(face.getOpposite()), false);
        VirtualPlaceContext ctx = new VirtualPlaceContext(level, stack, hit, target, look, lookH);

        BlockState state = null;
        try {
            Block wall = wallBlockOf(blockItem);
            if (wall != null && face.getAxis().isHorizontal()) state = wall.getStateForPlacement(ctx);
            if (state == null) state = block.getStateForPlacement(ctx);
        } catch (Exception ignored) {
        }
        if (state == null) state = fallback(block.defaultBlockState(), lookH, look, face, upper);
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        return state;
    }

    /** StandingAndWallBlockItem.wallBlock (비공개). 한 번만 찾는다. */
    @Nullable
    private static Field wallField;
    private static boolean wallFieldLooked;

    @Nullable
    private static Block wallBlockOf(BlockItem item) {
        if (!(item instanceof StandingAndWallBlockItem)) return null;
        try {
            if (!wallFieldLooked) {
                wallFieldLooked = true;
                Field f = StandingAndWallBlockItem.class.getDeclaredField("wallBlock");
                f.setAccessible(true);
                wallField = f;
            }
            return wallField == null ? null : (Block) wallField.get(item);
        } catch (Exception e) {
            return null;
        }
    }

    /** 설치 로직이 거부하면(받침이 없어서 등) 방향 속성만 직접 맞춘다. */
    private static BlockState fallback(BlockState s, Direction lookH, Direction look, Direction face, boolean upper) {
        boolean top = face == Direction.DOWN || (face.getAxis().isHorizontal() && upper);
        if (s.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
            Direction d = face.getAxis().isHorizontal() ? face : lookH.getOpposite();
            s = s.setValue(BlockStateProperties.HORIZONTAL_FACING, d);
        }
        if (s.hasProperty(BlockStateProperties.FACING)) s = s.setValue(BlockStateProperties.FACING, look.getOpposite());
        if (s.hasProperty(BlockStateProperties.AXIS)) s = s.setValue(BlockStateProperties.AXIS, face.getAxis());
        if (s.hasProperty(BlockStateProperties.HALF)) s = s.setValue(BlockStateProperties.HALF, top ? Half.TOP : Half.BOTTOM);
        if (s.hasProperty(BlockStateProperties.SLAB_TYPE)) s = s.setValue(BlockStateProperties.SLAB_TYPE, top ? SlabType.TOP : SlabType.BOTTOM);
        if (s.hasProperty(BlockStateProperties.ATTACH_FACE)) {
            s = s.setValue(BlockStateProperties.ATTACH_FACE,
                    face == Direction.UP ? AttachFace.FLOOR : face == Direction.DOWN ? AttachFace.CEILING : AttachFace.WALL);
        }
        // 매다는 블록 (랜턴·맹그로브 번식체): 블록 밑면을 누르거나 위를 보고 놓으면 매단 것 (바닐라는 받침이 있어야 정해 준다)
        boolean hang = face == Direction.DOWN || look == Direction.UP;
        if (s.hasProperty(BlockStateProperties.HANGING)) s = s.setValue(BlockStateProperties.HANGING, hang);
        // 종: 바닥 / 천장 / 벽
        if (s.hasProperty(BlockStateProperties.BELL_ATTACHMENT)) {
            s = s.setValue(BlockStateProperties.BELL_ATTACHMENT, face == Direction.UP ? BellAttachType.FLOOR
                    : face == Direction.DOWN ? BellAttachType.CEILING : BellAttachType.SINGLE_WALL);
        }
        // 뾰족한 점적석: 밑면에 붙이면 아래로 (종유석)
        if (s.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)) {
            s = s.setValue(BlockStateProperties.VERTICAL_DIRECTION, hang ? Direction.DOWN : Direction.UP);
        }
        return s;
    }

    /** 물 원천 자리에 물에 잠길 수 있는 블록을 놓으면 잠긴 상태로. */
    public static BlockState waterlog(BlockState state, BlockState current) {
        if (state.hasProperty(BlockStateProperties.WATERLOGGED) && current.getBlock() instanceof LiquidBlock
                && current.getFluidState().is(Fluids.WATER) && current.getFluidState().isSource()) {
            return state.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        return state;
    }

    /** 문·침대·큰 꽃의 나머지 반쪽을 함께 놓는다. */
    public static void expandMultiBlocks(EditorSession s, Long2ObjectLinkedOpenHashMap<BlockState> cells) {
        LongArrayList keys = new LongArrayList(cells.keySet());
        for (int i = 0; i < keys.size(); i++) {
            long p = keys.getLong(i);
            BlockState st = cells.get(p);
            if (st == null) continue;
            BlockPos pos = BlockPos.of(p);
            if (st.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                    && st.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER) {
                BlockPos up = pos.above();
                if (s.model.isInside(up.getX(), up.getY(), up.getZ())) {
                    cells.put(up.asLong(), st.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
                }
            } else if (st.hasProperty(BlockStateProperties.BED_PART) && st.hasProperty(BlockStateProperties.HORIZONTAL_FACING)
                    && st.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
                BlockPos head = pos.relative(st.getValue(BlockStateProperties.HORIZONTAL_FACING));
                if (s.model.isInside(head.getX(), head.getY(), head.getZ())) {
                    cells.put(head.asLong(), st.setValue(BlockStateProperties.BED_PART, BedPart.HEAD));
                }
            }
        }
    }

    /**
     * 놓은 칸과, 같은 층(지형 / 선택 구조물)에 속한 이웃 설계 칸의 연결 모양을 다시 계산한다.
     * 이웃이 바뀌면 cells 에 더한다. 실패하는 블록은 그대로 둔다.
     */
    public static void updateConnections(EditorSession s, Long2ObjectLinkedOpenHashMap<BlockState> cells, boolean structureLayer) {
        if (cells.isEmpty() || cells.size() > 200_000) return;
        LevelAccessor accessor = proxy(s, cells);
        if (accessor == null) return;
        LongArrayList targets = new LongArrayList(cells.keySet());
        for (long p : cells.keySet().toLongArray()) {
            BlockPos pos = BlockPos.of(p);
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                long nl = n.asLong();
                if (cells.containsKey(nl) || !s.model.isInside(nl)) continue;
                Resolved r = s.model.resolved(nl);
                if (r == null || r.isAir()) continue;
                boolean sameLayer = structureLayer ? (r.owner() != 0 && r.owner() == s.selected)
                        : (r.owner() == 0 && s.model.terrainAt(nl) != null);
                if (sameLayer && !targets.contains(nl)) targets.add(nl);
            }
        }
        BlockPos.MutableBlockPos np = new BlockPos.MutableBlockPos();
        for (int i = 0; i < targets.size(); i++) {
            long p = targets.getLong(i);
            BlockPos pos = BlockPos.of(p);
            BlockState st = cells.containsKey(p) ? cells.get(p) : s.blockAt(pos.getX(), pos.getY(), pos.getZ(), false);
            if (st == null || st.isAir()) continue;
            BlockState updated = st;
            try {
                for (Direction d : Direction.values()) {
                    np.setWithOffset(pos, d);
                    BlockState neighbor = accessor.getBlockState(np);
                    updated = updated.updateShape(d, neighbor, accessor, pos, np.immutable());
                    if (updated.isAir()) break;
                }
            } catch (Exception e) {
                updated = st;
            }
            if (updated.isAir()) updated = st; // 받침 없는 블록이 사라지지 않게
            if (updated != st) cells.put(p, updated);
        }
    }

    /** 설계 위에서 블록을 읽는 가짜 LevelAccessor (updateShape 용). */
    @Nullable
    private static LevelAccessor proxy(EditorSession s, Long2ObjectMap<BlockState> overlay) {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) return null;
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "getBlockState": {
                    BlockPos p = (BlockPos) args[0];
                    BlockState o = overlay.get(p.asLong());
                    return o != null ? o : s.blockAt(p.getX(), p.getY(), p.getZ(), false);
                }
                case "getFluidState": {
                    BlockPos p = (BlockPos) args[0];
                    BlockState o = overlay.get(p.asLong());
                    return (o != null ? o : s.blockAt(p.getX(), p.getY(), p.getZ(), false)).getFluidState();
                }
                case "getBlockEntity":
                    return null;
                case "isClientSide":
                    return true;
                case "getMinBuildHeight":
                    return s.model.minY;
                case "getHeight":
                    return s.model.maxY - s.model.minY;
                case "getMaxBuildHeight":
                    return s.model.maxY;
                case "registryAccess":
                    return level.registryAccess();
                case "enabledFeatures":
                    return level.enabledFeatures();
                case "dimensionType":
                    return level.dimensionType();
                case "getRandom":
                    return RandomSource.create();
                case "isEmptyBlock": {
                    BlockPos p = (BlockPos) args[0];
                    BlockState o = overlay.get(p.asLong());
                    return (o != null ? o : s.blockAt(p.getX(), p.getY(), p.getZ(), false)).isAir();
                }
                case "scheduleTick", "blockUpdated", "neighborShapeChanged", "playSound", "addParticle",
                     "levelEvent", "gameEvent":
                    return defaultValue(method.getReturnType());
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "equals":
                    return proxy == args[0];
                case "toString":
                    return "DesignAndBuild design accessor";
                default:
                    if (method.isDefault()) return InvocationHandler.invokeDefault(proxy, method, args);
                    return defaultValue(method.getReturnType());
            }
        };
        try {
            return (LevelAccessor) Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),
                    new Class<?>[]{LevelAccessor.class}, handler);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == char.class) return (char) 0;
        return null;
    }

    // ------------------------------------------------------------------

    /** 하늘 위 가상 설치 상황. 플레이어 없이, 삼면도에서 고른 시선을 쓴다. */
    private static final class VirtualPlaceContext extends BlockPlaceContext {
        private final BlockPos target;
        private final Direction look;
        private final Direction horizontal;

        VirtualPlaceContext(Level level, ItemStack stack, BlockHitResult hit, BlockPos target, Direction look, Direction horizontal) {
            super(level, null, InteractionHand.MAIN_HAND, stack, hit);
            this.target = target;
            this.look = look;
            this.horizontal = horizontal;
        }

        @Override
        public BlockPos getClickedPos() {
            return target;
        }

        @Override
        public boolean canPlace() {
            return true;
        }

        @Override
        public boolean replacingClickedOnBlock() {
            return false;
        }

        @Override
        public Direction getNearestLookingDirection() {
            return look;
        }

        @Override
        public Direction getNearestLookingVerticalDirection() {
            return look == Direction.UP ? Direction.UP : Direction.DOWN;
        }

        @Override
        public Direction[] getNearestLookingDirections() {
            Direction vertical = look == Direction.UP ? Direction.UP : Direction.DOWN;
            if (look.getAxis().isVertical()) {
                return new Direction[]{look, horizontal, horizontal.getClockWise(), horizontal.getCounterClockWise(),
                        horizontal.getOpposite(), look.getOpposite()};
            }
            return new Direction[]{horizontal, vertical, horizontal.getClockWise(), horizontal.getCounterClockWise(),
                    vertical.getOpposite(), horizontal.getOpposite()};
        }

        @Override
        public Direction getHorizontalDirection() {
            return horizontal;
        }

        @Override
        public float getRotation() {
            return horizontal.toYRot();
        }

        @Override
        public boolean isSecondaryUseActive() {
            return false;
        }
    }
}
