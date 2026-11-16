package dev.civilizeandgovern.server;

import dev.civilizeandgovern.Registration;
import dev.civilizeandgovern.block.PlazaBlockEntity;
import dev.civilizeandgovern.city.City;
import dev.civilizeandgovern.city.CityManager;
import dev.civilizeandgovern.net.ServerNet;
import dev.civilizeandgovern.zone.CagZones;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 서버 쪽 이벤트: 도시 자료 붙이기, 청크 로딩, 시계와 결산, 광장 블록 보호. */
public final class ServerEvents {
    private ServerEvents() {}

    public static void init() {
        // Lib 이 영역 저장을 준비한 뒤에 붙는다 (Lib 은 HIGHEST 로 듣는다)
        NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, ServerEvents::onStarted);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onStopped);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onTick);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onBreak);
    }

    private static void onStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        CityManager m = CityManager.attach(server);
        CagZones.CHUNK_CAPS.clear();
        for (City c : m.cities()) CagZones.CHUNK_CAPS.put(c.areaId, CityManager.maxChunks(c));
        ChunkLoader.releaseAll(server, m);
    }

    private static void onStopped(ServerStoppedEvent event) {
        CityManager.detach();
        CagZones.CHUNK_CAPS.clear();
    }

    private static void onTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        CityManager m = CityManager.current();
        if (m == null || server.getTickCount() % 20 != 0) return;
        ChunkLoader.tick(server, m);
        for (City c : m.cities()) clock(server, m, c);
    }

    /**
     * 도시 시계 (명세 01-architecture.md 6절, D-26): 도시가 켜진 채 새벽을 맞을 때만 날짜가 오른다.
     * 여러 날을 건너뛰어도 결산은 한 번만 돈다.
     */
    private static void clock(MinecraftServer server, CityManager m, City c) {
        ServerLevel level = server.getLevel(c.dimension);
        if (level == null) return;
        long dayIndex = level.getDayTime() / 24000L;
        if (c.active && c.lastDayIndex >= 0 && dayIndex > c.lastDayIndex) {
            Settlement.run(c);
            CagZones.CHUNK_CAPS.put(c.areaId, CityManager.maxChunks(c));
            m.setDirty();
        }
        if (c.lastDayIndex != dayIndex) {
            c.lastDayIndex = dayIndex;
            m.setDirty();
        }
    }

    /** 광장 블록은 시장만, 주민이 0명일 때만 부술 수 있다. 부수면 도시가 없어진다. */
    private static void onBreak(BlockEvent.BreakEvent event) {
        if (!event.getState().is(Registration.PLAZA.get())) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!(level.getBlockEntity(event.getPos()) instanceof PlazaBlockEntity plaza) || plaza.cityId() <= 0) return;
        CityManager m = CityManager.get();
        City c = m.byId(plaza.cityId());
        if (c == null) return;
        boolean mayor = event.getPlayer().getUUID().equals(c.founder);
        if (!mayor || c.population > 0) {
            event.setCanceled(true);
            if (event.getPlayer() instanceof ServerPlayer sp) {
                ServerNet.toast(sp, mayor ? "civilizeandgovern.msg.plaza_has_citizens" : "civilizeandgovern.msg.plaza_not_mayor");
            }
            return;
        }
        if (c.active) {
            c.forceLoad = false;
            dev.minedraft.lib.area.Area a = m.areaOf(c);
            if (a != null) {
                it.unimi.dsi.fastutil.longs.LongIterator it = a.chunks().iterator();
                while (it.hasNext()) {
                    long ch = it.nextLong();
                    level.setChunkForced(net.minecraft.world.level.ChunkPos.getX(ch), net.minecraft.world.level.ChunkPos.getZ(ch), false);
                }
            }
        }
        CagZones.CHUNK_CAPS.remove(c.areaId);
        m.dissolve(c);
    }
}
