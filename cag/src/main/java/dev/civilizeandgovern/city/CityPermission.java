package dev.civilizeandgovern.city;

import java.util.UUID;

/** 도시 조작 권한 (명세 01-architecture.md 10절). 시장은 전부 가진다. 의원은 시장이 준 것만. */
public enum CityPermission {
    EDIT_ZONES, RECRUIT, ASSIGN, HANDLE_REQUESTS, JUDGE, TREASURY_SPEND, SET_TAX, ISSUE, APPOINT, RULES, FORCE_LOAD, COUNCIL;

    public int bit() {
        return 1 << ordinal();
    }

    public static final int ALL = (1 << values().length) - 1;

    /** 이 플레이어의 권한 비트. 외부인은 0. */
    public static int bits(City city, UUID player) {
        if (city.founder.equals(player)) return ALL;
        // COUNCIL 은 시장만 가진다
        return city.council.getOrDefault(player, 0) & ~COUNCIL.bit();
    }

    public static boolean has(City city, UUID player, CityPermission p) {
        return (bits(city, player) & p.bit()) != 0;
    }

    /** 시장이거나 의원인가 (권한이 하나라도 있는가). */
    public static boolean isMember(City city, UUID player) {
        return city.founder.equals(player) || city.council.containsKey(player);
    }
}
