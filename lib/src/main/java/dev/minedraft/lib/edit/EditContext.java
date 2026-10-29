package dev.minedraft.lib.edit;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/** 서버가 요청 하나를 처리하는 동안의 상태: 만들 연산, 되돌리기 기록, 새로 넣은 청크. */
public final class EditContext {
    public final Area area;
    public final ServerPlayer player;
    public final UndoHistory history;
    /** 확정 연산 (이 순서로 적용·전송된다). */
    public final List<AreaOp> ops = new ArrayList<>();
    /** 되돌리기 기록 (적용 뒤에 쌓인다). */
    public final List<UndoChange> undo = new ArrayList<>();
    /** 새로 넣은 청크 (편집 중인 사람들에게 그 청크의 월드 스냅샷을 보낸다). */
    public final LongArrayList addedChunks = new LongArrayList();

    public EditContext(Area area, ServerPlayer player, UndoHistory history) {
        this.area = area;
        this.player = player;
        this.history = history;
    }
}
