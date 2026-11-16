package dev.civilizeandgovern.client;

import dev.minedraft.lib.client.editor.AreaEditorScreen;
import net.minecraft.network.chat.Component;

/** 도시의 구역 편집창 (Minedraft Lib 편집창 그대로). 구역 이름과 정보 패널은 뒤에 더한다. */
public final class CityEditorScreen extends AreaEditorScreen {
    public CityEditorScreen(CityEditorSession session) {
        super(Component.translatable("civilizeandgovern.editor.title"), session);
    }

    /** 도시는 편집 중에도 돌아가야 한다. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
