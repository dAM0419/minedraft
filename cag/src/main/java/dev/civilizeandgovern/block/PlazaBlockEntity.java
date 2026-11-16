package dev.civilizeandgovern.block;

import dev.civilizeandgovern.Registration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 광장 블록의 자료: 이 블록이 세운 도시의 id (0 = 아직 도시가 없다). */
public class PlazaBlockEntity extends BlockEntity {
    private int cityId;

    public PlazaBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.PLAZA_BE.get(), pos, state);
    }

    public int cityId() {
        return cityId;
    }

    public void setCity(int id) {
        cityId = id;
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("city", cityId);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        cityId = tag.getInt("city");
    }
}
