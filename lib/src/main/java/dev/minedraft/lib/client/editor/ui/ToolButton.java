package dev.minedraft.lib.client.editor.ui;

import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * 왼쪽 툴바의 단추. 도구(위쪽 묶음)나 켜고 끄는 보기(아래쪽 묶음)에 쓴다.
 *
 * @param icon     {@link dev.minedraft.lib.client.ui.Icons} 아이콘 번호
 * @param shortcut 툴팁에 붙일 단축키 글자 (없으면 null). 키 처리는 편집창이 따로 한다
 * @param title    이름 문구 키
 * @param tip      설명 문구 키 (없으면 null)
 * @param active   눌린 상태로 그릴지
 * @param action   눌렀을 때
 */
public record ToolButton(int icon, @Nullable String shortcut, String title, @Nullable String tip, BooleanSupplier active,
                         Runnable action) {
    public List<Component> tooltip() {
        List<Component> out = new ArrayList<>(2);
        out.add(shortcut == null ? Component.translatable(title) : Component.translatable(title).append(" (" + shortcut + ")"));
        if (tip != null) out.add(Component.translatable(tip));
        return out;
    }
}
