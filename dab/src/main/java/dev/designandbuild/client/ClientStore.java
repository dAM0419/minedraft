package dev.designandbuild.client;

import dev.designandbuild.client.editor.EditorSession;
import dev.designandbuild.core.BlueprintModel;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 클라이언트가 들고 있는 도면 사본. 편집창과 투영이 같은 사본을 쓴다. */
public final class ClientStore {
    private ClientStore() {}

    public static final Map<UUID, BlueprintModel> MODELS = new HashMap<>();
    @Nullable
    public static EditorSession editor;

    private static int nextSeq = 1;
    private static int nextGroup = 1;

    public static int nextSeq() {
        return nextSeq++;
    }

    public static int nextGroup() {
        return nextGroup++;
    }

    public static void reset() {
        MODELS.clear();
        if (editor != null) editor.dispose();
        editor = null;
    }
}
