package dev.minedraft.lib.client;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaType;
import dev.minedraft.lib.edit.EditRequest;
import dev.minedraft.lib.edit.EditRequests;
import dev.minedraft.lib.net.LibNet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/** 클라이언트가 들고 있는 영역 사본과 열려 있는 편집창. 편집창과 투영이 같은 사본을 쓴다. */
public final class ClientAreas {
    private ClientAreas() {}

    private static final Map<UUID, Area> AREAS = new HashMap<>();
    private static final Map<ResourceLocation, BiFunction<Area, BlockPos, ClientEditor>> OPENERS = new HashMap<>();
    private static final List<Consumer<Area>> REPLACE_LISTENERS = new ArrayList<>();
    @Nullable
    private static ClientEditor editor;
    private static int nextSeq = 1;
    private static int nextGroup = 1;

    /**
     * 영역 종류의 편집창을 여는 방법 (편집창 상태를 만들고 화면을 띄운다). 클라이언트 초기화에서 등록한다.
     * anchor = 편집창을 연 블록 위치.
     */
    public static void registerEditor(AreaType type, BiFunction<Area, BlockPos, ClientEditor> opener) {
        OPENERS.put(type.id(), opener);
    }

    /** 사본이 통째로 바뀌었을 때 (투영 등이 새 사본으로 갈아탄다). */
    public static void onReplaced(Consumer<Area> listener) {
        REPLACE_LISTENERS.add(listener);
    }

    @Nullable
    public static Area get(UUID id) {
        return AREAS.get(id);
    }

    @Nullable
    public static ClientEditor editor() {
        return editor;
    }

    public static int nextSeq() {
        return nextSeq++;
    }

    public static int nextGroup() {
        return nextGroup++;
    }

    /** 편집 요청을 보내고 요청 번호를 돌려준다. */
    public static int send(Area area, EditRequest req) {
        int seq = nextSeq();
        UUID id = area.id;
        LibNet.CHANNEL.toServer(LibNet.C_EDIT, buf -> {
            buf.writeUUID(id);
            buf.writeVarInt(seq);
            EditRequests.write(buf, req);
        });
        return seq;
    }

    /** 편집창 없이 영역 변경을 받기 시작 (투영). 사본이 최신이 아니면 서버가 통째로 보낸다. */
    public static void subscribe(UUID id) {
        Area a = AREAS.get(id);
        long known = a == null ? -1 : a.version;
        LibNet.CHANNEL.toServer(LibNet.C_SUBSCRIBE, b -> {
            b.writeUUID(id);
            b.writeVarLong(known);
        });
    }

    public static void unsubscribe(UUID id) {
        LibNet.CHANNEL.toServer(LibNet.C_UNSUBSCRIBE, b -> b.writeUUID(id));
    }

    /** 편집창을 닫았다 (화면이 사라질 때 부른다). */
    public static void editorClosed(ClientEditor e) {
        if (editor == e) {
            e.dispose();
            editor = null;
            LibNet.CHANNEL.toServer(LibNet.C_CLOSE_EDITOR, buf -> {});
        }
    }

    /** 편집창이 열려 있으면 편집창 알림, 아니면 핫바 위 알림. */
    public static void toast(Component text) {
        Minecraft mc = Minecraft.getInstance();
        ClientEditor e = editor;
        if (e != null && e.isShowing()) {
            e.toast(text);
        } else if (mc.player != null) {
            mc.player.displayClientMessage(text, true);
        }
    }

    // ---- LibClientNet 전용 ----

    static void put(Area a) {
        AREAS.put(a.id, a);
        for (Consumer<Area> l : REPLACE_LISTENERS) l.accept(a);
    }

    static void open(Area a, BlockPos anchor) {
        if (editor != null) editor.dispose();
        editor = null;
        BiFunction<Area, BlockPos, ClientEditor> opener = OPENERS.get(a.type.id());
        if (opener == null) return;
        editor = opener.apply(a, anchor);
    }

    static void closeFromServer() {
        ClientEditor e = editor;
        editor = null;
        if (e != null) {
            e.closeScreen();
            e.dispose();
        }
    }

    public static void reset() {
        AREAS.clear();
        if (editor != null) editor.dispose();
        editor = null;
    }
}
