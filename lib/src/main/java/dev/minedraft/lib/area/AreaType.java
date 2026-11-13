package dev.minedraft.lib.area;

import dev.minedraft.lib.space.SpaceTag;
import dev.minedraft.lib.space.SpaceTagLayer;
import dev.minedraft.lib.space.TagPolicy;
import java.util.ArrayList;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 영역 종류: 어느 모드의 영역인지, 어떤 층을 갖는지, 얼마나 넓힐 수 있는지.
 * 모드 생성자에서(서버·클라이언트 공통) {@link #register} 한다.
 *
 * @param maxChunks     넓힐 수 있는 최대 청크 수
 * @param initialRadius 새 영역의 처음 크기: 기준 청크 둘레 반경 (1 = 3×3)
 * @param layers        층 만들기 (순서대로 붙는다). 태그가 있으면 공간 태그 층({@link SpaceTagLayer})이 맨 앞에 자동으로 붙는다
 * @param tags          이 영역에서 쓰는 공간 태그 (없으면 공간 지정 없음)
 * @param tagPolicy     공간 태그를 칠할 수 있는 칸·태그
 */
public record AreaType(ResourceLocation id, int maxChunks, int initialRadius, List<Function<Area, AreaLayer>> layers,
                       List<SpaceTag> tags, TagPolicy tagPolicy) {
    public AreaType {
        tags = List.copyOf(tags);
        if (!tags.isEmpty()) {
            List<Function<Area, AreaLayer>> all = new ArrayList<>();
            all.add(SpaceTagLayer::new);
            all.addAll(layers);
            layers = List.copyOf(all);
        } else {
            layers = List.copyOf(layers);
        }
    }

    /** 공간 태그 없는 영역. */
    public AreaType(ResourceLocation id, int maxChunks, int initialRadius, List<Function<Area, AreaLayer>> layers) {
        this(id, maxChunks, initialRadius, layers, List.of(), TagPolicy.DEFAULT);
    }

    public boolean hasTags() {
        return !tags.isEmpty();
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
