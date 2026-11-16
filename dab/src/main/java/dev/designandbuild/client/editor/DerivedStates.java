package dev.designandbuild.client.editor;

import dev.designandbuild.core.StateRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;

/**
 * 파생 블록(설치된 자리의 조건에 따라 다른 블록이 되는 것: Create 축 → 동력 축) 을 문맥에 맞게 고른다.
 * 편집창 반응 시뮬레이션과 시공 투영이 함께 쓴다.
 */
public final class DerivedStates {
    private DerivedStates() {}

    @Nullable
    private static Set<Block> bases;

    /** 파생 블록이 있는 원래 블록들 (자기 아이템 없는 블록의 집기 아이템 블록). 레지스트리에서 한 번 모은다. */
    public static boolean hasVariants(Block b, Level probe) {
        if (bases == null) {
            StateRules.probe(probe);
            Set<Block> s = new HashSet<>();
            for (Block x : BuiltInRegistries.BLOCK) {
                if (x.asItem() != net.minecraft.world.item.Items.AIR) continue;
                if (StateRules.pickItem(x) instanceof BlockItem bi && bi.getBlock() != x) s.add(bi.getBlock());
            }
            bases = s;
        }
        return bases.contains(b);
    }

    /**
     * 이 자리(level 기준)에 st 를 놓으면 실제로 무엇이 되는가. 파생 블록이 나오면 그것 (놓을 때 고른 축·방향은 지킨다),
     * 아니면 null. level 은 그 칸을 비워 둔 채로 읽혀야 한다 (부르는 쪽 책임).
     */
    @Nullable
    public static BlockState inContext(Level level, BlockPos pos, BlockState st) {
        if (!(StateRules.pickItem(st.getBlock()) instanceof BlockItem bi)) return null;
        try {
            Direction look = lookOf(st);
            Direction horizontal = look.getAxis().isHorizontal() ? look : Direction.NORTH;
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), look.getOpposite(), pos.relative(look), false);
            BlockState alt = bi.getBlock().getStateForPlacement(
                    new PlacementStates.VirtualPlaceContext(level, new ItemStack(bi), hit, pos, look, horizontal));
            if (alt == null || alt.getBlock() == st.getBlock() || !StateRules.derivedEquivalent(alt, st)) return null;
            return copyShared(st, alt);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 상태가 가리키는 방향 (축·방향 속성). 없으면 아래. */
    static Direction lookOf(BlockState st) {
        for (Property<?> p : st.getProperties()) {
            Object v = st.getValue(p);
            if (v instanceof Direction d) return d;
            if (v instanceof Direction.Axis a) return Direction.fromAxisAndDirection(a, Direction.AxisDirection.POSITIVE);
        }
        return Direction.DOWN;
    }

    /** alt 에, st 와 이름이 같은 속성의 값(축·방향 등)을 옮긴다. */
    static BlockState copyShared(BlockState st, BlockState alt) {
        BlockState out = alt;
        for (Property<?> ap : alt.getProperties()) {
            for (Property<?> sp : st.getProperties()) {
                if (sp.getName().equals(ap.getName())) out = setByName(out, ap, String.valueOf(st.getValue(sp)));
            }
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState setByName(BlockState s, Property<T> p, String value) {
        return p.getValue(value).map(v -> s.setValue(p, v)).orElse(s);
    }
}
