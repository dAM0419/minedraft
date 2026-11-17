package dev.civilizeandgovern.client;

import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.editor.ui.RangeTab;
import dev.minedraft.lib.client.editor.ui.ToolButton;
import net.minecraft.network.chat.Component;

/**
 * 도시의 구역 편집창 (Minedraft Lib 편집창). 도구는 공간 지정 하나, 오른쪽 창은 범위(관리 청크) 탭 하나.
 * 구역 이름과 구역 정보 탭은 뒤에 더한다.
 */
public final class CityEditorScreen extends AreaEditorScreen {
    public CityEditorScreen(CityEditorSession session) {
        super(Component.translatable("civilizeandgovern.editor.title"), session);
        panelTabs.add(new RangeTab(this));
        ToolButton tags = tagToolButton();
        if (tags != null) tools.add(tags);
        // Lib 편집창은 모드가 조각을 모아 줘야 그려진다 (0.1.0 에서 이 호출이 빠져 화면에 아무것도 없었다)
        assembleParts(null);
        setTagTool(true);
    }

    /** 도시는 편집 중에도 돌아가야 한다. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
