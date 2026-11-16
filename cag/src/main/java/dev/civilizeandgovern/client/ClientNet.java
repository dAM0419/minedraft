package dev.civilizeandgovern.client;

import dev.civilizeandgovern.CaG;
import dev.civilizeandgovern.net.CityView;
import dev.civilizeandgovern.net.Msg;
import dev.civilizeandgovern.zone.CagZones;
import dev.minedraft.lib.net.Channel;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;

/** 클라이언트가 받는 메시지. */
public final class ClientNet {
    private ClientNet() {}

    public static void receive(Channel.Frame frame) {
        try {
            dispatch(frame.kind(), frame.buf());
        } catch (Exception e) {
            CaG.LOGGER.error("CaG: failed to handle message {}", frame.kind(), e);
        }
    }

    private static void dispatch(int kind, FriendlyByteBuf buf) {
        Minecraft mc = Minecraft.getInstance();
        switch (kind) {
            case Msg.S_FOUND_SCREEN -> {
                BlockPos pos = buf.readBlockPos();
                mc.setScreen(new FoundCityScreen(pos));
            }
            case Msg.S_PANEL -> {
                boolean open = buf.readBoolean();
                CityView view = CityView.read(buf);
                CagZones.CHUNK_CAPS.put(view.areaId(), view.maxChunks());
                if (mc.screen instanceof CityPanelScreen panel && panel.cityId() == view.id()) panel.update(view);
                else if (open) mc.setScreen(new CityPanelScreen(view));
            }
            default -> CaG.LOGGER.warn("CaG: unknown message {}", kind);
        }
    }
}
