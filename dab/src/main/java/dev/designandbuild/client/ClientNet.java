package dev.designandbuild.client;

import dev.minedraft.lib.client.ClientAreas;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.designandbuild.client.library.ClientLibrary;
import dev.designandbuild.client.projection.ProjectionManager;
import dev.designandbuild.client.table.BlueprintTableScreen;
import dev.minedraft.lib.util.Box;
import dev.designandbuild.core.BlueprintModel;
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

    public static void receive(dev.minedraft.lib.net.Channel.Frame frame) {
        try {
            dispatch(frame.kind(), frame.buf());
        } catch (Exception e) {
            DesignAndBuild.LOGGER.error("DesignAndBuild: failed to handle message {}", frame.kind(), e);
        }
    }

    private static void dispatch(int kind, FriendlyByteBuf buf) {
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
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

    /** 편집창이 열려 있으면 편집창 토스트, 아니면 핫바 위 알림. */
    public static void toast(Component text) {
        ClientAreas.toast(text);
    }
}
