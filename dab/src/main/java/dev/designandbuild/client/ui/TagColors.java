package dev.designandbuild.client.ui;

import dev.designandbuild.core.DesignTag;
import dev.minedraft.lib.client.ui.Ui;

/** DaB 설계 태그 색 (바닥·길·구조물·철거). */
public final class TagColors {
    private TagColors() {}

    public static int tagColor(DesignTag tag) {
        // 색은 Lib 공간 태그에 등록한 값 (철거 = 태그 없음)
        dev.minedraft.lib.space.SpaceTag t = tag.space();
        return t == null ? Ui.DEMOLISH : 0xFF000000 | t.color();
    }
}
