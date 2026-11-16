package dev.minedraft.lib.area;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 영역 종류별 청크 규칙. 모드가 등록한다 (영역 종류당 하나, 없으면 종류의 최대 청크 수만 본다).
 * 서버와 클라이언트 양쪽에서 불리므로, 규칙은 양쪽에서 답할 수 있는 자료만 써야 한다
 * (클라이언트가 모르는 것은 null 을 돌려주고 서버가 거절하게 둔다).
 */
public final class AreaRules {
    private AreaRules() {}

    public interface Rule {
        /** 이 영역이 가질 수 있는 최대 청크 수. */
        default int maxChunks(Area area) {
            return area.type.maxChunks();
        }

        /** 청크 변경을 막을 이유 (번역 키). 없으면 null. */
        @Nullable
        default String check(Area area, LongSet add, LongSet remove) {
            return null;
        }
    }

    private static final Map<ResourceLocation, Rule> RULES = new HashMap<>();

    public static void register(AreaType type, Rule rule) {
        if (RULES.putIfAbsent(type.id(), rule) != null) throw new IllegalStateException("Duplicate area rule for " + type.id());
    }

    public static int maxChunks(Area area) {
        Rule r = RULES.get(area.type.id());
        return r == null ? area.type.maxChunks() : Math.min(area.type.maxChunks(), r.maxChunks(area));
    }

    @Nullable
    public static String check(Area area, LongSet add, LongSet remove) {
        Rule r = RULES.get(area.type.id());
        return r == null ? null : r.check(area, add, remove);
    }
}
