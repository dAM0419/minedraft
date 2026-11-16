package dev.civilizeandgovern.server;

import dev.civilizeandgovern.city.City;
import dev.civilizeandgovern.city.CityLevel;

/**
 * 결산: 도시의 새벽마다 한 번, 정해진 순서로 돈다 (명세 01-architecture.md 7절, 불변식 5).
 * 1단계에서는 뼈대만 있다. 각 단계의 내용은 해당 개발 단계에서 채운다.
 */
public final class Settlement {
    private Settlement() {}

    public enum Step {
        DATE, ABSTRACT, LIFE, MARKET, TREASURY, NEEDS, BUDGET, HOUSEHOLD, CRIME, POLICE, ADMIN, WORK_PLAN, FINISH
    }

    public static void run(City city) {
        for (Step step : Step.values()) {
            switch (step) {
                case DATE -> city.cityDay++;
                case FINISH -> city.level = Math.max(city.level, CityLevel.forPopulation(city.population));
                default -> {
                    // 2단계 이후에 채운다
                }
            }
        }
    }

    /** 결산 단계의 난수 씨앗: hash(도시 시드, 도시 날짜, 단계 번호). */
    public static long seed(City city, Step step) {
        long h = city.seed * 0x9E3779B97F4A7C15L + city.cityDay;
        h ^= (h >>> 32);
        return h * 0xBF58476D1CE4E5B9L + step.ordinal();
    }
}
