package dev.civilizeandgovern.server;

import dev.civilizeandgovern.city.City;
import dev.civilizeandgovern.city.CityManager;
import dev.civilizeandgovern.city.CityPermission;
import dev.minedraft.lib.area.Area;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/** /cag status: 도시의 상태를 어디서든 본다 (도시가 켜져 있는지는 도시 밖에서 확인해야 하므로). */
public final class CagCommands {
    private CagCommands() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("cag")
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource()))));
    }

    private static int status(CommandSourceStack source) {
        CityManager m = CityManager.current();
        if (m == null) return 0;
        ServerPlayer player = source.getPlayer();
        boolean all = source.hasPermission(2);
        int shown = 0;
        for (City c : m.cities()) {
            if (!all && (player == null || !CityPermission.isMember(c, player.getUUID()))) continue;
            Area a = m.areaOf(c);
            int chunks = a == null ? 0 : a.chunks().size();
            Component state = Component.translatable(c.active
                    ? (c.forceLoad ? "civilizeandgovern.status.active_forced" : "civilizeandgovern.status.active")
                    : "civilizeandgovern.status.inactive");
            source.sendSuccess(() -> Component.translatable("civilizeandgovern.status.line", c.name, state, c.cityDay, chunks,
                    CityManager.maxChunks(c), c.treasury, c.currency), false);
            shown++;
        }
        if (shown == 0) source.sendSuccess(() -> Component.translatable("civilizeandgovern.status.none"), false);
        return shown;
    }
}
