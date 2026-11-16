package dev.minedraft.lib.area;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * 영역 편집 권한. 영역 종류마다 규칙을 하나 등록한다 (모드 생성자에서). 등록이 없으면 누구나 편집한다.
 * 서버가 편집창을 열 때와 편집 요청을 받을 때마다 묻는다 (권한이 도중에 바뀌어도 바로 따른다).
 * 예: DaB 도면은 누구나 편집, Civilize 도시는 관리자와 허락받은 사람만 편집하고 나머지는 보기만.
 */
public final class AreaAccess {
    private AreaAccess() {}

    public enum Level {
        /** 편집창을 열 수 없다. */
        NONE,
        /** 편집창을 열어 볼 수 있지만 편집 요청은 거부된다. */
        VIEW,
        /** 편집할 수 있다. */
        EDIT
    }

    @FunctionalInterface
    public interface Rule {
        Level level(ServerPlayer player, Area area);
    }

    private static final Map<ResourceLocation, Rule> RULES = new HashMap<>();

    public static void register(AreaType type, Rule rule) {
        if (RULES.putIfAbsent(type.id(), rule) != null) throw new IllegalStateException("Duplicate access rule for " + type.id());
    }

    public static Level level(ServerPlayer player, Area area) {
        Rule r = RULES.get(area.type.id());
        if (r == null) return Level.EDIT;
        Level l = r.level(player, area);
        return l == null ? Level.NONE : l;
    }
}
