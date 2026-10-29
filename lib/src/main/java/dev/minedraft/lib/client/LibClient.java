package dev.minedraft.lib.client;

import dev.minedraft.lib.net.LibNet;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;

/** Lib 클라이언트 초기화. */
public final class LibClient {
    private LibClient() {}

    public static void init(IEventBus modBus) {
        LibNet.CHANNEL.clientReceiver = LibClientNet::receive;
        NeoForge.EVENT_BUS.addListener(LibClient::loggingOut);
    }

    private static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientAreas.reset();
        LibNet.CHANNEL.resetClient();
    }
}
