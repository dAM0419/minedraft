package dev.civilizeandgovern.client;

import dev.civilizeandgovern.net.Net;
import dev.civilizeandgovern.zone.CagZones;
import dev.minedraft.lib.client.ClientAreas;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;

/** 클라이언트 쪽 등록. */
public final class ClientSetup {
    private ClientSetup() {}

    public static void init(IEventBus modBus) {
        Net.setClientReceiver(ClientNet::receive);
        // Lib 이 도시 영역의 편집창을 열 때: 편집 상태를 만들고 화면을 띄운다
        ClientAreas.registerEditor(CagZones.CITY, (area, anchor) -> {
            CityEditorSession session = new CityEditorSession(area, anchor);
            Minecraft.getInstance().setScreen(new CityEditorScreen(session));
            return session;
        });
    }
}
