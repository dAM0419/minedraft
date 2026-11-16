package dev.civilizeandgovern.net;

import dev.civilizeandgovern.city.City;
import dev.civilizeandgovern.city.CityManager;
import dev.civilizeandgovern.city.CityPermission;
import dev.minedraft.lib.area.Area;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 클라이언트로 가는 도시 자료. 공개 필드만 가진다 (불변식 3: 숨은 자료는 어떤 패킷에도 싣지 않는다).
 */
public record CityView(int id, UUID areaId, String name, String currency, String founderName, long cityDay,
                       long treasury, long issuedTotal, double priceAnchor, int level, int population,
                       int chunks, int maxChunks, boolean forceLoad, boolean active, int myPermissions,
                       List<Member> council) {

    public record Member(String name, int permissions) {}

    public static CityView of(City c, ServerPlayer viewer) {
        MinecraftServer server = viewer.server;
        Area a = CityManager.get().areaOf(c);
        List<Member> members = new ArrayList<>();
        for (Map.Entry<UUID, Integer> e : c.council.entrySet()) members.add(new Member(nameOf(server, e.getKey()), e.getValue()));
        return new CityView(c.id, c.areaId, c.name, c.currency, nameOf(server, c.founder), c.cityDay, c.treasury, c.issuedTotal,
                c.priceAnchor, c.level, c.population, a == null ? 0 : a.chunks().size(), CityManager.maxChunks(c),
                c.forceLoad, c.active, CityPermission.bits(c, viewer.getUUID()), members);
    }

    private static String nameOf(MinecraftServer server, UUID id) {
        ServerPlayer p = server.getPlayerList().getPlayer(id);
        if (p != null) return p.getGameProfile().getName();
        return server.getProfileCache() == null ? id.toString().substring(0, 8)
                : server.getProfileCache().get(id).map(com.mojang.authlib.GameProfile::getName).orElse(id.toString().substring(0, 8));
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(id);
        buf.writeUUID(areaId);
        buf.writeUtf(name);
        buf.writeUtf(currency);
        buf.writeUtf(founderName);
        buf.writeVarLong(cityDay);
        buf.writeVarLong(treasury);
        buf.writeVarLong(issuedTotal);
        buf.writeDouble(priceAnchor);
        buf.writeVarInt(level);
        buf.writeVarInt(population);
        buf.writeVarInt(chunks);
        buf.writeVarInt(maxChunks);
        buf.writeBoolean(forceLoad);
        buf.writeBoolean(active);
        buf.writeVarInt(myPermissions);
        buf.writeVarInt(council.size());
        for (Member m : council) {
            buf.writeUtf(m.name());
            buf.writeVarInt(m.permissions());
        }
    }

    public static CityView read(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        UUID area = buf.readUUID();
        String name = buf.readUtf(), currency = buf.readUtf(), founder = buf.readUtf();
        long day = buf.readVarLong(), treasury = buf.readVarLong(), issued = buf.readVarLong();
        double p = buf.readDouble();
        int level = buf.readVarInt(), pop = buf.readVarInt(), chunks = buf.readVarInt(), max = buf.readVarInt();
        boolean force = buf.readBoolean(), active = buf.readBoolean();
        int perms = buf.readVarInt();
        int n = buf.readVarInt();
        List<Member> council = new ArrayList<>();
        for (int i = 0; i < n && i < 256; i++) council.add(new Member(buf.readUtf(), buf.readVarInt()));
        return new CityView(id, area, name, currency, founder, day, treasury, issued, p, level, pop, chunks, max, force, active, perms, council);
    }

    public boolean can(CityPermission p) {
        return (myPermissions & p.bit()) != 0;
    }
}
