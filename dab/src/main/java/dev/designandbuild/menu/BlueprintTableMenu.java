package dev.designandbuild.menu;

import dev.designandbuild.Registration;
import dev.designandbuild.block.BlueprintTableBlockEntity;
import dev.designandbuild.item.BlueprintItem;
import dev.designandbuild.item.BlueprintRef;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 도면 테이블 화면.
 * <pre>
 * 칸 0: 도면 (테이블에 올려 둔 것)
 * 칸 1: 복사용 빈 도면  →  칸 2: 연동 사본 (같은 설계 ID)
 * 칸 3~38: 플레이어 인벤토리
 * </pre>
 */
public class BlueprintTableMenu extends AbstractContainerMenu {
    public static final int WIDTH = 196, HEIGHT = 214;
    public static final int BLUEPRINT_X = 12, BLUEPRINT_Y = 24;
    public static final int COPY_IN_X = 122, COPY_IN_Y = 112;
    public static final int COPY_OUT_X = 170, COPY_OUT_Y = 112;
    public static final int INV_X = 18, INV_Y = 136;

    private final Container table;
    private final SimpleContainer copyIn;
    private final ResultContainer copyOut = new ResultContainer();
    private final BlockPos pos;
    private final ContainerLevelAccess access;

    public static BlueprintTableMenu fromNetwork(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        return new BlueprintTableMenu(id, inventory, new SimpleContainer(1), buf.readBlockPos(), ContainerLevelAccess.NULL);
    }

    public BlueprintTableMenu(int id, Inventory inventory, BlueprintTableBlockEntity table) {
        this(id, inventory, table.container(), table.getBlockPos(),
                ContainerLevelAccess.create(table.getLevel(), table.getBlockPos()));
    }

    private BlueprintTableMenu(int id, Inventory inventory, Container table, BlockPos pos, ContainerLevelAccess access) {
        super(Registration.TABLE_MENU.get(), id);
        this.table = table;
        this.pos = pos;
        this.access = access;
        this.copyIn = new SimpleContainer(1) {
            @Override
            public void setChanged() {
                super.setChanged();
                BlueprintTableMenu.this.slotsChanged(this);
            }
        };

        addSlot(new Slot(table, 0, BLUEPRINT_X, BLUEPRINT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof BlueprintItem;
            }

            @Override
            public int getMaxStackSize() {
                return 1;
            }

            @Override
            public void setChanged() {
                super.setChanged();
                BlueprintTableMenu.this.updateCopy();
            }
        });
        addSlot(new Slot(copyIn, 0, COPY_IN_X, COPY_IN_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return BlueprintItem.isEmptyBlueprint(stack);
            }
        });
        addSlot(new Slot(copyOut, 0, COPY_OUT_X, COPY_OUT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return false;
            }

            @Override
            public void onTake(Player player, ItemStack stack) {
                copyIn.removeItem(0, 1);
                BlueprintTableMenu.this.updateCopy();
                super.onTake(player, stack);
            }
        });

        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 9; c++) {
                addSlot(new Slot(inventory, c + r * 9 + 9, INV_X + c * 18, INV_Y + r * 18));
            }
        }
        for (int c = 0; c < 9; c++) {
            addSlot(new Slot(inventory, c, INV_X + c * 18, INV_Y + 58));
        }
    }

    public BlockPos tablePos() {
        return pos;
    }

    public ItemStack blueprint() {
        return table.getItem(0);
    }

    @Override
    public void slotsChanged(Container container) {
        super.slotsChanged(container);
        updateCopy();
    }

    private void updateCopy() {
        ItemStack source = table.getItem(0);
        ItemStack input = copyIn.getItem(0);
        BlueprintRef ref = source.get(Registration.BLUEPRINT_REF.get());
        if (ref != null && BlueprintItem.isEmptyBlueprint(input)) {
            ItemStack out = new ItemStack(Registration.BLUEPRINT.get());
            out.set(Registration.BLUEPRINT_REF.get(), ref);
            copyOut.setItem(0, out);
        } else {
            copyOut.setItem(0, ItemStack.EMPTY);
        }
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        access.execute((level, p) -> clearContainer(player, copyIn));
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, Registration.TABLE.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int invStart = 3, invEnd = 39;
        if (index == 2) {
            if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY;
            slot.onQuickCraft(stack, original);
        } else if (index < invStart) {
            if (!moveItemStackTo(stack, invStart, invEnd, true)) return ItemStack.EMPTY;
        } else if (stack.getItem() instanceof BlueprintItem) {
            boolean moved = false;
            if (!slots.get(0).hasItem()) moved = moveItemStackTo(stack, 0, 1, false);
            if (!moved && BlueprintItem.isEmptyBlueprint(stack)) moved = moveItemStackTo(stack, 1, 2, false);
            if (!moved) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }
}
