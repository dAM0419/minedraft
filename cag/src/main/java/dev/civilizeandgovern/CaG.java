package dev.civilizeandgovern;

import dev.civilizeandgovern.client.ClientSetup;
import dev.civilizeandgovern.config.Params;
import dev.civilizeandgovern.net.Net;
import dev.civilizeandgovern.server.ServerEvents;
import dev.civilizeandgovern.zone.CagZones;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CaG (Minedraft: Civilize and Govern).
 * 광장 블록으로 도시를 세우고, Minedraft Lib 편집창으로 구역을 칠하고, 스스로 일하고 거래하는 주민을 다스린다.
 * 구현 명세: docs/spec (00-README.md 부터).
 */
@Mod(CaG.MODID)
public final class CaG {
    public static final String MODID = "civilizeandgovern";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public CaG(IEventBus modBus, ModContainer container) {
        Params.load();
        Registration.register(modBus);
        CagZones.bootstrap();
        modBus.addListener(Net::register);
        modBus.addListener(CaG::buildCreativeTabs);
        ServerEvents.init();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientSetup.init(modBus);
        }
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }

    private static void buildCreativeTabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(Registration.PLAZA_ITEM.get());
        }
        if (event.getTabKey() == CreativeModeTabs.INGREDIENTS) {
            event.accept(Registration.COIN.get());
            event.accept(Registration.COIN_10.get());
            event.accept(Registration.COIN_100.get());
        }
    }
}
