package dev.designandbuild.server;

import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.Resolved;
import dev.designandbuild.core.StateRules;
import dev.designandbuild.net.Msg;
import dev.designandbuild.net.Net;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.edit.EditRequests;
import dev.minedraft.lib.server.AreaManager;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DaB 쪽 도면 관리: 도면 = Minedraft Lib 영역 (종류 designandbuild:blueprint) 의 설계층.
 * 저장·편집·동기화·편집창은 Lib 의 {@link AreaManager} 가 하고, 여기서는 도면 테이블·진척도 같은 DaB 일만 한다.
 */
public final class BlueprintManager {
    @Nullable
    private static BlueprintManager instance;

    /** 서버가 돌고 있으면 관리자 (Lib 영역 관리자 위에 얹힌다). */
    @Nullable
    public static BlueprintManager get() {
        AreaManager lib = AreaManager.get();
        if (lib == null) {
            instance = null;
            return null;
        }
        if (instance == null || instance.lib != lib) instance = new BlueprintManager(lib);
        return instance;
    }

    private final AreaManager lib;
    private final Map<UUID, long[]> progressCache = new HashMap<>();

    private BlueprintManager(AreaManager lib) {
        this.lib = lib;
        lib.onApplied(a -> progressCache.remove(a.id));
    }

    public MinecraftServer server() {
        return lib.server();
    }

    /** 도면 (없거나 DaB 도면이 아니면 null). */
    @Nullable
    public BlueprintModel model(UUID id) {
        Area a = lib.area(id);
        return a == null || a.type != BlueprintModel.AREA_TYPE ? null : BlueprintModel.of(a);
    }

    public BlueprintModel create(ServerLevel level, BlockPos tablePos, String name) {
        return BlueprintModel.of(lib.create(BlueprintModel.AREA_TYPE, level, tablePos, name));
    }

    public void openEditor(ServerPlayer player, BlueprintModel m, BlockPos tablePos) {
        lib.openEditor(player, m.area, tablePos);
    }

    /** 테이블에서 이름을 바꿈 (편집창 없이). */
    public void renameFromTable(ServerPlayer player, BlueprintModel m, String name) {
        lib.rename(m.area, player, name);
    }

    /** 테이블의 도면이 빠지거나 바뀌었거나 테이블이 부서졌다. 그 테이블로 연 편집창을 닫는다. */
    public void onTableChanged(Level level, BlockPos tablePos, @Nullable UUID blueprintNow) {
        lib.closeEditorsAt(level, tablePos, blueprintNow, "designandbuild.msg.table_changed");
    }

    public int editorCount(UUID blueprint) {
        return lib.editorCount(blueprint);
    }

    /** DaB 알림 (편집창이 열려 있으면 편집창 알림, 아니면 행동 표시줄). */
    public static void toast(ServerPlayer player, String key) {
        Net.toPlayer(player, Msg.S_TOAST, buf -> buf.writeUtf(key));
    }

    // ------------------------------------------------------------------
    // 테이블 요약용 진척도
    // ------------------------------------------------------------------

    /** 불러온 청크 안의 설계 칸 중 완성된 비율 (0~100). 설계가 없으면 -1. 5초마다 다시 센다. */
    public int progress(BlueprintModel m) {
        long now = lib.server().getTickCount();
        long[] cached = progressCache.get(m.id);
        if (cached != null && cached[0] == m.version() && now - cached[1] < 100) return (int) cached[2];
        ServerLevel level = lib.server().getLevel(m.dimension);
        int result = -1;
        if (level != null) {
            long total = 0, done = 0;
            BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
            for (Long2ObjectMap.Entry<Resolved> e : m.resolvedMap().long2ObjectEntrySet()) {
                long p = e.getLongKey();
                mp.set(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p));
                if (!level.isLoaded(mp)) continue;
                total++;
                if (StateRules.matches(level.getBlockState(mp), e.getValue().state())) done++;
            }
            result = total == 0 ? -1 : (int) (done * 100 / total);
        }
        progressCache.put(m.id, new long[]{m.version(), now, result});
        return result;
    }

    // ------------------------------------------------------------------

    public static String sanitize(String s, int max) {
        return EditRequests.sanitize(s, max);
    }
}
