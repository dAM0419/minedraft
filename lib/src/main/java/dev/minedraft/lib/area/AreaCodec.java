package dev.minedraft.lib.area;

import dev.minedraft.lib.util.CellIO;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.UUID;

/** 영역 전체를 통신·저장용으로 바꾼다. 층 내용은 각 층이 직접 쓴다. */
public final class AreaCodec {
    private AreaCodec() {}

    public static void write(FriendlyByteBuf buf, Area a) {
        buf.writeUUID(a.id);
        buf.writeResourceLocation(a.type.id());
        buf.writeUtf(a.name());
        buf.writeResourceLocation(a.dimension.location());
        buf.writeVarInt(a.baseX);
        buf.writeVarInt(a.baseZ);
        buf.writeVarInt(a.minY);
        buf.writeVarInt(a.maxY);
        buf.writeVarLong(a.version);
        CellIO.writePositions(buf, a.chunks().toLongArray());
        for (AreaLayer l : a.layers()) l.writeNet(buf);
    }

    public static Area read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        AreaType type = AreaType.get(buf.readResourceLocation());
        String name = buf.readUtf();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
        int baseX = buf.readVarInt(), baseZ = buf.readVarInt(), minY = buf.readVarInt(), maxY = buf.readVarInt();
        Area a = new Area(id, type, name, dim, baseX, baseZ, minY, maxY);
        a.version = buf.readVarLong();
        for (long c : CellIO.readPositions(buf, type.maxChunks() * 4 + 16)) a.loadChunk(c);
        for (AreaLayer l : a.layers()) l.readNet(buf);
        a.afterLoad();
        return a;
    }

    public static CompoundTag toNbt(Area a) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", a.id);
        tag.putString("type", a.type.id().toString());
        tag.putString("name", a.name());
        tag.putString("dim", a.dimension.location().toString());
        tag.putInt("baseX", a.baseX);
        tag.putInt("baseZ", a.baseZ);
        tag.putInt("minY", a.minY);
        tag.putInt("maxY", a.maxY);
        tag.putLong("version", a.version);
        tag.putLongArray("chunks", a.chunks().toLongArray());
        CompoundTag layers = new CompoundTag();
        for (AreaLayer l : a.layers()) layers.put(l.id().toString(), l.save());
        tag.put("layers", layers);
        return tag;
    }

    public static Area fromNbt(CompoundTag tag) {
        AreaType type = AreaType.get(ResourceLocation.parse(tag.getString("type")));
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dim")));
        Area a = new Area(tag.getUUID("id"), type, tag.getString("name"), dim, tag.getInt("baseX"), tag.getInt("baseZ"),
                tag.getInt("minY"), tag.getInt("maxY"));
        a.version = tag.getLong("version");
        for (long c : tag.getLongArray("chunks")) a.loadChunk(c);
        CompoundTag layers = tag.getCompound("layers");
        for (AreaLayer l : a.layers()) l.load(layers.getCompound(l.id().toString()));
        a.afterLoad();
        return a;
    }
}
