package dev.designandbuild.server;

import dev.designandbuild.net.Net;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** 서버 수명·플레이어 접속 이벤트. */
public final class ServerEvents {
    private ServerEvents() {}

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerEvents::onStarted);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onStopped);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onReload);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onLogout);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onTick);
        NeoForge.EVENT_BUS.addListener(ServerEvents::onStopping);
    }

    private static void onStopping(ServerStoppingEvent event) {
        BuildService.shutdown();
    }

    private static void onTick(ServerTickEvent.Post event) {
        BuildService.tick(event.getServer());
        BlueprintManager manager = BlueprintManager.get();
        if (manager != null) manager.tick();
    }

    private static void onStarted(ServerStartedEvent event) {
        BlueprintManager.start(event.getServer());
        BuildService.reset();
        StructureLibrary.markDirty();
    }

    private static void onStopped(ServerStoppedEvent event) {
        BlueprintManager.stop();
        BuildService.reset();
        StructureLibrary.markDirty();
    }

    private static void onReload(AddReloadListenerEvent event) {
        event.addListener((ResourceManagerReloadListener) rm -> StructureLibrary.markDirty());
    }

    private static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            BlueprintManager manager = BlueprintManager.get();
            if (manager != null) manager.onLogout(player);
            BuildService.onLogout(player);
            Net.forgetPlayer(player.getUUID());
        }
    }
}
