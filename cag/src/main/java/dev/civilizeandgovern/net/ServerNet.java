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
            case Msg.C_MINT -> mint(player, buf.readBlockPos(), buf.readVarInt());
            case Msg.C_MINT_TAKE -> mintTake(player, buf.readBlockPos());
            case Msg.C_MOVE_PLAZA -> movePlaza(player, buf.readBlockPos());
            case Msg.C_ALLOW_DISSOLVE -> {
                City c = city(player, buf.readVarInt(), CityPermission.COUNCIL); // COUNCIL 은 시장만 가진다
                boolean on = buf.readBoolean();
                if (c == null) return;
                if (on && c.population > 0) {
                    toast(player, "civilizeandgovern.msg.plaza_has_citizens");
                    return;
                }
                c.allowDissolve = on;
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

    /**
     * 광장 옮기기 (D-55): 시장이 자기 도시의 관리 청크 안에 새 광장 블록을 놓고 지정하면,
     * 옛 광장 블록은 아이템으로 떨어지고 새 블록이 도시의 광장 블록이 된다.
     */
    private static void movePlaza(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64 * 64) return;
        if (!(level.getBlockEntity(pos) instanceof PlazaBlockEntity plaza) || plaza.cityId() > 0) return;
        CityManager m = CityManager.get();
        City c = m.cityAt(level.dimension(), new ChunkPos(pos).toLong());
        if (c == null) return;
        if (!player.getUUID().equals(c.founder)) {
            toast(player, "civilizeandgovern.msg.plaza_not_mayor");
            return;
        }
        BlockPos old = c.plazaPos;
        if (!old.equals(pos) && level.getBlockEntity(old) instanceof PlazaBlockEntity oldPlaza) {
            oldPlaza.setCity(0);
            level.destroyBlock(old, true);
        }
        plaza.setCity(c.id);
        c.plazaPos = pos.immutable();
        m.setDirty();
        toast(player, "civilizeandgovern.msg.plaza_moved");
        sendPanel(player, c, true);
    }

    /** 주조 (D-56): 주조대에 넣어 둔 구리 주괴를 녹여 그만큼 발행한다. 금고 잔고와 발행 누계가 같이 오른다. */
    private static void mint(ServerPlayer player, BlockPos pos, int ingots) {
        ServerLevel level = player.serverLevel();
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64 * 64) return;
        if (!(level.getBlockEntity(pos) instanceof dev.civilizeandgovern.block.MintBlockEntity mint)) return;
        City c = CityManager.get().cityAt(level.dimension(), new ChunkPos(pos).toLong());
        if (c == null) {
            toast(player, "civilizeandgovern.msg.mint_outside");
            return;
        }
        if (!CityPermission.has(c, player.getUUID(), CityPermission.ISSUE)) {
            toast(player, "civilizeandgovern.msg.no_permission");
            return;
        }
        if (ingots <= 0 || ingots > mint.copper()) return;
        long amount = (long) ingots * dev.civilizeandgovern.config.Params.i("mint.coins_per_ingot");
        mint.take(ingots);
        c.issue(amount);
        CityManager.get().setDirty();
        level.playSound(null, pos, net.minecraft.sounds.SoundEvents.ANVIL_USE, net.minecraft.sounds.SoundSource.BLOCKS, 0.6F, 1.4F);
        sendMint(player, pos, false);
    }

    private static void mintTake(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.serverLevel();
        if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64 * 64) return;
        if (!(level.getBlockEntity(pos) instanceof dev.civilizeandgovern.block.MintBlockEntity mint)) return;
        City c = CityManager.get().cityAt(level.dimension(), new ChunkPos(pos).toLong());
        if (c != null && !CityPermission.isMember(c, player.getUUID())) {
            toast(player, "civilizeandgovern.msg.outsider");
            return;
        }
        int n = mint.copper();
        mint.take(n);
        while (n > 0) {
            int k = Math.min(64, n);
            player.getInventory().placeItemBackInInventory(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COPPER_INGOT, k));
            n -= k;
        }
        sendMint(player, pos, false);
    }

    /** 주조대 화면 자료를 보낸다. */
    public static void sendMint(ServerPlayer player, BlockPos pos, boolean open) {
        ServerLevel level = player.serverLevel();
        if (!(level.getBlockEntity(pos) instanceof dev.civilizeandgovern.block.MintBlockEntity mint)) return;
        City c = CityManager.get().cityAt(level.dimension(), new ChunkPos(pos).toLong());
        if (c == null) {
            toast(player, "civilizeandgovern.msg.mint_outside");
            return;
        }
        if (!CityPermission.isMember(c, player.getUUID())) {
            toast(player, "civilizeandgovern.msg.outsider");
            return;
        }
        MintView view = new MintView(pos, c.name, c.currency, mint.copper(), dev.civilizeandgovern.config.Params.i("mint.coins_per_ingot"),
                c.treasury, c.issuedTotal, CityPermission.has(c, player.getUUID(), CityPermission.ISSUE));
        Net.toPlayer(player, Msg.S_MINT, buf -> {
            buf.writeBoolean(open);
            view.write(buf);
        });
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
