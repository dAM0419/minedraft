package dev.designandbuild.client;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.designandbuild.client.library.ClientLibrary;
import dev.designandbuild.client.projection.ProjectionManager;
import dev.designandbuild.client.table.BlueprintTableScreen;
import dev.designandbuild.core.Box;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.ModelCodec;
import dev.designandbuild.core.ModelOps;
import dev.designandbuild.core.TemplateData;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 서버에서 온 메시지 처리. 클라이언트 주 스레드에서 불린다. */
public final class ClientNet {
    private ClientNet() {}

    /** 테이블 요약 (도면 테이블 화면이 읽는다). */
    public record TableInfo(BlockPos pos, int state, String name, int baseX, int baseZ, int chunks, int structures,
                            int progress, int editors) {
        public static final int EMPTY_TABLE = 0, EMPTY_BLUEPRINT = 1, BOUND = 2, MISSING = 3;
    }

    @Nullable
    public static TableInfo tableInfo;

    public static void receive(Net.Frame frame) {
        try {
            dispatch(frame.kind(), frame.buf());
        } catch (Exception e) {
            DesignAndBuild.LOGGER.error("DesignAndBuild: failed to handle message {}", frame.kind(), e);
        }
    }

    private static void dispatch(int kind, FriendlyByteBuf buf) {
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
            case Msg.S_EDITOR_BEGIN -> {
                BlockPos table = buf.readBlockPos();
                BlueprintModel model = ModelCodec.read(buf);
                ClientStore.MODELS.put(model.id, model);
                ProjectionManager.onModelReplaced(model);
                if (ClientStore.editor != null) ClientStore.editor.dispose();
                EditorSession session = new EditorSession(model, table);
                ClientStore.editor = session;
                mc.setScreen(new EditorScreen(session));
            }
            case Msg.S_SNAPSHOT -> {
                UUID id = buf.readUUID();
                EditorSession s = ClientStore.editor;
                if (s == null || !s.model.id.equals(id) || mc.level == null) return;
                long chunk = s.snapshot.read(buf, mc.level.registryAccess());
                s.onSnapshotChunk(chunk);
            }
            case Msg.S_EDITOR_READY -> {
                UUID id = buf.readUUID();
                EditorSession s = ClientStore.editor;
                if (s != null && s.model.id.equals(id)) s.onReady();
            }
            case Msg.S_APPLIED -> applied(buf);
            case Msg.S_REJECTED -> {
                UUID id = buf.readUUID();
                int seq = buf.readVarInt();
                String key = buf.readUtf();
                EditorSession s = ClientStore.editor;
                if (s != null && s.model.id.equals(id)) s.onRejected(seq, key);
            }
            case Msg.S_MODEL_FULL -> {
                BlueprintModel model = ModelCodec.read(buf);
                ClientStore.MODELS.put(model.id, model);
                EditorSession s = ClientStore.editor;
                if (s != null && s.model.id.equals(model.id)) s.replaceModel(model);
                ProjectionManager.onModelReplaced(model);
            }
            case Msg.S_EDITORS -> {
                UUID id = buf.readUUID();
                int n = buf.readVarInt();
                EditorSession s = ClientStore.editor;
                List<UUID> ids = new ArrayList<>(n);
                List<String> names = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    ids.add(buf.readUUID());
                    names.add(buf.readUtf());
                }
                if (s == null || !s.model.id.equals(id)) return;
                s.editors.clear();
                for (int i = 0; i < n; i++) s.editors.put(ids.get(i), names.get(i));
                s.cursors.keySet().removeIf(u -> !s.editors.containsKey(u));
            }
            case Msg.S_CURSOR -> {
                UUID id = buf.readUUID();
                UUID from = buf.readUUID();
                byte[] data = buf.readByteArray(512);
                EditorSession s = ClientStore.editor;
                if (s == null || !s.model.id.equals(id)) return;
                FriendlyByteBuf b = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(data));
                boolean has = b.readBoolean();
                long pos = b.readLong();
                Box region = new Box(b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt());
                int selected = b.readVarInt();
                s.cursors.put(from, new EditorSession.RemoteCursor(from, has, pos, region, selected, System.currentTimeMillis()));
            }
            case Msg.S_CLOSE_EDITOR -> {
                String reason = buf.readUtf();
                if (mc.screen instanceof EditorScreen) mc.setScreen(null);
                if (ClientStore.editor != null) {
                    ClientStore.editor.dispose();
                    ClientStore.editor = null;
                }
                if (!reason.isEmpty()) toast(Component.translatable(reason));
            }
            case Msg.S_LIBRARY -> ClientLibrary.onServerList(buf);
            case Msg.S_TEMPLATE -> {
                String id = buf.readUtf();
                boolean found = buf.readBoolean();
                TemplateData data = found ? TemplateData.read(buf) : null;
                ClientLibrary.onTemplate(id, data);
            }
            case Msg.S_TABLE_INFO -> {
                BlockPos pos = buf.readBlockPos();
                int state = buf.readByte();
                TableInfo info;
                if (state == TableInfo.BOUND) {
                    String name = buf.readUtf();
                    int bx = buf.readVarInt(), bz = buf.readVarInt();
                    int chunks = buf.readVarInt(), structures = buf.readVarInt();
                    int progress = buf.readVarInt(), editors = buf.readVarInt();
                    info = new TableInfo(pos, state, name, bx, bz, chunks, structures, progress, editors);
                } else {
                    info = new TableInfo(pos, state, "", 0, 0, 0, 0, -1, 0);
                }
                tableInfo = info;
                if (mc.screen instanceof BlueprintTableScreen ts) ts.onInfo(info);
            }
            case Msg.S_TOAST -> toast(Component.translatable(buf.readUtf()));
            case Msg.S_TOAST_N -> {
                String key = buf.readUtf();
                int n = buf.readVarInt();
                toast(Component.translatable(key, n));
            }
            case Msg.S_INSTANT_STATE -> {
                UUID id = buf.readUUID();
                boolean allowed = buf.readBoolean();
                boolean running = buf.readBoolean();
                boolean hasUndo = buf.readBoolean();
                int undoKind = hasUndo ? buf.readVarInt() : -1;
                int sid = hasUndo ? buf.readVarInt() : 0;
                ProjectionManager.INSTANT.put(id, new ProjectionManager.InstantState(allowed, running, hasUndo, undoKind, sid));
            }
            case Msg.S_HAMMER_RESULT -> {
                int placed = buf.readVarInt();
                int n = buf.readVarInt();
                StringBuilder missing = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    String id = buf.readUtf();
                    int c = buf.readVarInt();
                    net.minecraft.world.item.Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .get(net.minecraft.resources.ResourceLocation.parse(id));
                    if (missing.length() > 0) missing.append(", ");
                    missing.append(item.getDescription().getString()).append(' ').append(c);
                }
                int[] fails = new int[dev.designandbuild.core.PlaceCheck.COUNT];
                int k = buf.readVarInt();
                for (int i = 0; i < k; i++) {
                    int code = buf.readVarInt();
                    int c = buf.readVarInt();
                    if (code >= 0 && code < fails.length) fails[code] = c;
                }
                toast(dev.designandbuild.client.projection.HammerText.result(placed, missing.toString(), fails));
            }
            case Msg.S_HAMMER_FAIL -> toast(dev.designandbuild.client.projection.HammerText.describe(
                    dev.designandbuild.core.PlaceCheck.Result.read(buf)));
            default -> {
            }
        }
    }

    private static void applied(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        long version = buf.readVarLong();
        UUID actor = buf.readUUID();
        int seq = buf.readVarInt();
        int n = buf.readVarInt();
        List<ModelOps.Op> ops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ops.add(ModelOps.read(buf));
        BlueprintModel m = ClientStore.MODELS.get(id);
        EditorSession s = ClientStore.editor;
        if (m == null) return;
        if (version != m.version + 1) {
            // 중간 변경을 놓쳤다: 전체를 다시 받는다
            if (s != null && s.model == m) s.onRejected(seq, "");
            Net.toServer(Msg.C_SUBSCRIBE, b -> {
                b.writeUUID(id);
                b.writeVarLong(-1);
            });
            return;
        }
        for (ModelOps.Op op : ops) op.apply(m);
        m.version = version;
        if (s != null && s.model == m) s.onApplied(actor, seq, ops);
    }

    /** 편집창이 열려 있으면 편집창 토스트, 아니면 핫바 위 알림. */
    public static void toast(Component text) {
        Minecraft mc = Minecraft.getInstance();
        EditorSession s = ClientStore.editor;
        if (s != null && mc.screen instanceof EditorScreen) {
            s.toast(text);
        } else if (mc.player != null) {
            mc.player.displayClientMessage(text, true);
        }
    }
}
