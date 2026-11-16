package dev.civilizeandgovern.city;

/**
 * 시 레벨 (명세 06-governance.md 8절). 인구로 정해지고 수용량만 올린다. 인구가 줄어도 내려가지 않는다.
 * 표는 나중에 데이터 city_levels.json 으로 옮긴다.
 */
public final class CityLevel {
    private CityLevel() {}

    private static final int[] MIN_POP = {0, 10, 25, 50, 100};
    private static final int[] POP_CAP = {12, 30, 60, 120, 200};
    private static final int[] CHUNKS = {9, 16, 25, 36, 49};
    private static final int[] GUARDS = {2, 4, 8, 14, 24};
    private static final int[] AIDES = {0, 1, 2, 3, 4};
    private static final int[] RULES = {0, 6, 12, 24, 48};

    public static final int MAX = MIN_POP.length;

    public static int forPopulation(int population) {
        int level = 1;
        for (int i = 0; i < MIN_POP.length; i++) if (population >= MIN_POP[i]) level = i + 1;
        return level;
    }

    private static int idx(int level) {
        return Math.max(1, Math.min(MAX, level)) - 1;
    }

    public static int populationCap(int level) {
        return POP_CAP[idx(level)];
    }

    public static int chunks(int level) {
        return CHUNKS[idx(level)];
    }

    public static int guards(int level) {
        return GUARDS[idx(level)];
    }

    public static int aides(int level) {
        return AIDES[idx(level)];
    }

    public static int ruleLines(int level) {
        return RULES[idx(level)];
    }
}
