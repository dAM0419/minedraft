package dev.civilizeandgovern.zone;

import dev.civilizeandgovern.CaG;
import dev.civilizeandgovern.city.City;
import dev.civilizeandgovern.city.CityManager;
import dev.civilizeandgovern.city.CityPermission;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaAccess;
import dev.minedraft.lib.area.AreaRules;
import dev.minedraft.lib.area.AreaType;
import dev.minedraft.lib.space.SpaceTag;
import dev.minedraft.lib.space.TagGroup;
import dev.minedraft.lib.space.TagPolicy;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 도시 영역 종류와 구역 태그 (명세 01-architecture.md 5절, D-02, D-34).
 * 구역 태그는 그룹 하나에 다 넣는다: 한 칸에는 구역 하나만. DaB 태그와는 그룹이 달라 겹칠 수 있다.
 * 태그는 여기서 코드로 전부 등록한다 (Lib 의 태그 그룹은 초기화 때 정해진다). 직업 JSON 은 이 태그만 가리킨다.
 */
public final class CagZones {
    private CagZones() {}

    public static final ResourceLocation GROUP = CaG.rl("zone");
    /** 도시 영역이 가질 수 있는 청크의 절대 상한 (시 레벨 5 + 부관 가산). 실제 상한은 시 레벨이 정한다. */
    public static final int ABSOLUTE_MAX_CHUNKS = 65;

    private static final List<SpaceTag> ALL = new ArrayList<>();

    // 아이콘은 Lib Icons 번호. 구역 전용 아이콘이 생기기 전까지 바닥(21)·구조물(23) 아이콘을 빌려 쓴다.
    private static SpaceTag tag(String path, int color, int icon) {
        SpaceTag t = SpaceTag.register(new SpaceTag(CaG.rl(path), GROUP, color, icon));
        ALL.add(t);
        return t;
    }

    // 공공·거주
    public static final SpaceTag RESIDENCE = tag("residence", 0x5B8DD9, 21);
    public static final SpaceTag PLAZA = tag("plaza", 0xE8E0C8, 21);
    public static final SpaceTag JAIL = tag("jail", 0x8A4B4B, 21);
    public static final SpaceTag POST = tag("post", 0x3F6FA0, 21);
    // 작업구역 (명세 04-jobs.md 4절의 zone_tag)
    public static final SpaceTag FIELD = tag("field", 0xB8C46A, 23);
    public static final SpaceTag FOREST = tag("forest", 0x4E8B3A, 23);
    public static final SpaceTag CARPENTRY = tag("carpentry", 0xB07A45, 23);
    public static final SpaceTag KITCHEN = tag("kitchen", 0xE0A050, 23);
    public static final SpaceTag MINE = tag("mine", 0x7C7C86, 23);
    public static final SpaceTag SMITHY = tag("smithy", 0x5A5F6B, 23);
    public static final SpaceTag SHEEP_PASTURE = tag("sheep_pasture", 0xD9D2B8, 23);
    public static final SpaceTag RANCH = tag("ranch", 0xA9825B, 23);
    public static final SpaceTag FISHERY = tag("fishery", 0x4F9BC4, 23);
    public static final SpaceTag SHOP = tag("shop", 0xD9B13B, 23);
    public static final SpaceTag STUDY = tag("study", 0x8E6BB8, 23);
    public static final SpaceTag WEAVERY = tag("weavery", 0xC96FA6, 23);
    public static final SpaceTag STALK_FIELD = tag("stalk_field", 0x8FBF5A, 23);
    public static final SpaceTag GOURD_FIELD = tag("gourd_field", 0xE08A2E, 23);
    public static final SpaceTag COOP = tag("coop", 0xE6D36A, 23);
    public static final SpaceTag APIARY = tag("apiary", 0xF0C030, 23);
    public static final SpaceTag HERBARY = tag("herbary", 0x6FB08A, 23);

    public static final TagGroup ZONES = new TagGroup(GROUP, ALL, TagPolicy.DEFAULT, true);

    public static final AreaType CITY = new AreaType(CaG.rl("city"), ABSOLUTE_MAX_CHUNKS, 0, List.of()).addTagGroup(ZONES);

    /**
     * 영역별 청크 상한 (시 레벨). 서버는 도시 자료에서, 클라이언트는 서버가 보낸 값에서 채운다.
     * 싱글플레이에서는 양쪽이 같은 값을 넣는다.
     */
    public static final Map<UUID, Integer> CHUNK_CAPS = new ConcurrentHashMap<>();

    public static List<SpaceTag> tags() {
        return List.copyOf(ALL);
    }

    public static boolean isWorkZone(SpaceTag tag) {
        return tag != RESIDENCE && tag != PLAZA && tag != JAIL && tag != POST && ALL.contains(tag);
    }

    /** 클래스 초기화 (태그·영역 종류·권한·청크 규칙 등록). 모드 생성자에서 한 번 부른다. */
    public static void bootstrap() {
        AreaType.register(CITY);
        // 편집 권한: 구역 편집 권한이 있으면 편집, 그 밖의 의원은 보기, 외부인은 불가
        AreaAccess.register(CITY, (player, area) -> {
            CityManager m = CityManager.current();
            City c = m == null ? null : m.byArea(area.id);
            if (c == null) return AreaAccess.Level.NONE;
            if (CityPermission.has(c, player.getUUID(), CityPermission.EDIT_ZONES)) return AreaAccess.Level.EDIT;
            return CityPermission.isMember(c, player.getUUID()) ? AreaAccess.Level.VIEW : AreaAccess.Level.NONE;
        });
        AreaRules.register(CITY, new AreaRules.Rule() {
            @Override
            public int maxChunks(Area area) {
                Integer cap = CHUNK_CAPS.get(area.id);
                return cap == null ? ABSOLUTE_MAX_CHUNKS : cap;
            }

            @Nullable
            @Override
            public String check(Area area, LongSet add, LongSet remove) {
                // 다른 도시의 관리 청크는 넣을 수 없다 (서버만 안다)
                CityManager m = CityManager.current();
                if (m == null) return null;
                LongIterator it = add.iterator();
                while (it.hasNext()) {
                    City other = m.cityAt(area.dimension, it.nextLong());
                    if (other != null && !other.areaId.equals(area.id)) return "civilizeandgovern.msg.chunk_taken";
                }
                return null;
            }
        });
    }
}
