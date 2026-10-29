package dev.designandbuild.core;

import dev.minedraft.lib.util.CellIO;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 도면 전체를 네트워크·저장용으로 바꾼다. */
public final class ModelCodec {
    private ModelCodec() {}

    // ------------------------------------------------------------------
    // 네트워크
    // ------------------------------------------------------------------

    public static void write(FriendlyByteBuf buf, BlueprintModel m) {
        buf.writeUUID(m.id);
        buf.writeUtf(m.name);
        buf.writeUtf(m.dimension.location().toString());
        buf.writeVarInt(m.baseX);
        buf.writeVarInt(m.baseZ);
        buf.writeVarInt(m.minY);
        buf.writeVarInt(m.maxY);
        buf.writeVarInt(m.nextStructureId);
        buf.writeVarLong(m.version);

        long[] chunks = m.chunks().toLongArray();
        CellIO.writePositions(buf, chunks);

        Long2ObjectMap<TerrainCell> terrain = m.terrain();
        long[] pos = new long[terrain.size()];
        BlockState[] states = new BlockState[pos.length];
        DesignTag[] tags = new DesignTag[pos.length];
        int i = 0;
        for (Long2ObjectMap.Entry<TerrainCell> e : terrain.long2ObjectEntrySet()) {
            pos[i] = e.getLongKey();
            states[i] = e.getValue().state();
            tags[i] = e.getValue().tag();
            i++;
        }
        CellIO.writePositions(buf, pos);
        CellIO.writeStates(buf, states);
        DesignTag.writeAll(buf, tags);

        List<StructureInstance> list = m.orderedStructures();
        buf.writeVarInt(list.size());
        for (StructureInstance s : list) {
            buf.writeVarInt(s.id);
            buf.writeUtf(s.name);
            buf.writeUtf(s.source);
            CellIO.writeDirection(buf, s.mount);
            CellIO.writeRotation(buf, s.rotation);
            CellIO.writeMirror(buf, s.mirror);
            long[] sp = new long[s.blocks.size()];
            BlockState[] ss = new BlockState[sp.length];
            int j = 0;
            for (Long2ObjectMap.Entry<BlockState> e : s.blocks.long2ObjectEntrySet()) {
                sp[j] = e.getLongKey();
                ss[j] = e.getValue();
                j++;
            }
            CellIO.writePositions(buf, sp);
            CellIO.writeStates(buf, ss);
        }
    }

    public static BlueprintModel read(FriendlyByteBuf buf) {
        UUID id = buf.readUUID();
        String name = buf.readUtf();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(buf.readUtf()));
        int baseX = buf.readVarInt();
        int baseZ = buf.readVarInt();
        int minY = buf.readVarInt();
        int maxY = buf.readVarInt();
        BlueprintModel m = new BlueprintModel(id, name, dim, baseX, baseZ, minY, maxY);
        m.nextStructureId = buf.readVarInt();
        m.version = buf.readVarLong();

        for (long c : CellIO.readPositions(buf, BlueprintModel.MAX_CHUNKS * 4)) m.loadChunk(c);

        long[] pos = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
        BlockState[] states = CellIO.readStates(buf, pos.length);
        DesignTag[] tags = DesignTag.readAll(buf, pos.length);
        for (int i = 0; i < pos.length; i++) {
            if (states[i] != null) m.loadTerrain(pos[i], new TerrainCell(states[i], tags[i]));
        }

        int count = buf.readVarInt();
        for (int i = 0; i < count; i++) {
            int sid = buf.readVarInt();
            String sname = buf.readUtf();
            String source = buf.readUtf();
            Direction mount = CellIO.readDirection(buf);
            Rotation rot = CellIO.readRotation(buf);
            Mirror mirror = CellIO.readMirror(buf);
            StructureInstance s = new StructureInstance(sid, sname, source, mount, rot, mirror);
            long[] sp = CellIO.readPositions(buf, ModelOps.MAX_CELLS);
            BlockState[] ss = CellIO.readStates(buf, sp.length);
            for (int j = 0; j < sp.length; j++) {
                if (ss[j] != null) s.blocks.put(sp[j], ss[j]);
            }
            m.loadStructure(s);
        }
        m.rebuildDerived();
        return m;
    }

    // ------------------------------------------------------------------
    // 저장 (NBT)
    // ------------------------------------------------------------------

    public static CompoundTag toNbt(BlueprintModel m) {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", m.id);
        tag.putString("name", m.name);
        tag.putString("dim", m.dimension.location().toString());
        tag.putInt("baseX", m.baseX);
        tag.putInt("baseZ", m.baseZ);
        tag.putInt("minY", m.minY);
        tag.putInt("maxY", m.maxY);
        tag.putInt("nextId", m.nextStructureId);
        tag.putLong("version", m.version);
        tag.putLongArray("chunks", m.chunks().toLongArray());

        Palette palette = new Palette();

        Long2ObjectMap<TerrainCell> terrain = m.terrain();
        long[] pos = new long[terrain.size()];
        int[] st = new int[pos.length];
        byte[] tg = new byte[pos.length];
        int i = 0;
        for (Long2ObjectMap.Entry<TerrainCell> e : terrain.long2ObjectEntrySet()) {
            pos[i] = e.getLongKey();
            st[i] = palette.id(e.getValue().state());
            tg[i] = (byte) e.getValue().tag().ordinal();
            i++;
        }
        CompoundTag t = new CompoundTag();
        t.putLongArray("pos", pos);
        t.putIntArray("state", st);
        t.putByteArray("tag", tg);
        tag.put("terrain", t);

        ListTag structs = new ListTag();
        for (StructureInstance s : m.orderedStructures()) {
            CompoundTag c = new CompoundTag();
            c.putInt("id", s.id);
            c.putString("name", s.name);
            c.putString("source", s.source);
            c.putInt("mount", s.mount.get3DDataValue());
            c.putInt("rot", s.rotation.ordinal());
            c.putInt("mirror", s.mirror.ordinal());
            long[] sp = new long[s.blocks.size()];
            int[] ss = new int[sp.length];
            int j = 0;
            for (Long2ObjectMap.Entry<BlockState> e : s.blocks.long2ObjectEntrySet()) {
                sp[j] = e.getLongKey();
                ss[j] = palette.id(e.getValue());
                j++;
            }
            c.putLongArray("pos", sp);
            c.putIntArray("state", ss);
            structs.add(c);
        }
        tag.put("structures", structs);

        ListTag pal = new ListTag();
        for (BlockState s : palette.states) pal.add(NbtUtils.writeBlockState(s));
        tag.put("palette", pal);
        return tag;
    }

    public static BlueprintModel fromNbt(CompoundTag tag) {
        UUID id = tag.getUUID("id");
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dim")));
        BlueprintModel m = new BlueprintModel(id, tag.getString("name"), dim, tag.getInt("baseX"), tag.getInt("baseZ"),
                tag.getInt("minY"), tag.getInt("maxY"));
        m.nextStructureId = Math.max(1, tag.getInt("nextId"));
        m.version = tag.getLong("version");
        for (long c : tag.getLongArray("chunks")) m.loadChunk(c);

        ListTag pal = tag.getList("palette", Tag.TAG_COMPOUND);
        BlockState[] palette = new BlockState[pal.size()];
        for (int i = 0; i < pal.size(); i++) {
            palette[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), pal.getCompound(i));
        }

        CompoundTag t = tag.getCompound("terrain");
        long[] pos = t.getLongArray("pos");
        int[] st = t.getIntArray("state");
        byte[] tg = t.getByteArray("tag");
        int n = Math.min(pos.length, Math.min(st.length, tg.length));
        for (int i = 0; i < n; i++) {
            m.loadTerrain(pos[i], new TerrainCell(stateAt(palette, st[i]), DesignTag.byId(tg[i])));
        }

        ListTag structs = tag.getList("structures", Tag.TAG_COMPOUND);
        for (int i = 0; i < structs.size(); i++) {
            CompoundTag c = structs.getCompound(i);
            Rotation[] rots = Rotation.values();
            Mirror[] mirrors = Mirror.values();
            int r = c.getInt("rot"), mi = c.getInt("mirror");
            StructureInstance s = new StructureInstance(c.getInt("id"), c.getString("name"), c.getString("source"),
                    Direction.from3DDataValue(c.getInt("mount")),
                    r >= 0 && r < rots.length ? rots[r] : Rotation.NONE,
                    mi >= 0 && mi < mirrors.length ? mirrors[mi] : Mirror.NONE);
            long[] sp = c.getLongArray("pos");
            int[] ss = c.getIntArray("state");
            int sn = Math.min(sp.length, ss.length);
            for (int j = 0; j < sn; j++) s.blocks.put(sp[j], stateAt(palette, ss[j]));
            m.loadStructure(s);
        }
        m.rebuildDerived();
        return m;
    }

    private static BlockState stateAt(BlockState[] palette, int index) {
        return index >= 0 && index < palette.length ? palette[index] : Blocks.AIR.defaultBlockState();
    }

    private static final class Palette {
        final List<BlockState> states = new ArrayList<>();
        final Object2IntOpenHashMap<BlockState> ids = new Object2IntOpenHashMap<>();

        Palette() {
            ids.defaultReturnValue(-1);
        }

        int id(BlockState s) {
            int i = ids.getInt(s);
            if (i < 0) {
                i = states.size();
                states.add(s);
                ids.put(s, i);
            }
            return i;
        }
    }
}
