package dev.designandbuild.client.projection;

import dev.designandbuild.core.PlaceCheck;
import dev.designandbuild.core.Stages;
import dev.designandbuild.core.StructureInstance;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 건설망치 판정 결과를 알림 문구로. 문구의 %1$s = 놓을 블록, %2$s = 받침·짝 칸 방향, %3$s = 그 칸·자리의 블록, %4$s·%5$s = 덧붙임. */
public final class HammerText {
    private HammerText() {}

    public static Component describe(PlaceCheck.Result r) {
        String name = PlaceCheck.NAMES[Math.max(0, Math.min(PlaceCheck.NAMES.length - 1, r.code()))];
        Component block = r.target().getBlock().getName();
        Component side = side(r.dir());
        Component other = r.other().getBlock().getName();
        Object a = r.a(), b = r.b();
        String key = "designandbuild.hammer.fail." + name;
        if (r.code() == PlaceCheck.OTHER_STAGE) {
            a = stageName(r.a(), r.b());
        } else if (r.code() == PlaceCheck.OTHER_LAYER && Stages.hangsFromAbove(r.target())) {
            key += "_hanging";
        }
        return Component.translatable(key, block, side, other, a, b);
    }

    /** 일괄 설치 결과: 놓은 칸 수 · 이유별 못 놓은 칸 수 · 부족한 재료. */
    public static Component result(int placed, String missing, int[] fails) {
        MutableComponent out = Component.translatable("designandbuild.hammer.placed", placed).copy();
        for (int c = 0; c < fails.length; c++) {
            if (fails[c] <= 0) continue;
            out.append(" · ").append(Component.translatable("designandbuild.hammer.count." + PlaceCheck.NAMES[c], fails[c]));
        }
        if (!missing.isEmpty()) out.append(" · ").append(Component.translatable("designandbuild.hammer.missing", missing));
        return out;
    }

    private static Component side(Direction d) {
        return d == null ? Component.empty() : Component.translatable("designandbuild.side." + d.getName());
    }

    private static Component stageName(int kind, int sid) {
        if (kind == Stages.STRUCTURE) {
            Projection p = ProjectionManager.active();
            StructureInstance st = p == null ? null : p.model.structure(sid);
            Component base = Component.translatable("designandbuild.stage.structures");
            return st == null || st.name == null || st.name.isEmpty() ? base
                    : Component.translatable("designandbuild.hammer.stage_structure", st.name);
        }
        return Component.translatable(switch (kind) {
            case Stages.FLOOR -> "designandbuild.stage.floor";
            case Stages.PATH -> "designandbuild.stage.path";
            default -> "designandbuild.stage.demolish";
        });
    }
}
