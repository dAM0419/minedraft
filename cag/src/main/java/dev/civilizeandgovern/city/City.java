package dev.civilizeandgovern.city;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 도시 하나: Lib 영역 하나 + CaG 자료 (명세 01-architecture.md 4절).
 * 뒤 단계의 자료(주민, 가구, 매물 …)는 그 단계에서 여기에 더한다. 저장 구조가 바뀌면 DATA_VERSION 을 올리고 이주 코드를 둔다.
 */
public final class City {
    public static final int DATA_VERSION = 1;
    private static final int LOG_MAX = 200;

    public final int id;
    public String name;
    /** 화폐 이름 (표시용). */
    public String currency;
    public final UUID founder;
    public final long seed;
    /** Lib 영역 ID. 관리 청크와 구역은 이 영역이 가진다. */
    public final UUID areaId;
    public final ResourceKey<Level> dimension;
    public final BlockPos plazaPos;

    // 시계: 도시가 켜진 채 맞은 새벽의 수 (D-26)
    public long cityDay;
    /** 마지막으로 본 월드 날짜(dayTime / 24000). -1 = 아직 없음. */
    public long lastDayIndex = -1;

    // 돈 (명세 03-economy.md 1절). 불변식 1: Σ지갑 + treasury + Σ조합 + coinsOut = issuedTotal
    public long treasury;
    public long issuedTotal;
    /** 세상에 나간 동화(아이템으로 존재하는 돈)의 합계 계수기. */
    public long coinsOut;
    /** 물가 기준선 P. */
    public double priceAnchor = 1.0;

    public boolean forceLoad;
    /** 의원: 플레이어 → 권한 비트. */
    public final Map<UUID, Integer> council = new HashMap<>();
    public int level = 1;
    /** 인구 (2단계부터 명부에서 센다). */
    public int population;

    public record LogEntry(long day, long amount, String reason) {}

    public final List<LogEntry> treasuryLog = new ArrayList<>();

    // 저장하지 않는 상태
    /** 지금 켜져 있는가 (관리 청크가 로딩돼 있고 시간이 흐르는가). */
    public transient boolean active;
    public transient int inactiveTicks;

    public City(int id, String name, String currency, UUID founder, long seed, UUID areaId, ResourceKey<Level> dimension, BlockPos plazaPos) {
        this.id = id;
        this.name = name;
        this.currency = currency;
        this.founder = founder;
        this.seed = seed;
        this.areaId = areaId;
        this.dimension = dimension;
        this.plazaPos = plazaPos.immutable();
    }

    /** 발행: 돈이 새로 생기는 유일한 길 (D-23: 시장만). */
    public void issue(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount");
        treasury = Math.addExact(treasury, amount);
        issuedTotal = Math.addExact(issuedTotal, amount);
        log(amount, "ISSUE");
    }

    public void log(long amount, String reason) {
        treasuryLog.add(new LogEntry(cityDay, amount, reason));
        while (treasuryLog.size() > LOG_MAX) treasuryLog.remove(0);
    }

    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        t.putInt("dataVersion", DATA_VERSION);
        t.putInt("id", id);
        t.putString("name", name);
        t.putString("currency", currency);
        t.putUUID("founder", founder);
        t.putLong("seed", seed);
        t.putUUID("area", areaId);
        t.putString("dimension", dimension.location().toString());
        t.put("plaza", NbtUtils.writeBlockPos(plazaPos));
        t.putLong("cityDay", cityDay);
        t.putLong("lastDayIndex", lastDayIndex);
        t.putLong("treasury", treasury);
        t.putLong("issuedTotal", issuedTotal);
        t.putLong("coinsOut", coinsOut);
        t.putDouble("priceAnchor", priceAnchor);
        t.putBoolean("forceLoad", forceLoad);
        t.putInt("level", level);
        t.putInt("population", population);
        ListTag c = new ListTag();
        for (Map.Entry<UUID, Integer> e : council.entrySet()) {
            CompoundTag m = new CompoundTag();
            m.putUUID("player", e.getKey());
            m.putInt("permissions", e.getValue());
            c.add(m);
        }
        t.put("council", c);
        ListTag log = new ListTag();
        for (LogEntry e : treasuryLog) {
            CompoundTag m = new CompoundTag();
            m.putLong("day", e.day());
            m.putLong("amount", e.amount());
            m.putString("reason", e.reason());
            log.add(m);
        }
        t.put("treasuryLog", log);
        return t;
    }

    public static City load(CompoundTag t) {
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(t.getString("dimension")));
        BlockPos plaza = NbtUtils.readBlockPos(t, "plaza").orElse(BlockPos.ZERO);
        City c = new City(t.getInt("id"), t.getString("name"), t.getString("currency"), t.getUUID("founder"), t.getLong("seed"),
                t.getUUID("area"), dim, plaza);
        c.cityDay = t.getLong("cityDay");
        c.lastDayIndex = t.getLong("lastDayIndex");
        c.treasury = t.getLong("treasury");
        c.issuedTotal = t.getLong("issuedTotal");
        c.coinsOut = t.getLong("coinsOut");
        c.priceAnchor = t.contains("priceAnchor") ? t.getDouble("priceAnchor") : 1.0;
        c.forceLoad = t.getBoolean("forceLoad");
        c.level = Math.max(1, t.getInt("level"));
        c.population = t.getInt("population");
        for (Tag e : t.getList("council", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) e;
            c.council.put(m.getUUID("player"), m.getInt("permissions"));
        }
        for (Tag e : t.getList("treasuryLog", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) e;
            c.treasuryLog.add(new LogEntry(m.getLong("day"), m.getLong("amount"), m.getString("reason")));
        }
        return c;
    }
}
