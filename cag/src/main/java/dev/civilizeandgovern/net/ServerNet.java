package dev.civilizeandgovern.net;

import dev.civilizeandgovern.CaG;
import dev.civilizeandgovern.block.PlazaBlockEntity;
import dev.civilizeandgovern.city.City;
import dev.civilizeandgovern.city.CityManager;
import dev.civilizeandgovern.city.CityPermission;
import dev.civilizeandgovern.zone.CagZones;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.net.Channel;
import dev.minedraft.lib.server.AreaManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.jetbrains.annotations.Nullable;

/** 서버가 받는 메시지. 모든 조작은 권한 검사를 거친다 (불변식 9). */
public final class ServerNet {
    private ServerNet() {}

    public static void receive(ServerPlayer player, Channel.Frame frame) {
        try {
            dispatch(player, frame.kind(), frame.buf());
        } catch (Exception e) {
            CaG.LOGGER.error("CaG: failed to handle message {} from {}", frame.kind(), player.getGameProfile().getName(), e);
        }
    }

    private static void dispatch(ServerPlayer player, int kind, FriendlyByteBuf buf) {
        switch (kind) {
            case Msg.C_FOUND -> found(player, buf.readBlockPos(), buf.readUtf(64), buf.readUtf(64));
            case Msg.C_PANEL -> {
                City c = city(player, buf.readVarInt(), null);
                if (c != null) sendPanel(player, c, false);
            }
            case Msg.C_OPEN_EDITOR -> {
                City c = city(player, buf.readVarInt(), null);
                if (c == null) return;
                Area a = CityManager.get().areaOf(c);
                if (a == null) return;
                CagZones.CHUNK_CAPS.put(a.id, CityManager.maxChunks(c));
                sendPanel(player, c, false); // 클라이언트의 청크 상한을 먼저 맞춘다
                AreaManager.get().openEditor(player, a, c.plazaPos);
            }
            case Msg.C_ISSUE -> {
                City c = city(player, buf.readVarInt(), CityPermission.ISSUE);
                long amount = buf.readVarLong();
                if (c == null || amount <= 0 || amount > 1_000_000_000L) return;
                c.issue(amount);
                CityManager.get().setDirty();
                sendPanel(player, c, false);
            }
            case Msg.C_FORCE_LOAD -> {
                City c = city(player, buf.readVarInt(), CityPermission.FORCE_LOAD);
                boolean on = buf.readBoolean();
                if (c == null) return;
                c.forceLoad = on;
                CityManager.get().setDirty();
                sendPanel(player, c, false);
            }
            case Msg.C_COUNCIL -> {
                City c = city(player, buf.readVarInt(), CityPermission.COUNCIL);
                String name = buf.readUtf(32);
                int bits = buf.readVarInt() & CityPermission.ALL & ~CityPermission.COUNCIL.bit();
                if (c == null) return;
                ServerPlayer target = player.server.getPlayerList().getPlayerByName(name);
                if (target == null) {
                    toast(player, "civilizeandgovern.msg.player_offline");
                    return;
                }
                if (target.getUUID().equals(c.founder)) return;
                if (bits == 0) c.council.remove(target.getUUID());
                else c.council.put(target.getUUID(), bits);
                CityManager.get().setDirty();
                sendPanel(player, c, false);
            }
            default -> CaG.LOGGER.warn("CaG: unknown message {}", kind);
        }
    }

    /** 도시를 찾고 권한을 검사한다. need 가 null 이면 시장·의원이기만 하면 된다. */
    @Nullable
    private static City city(ServerPlayer player, int id, @Nullable CityPermission need) {
        City c = CityManager.get().byId(id);
        if (c == null) return null;
        boolean ok = need == null ? CityPermission.isMember(c, player.getUUID()) : CityPermission.has(c, player.getUUID(), need);
        if (!ok) {
            toast(player, "civilizeandgovern.msg.no_permission");
            return null;
        }
        return c;
    }

    private static void found(ServerPlayer player, BlockPos pos, String rawName, String rawCurrency) {
        ServerLevel level = player.serverLevel();
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64 * 64) return;
        if (!(level.getBlockEntity(pos) instanceof PlazaBlockEntity plaza) || plaza.cityId() > 0) return;
        String name = clean(rawName, 32), currency = clean(rawCurrency, 16);
        if (name.isEmpty() || currency.isEmpty()) {
            toast(player, "civilizeandgovern.msg.name_required");
            return;
        }
        CityManager m = CityManager.get();
        if (m.cityAt(level.dimension(), new ChunkPos(pos).toLong()) != null) {
            toast(player, "civilizeandgovern.msg.chunk_taken");
            return;
        }
        City c = m.found(level, pos, player.getUUID(), name, currency);
        plaza.setCity(c.id);
        CagZones.CHUNK_CAPS.put(c.areaId, CityManager.maxChunks(c));
        sendPanel(player, c, true);
    }

    private static String clean(String s, int max) {
        String t = s.strip().replaceAll("[\\p{Cntrl}]", "");
        return t.length() > max ? t.substring(0, max) : t;
    }

    /** 도시 패널 자료를 보낸다. open = 화면을 새로 연다 (false 면 열려 있을 때만 갱신). */
    public static void sendPanel(ServerPlayer player, City c, boolean open) {
        CityView view = CityView.of(c, player);
        Net.toPlayer(player, Msg.S_PANEL, buf -> {
            buf.writeBoolean(open);
            view.write(buf);
        });
    }

    public static void toast(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }
}
