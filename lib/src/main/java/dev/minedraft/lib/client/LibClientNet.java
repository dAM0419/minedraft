package dev.minedraft.lib.client;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaCodec;
import dev.minedraft.lib.area.AreaOp;
import dev.minedraft.lib.area.AreaOps;
import dev.minedraft.lib.net.Channel;
import dev.minedraft.lib.net.LibNet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Lib 통로로 클라이언트가 받은 메시지. 클라이언트 주 스레드에서 불린다. */
public final class LibClientNet {
    private LibClientNet() {}

    public static void receive(Channel.Frame frame) {
        Minecraft mc = Minecraft.getInstance();
        FriendlyByteBuf buf = frame.buf();
        switch (frame.kind()) {
            case LibNet.S_EDITOR_BEGIN -> {
                BlockPos anchor = buf.readBlockPos();
                Area a = AreaCodec.read(buf);
                ClientAreas.put(a);
                ClientAreas.open(a, anchor);
            }
            case LibNet.S_SNAPSHOT -> {
                UUID id = buf.readUUID();
                ClientEditor e = ClientAreas.editor();
                if (e == null || !e.area().id.equals(id) || mc.level == null) return;
                e.onSnapshot(buf, mc.level.registryAccess());
            }
            case LibNet.S_EDITOR_READY -> {
                UUID id = buf.readUUID();
                ClientEditor e = ClientAreas.editor();
                if (e != null && e.area().id.equals(id)) e.onReady();
            }
            case LibNet.S_APPLIED -> applied(buf);
            case LibNet.S_REJECTED -> {
                UUID id = buf.readUUID();
                int seq = buf.readVarInt();
                String key = buf.readUtf();
                ClientEditor e = ClientAreas.editor();
                if (e != null && e.area().id.equals(id)) e.onRejected(seq, key);
            }
            case LibNet.S_AREA_FULL -> {
                Area a = AreaCodec.read(buf);
                ClientAreas.put(a);
                ClientEditor e = ClientAreas.editor();
                if (e != null && e.area().id.equals(a.id)) e.onAreaReplaced(a);
            }
            case LibNet.S_EDITORS -> {
                UUID id = buf.readUUID();
                int n = buf.readVarInt();
                List<UUID> ids = new ArrayList<>(n);
                List<String> names = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    ids.add(buf.readUUID());
                    names.add(buf.readUtf());
                }
                ClientEditor e = ClientAreas.editor();
                if (e != null && e.area().id.equals(id)) e.onEditors(ids, names);
            }
            case LibNet.S_CURSOR -> {
                UUID id = buf.readUUID();
                UUID from = buf.readUUID();
                byte[] data = buf.readByteArray(512);
                ClientEditor e = ClientAreas.editor();
                if (e != null && e.area().id.equals(id)) e.onCursor(from, data);
            }
            case LibNet.S_CLOSE_EDITOR -> {
                String reason = buf.readUtf();
                ClientAreas.closeFromServer();
                if (!reason.isEmpty()) ClientAreas.toast(Component.translatable(reason));
            }
            case LibNet.S_TOAST -> ClientAreas.toast(Component.translatable(buf.readUtf()));
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
        List<AreaOp> ops = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ops.add(AreaOps.read(buf));
        Area a = ClientAreas.get(id);
        if (a == null) return;
        ClientEditor e = ClientAreas.editor();
        boolean mine = e != null && e.area() == a;
        if (version != a.version + 1) {
            // 중간 변경을 놓쳤다: 전체를 다시 받는다
            if (mine) e.onRejected(seq, "");
            LibNet.CHANNEL.toServer(LibNet.C_SUBSCRIBE, b -> {
                b.writeUUID(id);
                b.writeVarLong(-1);
            });
            return;
        }
        for (AreaOp op : ops) op.apply(a);
        a.version = version;
        if (mine) e.onApplied(actor, seq, ops);
    }
}
