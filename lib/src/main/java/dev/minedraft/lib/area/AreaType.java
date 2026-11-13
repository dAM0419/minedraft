package dev.minedraft.lib.area;

import dev.minedraft.lib.space.TagGroup;
import dev.minedraft.lib.space.SpaceTagLayer;
import java.util.ArrayList;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 영역 종류: 어느 모드의 영역인지, 어떤 층을 갖는지, 얼마나 넓힐 수 있는지.
 * 모드 생성자에서(서버·클라이언트 공통) {@link #register} 한다.
 *
 * @param maxChunks     넓힐 수 있는 최대 청크 수
 * @param initialRadius 새 영역의 처음 크기: 기준 청크 둘레 반경 (1 = 3×3)
 * @param layers        층 만들기 (순서대로 붙는다). 공간 태그 층({@link SpaceTagLayer})은 맨 앞에 늘 붙는다
 * @param tagGroups     이 영역에서 쓰는 공간 태그 묶음 ({@link #addTagGroup}, 다른 모드도 붙일 수 있다)
 */
public record AreaType(ResourceLocation id, int maxChunks, int initialRadius, List<Function<Area, AreaLayer>> layers,
                       Map<ResourceLocation, TagGroup> tagGroups) {
    public AreaType {
        List<Function<Area, AreaLayer>> all = new ArrayList<>();
        all.add(SpaceTagLayer::new);
        all.addAll(layers);
        layers = List.copyOf(all);
        tagGroups = new LinkedHashMap<>(tagGroups);
    }

    public AreaType(ResourceLocation id, int maxChunks, int initialRadius, List<Function<Area, AreaLayer>> layers) {
        this(id, maxChunks, initialRadius, layers, Map.of());
    }

    /**
     * 공간 태그 묶음을 붙인다. 모드 생성자에서 (서버·클라이언트 같은 순서로). 다른 모드의 영역 종류에도 붙일 수 있다
     * (예: Civilize 가 DaB 도면에 구역 묶음을 붙이면 같은 칸에 DaB 태그와 겹쳐 칠할 수 있다).
     */
    public AreaType addTagGroup(TagGroup group) {
        if (tagGroups.putIfAbsent(group.id(), group) != null) throw new IllegalStateException("Duplicate tag group " + group.id() + " in " + id);
        return this;
    }

    @org.jetbrains.annotations.Nullable
    public TagGroup tagGroup(ResourceLocation id) {
        return tagGroups.get(id);
    }

    public java.util.Collection<TagGroup> groups() {
        return java.util.Collections.unmodifiableCollection(tagGroups.values());
    }

    public boolean hasTags() {
        return !tagGroups.isEmpty();
    }

    private static final Map<ResourceLocation, AreaType> TYPES = new HashMap<>();

    public static AreaType register(AreaType type) {
        if (TYPES.putIfAbsent(type.id(), type) != null) throw new IllegalStateException("Duplicate area type " + type.id());
        return type;
    }

    public static AreaType get(ResourceLocation id) {
        AreaType t = TYPES.get(id);
        if (t == null) throw new IllegalArgumentException("Unknown area type " + id);
        return t;
    }

    public static boolean exists(ResourceLocation id) {
        return TYPES.containsKey(id);
    }
}
