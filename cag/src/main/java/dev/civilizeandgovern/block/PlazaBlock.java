package dev.civilizeandgovern.block;

import dev.civilizeandgovern.city.City;
import dev.civilizeandgovern.city.CityManager;
import dev.civilizeandgovern.city.CityPermission;
import dev.civilizeandgovern.net.Msg;
import dev.civilizeandgovern.net.Net;
import dev.civilizeandgovern.net.ServerNet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * 광장 블록: 도시의 시작점 (명세 09-blocks.md 1절).
 * 주인 없는 블록을 누르면 도시 세우기 화면, 도시가 있으면 도시 패널.
 * 부수는 규칙(시장만, 주민 0명)은 ServerEvents 의 블록 파괴 이벤트에서 본다.
 */
public class PlazaBlock extends Block implements EntityBlock {
    public PlazaBlock(Properties properties) {
        super(properties);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new PlazaBlockEntity(pos, state);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level.isClientSide) return;
        CityManager m = CityManager.current();
        // 이미 어떤 도시의 관리 청크인 곳에는 놓을 수 없다: 놓은 블록을 아이템으로 돌려준다
        if (m != null && m.cityAt(level.dimension(), new ChunkPos(pos).toLong()) != null) {
            level.destroyBlock(pos, true);
            if (placer instanceof ServerPlayer sp) ServerNet.toast(sp, "civilizeandgovern.msg.chunk_taken");
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof PlazaBlockEntity plaza) {
            City c = plaza.cityId() > 0 ? CityManager.get().byId(plaza.cityId()) : null;
            if (c == null) {
                Net.toPlayer(sp, Msg.S_FOUND_SCREEN, buf -> buf.writeBlockPos(pos));
            } else if (CityPermission.isMember(c, sp.getUUID())) {
                ServerNet.sendPanel(sp, c, true);
            } else {
                ServerNet.toast(sp, "civilizeandgovern.msg.outsider");
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
