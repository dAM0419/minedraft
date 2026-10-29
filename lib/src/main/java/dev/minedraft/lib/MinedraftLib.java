package dev.minedraft.lib;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import dev.minedraft.lib.area.AreaOps;
import dev.minedraft.lib.client.LibClient;
import dev.minedraft.lib.edit.EditRequests;
import dev.minedraft.lib.net.LibNet;
import dev.minedraft.lib.server.LibServerEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Minedraft Lib: 쿼터뷰 영역 편집창, 청크 영역, 월드 스냅샷, 공간 태그, 동시 편집을 여러 모드가 함께 쓰게 하는 공용 모드.
 * DaB(Design and Build), Civilize 같은 모드가 이 위에 자기 층·도구·패널을 등록한다.
 */
@Mod(MinedraftLib.MOD_ID)
public final class MinedraftLib {
    public static final String MOD_ID = "minedraftlib";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MinedraftLib(IEventBus modBus, Dist dist) {
        AreaOps.bootstrap();
        EditRequests.bootstrap();
        modBus.addListener(LibNet.CHANNEL::register);
        LibServerEvents.register();
        if (dist.isClient()) LibClient.init(modBus);
    }

    public static ResourceLocation rl(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }
}
