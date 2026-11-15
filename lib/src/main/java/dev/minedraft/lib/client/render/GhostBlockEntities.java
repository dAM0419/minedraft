package dev.minedraft.lib.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 실제 월드에 없는 블록(유령 블록·투영)의 블록 엔티티. 칸마다 기본 상태로 하나씩 만들어 두고 렌더러로 그린다
 * (밸브 손잡이, 톱니바퀴, 상자 뚜껑처럼 모델이 아니라 렌더러가 그리는 부분). Ponder 처럼 가짜 월드에 붙인다.
 * 블록이 바뀐 칸은 다시 만들고, 만들기·그리기에 실패한 칸은 다시 시도하지 않는다.
 */
public final class GhostBlockEntities {
    private static final Logger LOG = LogUtils.getLogger();

    /** 칸의 유령 블록 (없으면 공기). */
    public interface Blocks {
        BlockState ghostAt(int x, int y, int z);
    }

    private final Blocks blocks;
    private final Long2ObjectOpenHashMap<BlockEntity> map = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet failed = new LongOpenHashSet();
    @Nullable
    private FakeLevel level;
    @Nullable
    private ClientLevel levelFor;

    public GhostBlockEntities(Blocks blocks) {
        this.blocks = blocks;
    }

    @Nullable
    private FakeLevel level() {
        ClientLevel real = Minecraft.getInstance().level;
        if (real == null) return null;
        if (level == null || levelFor != real) {
            map.clear();
            failed.clear();
            levelFor = real;
            try {
                level = new FakeLevel(new FakeLevel.Source() {
                    @Override
                    public BlockState blockAt(int x, int y, int z) {
                        BlockState g = blocks.ghostAt(x, y, z);
                        return g.isAir() ? real.getBlockState(new BlockPos(x, y, z)) : g;
                    }

                    @Override
                    public BlockEntity blockEntity(BlockPos pos) {
                        return get(pos);
                    }
                }, real, real.getMinBuildHeight(), real.getHeight());
            } catch (Throwable t) {
                level = null;
                LOG.warn("Minedraft: could not create the ghost level for block entities", t);
            }
        }
        return level;
    }

    /** 칸의 유령 블록 엔티티 (없거나 만들 수 없으면 null). */
    @Nullable
    public BlockEntity get(BlockPos pos) {
        long key = pos.asLong();
        BlockState state = blocks.ghostAt(pos.getX(), pos.getY(), pos.getZ());
        if (!state.hasBlockEntity()) {
            map.remove(key);
            return null;
        }
        BlockEntity be = map.get(key);
        if (be != null) {
            if (be.getBlockState().getBlock() == state.getBlock()) {
                if (be.getBlockState() != state) be.setBlockState(state);
                return be;
            }
            map.remove(key);
            failed.remove(key);
        }
        if (failed.contains(key)) return null;
        FakeLevel lv = level();
        if (lv == null || !(state.getBlock() instanceof EntityBlock eb)) return null;
        try {
            be = eb.newBlockEntity(pos.immutable(), state);
            if (be == null) {
                failed.add(key);
                return null;
            }
            be.setLevel(lv);
            map.put(key, be);
            return be;
        } catch (Throwable t) {
            failed.add(key);
            LOG.debug("Minedraft: ghost block entity for {} failed", state, t);
            return null;
        }
    }

    /**
     * positions 의 블록 엔티티 렌더러를 그린다. pose 는 카메라 기준 (칸 좌표 - cam 으로 옮긴다).
     * 빛은 실제 월드의 그 칸 빛. 그린 수를 돌려준다.
     */
    public int render(long[] positions, PoseStack pose, MultiBufferSource.BufferSource buffers,
                      double camX, double camY, double camZ, float partialTick, int max) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel real = mc.level;
        if (real == null) return 0;
        var dispatcher = mc.getBlockEntityRenderDispatcher();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();
        int n = 0;
        for (long key : positions) {
            if (n >= max) break;
            if (failed.contains(key)) continue;
            mp.set(key);
            BlockEntity be = get(mp);
            if (be == null) continue;
            BlockEntityRenderer<BlockEntity> r = dispatcher.getRenderer(be);
            if (r == null) continue;
            pose.pushPose();
            pose.translate(mp.getX() - camX, mp.getY() - camY, mp.getZ() - camZ);
            try {
                r.render(be, partialTick, pose, buffers, LevelRenderer.getLightColor(real, mp), OverlayTexture.NO_OVERLAY);
                n++;
            } catch (Throwable t) {
                failed.add(key);
                map.remove(key);
                try {
                    buffers.endBatch();
                } catch (Throwable ignored) {
                }
            }
            pose.popPose();
        }
        return n;
    }

    public void clear() {
        map.clear();
        failed.clear();
        level = null;
        levelFor = null;
    }
}
