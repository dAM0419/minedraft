package dev.designandbuild.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.DataMapHooks;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import org.jetbrains.annotations.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.DirtPathBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SeaPickleBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.TurtleEggBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

import java.util.function.BiConsumer;

/**
 * 투영 비교 규칙과 재료 환산.
 *
 * 비교: 설치로 정해지는 값(방향, 축, 위·아래, 반블록 종류, 물 잠김)만 본다.
 * 주변이나 시간에 따라 바뀌는 값(연결 모양, 전원, 열림, 작물 나이 등)은 무시한다.
 *
 * 예외:
 * - 흙과 잔디 블록은 서로 완료로 친다 (잔디는 흙 위로 번지고, 잔디 아래는 흙이다).
 * - 그 자리 블록을 도구로 가공해 목표가 되면 (흙 → 흙길, 원목 → 껍질 벗긴 원목, 녹슨 구리 → 구리 …)
 *   부수지 않고 "가공"으로 본다 ({@link #conversion}). 철거 단계에는 나오지 않고 그 칸의 단계에서 도구로 바꾼다.
 */
public final class StateRules {
    private StateRules() {}

    public static final TagKey<Block> NEEDS_SILK_TOUCH =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("designandbuild", "needs_silk_touch"));

    /** 재료 옆에 붙이는 안내 아이콘. */
    public static final int HINT_NONE = 0, HINT_SHOVEL = 1, HINT_HOE = 2, HINT_SILK = 3;

    // ------------------------------------------------------------------
    // 비교
    // ------------------------------------------------------------------

    public static boolean matches(BlockState world, BlockState target) {
        if (world == target) return true;
        if (target.isAir()) return world.isAir();
        if (soilEquivalent(world.getBlock(), target.getBlock())) return true;
        if (target.getBlock() instanceof LiquidBlock) {
            FluidState wf = world.getFluidState();
            return world.getBlock() == target.getBlock() && wf.isSource();
        }
        if (world.getBlock() != target.getBlock()) return false;
        for (Property<?> p : target.getProperties()) {
            if (isPlacementProperty(target, p) && world.hasProperty(p) && !world.getValue(p).equals(target.getValue(p))) {
                return false;
            }
        }
        return true;
    }

    /** 같은 블록이지만 방향 등 설치 값이 다르다 (주황 외곽선). */
    public static boolean sameBlockWrongState(BlockState world, BlockState target) {
        return !target.isAir() && world.getBlock() == target.getBlock() && !matches(world, target);
    }

    /** 목표를 이루려면 지금 있는 블록을 먼저 부숴야(메워야) 하는가. 가공으로 될 칸은 부수지 않는다. */
    public static boolean needsDemolish(Level level, BlockState world, BlockState target) {
        if (world.isAir()) return false;
        if (matches(world, target)) return false;
        FluidState wf = world.getFluidState();
        if (world.getBlock() instanceof LiquidBlock && !wf.isSource()) return false; // 흐르는 물은 원천을 치우면 사라진다
        if (!target.isAir()) {
            if (world.getBlock() == target.getBlock()) return false; // 방향만 다름 → 해당 단계에서 고친다
            if (conversion(level, world, target) != null) return false; // 도구로 가공한다
            if (world.canBeReplaced()) return false;                  // 풀·물 등은 바로 덮어 놓을 수 있다
        }
        return true;
    }

    /** 흙 ↔ 잔디 블록: 어느 쪽이 있어도 다른 쪽 목표를 이룬 것으로 본다. */
    private static boolean soilEquivalent(Block world, Block target) {
        return (target == Blocks.GRASS_BLOCK && world == Blocks.DIRT) || (target == Blocks.DIRT && world == Blocks.GRASS_BLOCK);
    }

    // ------------------------------------------------------------------
    // 가공 (도구로 블록을 바꿔 목표를 만든다)
    // ------------------------------------------------------------------

    /** 가공 도구 종류. 꿀벌집은 도구가 아니라 쓰는 아이템이다 (구리 밀랍 칠). */
    public static final int TOOL_SHOVEL = 0, TOOL_HOE = 1, TOOL_AXE = 2, TOOL_SHEARS = 3, TOOL_HONEYCOMB = 4, TOOL_COUNT = 5;

    private static final ItemAbility[] ABILITIES = {
            ItemAbilities.SHOVEL_FLATTEN, ItemAbilities.HOE_TILL, ItemAbilities.AXE_STRIP, ItemAbilities.AXE_SCRAPE,
            ItemAbilities.AXE_WAX_OFF, ItemAbilities.SHEARS_CARVE};
    private static final int[] ABILITY_TOOL = {TOOL_SHOVEL, TOOL_HOE, TOOL_AXE, TOOL_AXE, TOOL_AXE, TOOL_SHEARS};
    /** 연달아 가공하는 최대 횟수 (산화된 구리 → 구리 = 도끼 3회, 밀랍 칠까지 4회). */
    private static final int MAX_STEPS = 4;

    /** 가공 방법: 도구별 사용 횟수. */
    public record Conversion(int[] uses) {}

    private record Key(BlockState world, BlockState target) {}

    private static final Conversion NONE_CONVERSION = new Conversion(new int[0]);
    private static final java.util.concurrent.ConcurrentHashMap<Key, Conversion> CONVERSIONS = new java.util.concurrent.ConcurrentHashMap<>();

    public static ItemAbility abilityOf(int tool) {
        return switch (tool) {
            case TOOL_SHOVEL -> ItemAbilities.SHOVEL_FLATTEN;
            case TOOL_HOE -> ItemAbilities.HOE_TILL;
            case TOOL_AXE -> ItemAbilities.AXE_STRIP;
            case TOOL_SHEARS -> ItemAbilities.SHEARS_CARVE;
            default -> null;
        };
    }

    /** HUD 에 그릴 대표 아이템. */
    public static Item toolIcon(int tool) {
        return switch (tool) {
            case TOOL_SHOVEL -> Items.IRON_SHOVEL;
            case TOOL_HOE -> Items.IRON_HOE;
            case TOOL_AXE -> Items.IRON_AXE;
            case TOOL_SHEARS -> Items.SHEARS;
            default -> Items.HONEYCOMB;
        };
    }

    /**
     * 지금 블록을 도구로 가공해 목표를 만들 수 있으면 그 방법, 아니면 null. 같은 블록끼리(방향만 다름)는 가공이 아니다.
     * NeoForge 의 도구 가공 경로(getToolModifiedState, 시뮬레이션)를 쓰므로 다른 모드 블록의 가공도 따라온다.
     * 위가 비어 있어야 하는 가공(괭이)은 비어 있다고 가정한다 — 위 칸은 철거 단계에서 이미 치워진다.
     * 결과는 (지금 블록, 목표) 쌍마다 기억한다.
     */
    @Nullable
    public static Conversion conversion(@Nullable Level level, BlockState world, BlockState target) {
        if (level == null || target.isAir() || world.isAir() || world.getBlock() == target.getBlock()) return null;
        Key key = new Key(world, target);
        Conversion c = CONVERSIONS.get(key);
        if (c == null) {
            c = search(level, world, target);
            if (CONVERSIONS.size() > 50_000) CONVERSIONS.clear();
            CONVERSIONS.put(key, c == null ? NONE_CONVERSION : c);
        }
        return c == NONE_CONVERSION ? null : c;
    }

    /** 너비 우선으로 MAX_STEPS 번까지 가공해 본다. */
    @Nullable
    private static Conversion search(Level level, BlockState world, BlockState target) {
        record Node(BlockState state, int[] uses) {}
        java.util.ArrayDeque<Node> queue = new java.util.ArrayDeque<>();
        java.util.Set<BlockState> seen = new java.util.HashSet<>();
        queue.add(new Node(world, new int[TOOL_COUNT]));
        seen.add(world);
        // 월드 밖 높이: 위 칸도 월드 밖이라 "비어 있음"으로 읽히고, 청크를 불러오지 않는다
        BlockPos at = new BlockPos(0, level.getMaxBuildHeight() + 16, 0);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false);
        while (!queue.isEmpty()) {
            Node n = queue.poll();
            int depth = 0;
            for (int u : n.uses) depth += u;
            if (depth >= MAX_STEPS) continue;
            for (int k = 0; k <= ABILITIES.length; k++) {
                BlockState next;
                int tool;
                if (k == ABILITIES.length) {
                    Block waxed = DataMapHooks.getBlockWaxed(n.state.getBlock());
                    next = waxed == null ? null : waxed.withPropertiesOf(n.state);
                    tool = TOOL_HONEYCOMB;
                } else {
                    tool = ABILITY_TOOL[k];
                    next = modified(level, n.state, ABILITIES[k], toolIcon(tool), hit);
                }
                if (next == null || next == n.state || !seen.add(next)) continue;
                int[] uses = n.uses.clone();
                uses[tool]++;
                if (matches(next, target)) return new Conversion(uses);
                queue.add(new Node(next, uses));
            }
        }
        return null;
    }

    @Nullable
    private static BlockState modified(Level level, BlockState state, ItemAbility ability, Item tool, BlockHitResult hit) {
        try {
            UseOnContext ctx = new UseOnContext(level, null, InteractionHand.MAIN_HAND, new ItemStack(tool), hit) {};
            return state.getToolModifiedState(ctx, ability, true);
        } catch (Exception e) {
            return null; // 다른 모드의 가공 처리가 플레이어 없이 실패해도 무시
        }
    }

    /** 철거 대상이 액체 원천이면 "메우기"로 표시한다. */
    public static boolean isFillTarget(BlockState world) {
        return world.getBlock() instanceof LiquidBlock && world.getFluidState().isSource();
    }

    private static boolean isPlacementProperty(BlockState state, Property<?> p) {
        return switch (p.getName()) {
            case "facing", "axis", "half", "face", "attach_face", "rotation", "orientation", "hanging", "part",
                 "waterlogged" -> true;
            case "type" -> state.getBlock() instanceof SlabBlock;
            default -> false;
        };
    }

    // ------------------------------------------------------------------
    // 재료 환산
    // ------------------------------------------------------------------

    /**
     * 블록 하나를 놓는 데 드는 아이템을 out(item, count) 으로 알린다. 안내 아이콘은 hint 로 돌려준다.
     * 아이템이 없는 블록(불 등)은 Items.AIR 로 알린다.
     */
    public static int requirement(BlockState s, BiConsumer<Item, Integer> out) {
        if (s.isAir()) return HINT_NONE;
        Block block = s.getBlock();
        if (s.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && s.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return HINT_NONE;
        if (s.hasProperty(BlockStateProperties.BED_PART) && s.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) {
            return HINT_NONE;
        }
        if (block instanceof LiquidBlock) {
            FluidState f = s.getFluidState();
            if (!f.isSource()) return HINT_NONE;
            Item bucket = f.getType().getBucket();
            out.accept(bucket == Items.AIR ? Items.BUCKET : bucket, 1);
            return HINT_NONE;
        }
        int hint = s.is(NEEDS_SILK_TOUCH) ? HINT_SILK : HINT_NONE;
        Item item = block.asItem();
        int count = 1;
        if (block instanceof FarmBlock) {
            item = Items.DIRT;
            hint = HINT_HOE;
        } else if (block instanceof DirtPathBlock) {
            item = Items.DIRT;
            hint = HINT_SHOVEL;
        } else if (block == Blocks.GRASS_BLOCK) {
            item = Items.DIRT; // 흙을 놓으면 잔디가 번진다 (흙도 완료로 친다)
            hint = HINT_NONE;
        } else if (block instanceof SlabBlock && s.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE) {
            count = 2;
        } else if (block instanceof CandleBlock) {
            count = s.getValue(CandleBlock.CANDLES);
        } else if (block instanceof SeaPickleBlock) {
            count = s.getValue(SeaPickleBlock.PICKLES);
        } else if (block instanceof TurtleEggBlock) {
            count = s.getValue(TurtleEggBlock.EGGS);
        } else if (block instanceof SnowLayerBlock) {
            count = s.getValue(SnowLayerBlock.LAYERS);
        }
        out.accept(item, count);
        if (s.hasProperty(BlockStateProperties.WATERLOGGED) && s.getValue(BlockStateProperties.WATERLOGGED)
                && !(block instanceof LiquidBlock)) {
            out.accept(Fluids.WATER.getBucket(), 1);
        }
        return hint;
    }
}
