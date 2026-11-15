package dev.designandbuild.client.editor;

import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.StateRules;
import dev.minedraft.lib.client.render.FakeLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 설치·제거 때 블록이 주변을 보고 다른 블록이 되는 반응을 설계 위에서 흉내 낸다 (Ponder 처럼 가짜 월드에서).
 * 예: Create 증기기관 앞에 축을 놓으면 동력 축이 되고, 증기기관을 놓거나 치우면 앞의 축이 동력 축 ↔ 축으로 바뀐다.
 *
 * 1) 놓는 칸: 실제 자리에서 설치 상태를 다시 물어, 같은 아이템의 파생 블록이 나오면 그것으로 바꾼다.
 * 2) 놓은·지운 칸의 설치·제거 반응(onPlace / onRemove)을 돌려, 블록이 이웃에 쓰려는 변화를 모은다.
 *
 * 안전하게: 결과가 원래 블록의 파생 블록일 때만 받아들인다 ({@link StateRules#derivedEquivalent}).
 * 그 밖의 변화(다른 블록 생성 등)는 버린다. 실패하는 블록은 그대로 둔다. 큰 채우기는 건너뛴다.
 */
final class WorldReactions {
    private WorldReactions() {}

    /** 이 칸 수를 넘는 채우기는 흉내 내지 않는다 (미리보기마다 돌기 때문). */
    private static final int MAX_CELLS = 2048;

    /** 가짜 월드가 읽고 쓰는 곳. 가짜 월드는 한 번 만들고 이 내용만 바꿔 쓴다. */
    private static final class Sim implements FakeLevel.Source {
        EditorSession s;
        Long2ObjectLinkedOpenHashMap<BlockState> cells;
        final Long2ObjectOpenHashMap<BlockState> writes = new Long2ObjectOpenHashMap<>();
        boolean recording;

        BlockState read(long p) {
            BlockState w = writes.get(p);
            if (w != null) return w;
            BlockState c = cells.get(p);
            if (c != null) return c;
            return s.blockAt(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), false);
        }

        @Override
        public BlockState blockAt(int x, int y, int z) {
            return read(BlockPos.asLong(x, y, z));
        }

        @Nullable
        @Override
        public BlockEntity blockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public boolean setBlock(BlockPos pos, BlockState state) {
            if (!recording) return false;
            writes.put(pos.asLong(), state);
            return true;
        }
    }

    private static final Sim SIM = new Sim();
    @Nullable
    private static FakeLevel level;
    @Nullable
    private static ClientLevel levelFor;

    @Nullable
    private static FakeLevel level(EditorSession s) {
        ClientLevel real = Minecraft.getInstance().level;
        if (real == null) return null;
        if (level == null || levelFor != real) {
            levelFor = real;
            try {
                level = new FakeLevel(SIM, real, real.getMinBuildHeight(), real.getHeight());
            } catch (Throwable t) {
                level = null;
            }
        }
        return level;
    }

    /** cells (칸 → 놓일 상태, 지우기면 공기) 에 반응을 더한다. structureLayer: 구조물 층을 편집 중. */
    static void apply(EditorSession s, Long2ObjectLinkedOpenHashMap<BlockState> cells, boolean structureLayer, boolean erase) {
        if (cells.isEmpty() || cells.size() > MAX_CELLS) return;
        FakeLevel lv = level(s);
        if (lv == null) return;
        Minecraft mc = Minecraft.getInstance();
        StateRules.probe(mc.level);
        Sim sim = SIM;
        sim.s = s;
        sim.cells = cells;
        sim.writes.clear();
        try {
            long[] keys = cells.keySet().toLongArray();
            // 1) 놓는 칸: 실제 자리 기준 설치 상태
            if (!erase) {
                for (long p : keys) {
                    BlockState st = cells.get(p);
                    if (st == null || st.isAir()) continue;
                    BlockState alt = placeInContext(lv, sim, p, st);
                    if (alt != null && alt.getBlock() != st.getBlock() && StateRules.derivedEquivalent(alt, st)) {
                        cells.put(p, copyShared(st, alt));
                    }
                }
            }
            // 2) 설치·제거 반응이 이웃에 쓰는 변화
            sim.recording = true;
            for (long p : keys) {
                BlockPos pos = BlockPos.of(p);
                BlockState old = s.blockAt(pos.getX(), pos.getY(), pos.getZ(), false);
                BlockState now = cells.get(p);
                if (now == null) continue;
                try {
                    if (!old.isAir() && old.getBlock() != now.getBlock()) old.onRemove(lv, pos, now, false);
                    if (!now.isAir() && old.getBlock() != now.getBlock()) now.onPlace(lv, pos, old, false);
                } catch (Throwable ignored) {
                    // 진짜 월드(서버)를 가정한 블록: 반응 없이 둔다
                }
            }
            sim.recording = false;
            for (var e : sim.writes.long2ObjectEntrySet()) {
                long q = e.getLongKey();
                BlockState ns = e.getValue();
                if (!s.model.isInside(q) || ns.isAir()) continue;
                BlockState cur = cells.containsKey(q) ? cells.get(q) : s.blockAt(BlockPos.getX(q), BlockPos.getY(q), BlockPos.getZ(q), false);
                if (cur == null || cur.isAir() || cur == ns) continue;
                if (!StateRules.derivedEquivalent(ns, cur)) continue;   // 파생 블록 사이의 변화만
                if (!cells.containsKey(q) && !sameLayer(s, q, structureLayer)) continue;
                cells.put(q, ns);
            }
        } catch (Throwable ignored) {
        } finally {
            sim.recording = false;
            sim.writes.clear();
            sim.cells = null;
            sim.s = null;
        }
    }

    /** 이웃 칸이 지금 편집 중인 층(지형 / 선택 구조물)의 설계 칸인가. */
    private static boolean sameLayer(EditorSession s, long q, boolean structureLayer) {
        Resolved r = s.model.resolved(q);
        if (r == null || r.isAir()) return false;
        return structureLayer ? (r.owner() != 0 && r.owner() == s.selected) : (r.owner() == 0 && s.model.terrainAt(q) != null);
    }

    /** 실제 자리에서 이 블록의 설치 상태를 다시 묻는다 (그 칸은 비운 채로). */
    @Nullable
    private static BlockState placeInContext(FakeLevel lv, Sim sim, long p, BlockState st) {
        if (!(StateRules.pickItem(st.getBlock()) instanceof BlockItem bi)) return null;
        BlockPos pos = BlockPos.of(p);
        BlockState saved = sim.cells.get(p);
        sim.writes.put(p, Blocks.AIR.defaultBlockState());
        try {
            BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos.below(), false);
            BlockPlaceContext ctx = new BlockPlaceContext(lv, null, InteractionHand.MAIN_HAND, new ItemStack(bi), hit) {
                @Override
                public BlockPos getClickedPos() {
                    return pos;
                }

                @Override
                public boolean canPlace() {
                    return true;
                }

                @Override
                public boolean replacingClickedOnBlock() {
                    return false;
                }
            };
            return bi.getBlock().getStateForPlacement(ctx);
        } catch (Throwable t) {
            return null;
        } finally {
            sim.writes.remove(p);
            if (saved != null) sim.cells.put(p, saved);
        }
    }

    /** alt 에, st 와 이름이 같은 속성의 값(축·방향 등)을 옮긴다. 놓을 때 고른 방향을 지킨다. */
    private static BlockState copyShared(BlockState st, BlockState alt) {
        BlockState out = alt;
        for (Property<?> ap : alt.getProperties()) {
            for (Property<?> sp : st.getProperties()) {
                if (!sp.getName().equals(ap.getName())) continue;
                out = setByName(out, ap, String.valueOf(st.getValue(sp)));
            }
        }
        return out;
    }

    private static <T extends Comparable<T>> BlockState setByName(BlockState s, Property<T> p, String value) {
        return p.getValue(value).map(v -> s.setValue(p, v)).orElse(s);
    }
}
