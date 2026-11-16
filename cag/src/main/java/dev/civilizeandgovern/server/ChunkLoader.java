package dev.civilizeandgovern.server;

import dev.civilizeandgovern.city.City;
import dev.civilizeandgovern.city.CityManager;
import dev.civilizeandgovern.config.Params;
import dev.minedraft.lib.area.Area;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;

/**
 * 도시 청크의 일괄 로딩 (명세 01-architecture.md 8절).
 * 관리 청크 하나라도 플레이어의 시뮬레이션 거리 안에 있으면 전부 로딩하고, 하나도 없으면 전부 푼다.
 * 판정은 플레이어 위치로 한다 (청크 로딩 여부로 하면 스스로 건 표 때문에 영영 안 꺼진다).
 * 지금은 바닐라의 강제 로딩(setChunkForced)을 쓴다. NeoForge 의 청크 표 제어로 바꿀지는 실제 시험 뒤에 정한다.
 */
public final class ChunkLoader {
    private ChunkLoader() {}

    /** 20틱마다 부른다. */
    public static void tick(MinecraftServer server, CityManager manager) {
        int sim = server.getPlayerList().getSimulationDistance();
        int offDelay = Params.i("load.off_delay_ticks");
        for (City c : manager.cities()) {
            ServerLevel level = server.getLevel(c.dimension);
            Area a = manager.areaOf(c);
            if (level == null || a == null) continue;
            boolean wanted = c.forceLoad || playerNear(level, a, sim);
            if (wanted) {
                c.inactiveTicks = 0;
                if (!c.active) setForced(level, a, true);
                c.active = true;
            } else if (c.active) {
                c.inactiveTicks += 20;
                if (c.inactiveTicks >= offDelay) {
                    setForced(level, a, false);
                    c.active = false;
                }
            }
            if (c.active) syncChunks(level, a, c);
        }
    }

    private static boolean playerNear(ServerLevel level, Area a, int sim) {
        for (ServerPlayer p : level.players()) {
            ChunkPos pc = p.chunkPosition();
            LongIterator it = a.chunks().iterator();
            while (it.hasNext()) {
                long c = it.nextLong();
                if (Math.max(Math.abs(ChunkPos.getX(c) - pc.x), Math.abs(ChunkPos.getZ(c) - pc.z)) <= sim) return true;
            }
        }
        return false;
    }

    private static void setForced(ServerLevel level, Area a, boolean on) {
        LongIterator it = a.chunks().iterator();
        while (it.hasNext()) {
            long c = it.nextLong();
            level.setChunkForced(ChunkPos.getX(c), ChunkPos.getZ(c), on);
        }
    }

    /** 켜져 있는 동안 관리 청크가 늘면 새 청크도 건다 (이미 걸린 청크에 다시 걸어도 된다). */
    private static void syncChunks(ServerLevel level, Area a, City c) {
        LongIterator it = a.chunks().iterator();
        while (it.hasNext()) {
            long ch = it.nextLong();
            if (!level.getForcedChunks().contains(ch)) level.setChunkForced(ChunkPos.getX(ch), ChunkPos.getZ(ch), true);
        }
    }

    /** 서버가 뜰 때: 지난 실행에서 걸어 둔 강제 로딩을 모두 푼다 (도시는 꺼진 상태로 시작한다). */
    public static void releaseAll(MinecraftServer server, CityManager manager) {
        for (City c : manager.cities()) {
            ServerLevel level = server.getLevel(c.dimension);
            Area a = manager.areaOf(c);
            c.active = false;
            c.inactiveTicks = 0;
            if (level != null && a != null) setForced(level, a, false);
        }
    }
}
