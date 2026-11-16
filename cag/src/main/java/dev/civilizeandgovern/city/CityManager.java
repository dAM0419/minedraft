package dev.civilizeandgovern.city;

import dev.civilizeandgovern.zone.CagZones;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.server.AreaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 모든 도시의 저장과 조회 (data/civilizeandgovern_cities.dat). */
public final class CityManager extends SavedData {
    private static final String FILE = "civilizeandgovern_cities";

    /** 서버가 떠 있는 동안의 인스턴스. 서버가 없으면 null (순수 클라이언트). */
    @Nullable
    private static CityManager current;

    private final Map<Integer, City> cities = new LinkedHashMap<>();
    private int nextId = 1;

    public static CityManager attach(MinecraftServer server) {
        current = server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(CityManager::new, CityManager::load, null), FILE);
        return current;
    }

    public static void detach() {
        current = null;
    }

    @Nullable
    public static CityManager current() {
        return current;
    }

    public static CityManager get() {
        if (current == null) throw new IllegalStateException("CityManager used without a running server");
        return current;
    }

    public Collection<City> cities() {
        return cities.values();
    }

    @Nullable
    public City byId(int id) {
        return cities.get(id);
    }

    @Nullable
    public City byArea(UUID areaId) {
        for (City c : cities.values()) if (c.areaId.equals(areaId)) return c;
        return null;
    }

    @Nullable
    public Area areaOf(City city) {
        return AreaManager.get().area(city.areaId);
    }

    /** 이 청크를 관리 청크로 가진 도시. */
    @Nullable
    public City cityAt(ResourceKey<Level> dimension, long chunk) {
        for (City c : cities.values()) {
            if (!c.dimension.equals(dimension)) continue;
            Area a = areaOf(c);
            if (a != null && a.chunks().contains(chunk)) return c;
        }
        return null;
    }

    public City found(ServerLevel level, BlockPos plazaPos, UUID founder, String name, String currency) {
        Area area = AreaManager.get().create(CagZones.CITY, level, plazaPos, name);
        City city = new City(nextId++, name, currency, founder, level.getRandom().nextLong(), area.id, level.dimension(), plazaPos);
        cities.put(city.id, city);
        setDirty();
        return city;
    }

    /** 도시를 없앤다. Lib 영역 자료는 남는다 (Lib 에 영역 삭제가 아직 없다). */
    public void dissolve(City city) {
        cities.remove(city.id);
        setDirty();
    }

    /** 이 도시가 지금 가질 수 있는 관리 청크 수 (시 레벨. 부관 가산은 9단계). */
    public static int maxChunks(City city) {
        return CityLevel.chunks(city.level);
    }

    public static CityManager load(CompoundTag tag, HolderLookup.Provider registries) {
        CityManager m = new CityManager();
        m.nextId = Math.max(1, tag.getInt("nextId"));
        for (Tag t : tag.getList("cities", Tag.TAG_COMPOUND)) {
            City c = City.load((CompoundTag) t);
            m.cities.put(c.id, c);
        }
        return m;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("nextId", nextId);
        ListTag list = new ListTag();
        for (City c : cities.values()) list.add(c.save());
        tag.put("cities", list);
        return tag;
    }
}
