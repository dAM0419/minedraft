package dev.minedraft.lib.space;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공간 태그 하나: 칸(블록 자리)에 붙이는 용도 표시. 예: DaB 의 바닥·길·구조물, Civilize 의 작업 영역·거주 영역.
 * 태그는 묶음({@link TagGroup})에 속한다. 한 칸에는 묶음마다 태그를 하나씩 붙일 수 있어서,
 * 다른 묶음의 태그끼리는 겹친다 (DaB 바닥 + Civilize 거주 영역). 같은 묶음 안에서는 하나만 (바닥이거나 길).
 * 모드가 생성자에서(서버·클라이언트 공통) {@link #register} 한다.
 *
 * @param id    태그 ID. 이름 문구 키는 {@code <namespace>.tag.<path>}
 * @param group 속한 묶음 ID
 * @param color 편집창 표시 색 (0xRRGGBB)
 * @param icon  {@link dev.minedraft.lib.client.ui.Icons} 아이콘 번호 (없으면 -1)
 */
public record SpaceTag(ResourceLocation id, ResourceLocation group, int color, int icon) {
    private static final Map<ResourceLocation, SpaceTag> TAGS = new LinkedHashMap<>();

    public static SpaceTag register(SpaceTag tag) {
        if (TAGS.putIfAbsent(tag.id(), tag) != null) throw new IllegalStateException("Duplicate space tag " + tag.id());
        return tag;
    }

    @Nullable
    public static SpaceTag get(ResourceLocation id) {
        return TAGS.get(id);
    }

    public static Collection<SpaceTag> all() {
        return Collections.unmodifiableCollection(TAGS.values());
    }

    public String nameKey() {
        return id.getNamespace() + ".tag." + id.getPath();
    }

    /** 통신: 태그 하나 (없음 = 빈 ID). */
    public static void write(FriendlyByteBuf buf, @Nullable SpaceTag tag) {
        buf.writeUtf(tag == null ? "" : tag.id().toString());
    }

    @Nullable
    public static SpaceTag read(FriendlyByteBuf buf) {
        String s = buf.readUtf();
        if (s.isEmpty()) return null;
        ResourceLocation id = ResourceLocation.tryParse(s);
        return id == null ? null : TAGS.get(id);
    }
}
