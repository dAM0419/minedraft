package dev.designandbuild.net;

import dev.designandbuild.DesignAndBuild;
import dev.designandbuild.server.ServerNet;
import dev.minedraft.lib.net.Channel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.UUID;
import java.util.function.Consumer;

/** DaB 통신 통로 (Minedraft Lib 의 Channel). 메시지 종류는 {@link Msg}. */
public final class Net {
    private Net() {}

    public static final Channel CHANNEL = new Channel(DesignAndBuild.rl("net"), ServerNet::receive);

    public static void register(RegisterPayloadHandlersEvent event) {
        CHANNEL.register(event);
    }

    public static void setClientReceiver(Consumer<Channel.Frame> receiver) {
        CHANNEL.clientReceiver = receiver;
    }

    public static FriendlyByteBuf buffer() {
        return Channel.buffer();
    }

    public static void toServer(int kind, Consumer<FriendlyByteBuf> writer) {
        CHANNEL.toServer(kind, writer);
    }

    public static void toPlayer(ServerPlayer player, int kind, Consumer<FriendlyByteBuf> writer) {
        CHANNEL.toPlayer(player, kind, writer);
    }

    public static void toPlayer(ServerPlayer player, int kind, byte[] body) {
        CHANNEL.toPlayer(player, kind, body);
    }

    public static byte[] encode(Consumer<FriendlyByteBuf> writer) {
        return Channel.encode(writer);
    }

    public static void forgetPlayer(UUID player) {
        CHANNEL.forgetPlayer(player);
    }

    public static void resetClient() {
        CHANNEL.resetClient();
    }
}
