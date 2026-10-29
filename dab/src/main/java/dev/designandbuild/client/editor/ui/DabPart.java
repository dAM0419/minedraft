package dev.designandbuild.client.editor.ui;

import dev.designandbuild.client.editor.EditorScreen;
import dev.designandbuild.client.editor.EditorSession;
import dev.minedraft.lib.client.editor.ui.UiPart;

/** DaB 편집창의 UI 조각 (DaB 세션을 바로 쓴다). */
public abstract class DabPart extends UiPart<EditorScreen> {
    protected DabPart(EditorScreen screen) {
        super(screen);
    }

    @Override
    protected EditorSession s() {
        return screen.session();
    }
}
