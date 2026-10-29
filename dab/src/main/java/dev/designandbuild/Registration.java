package dev.designandbuild;

import dev.designandbuild.block.BlueprintTableBlock;
import dev.designandbuild.block.BlueprintTableBlockEntity;
import dev.designandbuild.item.BlueprintItem;
import dev.designandbuild.item.BlueprintRef;
import dev.designandbuild.item.HammerItem;
import dev.designandbuild.menu.BlueprintTableMenu;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class Registration {
    private Registration() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(DesignAndBuild.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(DesignAndBuild.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, DesignAndBuild.MODID);
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, DesignAndBuild.MODID);
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, DesignAndBuild.MODID);

    /** 도면이 가리키는 설계 (없으면 빈 도면). */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<BlueprintRef>> BLUEPRINT_REF =
            COMPONENTS.register("blueprint", () -> DataComponentType.<BlueprintRef>builder()
                    .persistent(BlueprintRef.CODEC)
                    .networkSynchronized(BlueprintRef.STREAM_CODEC)
                    .build());

    public static final DeferredBlock<BlueprintTableBlock> TABLE = BLOCKS.register("blueprint_table",
            () -> new BlueprintTableBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.WOOD)
                    .strength(2.0F, 3.0F)
                    .sound(SoundType.WOOD)
                    .noOcclusion()
                    .ignitedByLava()));

    public static final DeferredItem<BlockItem> TABLE_ITEM = ITEMS.register("blueprint_table",
            () -> new BlockItem(TABLE.get(), new Item.Properties()));

    public static final DeferredItem<BlueprintItem> BLUEPRINT = ITEMS.register("blueprint",
            () -> new BlueprintItem(new Item.Properties().stacksTo(16)));

    public static final DeferredItem<HammerItem> HAMMER = ITEMS.register("construction_hammer",
            () -> new HammerItem(new Item.Properties().stacksTo(1)));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BlueprintTableBlockEntity>> TABLE_BE =
            BLOCK_ENTITIES.register("blueprint_table",
                    () -> BlockEntityType.Builder.of(BlueprintTableBlockEntity::new, TABLE.get()).build(null));

    public static final DeferredHolder<MenuType<?>, MenuType<BlueprintTableMenu>> TABLE_MENU =
            MENUS.register("blueprint_table", () -> IMenuTypeExtension.create(BlueprintTableMenu::fromNetwork));

    static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
        MENUS.register(modBus);
    }
}
