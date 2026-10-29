package dev.minedraft.lib.server;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.UUID;

/** 영역 하나 = 저장 파일 하나 (data/minedraftlib_area_&lt;id&gt;.dat). 바뀐 영역만 다시 저장된다. */
public final class AreaStorage extends SavedData {
    private final Area area;

    public AreaStorage(Area area) {
        this.area = area;
    }

    public Area area() {
        return area;
    }

    public static AreaStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        return new AreaStorage(AreaCodec.fromNbt(tag.getCompound("area")));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("area", AreaCodec.toNbt(area));
        return tag;
    }

    public static SavedData.Factory<AreaStorage> factory() {
        return new SavedData.Factory<>(() -> {
            throw new IllegalStateException("Area data must be created explicitly");
        }, AreaStorage::load, null);
    }

    public static String fileName(UUID id) {
        return "minedraftlib_area_" + id;
    }
}
