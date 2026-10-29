package dev.minedraft.lib.edit;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 편집자가 서버로 보내는 편집 요청 하나. 랜덤·방향 같은 계산은 클라이언트가 끝내고 확정된 내용만 보낸다.
 * 종류마다 {@link EditRequests#register} 로 읽기 함수와 서버 처리기를 등록한다.
 */
public interface EditRequest {
    ResourceLocation type();

    /** 종류 ID 뒤의 본문만 쓴다. */
    void write(FriendlyByteBuf buf);

    /** 같은 0 아닌 group 의 연속 요청은 되돌리기 한 번으로 묶인다 (드래그 한 번). */
    default int group() {
        return 0;
    }
}
