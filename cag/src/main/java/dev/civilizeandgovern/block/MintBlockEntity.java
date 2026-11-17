package dev.civilizeandgovern.block;

import dev.civilizeandgovern.Registration;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** 주조대의 자료: 넣어 둔 구리 주괴 수. */
public class MintBlockEntity extends BlockEntity {
    /** 넣어 둘 수 있는 구리 주괴의 상한 (27묶음). */
    public static final int CAPACITY = 64 * 27;
    private int copper;

    public MintBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.MINT_BE.get(), pos, state);
    }

    public int copper() {
        return copper;
    }

    /** 넣는다. 돌려주는 값: 실제로 들어간 수. */
    public int add(int n) {
        int k = Math.max(0, Math.min(n, CAPACITY - copper));
        copper += k;
        if (k > 0) setChanged();
        return k;
    }

    public void take(int n) {
        copper = Math.max(0, copper - n);
        setChanged();
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("copper", copper);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        copper = tag.getInt("copper");
    }
}
