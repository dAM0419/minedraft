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
        // 도시의 관리 청크 안에는 그 도시의 시장만 놓을 수 있다 (광장을 옮기려고 놓는 경우. D-55). 아니면 아이템으로 돌려준다
        City here = m == null ? null : m.cityAt(level.dimension(), new ChunkPos(pos).toLong());
        if (here != null && !(placer instanceof ServerPlayer sp0 && sp0.getUUID().equals(here.founder))) {
            level.destroyBlock(pos, true);
            if (placer instanceof ServerPlayer sp) ServerNet.toast(sp, "civilizeandgovern.msg.chunk_taken");
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer sp && level.getBlockEntity(pos) instanceof PlazaBlockEntity plaza) {
            City c = plaza.cityId() > 0 ? CityManager.get().byId(plaza.cityId()) : null;
            City here = c != null ? null : CityManager.get().cityAt(level.dimension(), new ChunkPos(pos).toLong());
            if (c == null && here != null) {
                // 도시 안에 놓인 주인 없는 광장 블록: 시장이면 광장을 이곳으로 옮길지 묻는다
                if (sp.getUUID().equals(here.founder)) {
                    Net.toPlayer(sp, Msg.S_MOVE_SCREEN, buf -> {
                        buf.writeBlockPos(pos);
                        buf.writeUtf(here.name);
                    });
                } else {
                    ServerNet.toast(sp, "civilizeandgovern.msg.plaza_not_mayor");
                }
            } else if (c == null) {
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
