package dev.designandbuild.server;

import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.core.ModelCodec;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.saveddata.SavedData;

/** 도면 하나 = 저장 파일 하나 (data/designandbuild_&lt;id&gt;.dat). 바뀐 도면만 다시 저장된다. */
public final class BlueprintStorage extends SavedData {
    private final BlueprintModel model;

    public BlueprintStorage(BlueprintModel model) {
        this.model = model;
    }

    public BlueprintModel model() {
        return model;
    }

    public static BlueprintStorage load(CompoundTag tag, HolderLookup.Provider registries) {
        return new BlueprintStorage(ModelCodec.fromNbt(tag.getCompound("model")));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("model", ModelCodec.toNbt(model));
        return tag;
    }

    public static SavedData.Factory<BlueprintStorage> factory() {
        return new SavedData.Factory<>(() -> {
            throw new IllegalStateException("Blueprint data must be created explicitly");
        }, BlueprintStorage::load, null);
    }

    public static String fileName(java.util.UUID id) {
        return "designandbuild_" + id;
    }
}
