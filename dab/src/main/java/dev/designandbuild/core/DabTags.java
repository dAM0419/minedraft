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

    // 아이콘 번호는 Lib Icons 의 TAG_FLOOR(21)·TAG_PATH(22)·TAG_STRUCTURE(23), 색은 Lib Ui 의 FLOOR·PATH·STRUCTURE
    public static final SpaceTag FLOOR = SpaceTag.register(new SpaceTag(DesignAndBuild.rl("floor"), 0xC8963E, 21));
    public static final SpaceTag PATH = SpaceTag.register(new SpaceTag(DesignAndBuild.rl("path"), 0x6E8BA8, 22));
    public static final SpaceTag STRUCTURE = SpaceTag.register(new SpaceTag(DesignAndBuild.rl("structure"), 0x9B6BD6, 23));

    public static final List<SpaceTag> ALL = List.of(FLOOR, PATH, STRUCTURE);

    /**
     * 공간 지정 도구로는 지형 층 블록의 바닥/길만 바꾼다. 구조물 태그는 직접조작 도구의 구조물 층으로 정해지고,
     * 지형 블록에는 늘 바닥이나 길 중 하나가 붙으므로 지우기는 없다.
     */
    public static final TagPolicy POLICY = new TagPolicy() {
        @Override
        public List<SpaceTag> paintable(Area area) {
            return List.of(FLOOR, PATH);
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

    /** 클래스 초기화 (태그 등록). */
    public static void bootstrap() {}
}
