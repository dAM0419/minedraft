package dev.minedraft.lib.area;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * 영역에 얹는 데이터 층 하나 (DaB 의 블록 설계층, Civilize 의 공간 할당층 …). 영역 종류가 어떤 층을 가질지 정한다.
 * 서버(원본)와 클라이언트(사본)에서 같은 코드로 쓴다. 칸이 바뀌면 {@link Area#markChanged} 로 알리고
 * 변경 연산이 끝나면 {@link Area#flush} 가 편집창 그림·투영 같은 구독자에게 알린다.
 */
public interface AreaLayer {
    ResourceLocation id();

    /** 통신: 층 전체 (편집창을 열 때, 사본을 다시 보낼 때). */
    void writeNet(FriendlyByteBuf buf);

    void readNet(FriendlyByteBuf buf);

    /** 저장 (월드 data 폴더). */
    CompoundTag save();

    void load(CompoundTag tag);

    /** 불러온 직후 (통신·저장 모두): 파생 데이터를 처음부터 만든다. */
    default void afterLoad() {}

    /** 청크가 영역에서 빠졌다: 그 안의 데이터를 지우고 바뀐 칸을 알린다. */
    default void onChunkRemoved(long chunk) {}

    /** 이 층이 내용을 가진 청크를 out 에 넣는다 (범위 탭의 "수정됨" 표시, 청크 제거 확인). */
    default void collectUsedChunks(it.unimi.dsi.fastutil.longs.LongSet out) {}
}
