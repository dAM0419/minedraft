package dev.minedraft.lib.client;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/** 열려 있는 편집창 하나 (클라이언트). Lib 이 서버 메시지를 여기로 넘긴다. */
public interface ClientEditor {
    Area area();

    /** 월드 스냅샷 청크 하나 (영역 UUID 는 이미 읽음). */
    void onSnapshot(FriendlyByteBuf buf, RegistryAccess access);

    /** 처음 스냅샷을 다 받았다. */
    void onReady();

    /** 서버가 확정한 변경을 사본에 적용한 뒤. actor = 요청한 사람, seq = 그 사람의 요청 번호. */
    void onApplied(UUID actor, int seq, List<AreaOp> ops);

    /** 내 요청이 거절됐다 (key 가 비어 있으면 알릴 것 없음). */
    void onRejected(int seq, String key);

    /** 서버가 사본을 통째로 다시 보냈다. */
    void onAreaReplaced(Area area);

    /** 지금 이 영역을 편집 중인 사람들. */
    void onEditors(List<UUID> ids, List<String> names);

    /** 다른 편집자의 커서 (편집창이 정한 형식 그대로). */
    void onCursor(UUID from, byte[] data);

    /** 편집창 화면이 지금 떠 있는가 (알림을 편집창에 띄울지 정한다). */
    boolean isShowing();

    void toast(Component text);

    /** 서버가 닫으라고 했다: 화면을 닫는다 (dispose 는 Lib 이 부른다). */
    void closeScreen();

    /** 그림 버퍼·구독 해제. */
    void dispose();
}
