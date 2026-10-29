package dev.designandbuild.server;

import dev.minedraft.lib.server.SnapshotWriter;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import it.unimi.dsi.fastutil.longs.LongArrayList;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.core.BlueprintModel;
import dev.minedraft.lib.util.Box;
import dev.designandbuild.core.DesignTag;
import dev.designandbuild.core.ModelCodec;
import dev.designandbuild.core.ModelOps;
import dev.designandbuild.core.Requests;
import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.StateRules;
import dev.designandbuild.core.StructureInstance;
import dev.designandbuild.core.TerrainCell;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 서버의 도면 관리자. 원본 데이터를 쥐고, 받은 순서대로 편집을 적용해 열람자 전원에게 뿌린다.
 */
public final class BlueprintManager {
    @Nullable
    private static BlueprintManager instance;

    @Nullable
    public static BlueprintManager get() {
        return instance;
    }

    public static void start(MinecraftServer server) {
        instance = new BlueprintManager(server);
    }

    public static void stop() {
        instance = null;
    }

    public record Session(UUID blueprint, ResourceKey<Level> tableDim, BlockPos tablePos) {}

    private final MinecraftServer server;
    private final Map<UUID, BlueprintStorage> storages = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Set<UUID>> subscribers = new HashMap<>();
    private final Map<UUID, Map<UUID, UndoHistory>> histories = new HashMap<>();
    private final Map<UUID, long[]> progressCache = new HashMap<>();

    private BlueprintManager(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer server() {
        return server;
    }

    // ------------------------------------------------------------------
    // 데이터
    // ------------------------------------------------------------------

    @Nullable
    public BlueprintModel model(UUID id) {
        BlueprintStorage st = storages.get(id);
        if (st == null) {
            try {
                st = server.overworld().getDataStorage().get(BlueprintStorage.factory(), BlueprintStorage.fileName(id));
            } catch (Exception e) {
                DesignAndBuild.LOGGER.error("Could not load blueprint {}", id, e);
                st = null;
            }
            if (st == null) return null;
            storages.put(id, st);
        }
        return st.model();
    }

    public BlueprintModel create(ServerLevel level, BlockPos tablePos, String name) {
        UUID id = UUID.randomUUID();
        ChunkPos base = new ChunkPos(tablePos);
        BlueprintModel m = BlueprintModel.createNew(id, sanitize(name, 48), level.dimension(), base.x, base.z,
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        BlueprintStorage st = new BlueprintStorage(m);
        st.setDirty();
        server.overworld().getDataStorage().set(BlueprintStorage.fileName(id), st);
        storages.put(id, st);
        return m;
    }

    private void markDirty(UUID id) {
        BlueprintStorage st = storages.get(id);
        if (st != null) st.setDirty();
    }

    // ------------------------------------------------------------------
    // 편집창 세션
    // ------------------------------------------------------------------

    @Nullable
    public Session session(ServerPlayer player) {
        return sessions.get(player.getUUID());
    }

    public void openEditor(ServerPlayer player, BlueprintModel m, BlockPos tablePos) {
        Session old = sessions.put(player.getUUID(), new Session(m.id, player.level().dimension(), tablePos.immutable()));
        if (old != null && !old.blueprint().equals(m.id)) broadcastEditors(old.blueprint());
        Net.toPlayer(player, Msg.S_EDITOR_BEGIN, buf -> {
            buf.writeBlockPos(tablePos);
            ModelCodec.write(buf, m);
        });
        // 청크 스냅샷은 틱마다 나눠 보낸다 (최대 100청크를 한 틱에 읽으면 서버가 멈춘다). 다 보내면 준비 완료.
        Deque<long[]> q = snapshotQueue.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        if (!m.id.equals(snapshotTarget.get(player.getUUID()))) q.clear(); // 다른 도면의 남은 전송은 버린다
        q.removeIf(e -> e[0] == 1); // 이전에 열던 편집창의 남은 전송도 버린다
        long[] chunks = m.chunks().toLongArray();
        java.util.Arrays.sort(chunks); // 순서는 상관없지만 같은 영역이면 같은 순서로
        for (long c : chunks) q.add(new long[]{1, c, 0});
        q.add(new long[]{1, 0, 1});
        snapshotTarget.put(player.getUUID(), m.id);
        broadcastEditors(m.id);
    }

    // ------------------------------------------------------------------
    // 스냅샷 분산 전송
    // ------------------------------------------------------------------

    /** 플레이어별 보낼 것: {종류(1=편집창 열기, 2=추가된 청크), 청크, 준비완료 표시}. */
    private final Map<UUID, Deque<long[]>> snapshotQueue = new HashMap<>();
    /** 플레이어별 전송 대상 도면. */
    private final Map<UUID, UUID> snapshotTarget = new HashMap<>();
    /** 틱당 스냅샷 작업 시간 상한. */
    private static final long SNAPSHOT_NANOS_PER_TICK = 12_000_000L;

    public void tick() {
        if (snapshotQueue.isEmpty()) return;
        long deadline = System.nanoTime() + SNAPSHOT_NANOS_PER_TICK;
        boolean progressed = true;
        // 여러 사람이 동시에 열어도 한 사람이 다 차지하지 않게 돌아가며 하나씩
        while (progressed && System.nanoTime() < deadline) {
            progressed = false;
            var it = snapshotQueue.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Deque<long[]> q = e.getValue();
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                UUID bp = snapshotTarget.get(e.getKey());
                Session sess = sessions.get(e.getKey());
                if (p == null || bp == null || sess == null || !sess.blueprint().equals(bp) || q.isEmpty()) {
                    it.remove();
                    snapshotTarget.remove(e.getKey());
                    continue;
                }
                long[] item = q.poll();
                BlueprintModel m = model(bp);
                if (m == null) continue;
                if (item[2] == 1) {
                    Net.toPlayer(p, Msg.S_EDITOR_READY, buf -> buf.writeUUID(bp));
                } else if (m.chunks().contains(item[1])) {
                    ServerLevel level = server.getLevel(m.dimension);
                    if (level == null) level = p.serverLevel();
                    Net.toPlayer(p, Msg.S_SNAPSHOT, SnapshotWriter.encode(level, bp, item[1]));
                }
                progressed = true;
                if (System.nanoTime() >= deadline) break;
            }
        }
    }

    /** 청크가 추가됐다: 그 도면 편집자들에게 나눠 보낸다. */
    private void queueAddedChunks(BlueprintModel m, it.unimi.dsi.fastutil.longs.LongCollection added) {
        for (ServerPlayer p : editorsOf(m.id)) {
            Deque<long[]> q = snapshotQueue.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
            snapshotTarget.putIfAbsent(p.getUUID(), m.id);
            if (!snapshotTarget.get(p.getUUID()).equals(m.id)) continue;
            for (long c : added) q.add(new long[]{2, c, 0});
        }
    }

    public void closeEditor(ServerPlayer player, @Nullable String reasonKey) {
        Session s = sessions.remove(player.getUUID());
        if (s == null) return;
        if (reasonKey != null) Net.toPlayer(player, Msg.S_CLOSE_EDITOR, buf -> buf.writeUtf(reasonKey));
        broadcastEditors(s.blueprint());
    }

    /** 테이블의 도면이 빠지거나 바뀌었거나 테이블이 부서졌다. 그 테이블로 연 편집창을 닫는다. */
    public void onTableChanged(Level level, BlockPos tablePos, @Nullable UUID blueprintNow) {
        List<ServerPlayer> close = new ArrayList<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            if (s.tableDim().equals(level.dimension()) && s.tablePos().equals(tablePos) && !s.blueprint().equals(blueprintNow)) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) close.add(p);
            }
        }
        for (ServerPlayer p : close) closeEditor(p, "designandbuild.msg.table_changed");
    }

    public void onLogout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Session s = sessions.remove(uuid);
        if (s != null) broadcastEditors(s.blueprint());
        for (Set<UUID> set : subscribers.values()) set.remove(uuid);
        histories.remove(uuid);
        snapshotQueue.remove(uuid);
        snapshotTarget.remove(uuid);
    }

    private void broadcastEditors(UUID blueprint) {
        List<ServerPlayer> editors = editorsOf(blueprint);
        byte[] body = Net.encode(buf -> {
            buf.writeUUID(blueprint);
            buf.writeVarInt(editors.size());
            for (ServerPlayer p : editors) {
                buf.writeUUID(p.getUUID());
                buf.writeUtf(p.getGameProfile().getName());
            }
        });
        for (ServerPlayer p : editors) Net.toPlayer(p, Msg.S_EDITORS, body);
    }

    private List<ServerPlayer> editorsOf(UUID blueprint) {
        List<ServerPlayer> list = new ArrayList<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().blueprint().equals(blueprint)) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) list.add(p);
            }
        }
        return list;
    }

    public int editorCount(UUID blueprint) {
        return editorsOf(blueprint).size();
    }

    /** 다른 편집자의 커서를 그대로 전달한다. */
    public void relayCursor(ServerPlayer from, UUID blueprint, byte[] data) {
        Session s = sessions.get(from.getUUID());
        if (s == null || !s.blueprint().equals(blueprint)) return;
        byte[] body = Net.encode(buf -> {
            buf.writeUUID(blueprint);
            buf.writeUUID(from.getUUID());
            buf.writeByteArray(data);
        });
        for (ServerPlayer p : editorsOf(blueprint)) {
            if (p != from) Net.toPlayer(p, Msg.S_CURSOR, body);
        }
    }

    // ------------------------------------------------------------------
    // 투영 구독 (도면을 든 플레이어)
    // ------------------------------------------------------------------

    public void subscribe(ServerPlayer player, UUID blueprint, long knownVersion) {
        BlueprintModel m = model(blueprint);
        if (m == null) return;
        subscribers.computeIfAbsent(blueprint, k -> new HashSet<>()).add(player.getUUID());
        if (knownVersion != m.version) {
            Net.toPlayer(player, Msg.S_MODEL_FULL, buf -> ModelCodec.write(buf, m));
        }
    }

    public void unsubscribe(ServerPlayer player, UUID blueprint) {
        Set<UUID> set = subscribers.get(blueprint);
        if (set != null) set.remove(player.getUUID());
    }

    // ------------------------------------------------------------------
    // 편집
    // ------------------------------------------------------------------

    public void handleEdit(ServerPlayer player, UUID blueprint, int clientSeq, Requests.Req req) {
        Session s = sessions.get(player.getUUID());
        if (s == null || !s.blueprint().equals(blueprint)) {
            reject(player, blueprint, clientSeq, "designandbuild.msg.no_session");
            return;
        }
        BlueprintModel m = model(blueprint);
        if (m == null) {
            reject(player, blueprint, clientSeq, "designandbuild.msg.blueprint_missing");
            return;
        }
        UndoHistory history = histories.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
                .computeIfAbsent(blueprint, k -> new UndoHistory());
        List<ModelOps.Op> ops = new ArrayList<>();
        String error;
        UndoHistory.Entry entry = null;
        LongArrayList addedChunks = new LongArrayList();
        switch (req) {
            case Requests.Undo u -> {
                error = history.undo(m, ops) ? null : "designandbuild.msg.nothing_to_undo";
            }
            case Requests.Redo r -> {
                error = history.redo(m, ops) ? null : "designandbuild.msg.nothing_to_redo";
            }
            case Requests.ChunkChange c -> {
                // 이미 반영된 것은 건너뛴다 (다른 편집자가 먼저 바꿨을 때)
                LongOpenHashSet add = new LongOpenHashSet(), remove = new LongOpenHashSet();
                for (long a : c.add()) if (!m.chunks().contains(a)) add.add(a);
                for (long r : c.remove()) if (m.chunks().contains(r)) remove.add(r);
                add.removeAll(remove);
                error = add.isEmpty() && remove.isEmpty() ? null : m.checkChunkChange(add, remove);
                if (error == null) {
                    for (long r : remove) ops.add(new ModelOps.ChunkRemove(r));
                    for (long a : add) {
                        ops.add(new ModelOps.ChunkAdd(a));
                        addedChunks.add(a);
                    }
                }
            }
            default -> {
                entry = history.newEntry(groupOf(req));
                error = build(m, req, ops, entry);
            }
        }
        if (error != null) {
            reject(player, blueprint, clientSeq, error);
            return;
        }
        if (ops.isEmpty()) {
            reject(player, blueprint, clientSeq, null);
            return;
        }
        // 생성 연산의 "직후 해시"는 적용 뒤에 채운다
        for (ModelOps.Op op : ops) op.apply(m);
        if (entry != null) {
            finishEntry(m, entry);
            history.push(entry);
        }
        m.version++;
        markDirty(m.id);
        broadcast(m, player, clientSeq, ops);
        if (!addedChunks.isEmpty()) queueAddedChunks(m, addedChunks);
    }

    /** 테이블에서 이름을 바꿈 (편집창 없이). */
    public void renameFromTable(ServerPlayer player, BlueprintModel m, String name) {
        String clean = sanitize(name, 48);
        if (clean.isEmpty() || clean.equals(m.name)) return;
        List<ModelOps.Op> ops = List.of(new ModelOps.Rename(clean));
        for (ModelOps.Op op : ops) op.apply(m);
        m.version++;
        markDirty(m.id);
        broadcast(m, player, 0, ops);
    }

    private static int groupOf(Requests.Req req) {
        return switch (req) {
            case Requests.Terrain t -> t.group();
            case Requests.StructCells t -> t.group();
            case Requests.StructCreate t -> t.group();
            default -> 0;
        };
    }

    private void finishEntry(BlueprintModel m, UndoHistory.Entry entry) {
        for (int i = 0; i < entry.changes.size(); i++) {
            UndoHistory.Change c = entry.changes.get(i);
            if (c instanceof UndoHistory.StructCreated sc) {
                StructureInstance s = m.structure(sc.id());
                entry.changes.set(i, new UndoHistory.StructCreated(sc.id(), s == null ? 0 : s.contentHash()));
            } else if (c instanceof UndoHistory.StructReplaced sr) {
                StructureInstance s = m.structure(sr.id());
                entry.changes.set(i, new UndoHistory.StructReplaced(sr.id(), sr.beforePos(), sr.beforeStates(),
                        sr.beforeRot(), sr.beforeMirror(), s == null ? 0 : s.contentHash()));
            }
        }
    }

    /** 요청을 확정 연산으로 바꾼다. 오류면 번역 키를 돌려준다. */
    @Nullable
    private String build(BlueprintModel m, Requests.Req req, List<ModelOps.Op> ops, UndoHistory.Entry entry) {
        switch (req) {
            case Requests.Terrain t -> {
                int n = Math.min(t.pos().length, Math.min(t.states().length, t.tags().length));
                List<Long> pos = new ArrayList<>();
                List<BlockState> states = new ArrayList<>();
                List<DesignTag> tags = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    long p = t.pos()[i];
                    BlockState st = t.states()[i];
                    if (st == null || !m.isInside(p)) continue;
                    Resolved r = m.resolved(p);
                    if (r != null && r.owner() != 0) continue; // 구조물 블록은 그 구조물을 선택해야 바꿀 수 있다
                    DesignTag tag = st.isAir() ? DesignTag.NONE
                            : (t.tags()[i] == DesignTag.PATH ? DesignTag.PATH : DesignTag.FLOOR);
                    TerrainCell before = m.terrainAt(p);
                    TerrainCell after = new TerrainCell(st, tag);
                    if (Objects.equals(before, after)) continue;
                    pos.add(p);
                    states.add(st);
                    tags.add(tag);
                    entry.changes.add(new UndoHistory.TerrainChange(p, before, after));
                }
                if (!pos.isEmpty()) {
                    ops.add(new ModelOps.Terrain(toLongs(pos), states.toArray(new BlockState[0]), tags.toArray(new DesignTag[0])));
                }
                return null;
            }
            case Requests.StructCells t -> {
                boolean create = t.id() == 0;
                StructureInstance s = create ? null : m.structure(t.id());
                if (!create && s == null) return "designandbuild.msg.structure_gone";
                int id = create ? m.nextStructureId : t.id();
                int n = Math.min(t.pos().length, t.states().length);
                List<Long> pos = new ArrayList<>();
                List<BlockState> states = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    long p = t.pos()[i];
                    BlockState st = t.states()[i];
                    if (st != null && !m.isInside(p)) continue;
                    Resolved r = m.resolved(p);
                    if (r != null && r.owner() != 0 && r.owner() != id) continue; // 다른 구조물 칸은 보호
                    BlockState before = s == null ? null : s.get(p);
                    if (before == st) continue;
                    pos.add(p);
                    states.add(st);
                    if (!create) entry.changes.add(new UndoHistory.StructCellChange(id, p, before, st));
                }
                if (pos.isEmpty()) return null;
                if (create) {
                    ops.add(new ModelOps.StructCreate(id, -1, sanitize(t.name(), 64), "", Direction.DOWN, Rotation.NONE,
                            Mirror.NONE, toLongs(pos), states.toArray(new BlockState[0])));
                    entry.changes.add(new UndoHistory.StructCreated(id, 0));
                } else {
                    ops.add(new ModelOps.StructCells(id, toLongs(pos), states.toArray(new BlockState[0])));
                }
                return null;
            }
            case Requests.StructCreate t -> {
                int n = Math.min(t.pos().length, t.states().length);
                if (n == 0) return null;
                for (int i = 0; i < n; i++) {
                    if (!m.isInside(t.pos()[i])) return "designandbuild.msg.outside_area";
                }
                int id = m.nextStructureId;
                ops.add(new ModelOps.StructCreate(id, -1, sanitize(t.name(), 64), sanitize(t.source(), 256),
                        t.mount(), t.rotation(), t.mirror(), t.pos(), t.states()));
                entry.changes.add(new UndoHistory.StructCreated(id, 0));
                return null;
            }
            case Requests.StructMove t -> {
                StructureInstance s = m.structure(t.id());
                if (s == null) return "designandbuild.msg.structure_gone";
                Box b = s.bounds();
                if (b == null) return null;
                int steps = Math.floorMod(t.rotSteps(), 4);
                if (t.dx() == 0 && t.dy() == 0 && t.dz() == 0 && steps == 0 && !t.mirror()) return null;
                long[] pos = new long[s.size()];
                BlockState[] states = new BlockState[pos.length];
                long[] beforePos = new long[pos.length];
                BlockState[] beforeStates = new BlockState[pos.length];
                int i = 0;
                for (Long2ObjectMap.Entry<BlockState> e : s.blocks().long2ObjectEntrySet()) {
                    long p = e.getLongKey();
                    BlockState st = e.getValue();
                    beforePos[i] = p;
                    beforeStates[i] = st;
                    int x = BlockPos.getX(p) - b.minX(), y = BlockPos.getY(p) - b.minY(), z = BlockPos.getZ(p) - b.minZ();
                    int sx = b.sizeX(), sz = b.sizeZ();
                    if (t.mirror()) {
                        x = sx - 1 - x;
                        st = st.mirror(Mirror.FRONT_BACK);
                    }
                    for (int k = 0; k < steps; k++) {
                        int nx = sz - 1 - z;
                        int nz = x;
                        x = nx;
                        z = nz;
                        int tmp = sx;
                        sx = sz;
                        sz = tmp;
                        st = st.rotate(Rotation.CLOCKWISE_90);
                    }
                    int wx = b.minX() + x + t.dx(), wy = b.minY() + y + t.dy(), wz = b.minZ() + z + t.dz();
                    if (!m.isInside(wx, wy, wz)) return "designandbuild.msg.outside_area";
                    pos[i] = BlockPos.asLong(wx, wy, wz);
                    states[i] = st;
                    i++;
                }
                Rotation rot = s.rotation;
                for (int k = 0; k < steps; k++) rot = rot.getRotated(Rotation.CLOCKWISE_90);
                Mirror mirror = t.mirror() ? (s.mirror == Mirror.NONE ? Mirror.FRONT_BACK : Mirror.NONE) : s.mirror;
                ops.add(new ModelOps.StructReplace(s.id, rot, mirror, pos, states));
                entry.changes.add(new UndoHistory.StructReplaced(s.id, beforePos, beforeStates, s.rotation, s.mirror, 0));
                return null;
            }
            case Requests.StructDelete t -> {
                StructureInstance s = m.structure(t.id());
                if (s == null) return null;
                entry.changes.add(new UndoHistory.StructDeleted(UndoHistory.Snapshot.of(m, s)));
                ops.add(new ModelOps.StructDelete(t.id()));
                return null;
            }
            case Requests.StructReorder t -> {
                if (m.structure(t.id()) == null) return null;
                int before = m.orderIndexOf(t.id());
                int after = Math.max(0, Math.min(t.index(), m.structureCount() - 1));
                if (before == after) return null;
                ops.add(new ModelOps.StructReorder(t.id(), after));
                entry.changes.add(new UndoHistory.StructReordered(t.id(), before, after));
                return null;
            }
            case Requests.StructRename t -> {
                StructureInstance s = m.structure(t.id());
                String name = sanitize(t.name(), 64);
                if (s == null || name.isEmpty() || name.equals(s.name)) return null;
                entry.changes.add(new UndoHistory.StructRenamed(t.id(), s.name, name));
                ops.add(new ModelOps.StructRename(t.id(), name));
                return null;
            }
            case Requests.Rename t -> {
                String name = sanitize(t.name(), 48);
                if (name.isEmpty() || name.equals(m.name)) return null;
                entry.changes.add(new UndoHistory.BlueprintRenamed(m.name, name));
                ops.add(new ModelOps.Rename(name));
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private void reject(ServerPlayer player, UUID blueprint, int clientSeq, @Nullable String key) {
        Net.toPlayer(player, Msg.S_REJECTED, buf -> {
            buf.writeUUID(blueprint);
            buf.writeVarInt(clientSeq);
            buf.writeUtf(key == null ? "" : key);
        });
    }

    private void broadcast(BlueprintModel m, ServerPlayer actor, int clientSeq, List<ModelOps.Op> ops) {
        byte[] body = Net.encode(buf -> {
            buf.writeUUID(m.id);
            buf.writeVarLong(m.version);
            buf.writeUUID(actor.getUUID());
            buf.writeVarInt(clientSeq);
            buf.writeVarInt(ops.size());
            for (ModelOps.Op op : ops) op.write(buf);
        });
        Set<UUID> targets = new LinkedHashSet<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().blueprint().equals(m.id)) targets.add(e.getKey());
        }
        Set<UUID> subs = subscribers.get(m.id);
        if (subs != null) targets.addAll(subs);
        targets.add(actor.getUUID());
        for (UUID uuid : targets) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) Net.toPlayer(p, Msg.S_APPLIED, body);
        }
        progressCache.remove(m.id);
    }

    public static void toast(ServerPlayer player, String key) {
        Net.toPlayer(player, Msg.S_TOAST, buf -> buf.writeUtf(key));
    }

    // ------------------------------------------------------------------
    // 테이블 요약용 진척도
    // ------------------------------------------------------------------

    /** 불러온 청크 안의 설계 칸 중 완성된 비율 (0~100). 설계가 없으면 -1. 5초마다 다시 센다. */
    public int progress(BlueprintModel m) {
        long now = server.getTickCount();
        long[] cached = progressCache.get(m.id);
        if (cached != null && cached[0] == m.version && now - cached[1] < 100) return (int) cached[2];
        ServerLevel level = server.getLevel(m.dimension);
        int result = -1;
        if (level != null) {
            long total = 0, done = 0;
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (Long2ObjectMap.Entry<Resolved> e : m.resolvedMap().long2ObjectEntrySet()) {
                long p = e.getLongKey();
                mp.set(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
                if (!level.isLoaded(mp)) continue;
                total++;
                if (StateRules.matches(level.getBlockState(mp), e.getValue().state())) done++;
            }
            result = total == 0 ? -1 : (int) (done * 100 / total);
        }
        progressCache.put(m.id, new long[]{m.version, now, result});
        return result;
    }

    // ------------------------------------------------------------------

    public static String sanitize(String s, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c >= 32 && c != 127 && c != '§') b.append(c);
        }
        return b.toString().trim();
    }

    private static long[] toLongs(List<Long> list) {
        long[] out = new long[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }
}
