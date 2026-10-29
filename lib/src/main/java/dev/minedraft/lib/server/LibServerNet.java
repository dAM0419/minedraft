package dev.minedraft.lib.server;

import dev.minedraft.lib.edit.EditRequest;
import dev.minedraft.lib.edit.EditRequests;
import dev.minedraft.lib.net.Channel;
import dev.minedraft.lib.net.LibNet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Lib 통로로 서버가 받은 메시지. 서버 주 스레드에서 불린다. */
public final class LibServerNet {
    private LibServerNet() {}

    public static void receive(ServerPlayer player, Channel.Frame frame) {
        AreaManager manager = AreaManager.get();
        if (manager == null) return;
        FriendlyByteBuf buf = frame.buf();
        switch (frame.kind()) {
            case LibNet.C_CLOSE_EDITOR -> manager.closeEditor(player, null);
            case LibNet.C_EDIT -> {
                UUID id = buf.readUUID();
                int seq = buf.readVarInt();
                EditRequest req = EditRequests.read(buf);
                manager.handleEdit(player, id, seq, req);
            }
            case LibNet.C_CURSOR -> {
                UUID id = buf.readUUID();
                byte[] data = buf.readByteArray(512);
                manager.relayCursor(player, id, data);
            }
            case LibNet.C_SUBSCRIBE -> manager.subscribe(player, buf.readUUID(), buf.readVarLong());
            case LibNet.C_UNSUBSCRIBE -> manager.unsubscribe(player, buf.readUUID());
            default -> {
            }
        }
    }
}
