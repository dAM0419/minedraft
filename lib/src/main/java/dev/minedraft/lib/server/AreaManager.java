package dev.minedraft.lib.server;

import dev.minedraft.lib.MinedraftLib;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaCodec;
import dev.minedraft.lib.area.AreaOp;
import dev.minedraft.lib.area.AreaOps;
import dev.minedraft.lib.area.AreaType;
import dev.minedraft.lib.edit.EditContext;
import dev.minedraft.lib.edit.EditRequest;
import dev.minedraft.lib.edit.EditRequests;
import dev.minedraft.lib.edit.UndoHistory;
import dev.minedraft.lib.net.Channel;
import dev.minedraft.lib.net.LibNet;
import it.unimi.dsi.fastutil.longs.LongCollection;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 서버의 영역 관리자. 원본 데이터를 쥐고, 받은 순서대로 편집을 적용해 열람자 전원에게 뿌린다.
 * 편집창 세션(누가 어느 영역을 어디서 열었나), 투영 구독, 월드 스냅샷 분산 전송, 되돌리기 기록, 저장을 맡는다.
 */
public final class AreaManager {
    @Nullable
    private static AreaManager instance;

    @Nullable
    public static AreaManager get() {
        return instance;
    }

    static void start(MinecraftServer server) {
        instance = new AreaManager(server);
    }

    static void stop() {
        instance = null;
    }

    /**
     * 편집창 하나. anchor 는 편집창을 연 곳(도면 테이블, 광장 블록 …). 그 블록이 바뀌면 {@link #closeEditorsAt} 로 닫는다.
     */
    public record Session(UUID area, ResourceKey<Level> anchorDim, BlockPos anchor) {}

    private final MinecraftServer server;
    private final Map<UUID, AreaStorage> storages = new HashMap<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<UUID, Set<UUID>> subscribers = new HashMap<>();
    private final Map<UUID, Map<UUID, UndoHistory>> histories = new HashMap<>();
    private final List<Consumer<Area>> appliedListeners = new ArrayList<>();

    private AreaManager(MinecraftServer server) {
        this.server = server;
    }

    public MinecraftServer server() {
        return server;
    }

    /** 변경이 적용될 때마다 (진척도 캐시 무효화 등). */
    public void onApplied(Consumer<Area> listener) {
        appliedListeners.add(listener);
    }

    // ------------------------------------------------------------------
    // 데이터
    // ------------------------------------------------------------------

    @Nullable
    public Area area(UUID id) {
        AreaStorage st = storages.get(id);
        if (st == null) {
            try {
                st = server.overworld().getDataStorage().get(AreaStorage.factory(), AreaStorage.fileName(id));
            } catch (Exception e) {
                MinedraftLib.LOGGER.error("Could not load area {}", id, e);
                st = null;
            }
            if (st == null) return null;
            storages.put(id, st);
        }
        return st.area();
    }

    /** 새 영역: 기준 위치의 청크를 가운데로, 종류가 정한 처음 크기. */
    public Area create(AreaType type, ServerLevel level, BlockPos basePos, String name) {
        UUID id = UUID.randomUUID();
        ChunkPos base = new ChunkPos(basePos);
        Area a = Area.createNew(id, type, EditRequests.sanitize(name, 48), level.dimension(), base.x, base.z,
                level.getMinBuildHeight(), level.getMaxBuildHeight());
        AreaStorage st = new AreaStorage(a);
        st.setDirty();
        server.overworld().getDataStorage().set(AreaStorage.fileName(id), st);
        storages.put(id, st);
        return a;
    }

    public void markDirty(UUID id) {
        AreaStorage st = storages.get(id);
        if (st != null) st.setDirty();
    }

    // ------------------------------------------------------------------
    // 편집창 세션
    // ------------------------------------------------------------------

    @Nullable
    public Session session(ServerPlayer player) {
        return sessions.get(player.getUUID());
    }

    /** 편집창을 연다: 영역 사본을 보내고, 월드 스냅샷은 틱마다 나눠 보낸다. */
    public void openEditor(ServerPlayer player, Area a, BlockPos anchor) {
        Session old = sessions.put(player.getUUID(), new Session(a.id, player.level().dimension(), anchor.immutable()));
        if (old != null && !old.area().equals(a.id)) broadcastEditors(old.area());
        LibNet.CHANNEL.toPlayer(player, LibNet.S_EDITOR_BEGIN, buf -> {
            buf.writeBlockPos(anchor);
            AreaCodec.write(buf, a);
        });
        // 청크 스냅샷은 틱마다 나눠 보낸다 (최대 100청크를 한 틱에 읽으면 서버가 멈춘다). 다 보내면 준비 완료.
        Deque<long[]> q = snapshotQueue.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        if (!a.id.equals(snapshotTarget.get(player.getUUID()))) q.clear(); // 다른 영역의 남은 전송은 버린다
        q.removeIf(e -> e[0] == 1); // 이전에 열던 편집창의 남은 전송도 버린다
        long[] chunks = a.chunks().toLongArray();
        java.util.Arrays.sort(chunks);
        for (long c : chunks) q.add(new long[]{1, c, 0});
        q.add(new long[]{1, 0, 1});
        snapshotTarget.put(player.getUUID(), a.id);
        broadcastEditors(a.id);
    }

    public void closeEditor(ServerPlayer player, @Nullable String reasonKey) {
        Session s = sessions.remove(player.getUUID());
        if (s == null) return;
        if (reasonKey != null) LibNet.CHANNEL.toPlayer(player, LibNet.S_CLOSE_EDITOR, buf -> buf.writeUtf(reasonKey));
        broadcastEditors(s.area());
    }

    /** 편집창을 연 블록이 바뀌었다 (부서짐, 다른 영역으로 바뀜). 그 블록으로 연 편집창 중 영역이 다른 것을 닫는다. */
    public void closeEditorsAt(Level level, BlockPos anchor, @Nullable UUID areaNow, String reasonKey) {
        List<ServerPlayer> close = new ArrayList<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            if (s.anchorDim().equals(level.dimension()) && s.anchor().equals(anchor) && !s.area().equals(areaNow)) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) close.add(p);
            }
        }
        for (ServerPlayer p : close) closeEditor(p, reasonKey);
    }

    void onLogout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Session s = sessions.remove(uuid);
        if (s != null) broadcastEditors(s.area());
        for (Set<UUID> set : subscribers.values()) set.remove(uuid);
        histories.remove(uuid);
        snapshotQueue.remove(uuid);
        snapshotTarget.remove(uuid);
    }

    private void broadcastEditors(UUID area) {
        List<ServerPlayer> editors = editorsOf(area);
        byte[] body = Channel.encode(buf -> {
            buf.writeUUID(area);
            buf.writeVarInt(editors.size());
            for (ServerPlayer p : editors) {
                buf.writeUUID(p.getUUID());
                buf.writeUtf(p.getGameProfile().getName());
            }
        });
        for (ServerPlayer p : editors) LibNet.CHANNEL.toPlayer(p, LibNet.S_EDITORS, body);
    }

    private List<ServerPlayer> editorsOf(UUID area) {
        List<ServerPlayer> list = new ArrayList<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().area().equals(area)) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) list.add(p);
            }
        }
        return list;
    }

    public int editorCount(UUID area) {
        return editorsOf(area).size();
    }

    /** 다른 편집자의 커서를 그대로 전달한다. */
    void relayCursor(ServerPlayer from, UUID area, byte[] data) {
        Session s = sessions.get(from.getUUID());
        if (s == null || !s.area().equals(area)) return;
        byte[] body = Channel.encode(buf -> {
            buf.writeUUID(area);
            buf.writeUUID(from.getUUID());
            buf.writeByteArray(data);
        });
        for (ServerPlayer p : editorsOf(area)) {
            if (p != from) LibNet.CHANNEL.toPlayer(p, LibNet.S_CURSOR, body);
        }
    }

    // ------------------------------------------------------------------
    // 스냅샷 분산 전송
    // ------------------------------------------------------------------

    /** 플레이어별 보낼 것: {종류(1=편집창 열기, 2=추가된 청크), 청크, 준비완료 표시}. */
    private final Map<UUID, Deque<long[]>> snapshotQueue = new HashMap<>();
    /** 플레이어별 전송 대상 영역. */
    private final Map<UUID, UUID> snapshotTarget = new HashMap<>();
    /** 틱당 스냅샷 작업 시간 상한. */
    private static final long SNAPSHOT_NANOS_PER_TICK = 12_000_000L;

    void tick() {
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
                UUID id = snapshotTarget.get(e.getKey());
                Session sess = sessions.get(e.getKey());
                if (p == null || id == null || sess == null || !sess.area().equals(id) || q.isEmpty()) {
                    it.remove();
                    snapshotTarget.remove(e.getKey());
                    continue;
                }
                long[] item = q.poll();
                Area a = area(id);
                if (a == null) continue;
                if (item[2] == 1) {
                    LibNet.CHANNEL.toPlayer(p, LibNet.S_EDITOR_READY, buf -> buf.writeUUID(id));
                } else if (a.chunks().contains(item[1])) {
                    ServerLevel level = server.getLevel(a.dimension);
                    if (level == null) level = p.serverLevel();
                    LibNet.CHANNEL.toPlayer(p, LibNet.S_SNAPSHOT, SnapshotWriter.encode(level, id, item[1]));
                }
                progressed = true;
                if (System.nanoTime() >= deadline) break;
            }
        }
    }

    /** 청크가 추가됐다: 그 영역 편집자들에게 나눠 보낸다. */
    private void queueAddedChunks(Area a, LongCollection added) {
        for (ServerPlayer p : editorsOf(a.id)) {
            Deque<long[]> q = snapshotQueue.computeIfAbsent(p.getUUID(), k -> new ArrayDeque<>());
            snapshotTarget.putIfAbsent(p.getUUID(), a.id);
            if (!snapshotTarget.get(p.getUUID()).equals(a.id)) continue;
            for (long c : added) q.add(new long[]{2, c, 0});
        }
    }

    // ------------------------------------------------------------------
    // 구독 (편집창 없이 변경을 받음: 투영 등)
    // ------------------------------------------------------------------

    void subscribe(ServerPlayer player, UUID id, long knownVersion) {
        Area a = area(id);
        if (a == null) return;
        subscribers.computeIfAbsent(id, k -> new HashSet<>()).add(player.getUUID());
        if (knownVersion != a.version) {
            LibNet.CHANNEL.toPlayer(player, LibNet.S_AREA_FULL, buf -> AreaCodec.write(buf, a));
        }
    }

    void unsubscribe(ServerPlayer player, UUID id) {
        Set<UUID> set = subscribers.get(id);
        if (set != null) set.remove(player.getUUID());
    }

    // ------------------------------------------------------------------
    // 편집
    // ------------------------------------------------------------------

    void handleEdit(ServerPlayer player, UUID id, int clientSeq, EditRequest req) {
        Session s = sessions.get(player.getUUID());
        if (s == null || !s.area().equals(id)) {
            reject(player, id, clientSeq, "minedraftlib.msg.no_session");
            return;
        }
        Area a = area(id);
        if (a == null) {
            reject(player, id, clientSeq, "minedraftlib.msg.area_missing");
            return;
        }
        UndoHistory history = histories.computeIfAbsent(player.getUUID(), k -> new HashMap<>())
                .computeIfAbsent(id, k -> new UndoHistory());
        EditContext ctx = new EditContext(a, player, history);
        String error = EditRequests.handle(ctx, req);
        if (error != null) {
            reject(player, id, clientSeq, error);
            return;
        }
        if (ctx.ops.isEmpty()) {
            reject(player, id, clientSeq, null);
            return;
        }
        EditRequests.applyAll(a, ctx.ops);
        history.push(a, req.group(), ctx.undo);
        commit(a, player, clientSeq, ctx.ops);
        if (!ctx.addedChunks.isEmpty()) queueAddedChunks(a, ctx.addedChunks);
    }

    /**
     * 서버가 직접 만든 변경 (편집창 밖: 테이블에서 이름 바꾸기 등). 되돌리기 기록에는 남지 않는다.
     * actor 는 알림을 받을 사람 (없으면 null).
     */
    public void apply(Area a, @Nullable ServerPlayer actor, List<AreaOp> ops) {
        if (ops.isEmpty()) return;
        EditRequests.applyAll(a, ops);
        commit(a, actor, 0, ops);
    }

    /** 이름 바꾸기 (편집창 밖). */
    public void rename(Area a, @Nullable ServerPlayer actor, String name) {
        String clean = EditRequests.sanitize(name, 48);
        if (clean.isEmpty() || clean.equals(a.name())) return;
        apply(a, actor, List.of(new AreaOps.Rename(clean)));
    }

    private void commit(Area a, @Nullable ServerPlayer actor, int clientSeq, List<AreaOp> ops) {
        a.version++;
        markDirty(a.id);
        broadcast(a, actor, clientSeq, ops);
        for (Consumer<Area> l : appliedListeners) l.accept(a);
    }

    private void reject(ServerPlayer player, UUID id, int clientSeq, @Nullable String key) {
        LibNet.CHANNEL.toPlayer(player, LibNet.S_REJECTED, buf -> {
            buf.writeUUID(id);
            buf.writeVarInt(clientSeq);
            buf.writeUtf(key == null ? "" : key);
        });
    }

    private void broadcast(Area a, @Nullable ServerPlayer actor, int clientSeq, List<AreaOp> ops) {
        UUID actorId = actor == null ? new UUID(0, 0) : actor.getUUID();
        byte[] body = Channel.encode(buf -> {
            buf.writeUUID(a.id);
            buf.writeVarLong(a.version);
            buf.writeUUID(actorId);
            buf.writeVarInt(clientSeq);
            buf.writeVarInt(ops.size());
            for (AreaOp op : ops) AreaOps.write(buf, op);
        });
        Set<UUID> targets = new LinkedHashSet<>();
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            if (e.getValue().area().equals(a.id)) targets.add(e.getKey());
        }
        Set<UUID> subs = subscribers.get(a.id);
        if (subs != null) targets.addAll(subs);
        if (actor != null) targets.add(actor.getUUID());
        for (UUID uuid : targets) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) LibNet.CHANNEL.toPlayer(p, LibNet.S_APPLIED, body);
        }
    }

    /** 짧은 알림 (편집창이 열려 있으면 편집창 알림, 아니면 행동 표시줄). */
    public static void toast(ServerPlayer player, String key) {
        LibNet.CHANNEL.toPlayer(player, LibNet.S_TOAST, buf -> buf.writeUtf(key));
    }
}
