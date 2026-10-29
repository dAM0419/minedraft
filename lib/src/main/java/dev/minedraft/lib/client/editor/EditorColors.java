package dev.minedraft.lib.client.editor;

import java.util.UUID;

/** 다른 편집자 고유색. 의미 색(청록·황토·회청·보라·빨강·주황·흰색·초록)과 겹치지 않는 색에서 고른다. */
public final class EditorColors {
    private EditorColors() {}

    private static final int[] PALETTE = {0xFFFF6FB5, 0xFFF2D84B, 0xFFB5835A, 0xFFE86FE8, 0xFF7FD1FF, 0xFFC9C9C9,
            0xFFFFA8A8, 0xFFB8E0FF};

    public static int editorColor(UUID player) {
        int h = player.hashCode();
        return PALETTE[Math.floorMod(h ^ (h >>> 16), PALETTE.length)];
    }
}
