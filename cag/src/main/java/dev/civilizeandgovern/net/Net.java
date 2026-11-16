package dev.civilizeandgovern.net;

import dev.civilizeandgovern.CaG;
import dev.minedraft.lib.net.Channel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.function.Consumer;

/** CaG 통신 통로 (Minedraft Lib 의 Channel). 메시지 종류는 {@link Msg}. */
public final class Net {
    private Net() {}

    public static final Channel CHANNEL = new Channel(CaG.rl("net"), ServerNet::receive);

    public static void register(RegisterPayloadHandlersEvent event) {
        CHANNEL.register(event);
    }

    public static void setClientReceiver(Consumer<Channel.Frame> receiver) {
        CHANNEL.clientReceiver = receiver;
    }

    public static void toServer(int kind, Consumer<FriendlyByteBuf> writer) {
        CHANNEL.toServer(kind, writer);
    }

    public static void toPlayer(ServerPlayer player, int kind, Consumer<FriendlyByteBuf> writer) {
        CHANNEL.toPlayer(player, kind, writer);
    }
}
