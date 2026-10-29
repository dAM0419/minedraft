package dev.minedraft.lib.edit;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;

import java.util.List;

/**
 * 한 영역(domain)의 되돌리기 기록을 거꾸로 적용하는 방법. 되돌리기는 "내가 바꾼 뒤 아무도 건드리지 않은 칸"만
 * 되돌려야 하므로, 지금 영역 상태를 보고 판단한다.
 */
public interface UndoInverter {
    /**
     * @param changes 원래 순서대로의 기록
     * @param out     거꾸로 적용할 연산을 담는다
     * @return 다시 하기용 기록 (원래 순서대로). 비어 있으면 되돌린 것이 없다.
     */
    List<UndoChange> invert(Area area, List<UndoChange> changes, List<AreaOp> out);

    /** 기록한 연산을 적용한 직후: 적용 결과로 정해지는 값(내용 해시 등)을 기록에 채운다. */
    default void finish(Area area, List<UndoChange> changes) {}
}
