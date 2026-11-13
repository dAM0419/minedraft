package dev.minedraft.lib.space;

import dev.minedraft.lib.area.Area;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 영역 종류마다 공간 태그를 어떻게 칠할 수 있는지. 서버가 칠하기 요청을 걸러 내고, 편집창 태그 목록도 이것을 따른다.
 * 기본({@link #DEFAULT}): 영역의 모든 태그를 영역 안 어느 칸에나 칠하고 지울 수 있다.
 */
public interface TagPolicy {
    TagPolicy DEFAULT = new TagPolicy() {};

    /** 공간 지정 도구로 칠할 수 있는 태그 (보통 영역의 태그 전부). */
    default List<SpaceTag> paintable(Area area) {
        return area.type.tags();
    }

    /** 공간 지정 도구로 태그를 지울 수 있는가. */
    default boolean canErase(Area area) {
        return true;
    }

    /** 이 칸에 이 태그를 칠할 수 있는가 (null = 지우기). 영역 밖·허용 안 된 태그는 이미 걸러진 뒤에 묻는다. */
    default boolean allows(Area area, long pos, @Nullable SpaceTag tag) {
        return true;
    }
}
