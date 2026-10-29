package dev.designandbuild.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * 월드 투영 키. 다른 모드와 겹칠 수 있으므로 바닐라 조작 설정에서 바꿀 수 있게 등록한다.
 * 편집창 안의 키는 편집창이 직접 처리한다 (편집창이 열려 있으면 다른 키는 막힌다).
 */
public final class Keys {
    private Keys() {}

    public static final String CATEGORY = "key.categories.designandbuild";

    public static final KeyMapping LAYER_UP = key("key.designandbuild.layer_up", GLFW.GLFW_KEY_UP);
    public static final KeyMapping LAYER_DOWN = key("key.designandbuild.layer_down", GLFW.GLFW_KEY_DOWN);
    public static final KeyMapping STAGE_PREV = key("key.designandbuild.stage_prev", GLFW.GLFW_KEY_LEFT);
    public static final KeyMapping STAGE_NEXT = key("key.designandbuild.stage_next", GLFW.GLFW_KEY_RIGHT);
    /** 재료 목록 펼치기. 기획서는 B 였지만 배낭 모드(B)와 겹쳐 기본값을 N 으로 둔다. */
    public static final KeyMapping MATERIALS = key("key.designandbuild.materials", GLFW.GLFW_KEY_N);

    private static KeyMapping key(String name, int key) {
        return new KeyMapping(name, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, key, CATEGORY);
    }

    public static void register(RegisterKeyMappingsEvent event) {
        event.register(LAYER_UP);
        event.register(LAYER_DOWN);
        event.register(STAGE_PREV);
        event.register(STAGE_NEXT);
        event.register(MATERIALS);
    }
}
