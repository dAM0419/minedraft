package dev.minedraft.lib.area;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 서버가 확정해 모든 열람자에게 뿌리는 영역 변경 하나. 서버와 클라이언트가 같은 순서로 적용하므로 결과가 같다.
 * 종류마다 {@link AreaOps#register} 로 읽기 함수를 등록한다.
 */
public interface AreaOp {
    ResourceLocation type();

    void apply(Area area);

    /** 종류 ID 뒤의 본문만 쓴다. */
    void write(FriendlyByteBuf buf);
}
