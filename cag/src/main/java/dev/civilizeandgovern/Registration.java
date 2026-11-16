package dev.civilizeandgovern;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.civilizeandgovern.block.PlazaBlock;
import dev.civilizeandgovern.block.PlazaBlockEntity;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** 블록·아이템·블록 엔티티·데이터 컴포넌트 등록 (명세 01-architecture.md 2절, 09-blocks.md). */
public final class Registration {
    private Registration() {}

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(CaG.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(CaG.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, CaG.MODID);
    public static final DeferredRegister<DataComponentType<?>> COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, CaG.MODID);

    /** 동화가 어느 도시의 것인지. 다른 도시의 동화는 그 도시에서 통하지 않는다. */
    public record CoinData(int cityId, String currency) {
        public static final Codec<CoinData> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.fieldOf("city").forGetter(CoinData::cityId),
                Codec.STRING.fieldOf("currency").forGetter(CoinData::currency)
        ).apply(i, CoinData::new));
        public static final StreamCodec<io.netty.buffer.ByteBuf, CoinData> STREAM_CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, CoinData::cityId,
                ByteBufCodecs.STRING_UTF8, CoinData::currency,
                CoinData::new);
    }

    public static final DeferredHolder<DataComponentType<?>, DataComponentType<CoinData>> COIN_DATA =
            COMPONENTS.register("coin", () -> DataComponentType.<CoinData>builder()
                    .persistent(CoinData.CODEC)
                    .networkSynchronized(CoinData.STREAM_CODEC)
                    .build());

    public static final DeferredBlock<PlazaBlock> PLAZA = BLOCKS.register("plaza_block",
            () -> new PlazaBlock(BlockBehaviour.Properties.of()
                    .mapColor(MapColor.STONE)
                    .strength(3.0F, 1200.0F)
                    .sound(SoundType.STONE)
                    .requiresCorrectToolForDrops()));
    public static final DeferredItem<BlockItem> PLAZA_ITEM = ITEMS.register("plaza_block",
            () -> new BlockItem(PLAZA.get(), new Item.Properties()));

    // 동화 3종. 1단계에서는 등록만 한다 (인출·입금은 금고 블록이 생기는 4단계).
    public static final DeferredItem<Item> COIN = ITEMS.register("coin", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> COIN_10 = ITEMS.register("coin_10", () -> new Item(new Item.Properties()));
    public static final DeferredItem<Item> COIN_100 = ITEMS.register("coin_100", () -> new Item(new Item.Properties()));

    @SuppressWarnings("DataFlowIssue")
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<PlazaBlockEntity>> PLAZA_BE =
            BLOCK_ENTITIES.register("plaza_block",
                    () -> BlockEntityType.Builder.of(PlazaBlockEntity::new, PLAZA.get()).build(null));

    static void register(IEventBus modBus) {
        COMPONENTS.register(modBus);
        BLOCKS.register(modBus);
        ITEMS.register(modBus);
        BLOCK_ENTITIES.register(modBus);
    }
}
