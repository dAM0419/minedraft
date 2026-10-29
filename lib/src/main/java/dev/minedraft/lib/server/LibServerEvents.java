package dev.minedraft.lib.server;

import dev.minedraft.lib.net.LibNet;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.bus.api.EventPriority;

/** 서버 수명·접속 이벤트. 영역 관리자는 다른 모드보다 먼저 시작하고 나중에 멈춘다. */
public final class LibServerEvents {
    private LibServerEvents() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, LibServerEvents::onStarted);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, LibServerEvents::onStopped);
        NeoForge.EVENT_BUS.addListener(LibServerEvents::onLogout);
        NeoForge.EVENT_BUS.addListener(LibServerEvents::onTick);
    }

    private static void onStarted(ServerStartedEvent event) {
        AreaManager.start(event.getServer());
    }

    private static void onStopped(ServerStoppedEvent event) {
        AreaManager.stop();
    }

    private static void onTick(ServerTickEvent.Post event) {
        AreaManager m = AreaManager.get();
        if (m != null) m.tick();
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            AreaManager m = AreaManager.get();
            if (m != null) m.onLogout(player);
            LibNet.CHANNEL.forgetPlayer(player.getUUID());
        }
    }
}
