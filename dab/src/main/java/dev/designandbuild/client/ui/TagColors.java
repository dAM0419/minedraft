package dev.designandbuild.client.ui;

import dev.designandbuild.core.DesignTag;
import dev.minedraft.lib.client.ui.Ui;

/** DaB 설계 태그 색 (바닥·길·구조물·철거). */
public final class TagColors {
    private TagColors() {}

    public static int tagColor(DesignTag tag) {
        return switch (tag) {
            case FLOOR -> Ui.FLOOR;
            case PATH -> Ui.PATH;
            case STRUCTURE -> Ui.STRUCTURE;
            default -> Ui.DEMOLISH;
        };
    }
}
