package dev.minedraft.lib.space;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 공간 태그 하나: 칸(블록 자리)에 붙이는 용도 표시. 모드가 생성자에서(서버·클라이언트 공통) {@link #register} 한다.
 * 예: DaB 의 바닥·길·구조물, Civilize 의 작업 영역·거주 영역.
 * 어느 영역에서 어떤 태그를 쓸 수 있는지는 영역 종류가 정한다 ({@link dev.minedraft.lib.area.AreaType#tags}).
 *
 * @param id    태그 ID. 이름 문구 키는 {@code <namespace>.tag.<path>}
 * @param color 편집창 표시 색 (0xRRGGBB)
 * @param icon  {@link dev.minedraft.lib.client.ui.Icons} 아이콘 번호 (없으면 -1)
 */
public record SpaceTag(ResourceLocation id, int color, int icon) {
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
