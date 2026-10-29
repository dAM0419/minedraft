package dev.designandbuild.server;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.Registration;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.PlaceCheck;
import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.Stages;
import dev.designandbuild.core.StateRules;
import dev.designandbuild.item.BlueprintRef;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Clearable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.EventHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 월드에 실제로 짓는 일: 즉시 반영(권한자, 단계 단위, 마지막 1회 되돌리기)과 건설망치.
 * 큰 작업은 틱마다 나눠 처리한다. 서버 주 스레드에서만 불린다.
 */
public final class BuildService {
    private BuildService() {}

    /** 즉시 반영: 틱당 칸 수. */
    private static final int APPLY_PER_TICK = 4096;
    /** 망치 일괄 설치: 한 번에 최대 칸 수, 틱당 칸 수, 플레이어로부터 최대 거리. */
    private static final int HAMMER_MAX = 512, HAMMER_PER_TICK = 64;
    private static final double HAMMER_RANGE_SQR = 48 * 48;
    /** 즉시 반영 확인 창의 경고 기준 (클라이언트와 같은 값). */
    public static final int LARGE_APPLY = dev.designandbuild.core.Stages.LARGE_APPLY;
    /** 설치 플래그: 클라이언트에 알리고, 이웃 모양 갱신은 끝에 한 번에 (중간에 물리·붕괴가 일어나지 않게). */
    private static final int QUIET = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    // ------------------------------------------------------------------
    // 플레이어가 보는 단계 (B0)
    // ------------------------------------------------------------------

    /** 클라이언트가 지금 투영으로 보는 단계·층. 건설망치 판정에만 쓴다. */
    public record View(UUID blueprint, int kind, int structureId, int layerMode, int layerY, boolean topDown) {}

    private static final Map<UUID, View> VIEWS = new HashMap<>();

    public static void setView(ServerPlayer player, @Nullable View view) {
        if (view == null) VIEWS.remove(player.getUUID());
        else VIEWS.put(player.getUUID(), view);
    }

    @Nullable
    public static View view(ServerPlayer player) {
        return VIEWS.get(player.getUUID());
    }

    public static void onLogout(ServerPlayer player) {
        VIEWS.remove(player.getUUID());
    }

    public static void reset() {
        VIEWS.clear();
        JOBS.clear();
        UNDO.clear();
        RUNNING.clear();
        HOLDS.clear();
    }

    // ------------------------------------------------------------------
    // 작업 큐
    // ------------------------------------------------------------------

    private interface Job {
        /** @return 끝났으면 true */
        boolean tick(MinecraftServer server);

        /** 오류로 중단될 때: 잡아 둔 것(강제 로드 청크, 진행 중 표시)을 푼다. */
        default void abort() {}
    }

    private static final List<Job> JOBS = new ArrayList<>();

    public static void tick(MinecraftServer server) {
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext()) {
            Job j = it.next();
            boolean done;
            try {
                done = j.tick(server);
            } catch (Exception e) {
                DesignAndBuild.LOGGER.error("DesignAndBuild build job failed", e);
                try {
                    j.abort();
                } catch (Exception ignored) {
                }
                done = true;
            }
            if (done) it.remove();
        }
    }

    // ------------------------------------------------------------------
    // 즉시 반영 (B1)
    // ------------------------------------------------------------------

    /** 마지막 즉시 반영 기록 (도면마다 1회, 메모리에만). placed 는 넣은 상태 — 되돌릴 때 그대로인 칸만 되돌린다. */
    private record Undo(UUID blueprint, net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim, int kind,
                        int structureId, long[] pos, BlockState[] prev, CompoundTag[] nbt, BlockState[] placed) {}

    private static final Map<UUID, Undo> UNDO = new HashMap<>();
    /** 즉시 반영·되돌리기가 진행 중인 도면. */
    private static final Map<UUID, UUID> RUNNING = new HashMap<>();

    public static boolean allowed(ServerPlayer player) {
        return player.hasPermissions(2);
    }

    public static void sendState(ServerPlayer player, UUID blueprint) {
        Undo u = UNDO.get(blueprint);
        boolean running = RUNNING.containsKey(blueprint);
        Net.toPlayer(player, Msg.S_INSTANT_STATE, b -> {
            b.writeUUID(blueprint);
            b.writeBoolean(allowed(player));
            b.writeBoolean(running);
            b.writeBoolean(u != null);
            if (u != null) {
                b.writeVarInt(u.kind);
                b.writeVarInt(u.structureId);
            }
        });
    }

    public static void instantApply(ServerPlayer player, UUID blueprint, int kind, int structureId) {
        BlueprintManager manager = BlueprintManager.get();
        if (manager == null) return;
        if (!allowed(player)) {
            BlueprintManager.toast(player, "designandbuild.instant.no_permission");
            return;
        }
        if (RUNNING.containsKey(blueprint)) {
            BlueprintManager.toast(player, "designandbuild.instant.busy");
            return;
        }
        BlueprintModel m = manager.model(blueprint);
        if (m == null) return;
        ServerLevel level = manager.server().getLevel(m.dimension);
        if (level == null) return;
        if (kind == Stages.STRUCTURE && m.structure(structureId) == null) return;

        // 바꿀 칸: 철거는 부숴야 하는 칸 → 공기, 설치 단계는 그 단계에서 아직 안 맞는 칸 → 목표
        LongArrayList pos = new LongArrayList();
        List<BlockState> next = new ArrayList<>();
        LongOpenHashSet chunks = new LongOpenHashSet();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        for (Long2ObjectMap.Entry<Resolved> e : m.resolvedMap().long2ObjectEntrySet()) {
            long p = e.getLongKey();
            Resolved r = e.getValue();
            boolean demolish = kind == Stages.DEMOLISH;
            if (!demolish && !Stages.inBuildStage(m, r, kind, structureId)) continue;
            mp.set(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
            level.getChunk(mp.getX() >> 4, mp.getZ() >> 4); // 불러오기
            BlockState world = level.getBlockState(mp);
            BlockState target = r.state();
            if (demolish) {
                if (!StateRules.needsDemolish(level, world, target)) continue;
                pos.add(p);
                next.add(Blocks.AIR.defaultBlockState());
            } else {
                if (StateRules.matches(world, target)) continue;
                pos.add(p);
                next.add(target);
            }
            chunks.add(ChunkPos.asLong(mp.getX() >> 4, mp.getZ() >> 4));
        }
        if (pos.isEmpty()) {
            BlueprintManager.toast(player, "designandbuild.instant.nothing");
            return;
        }
        UNDO.remove(blueprint); // 새로 반영하면 이전 되돌리기는 사라진다
        RUNNING.put(blueprint, player.getUUID());
        JOBS.add(new ApplyJob(player.getUUID(), m, level, kind, structureId, pos.toLongArray(),
                next.toArray(new BlockState[0]), chunks));
        Net.toPlayer(player, Msg.S_TOAST_N, b -> {
            b.writeUtf("designandbuild.instant.started");
            b.writeVarInt(pos.size());
        });
        sendState(player, blueprint);
    }

    public static void instantUndo(ServerPlayer player, UUID blueprint) {
        if (!allowed(player)) {
            BlueprintManager.toast(player, "designandbuild.instant.no_permission");
            return;
        }
        if (RUNNING.containsKey(blueprint)) {
            BlueprintManager.toast(player, "designandbuild.instant.busy");
            return;
        }
        Undo u = UNDO.remove(blueprint);
        BlueprintManager manager = BlueprintManager.get();
        if (u == null || manager == null) {
            BlueprintManager.toast(player, "designandbuild.instant.no_undo");
            sendState(player, blueprint);
            return;
        }
        ServerLevel level = manager.server().getLevel(u.dim);
        if (level == null) return;
        RUNNING.put(blueprint, player.getUUID());
        JOBS.add(new UndoJob(player.getUUID(), u, level));
        sendState(player, blueprint);
    }

    /** 칸 하나를 조용히 바꾼다. 상자 등 내용물은 쏟지 않도록 미리 비운다(되돌리기용 기록은 호출 쪽이 먼저 떠 둔다). */
    private static void quietSet(ServerLevel level, BlockPos p, BlockState state) {
        BlockEntity be = level.getBlockEntity(p);
        if (be != null) Clearable.tryClear(be);
        level.setBlock(p, state, QUIET);
    }

    /** 넣은 칸들의 연결 모양(울타리·판유리·계단 등)을 이웃 기준으로 다시 맞춘다. */
    private static int fixShapes(ServerLevel level, long[] pos, int from, int budget) {
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int i = from;
        for (; i < pos.length && i < from + budget; i++) {
            mp.set(BlockPos.getX(pos[i]), BlockPos.getY(pos[i]), BlockPos.getZ(pos[i]));
            BlockState cur = level.getBlockState(mp);
            if (cur.isAir()) continue;
            BlockState fixed = Block.updateFromNeighbourShapes(cur, level, mp);
            if (fixed != cur) level.setBlock(mp, fixed, QUIET);
        }
        return i;
    }

    private static final List<ChunkHold> HOLDS = new ArrayList<>();

    /** 서버가 멈출 때: 진행 중 작업이 잡아 둔 강제 로드를 풀어 저장 파일에 남지 않게. */
    public static void shutdown() {
        for (ChunkHold h : new ArrayList<>(HOLDS)) h.release();
        HOLDS.clear();
        JOBS.clear();
        RUNNING.clear();
    }

    private static final class ChunkHold {
        private final ServerLevel level;
        private final LongArrayList forced = new LongArrayList();

        ChunkHold(ServerLevel level, LongOpenHashSet chunks) {
            this.level = level;
            HOLDS.add(this);
            for (long c : chunks) {
                int cx = ChunkPos.getX(c), cz = ChunkPos.getZ(c);
                if (level.getForcedChunks().contains(c)) continue; // 원래 고정된 청크는 건드리지 않는다
                level.setChunkForced(cx, cz, true);
                forced.add(c);
            }
        }

        void release() {
            for (long c : forced) level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), false);
            forced.clear();
            HOLDS.remove(this);
        }
    }

    private static final class ApplyJob implements Job {
        private final UUID player;
        private final BlueprintModel model;
        private final ServerLevel level;
        private final int kind, structureId;
        private final long[] pos;
        private final BlockState[] next;
        private final BlockState[] prev;
        private final CompoundTag[] nbt;
        private final ChunkHold hold;
        private int index, shapeIndex;

        ApplyJob(UUID player, BlueprintModel model, ServerLevel level, int kind, int structureId, long[] pos,
                 BlockState[] next, LongOpenHashSet chunks) {
            this.player = player;
            this.model = model;
            this.level = level;
            this.kind = kind;
            this.structureId = structureId;
            this.pos = pos;
            this.next = next;
            this.prev = new BlockState[pos.length];
            this.nbt = new CompoundTag[pos.length];
            this.hold = new ChunkHold(level, chunks);
        }

        @Override
        public void abort() {
            hold.release();
            RUNNING.remove(model.id);
        }

        @Override
        public boolean tick(MinecraftServer server) {
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            if (index < pos.length) {
                int end = Math.min(pos.length, index + APPLY_PER_TICK);
                for (; index < end; index++) {
                    mp.set(BlockPos.getX(pos[index]), BlockPos.getY(pos[index]), BlockPos.getZ(pos[index]));
                    prev[index] = level.getBlockState(mp);
                    BlockEntity be = level.getBlockEntity(mp);
                    if (be != null) nbt[index] = be.saveWithFullMetadata(level.registryAccess());
                    quietSet(level, mp.immutable(), next[index]);
                }
                return false;
            }
            if (shapeIndex < pos.length) {
                shapeIndex = fixShapes(level, pos, shapeIndex, APPLY_PER_TICK);
                return false;
            }
            hold.release();
            RUNNING.remove(model.id);
            BlockState[] placed = new BlockState[pos.length];
            for (int i = 0; i < pos.length; i++) {
                mp.set(BlockPos.getX(pos[i]), BlockPos.getY(pos[i]), BlockPos.getZ(pos[i]));
                placed[i] = level.getBlockState(mp);
            }
            UNDO.put(model.id, new Undo(model.id, level.dimension(), kind, structureId, pos, prev, nbt, placed));
            ServerPlayer p = server.getPlayerList().getPlayer(player);
            if (p != null) {
                Net.toPlayer(p, Msg.S_TOAST_N, b -> {
                    b.writeUtf("designandbuild.instant.done");
                    b.writeVarInt(pos.length);
                });
                sendState(p, model.id);
            }
            return true;
        }
    }

    private static final class UndoJob implements Job {
        private final UUID player;
        private final Undo undo;
        private final ServerLevel level;
        private final ChunkHold hold;
        private final LongArrayList restored = new LongArrayList();
        private int index, shapeIndex, skipped;
        private long[] restoredArr;

        UndoJob(UUID player, Undo undo, ServerLevel level) {
            this.player = player;
            this.undo = undo;
            this.level = level;
            LongOpenHashSet chunks = new LongOpenHashSet();
            for (long p : undo.pos) chunks.add(ChunkPos.asLong(BlockPos.getX(p) >> 4, BlockPos.getZ(p) >> 4));
            this.hold = new ChunkHold(level, chunks);
        }

        @Override
        public void abort() {
            hold.release();
            RUNNING.remove(undo.blueprint);
        }

        @Override
        public boolean tick(MinecraftServer server) {
            if (index < undo.pos.length) {
                int end = Math.min(undo.pos.length, index + APPLY_PER_TICK);
                for (; index < end; index++) {
                    BlockPos p = BlockPos.of(undo.pos[index]);
                    BlockState cur = level.getBlockState(p);
                    // 반영 뒤 누가 바꾼 칸은 건드리지 않는다
                    if (cur.getBlock() != undo.placed[index].getBlock()) {
                        skipped++;
                        continue;
                    }
                    quietSet(level, p, undo.prev[index]);
                    CompoundTag tag = undo.nbt[index];
                    if (tag != null) {
                        BlockEntity be = level.getBlockEntity(p);
                        if (be != null) {
                            be.loadWithComponents(tag, level.registryAccess());
                            be.setChanged();
                        }
                    }
                    restored.add(undo.pos[index]);
                }
                return false;
            }
            if (restoredArr == null) restoredArr = restored.toLongArray();
            if (shapeIndex < restoredArr.length) {
                shapeIndex = fixShapes(level, restoredArr, shapeIndex, APPLY_PER_TICK);
                return false;
            }
            hold.release();
            RUNNING.remove(undo.blueprint);
            ServerPlayer p = server.getPlayerList().getPlayer(player);
            if (p != null) {
                int n = restoredArr.length;
                Net.toPlayer(p, Msg.S_TOAST_N, b -> {
                    b.writeUtf(skipped > 0 ? "designandbuild.instant.undone_partial" : "designandbuild.instant.undone");
                    b.writeVarInt(skipped > 0 ? skipped : n);
                });
                sendState(p, undo.blueprint);
            }
            return true;
        }
    }

    // ------------------------------------------------------------------
    // 건설망치 (B2)
    // ------------------------------------------------------------------

    /** 망치로 놓을 수 있는가 (거리·재료·보호 구역 빼고). 못 놓으면 그 이유. */
    private static PlaceCheck.Result check(BlueprintModel m, View v, ServerLevel level, BlockPos p) {
        return PlaceCheck.check(m, level, p, v.kind(), v.structureId(), v.layerMode(), v.layerY(), v.topDown());
    }

    /** 이어진 칸 찾기용: 설계·단계·층·자리만 (받침은 놓을 때 본다). */
    private static boolean candidate(BlueprintModel m, View v, ServerLevel level, BlockPos p) {
        PlaceCheck.Result r = PlaceCheck.design(m, p, v.kind(), v.structureId(), v.layerMode(), v.layerY(), v.topDown());
        return r.ok() && PlaceCheck.spot(level, p, r.target()).ok();
    }

    private static void sendFail(ServerPlayer player, PlaceCheck.Result r) {
        Net.toPlayer(player, Msg.S_HAMMER_FAIL, r::write);
    }

    /** 망치를 쓸 수 있는 상태인가: 주손 망치, 보조손 도면, 그 도면을 투영으로 보고 있음. */
    @Nullable
    private static BlueprintModel hammerContext(ServerPlayer player, @Nullable View v) {
        if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(Registration.HAMMER.get())) return null;
        ItemStack off = player.getItemInHand(InteractionHand.OFF_HAND);
        BlueprintRef ref = off.is(Registration.BLUEPRINT.get()) ? off.get(Registration.BLUEPRINT_REF.get()) : null;
        if (ref == null) {
            BlueprintManager.toast(player, "designandbuild.hammer.need_offhand");
            return null;
        }
        if (v == null || !v.blueprint().equals(ref.id())) {
            BlueprintManager.toast(player, "designandbuild.hammer.not_viewing");
            return null;
        }
        if (v.kind() <= Stages.DEMOLISH) {
            BlueprintManager.toast(player, "designandbuild.hammer.demolish_stage");
            return null;
        }
        BlueprintManager manager = BlueprintManager.get();
        BlueprintModel m = manager == null ? null : manager.model(ref.id());
        if (m == null || !m.dimension.equals(player.level().dimension())) return null;
        return m;
    }

    public static void hammer(ServerPlayer player, BlockPos clicked, boolean bulk) {
        View v = view(player);
        BlueprintModel m = hammerContext(player, v);
        if (m == null) return;
        ServerLevel level = player.serverLevel();
        if (!player.canInteractWithBlock(clicked, 1.0)) {
            sendFail(player, PlaceCheck.of(PlaceCheck.TOO_FAR, Blocks.AIR.defaultBlockState()));
            return;
        }
        BlockPos start = primaryOf(m, clicked);
        if (!start.equals(clicked) && !candidate(m, v, level, start) && candidate(m, v, level, clicked)) start = clicked;
        if (!candidate(m, v, level, start)) {
            sendFail(player, check(m, v, level, start));
            return;
        }
        if (!bulk) {
            Report rep = new Report();
            PlaceCheck.Result r = placeOne(player, m, v, level, start, rep);
            if (!r.ok()) rep.fail(r);
            rep.send(player, true);
            return;
        }
        // 맞닿아 이어진 미설치 투영 칸 (상하좌우앞뒤, 이미 지은 블록을 건너뛰지 않음)
        LongOpenHashSet seen = new LongOpenHashSet();
        LongArrayList found = new LongArrayList();
        LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
        queue.enqueue(start.asLong());
        seen.add(start.asLong());
        while (!queue.isEmpty() && found.size() < HAMMER_MAX) {
            long c = queue.dequeueLong();
            BlockPos p = BlockPos.of(c);
            if (!candidate(m, v, level, p)) continue;
            if (player.distanceToSqr(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) > HAMMER_RANGE_SQR) continue;
            found.add(c);
            for (Direction d : Direction.values()) {
                long n = p.relative(d).asLong();
                if (seen.add(n)) queue.enqueue(n);
            }
        }
        long[] cells = found.toLongArray();
        // 아래층부터, 같은 층은 가까운 것부터
        BlockPos origin = start;
        Long[] boxed = new Long[cells.length];
        for (int i = 0; i < cells.length; i++) boxed[i] = cells[i];
        // 층 = 설치하는 층 (매다는 블록은 받침 층). 같은 층에서는 받침이 먼저 필요한 블록(지금은 못 버티는 것)을 뒤로
        it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap layerOf = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        it.unimi.dsi.fastutil.longs.LongOpenHashSet later = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (long c : cells) {
            Resolved rc = m.resolved(c);
            BlockState t = rc == null ? Blocks.AIR.defaultBlockState() : rc.state();
            BlockPos cp = BlockPos.of(c);
            layerOf.put(c, Stages.buildLayerY(t, cp.getY()));
            if (Stages.hangsFromAbove(t) || !t.canSurvive(level, cp)) later.add(c);
        }
        java.util.Arrays.sort(boxed, (a, b) -> {
            int ya = layerOf.get((long) a), yb = layerOf.get((long) b);
            if (ya != yb) return Integer.compare(ya, yb);
            boolean la = later.contains((long) a), lb = later.contains((long) b);
            if (la != lb) return la ? 1 : -1;
            return Double.compare(BlockPos.of(a).distSqr(origin), BlockPos.of(b).distSqr(origin));
        });
        for (int i = 0; i < cells.length; i++) cells[i] = boxed[i];
        JOBS.add(new HammerJob(player.getUUID(), m, level, cells));
    }

    /** 2칸 블록의 위쪽·머리를 눌렀으면 아이템을 쓰는 쪽(아래·발)을 기준으로. */
    private static BlockPos primaryOf(BlueprintModel m, BlockPos p) {
        Resolved r = m.resolved(p.asLong());
        if (r == null || r.isAir() || Stages.isPrimaryPart(r.state())) return p;
        BlockPos q = Stages.partner(r.state(), p);
        if (q == null) return p;
        Resolved rq = m.resolved(q.asLong());
        return rq != null && !rq.isAir() && Stages.isPrimaryPart(rq.state()) ? q : p;
    }

    /** 설치 결과 집계: 놓은 칸 수, 모자란 재료, 못 놓은 이유별 칸 수. */
    private static final class Report {
        int placed;
        @Nullable
        PlaceCheck.Result first;
        final int[] fails = new int[PlaceCheck.COUNT];
        final Object2IntLinkedOpenHashMap<Item> missing = new Object2IntLinkedOpenHashMap<>();

        void fail(PlaceCheck.Result r) {
            if (first == null) first = r;
            fails[r.code()]++;
        }

        void send(ServerPlayer player, boolean single) {
            if (single && placed == 0 && missing.isEmpty() && first != null) {
                sendFail(player, first);
                return;
            }
            Net.toPlayer(player, Msg.S_HAMMER_RESULT, b -> {
                b.writeVarInt(placed);
                b.writeVarInt(Math.min(missing.size(), 6));
                int k = 0;
                for (var e : missing.object2IntEntrySet()) {
                    if (k++ >= 6) break;
                    b.writeUtf(BuiltInRegistries.ITEM.getKey(e.getKey()).toString());
                    b.writeVarInt(e.getIntValue());
                }
                // 이유별 칸 수 (재료 부족은 위 목록, 이미 완료·짝 칸 차례는 세지 않는다)
                int n = 0;
                for (int c = 0; c < fails.length; c++) if (counted(c) && fails[c] > 0) n++;
                b.writeVarInt(n);
                for (int c = 0; c < fails.length; c++) {
                    if (!counted(c) || fails[c] == 0) continue;
                    b.writeVarInt(c);
                    b.writeVarInt(fails[c]);
                }
            });
        }

        private static boolean counted(int code) {
            return code != PlaceCheck.OK && code != PlaceCheck.MISSING && code != PlaceCheck.DONE && code != PlaceCheck.LOWER_FIRST;
        }
    }

    /** 칸 하나(와 2칸 블록의 짝)를 놓는다. 놓았으면 OK, 아니면 이유 (재료 부족은 rep.missing 에도 적는다). */
    private static PlaceCheck.Result placeOne(ServerPlayer player, BlueprintModel m, View v, ServerLevel level, BlockPos p, Report rep) {
        PlaceCheck.Result c = check(m, v, level, p);
        if (!c.ok()) return c;
        BlockState state = c.target();
        boolean primary = Stages.isPrimaryPart(state);
        // 재료 (2칸 블록의 위쪽·머리는 짝을 놓을 때 이미 냈다)
        Object2IntLinkedOpenHashMap<Item> cost = new Object2IntLinkedOpenHashMap<>();
        if (primary) {
            StateRules.requirement(state, (item, n) -> {
                if (item instanceof BucketItem || item == Items.BUCKET) return; // 물에 잠김은 그 자리 물을 따른다
                cost.addTo(item, n);
            });
        }
        boolean free = player.getAbilities().instabuild;
        if (!free) {
            boolean short_ = false;
            for (var e : cost.object2IntEntrySet()) {
                int have = player.getInventory().countItem(e.getKey());
                if (have < e.getIntValue()) {
                    rep.missing.addTo(e.getKey(), e.getIntValue() - have);
                    short_ = true;
                }
            }
            if (short_) return PlaceCheck.of(PlaceCheck.MISSING, state);
        }
        if (!level.mayInteract(player, p)) return PlaceCheck.of(PlaceCheck.PROTECTED, state);
        // 2칸 블록(문·침대·큰 꽃)은 짝 칸이 아직 없을 때 이웃 모양 갱신을 하면 공기로 바뀌어 버린다.
        // 바닐라 아이템처럼 짝 칸을 함께 놓으므로 이 갱신은 건너뛴다 (받침은 판정에서 이미 봤다).
        BlockPos partnerPos = primary ? Stages.partner(state, p) : null;
        if (partnerPos == null) {
            state = Block.updateFromNeighbourShapes(state, level, p);
            if (state.isAir()) return PlaceCheck.of(PlaceCheck.FAILED, c.target());
        }
        BlockSnapshot snap = BlockSnapshot.create(level.dimension(), level, p);
        if (!level.setBlock(p, state, Block.UPDATE_ALL)) return PlaceCheck.of(PlaceCheck.FAILED, state);
        if (EventHooks.onBlockPlace(player, snap, Direction.UP)) {
            snap.restore(Block.UPDATE_ALL);
            return PlaceCheck.of(PlaceCheck.PROTECTED, state);
        }
        // 짝 칸
        BlockPos q = partnerPos;
        if (q != null) {
            Resolved rq = m.resolved(q.asLong());
            BlockState wq = level.getBlockState(q);
            if (rq != null && !rq.isAir() && rq.state().is(state.getBlock()) && (wq.isAir() || wq.canBeReplaced())) {
                level.setBlock(q, Block.updateFromNeighbourShapes(rq.state(), level, q), Block.UPDATE_ALL);
            }
        }
        if (!free) {
            for (var e : cost.object2IntEntrySet()) {
                Item item = e.getKey();
                player.getInventory().clearOrCountMatchingItems(st -> st.is(item), e.getIntValue(),
                        player.inventoryMenu.getCraftSlots());
            }
        }
        SoundType sound = state.getSoundType(level, p, player);
        level.playSound(null, p, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F,
                sound.getPitch() * 0.8F);
        rep.placed++;
        return c;
    }

    private static final class HammerJob implements Job {
        private final UUID player;
        private final BlueprintModel model;
        private final ServerLevel level;
        private final long[] cells;
        private final Report rep = new Report();
        private int index;
        /** 받침이 없어 못 놓은 칸은 끝에 한 번 더 시도한다 (위층 받침이 나중에 놓이는 경우). */
        private final LongArrayList retry = new LongArrayList();
        private boolean retrying;

        HammerJob(UUID player, BlueprintModel model, ServerLevel level, long[] cells) {
            this.player = player;
            this.model = model;
            this.level = level;
            this.cells = cells;
        }

        @Override
        public boolean tick(MinecraftServer server) {
            ServerPlayer p = server.getPlayerList().getPlayer(player);
            if (p == null) return true;
            View v = view(p);
            if (v == null || !v.blueprint().equals(model.id)) {
                rep.send(p, false);
                return true;
            }
            long[] list = retrying ? retry.toLongArray() : cells;
            int end = Math.min(list.length, index + HAMMER_PER_TICK);
            for (; index < end; index++) {
                BlockPos pos = BlockPos.of(list[index]);
                PlaceCheck.Result r = placeOne(p, model, v, level, pos, rep);
                if (r.ok()) continue;
                if (r.supportIssue() && !retrying) {
                    retry.add(list[index]); // 받침이 나중에 놓일 수 있다: 끝에 한 번 더
                } else {
                    rep.fail(r);
                }
            }
            if (index < list.length) return false;
            if (!retrying && !retry.isEmpty()) {
                retrying = true;
                index = 0;
                return false;
            }
            rep.send(p, false);
            return true;
        }
    }
}
