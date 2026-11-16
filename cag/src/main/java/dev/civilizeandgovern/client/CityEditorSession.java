package dev.civilizeandgovern.client;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.client.editor.AreaEditorSession;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** 도시 편집창의 상태. 도시 영역에는 설계 블록이 없다: 월드를 그대로 보여 주고 구역 태그만 칠한다. */
public final class CityEditorSession extends AreaEditorSession {
    public CityEditorSession(Area area, BlockPos anchor) {
        super(area, anchor);
    }

    @Nullable
    @Override
    protected BlockState designAt(long pos) {
        return null;
    }
}
