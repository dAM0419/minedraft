package dev.minedraft.lib.space;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * 공간 태그 묶음: 한 모드의 한 가지 구분 (DaB 의 건설 단계, Civilize 의 구역 용도 …).
 * 한 칸에는 묶음마다 태그 하나. 영역 종류에 {@link dev.minedraft.lib.area.AreaType#addTagGroup} 로 붙인다
 * (자기 영역 종류든 다른 모드의 영역 종류든).
 *
 * @param id     묶음 ID. 이름 문구 키는 {@code <namespace>.tag_group.<path>}
 * @param tags   이 묶음의 태그 (모두 group = id)
 * @param policy 칠할 수 있는 태그·칸
 * @param instanced 개체 묶음인가. 개체 묶음의 칸은 태그와 함께 개체 번호(1 이상)를 갖는다: 같은 태그라도 번호가 다르면
 *                  다른 개체다 (Civilize 의 밭 1·밭 2). 개체 하나의 칸은 모두 같은 태그다. 번호는 서버가 매긴다
 *                  ({@link SpaceTagLayer}).
 */
public record TagGroup(ResourceLocation id, List<SpaceTag> tags, TagPolicy policy, boolean instanced) {
    public TagGroup {
        tags = List.copyOf(tags);
        for (SpaceTag t : tags) {
            if (!t.group().equals(id)) throw new IllegalArgumentException("Tag " + t.id() + " is not in group " + id);
        }
    }

    public TagGroup(ResourceLocation id, List<SpaceTag> tags, TagPolicy policy) {
        this(id, tags, policy, false);
    }

    public TagGroup(ResourceLocation id, List<SpaceTag> tags) {
        this(id, tags, TagPolicy.DEFAULT, false);
    }

    public String nameKey() {
        return id.getNamespace() + ".tag_group." + id.getPath();
    }
}
