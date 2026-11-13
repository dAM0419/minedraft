package dev.designandbuild.core;

import dev.designandbuild.DesignAndBuild;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.space.SpaceTag;
import dev.minedraft.lib.space.TagPolicy;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * DaB 의 공간 태그 (Minedraft Lib 공간 태그로 등록): 바닥·길·구조물.
 * 지형 층 칸의 바닥/길 태그는 Lib 공간 태그 층에 저장된다. 구조물 태그는 구조물이 차지한 칸에 붙는다.
 */
public final class DabTags {
    private DabTags() {}

    /** DaB 묶음: 건설 단계 (바닥·길·구조물). 다른 모드의 묶음(예: Civilize 구역)과는 같은 칸에 겹쳐 붙는다. */
    public static final net.minecraft.resources.ResourceLocation GROUP = DesignAndBuild.rl("stage");

    // 아이콘 번호는 Lib Icons 의 TAG_FLOOR(21)·TAG_PATH(22)·TAG_STRUCTURE(23), 색은 Lib Ui 의 FLOOR·PATH·STRUCTURE
    public static final SpaceTag FLOOR = SpaceTag.register(new SpaceTag(DesignAndBuild.rl("floor"), GROUP, 0xC8963E, 21));
    public static final SpaceTag PATH = SpaceTag.register(new SpaceTag(DesignAndBuild.rl("path"), GROUP, 0x6E8BA8, 22));
    public static final SpaceTag STRUCTURE = SpaceTag.register(new SpaceTag(DesignAndBuild.rl("structure"), GROUP, 0x9B6BD6, 23));

    public static final List<SpaceTag> ALL = List.of(FLOOR, PATH, STRUCTURE);

    /**
     * 공간 지정 도구: 바닥/길은 설계한 지형 블록의 태그를 바꾼다 (서버가 이 규칙으로 거른다).
     * 구조물은 이미 있는 블록(월드·설계)을 구조물에 넣는 것이라 편집창이 구조물 칸 요청으로 바꿔 보낸다 (Lib 칠하기로는 오지 않는다).
     * 지형 블록에는 늘 바닥이나 길 중 하나가 붙으므로 지우기는 없다.
     */
    public static final TagPolicy POLICY = new TagPolicy() {
        @Override
        public List<SpaceTag> paintable(Area area, dev.minedraft.lib.space.TagGroup group) {
            return List.of(FLOOR, PATH, STRUCTURE);
        }

        @Override
        public boolean canErase(Area area) {
            return false;
        }

        @Override
        public boolean allows(Area area, long pos, @Nullable SpaceTag tag) {
            BlueprintModel m = BlueprintModel.ofNullable(area);
            if (m == null || (tag != FLOOR && tag != PATH)) return false;
            TerrainCell t = m.terrainAt(pos);
            Resolved r = m.resolved(pos);
            return t != null && t.isRealBlock() && (r == null || r.owner() == 0);
        }
    };

    public static final dev.minedraft.lib.space.TagGroup STAGE = new dev.minedraft.lib.space.TagGroup(GROUP, ALL, POLICY);

    /** 클래스 초기화 (태그 등록). */
    public static void bootstrap() {}
}
