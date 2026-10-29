package dev.designandbuild.block;

import dev.designandbuild.Registration;
import dev.designandbuild.core.BlueprintModel;
import dev.designandbuild.item.BlueprintItem;
import dev.designandbuild.item.BlueprintRef;
import dev.designandbuild.menu.BlueprintTableMenu;
import dev.designandbuild.server.BlueprintManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** 도면 테이블의 주 칸. 도면 한 장을 올려 둔다. */
public class BlueprintTableBlockEntity extends BlockEntity implements MenuProvider {
    private ItemStack blueprint = ItemStack.EMPTY;

    public BlueprintTableBlockEntity(BlockPos pos, BlockState state) {
        super(Registration.TABLE_BE.get(), pos, state);
    }

    /** 테이블 어느 칸을 주든 주 칸의 블록 엔티티를 찾는다. */
    @Nullable
    public static BlueprintTableBlockEntity at(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof BlueprintTableBlock)) return null;
        BlockPos main = BlueprintTableBlock.mainPos(pos, state);
        return level.getBlockEntity(main) instanceof BlueprintTableBlockEntity be ? be : null;
    }

    public ItemStack blueprint() {
        return blueprint;
    }

    public void setBlueprint(ItemStack stack) {
        this.blueprint = stack;
        onContentsChanged();
    }

    /** 블록이 사라질 때 떨굴 도면을 꺼낸다 (알림 없이). */
    ItemStack takeForRemoval() {
        ItemStack s = blueprint;
        blueprint = ItemStack.EMPTY;
        return s;
    }

    @Nullable
    public UUID blueprintId() {
        BlueprintRef ref = blueprint.get(Registration.BLUEPRINT_REF.get());
        return ref == null ? null : ref.id();
    }

    public void syncBlueprintName(BlueprintModel m) {
        BlueprintRef ref = blueprint.get(Registration.BLUEPRINT_REF.get());
        if (ref != null && !ref.name().equals(m.name())) {
            blueprint.set(Registration.BLUEPRINT_REF.get(), ref.withName(m.name()));
            setChanged();
        }
    }

    private void onContentsChanged() {
        setChanged();
        if (level == null || level.isClientSide) return;
        BlockState state = getBlockState();
        boolean has = !blueprint.isEmpty();
        if (state.hasProperty(BlueprintTableBlock.HAS_BLUEPRINT) && state.getValue(BlueprintTableBlock.HAS_BLUEPRINT) != has) {
            level.setBlock(worldPosition, state.setValue(BlueprintTableBlock.HAS_BLUEPRINT, has), 3);
        }
        BlueprintManager manager = BlueprintManager.get();
        if (manager != null) manager.onTableChanged(level, worldPosition, blueprintId());
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!blueprint.isEmpty()) tag.put("blueprint", blueprint.save(registries));
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        blueprint = tag.contains("blueprint") ? ItemStack.parseOptional(registries, tag.getCompound("blueprint")) : ItemStack.EMPTY;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.designandbuild.blueprint_table");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new BlueprintTableMenu(id, inventory, this);
    }

    /** 메뉴의 도면 칸 (크기 1). */
    public Container container() {
        return new Container() {
            @Override
            public int getContainerSize() {
                return 1;
            }

            @Override
            public boolean isEmpty() {
                return blueprint.isEmpty();
            }

            @Override
            public ItemStack getItem(int slot) {
                return blueprint;
            }

            @Override
            public ItemStack removeItem(int slot, int amount) {
                if (blueprint.isEmpty() || amount <= 0) return ItemStack.EMPTY;
                ItemStack out = blueprint.split(amount);
                onContentsChanged();
                return out;
            }

            @Override
            public ItemStack removeItemNoUpdate(int slot) {
                ItemStack out = blueprint;
                blueprint = ItemStack.EMPTY;
                onContentsChanged();
                return out;
            }

            @Override
            public void setItem(int slot, ItemStack stack) {
                blueprint = stack;
                onContentsChanged();
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public void setChanged() {
                BlueprintTableBlockEntity.this.setChanged();
            }

            @Override
            public boolean stillValid(Player player) {
                return !isRemoved() && Container.stillValidBlockEntity(BlueprintTableBlockEntity.this, player);
            }

            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return stack.getItem() instanceof BlueprintItem;
            }

            @Override
            public void clearContent() {
                blueprint = ItemStack.EMPTY;
                onContentsChanged();
            }
        };
    }
}
