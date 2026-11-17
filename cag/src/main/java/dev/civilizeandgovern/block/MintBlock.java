package dev.civilizeandgovern.block;

import dev.civilizeandgovern.net.ServerNet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 주조대 (명세 09-blocks.md, D-56): 화폐 발행에는 재료가 든다.
 * 구리 주괴를 들고 누르면 넣고, 빈손으로 누르면 주조 화면이 뜬다. 주조하면 넣어 둔 구리가 줄고 금고 잔고가 오른다.
 */
public class MintBlock extends Block implements EntityBlock {
    public MintBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MintBlockEntity(pos, state);
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Items.COPPER_INGOT)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof MintBlockEntity mint) {
            int added = mint.add(stack.getCount());
            if (added > 0) {
                stack.shrink(added);
                level.playSound(null, pos, SoundEvents.COPPER_PLACE, SoundSource.BLOCKS, 0.8F, 1.0F);
            }
            player.displayClientMessage(Component.translatable("civilizeandgovern.msg.mint_stored", mint.copper()), true);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp) ServerNet.sendMint(sp, pos, true);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** 부서지면 넣어 둔 구리를 떨군다. */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof MintBlockEntity mint) {
            int n = mint.copper();
            mint.take(n);
            while (n > 0) {
                int k = Math.min(64, n);
                Block.popResource(level, pos, new ItemStack(Items.COPPER_INGOT, k));
                n -= k;
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
