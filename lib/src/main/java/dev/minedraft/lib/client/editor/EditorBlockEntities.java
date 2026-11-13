package dev.minedraft.lib.client.editor;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.function.Consumer;

/**
 * 편집창 블록 엔티티: 블록 엔티티가 있는 블록마다 하나씩 만들어 둔다 (처음 찾을 때).
 * 월드에서 온 블록이면 서버가 보낸 동기화 데이터(update tag)를 읽어, 연결 텍스처·보일러 외장 같은
 * 블록 엔티티에 따른 모델과 블록 엔티티 렌더러가 월드와 같게 보이게 한다. 설계로 놓은 블록은 기본 상태.
 * 블록이 바뀐 칸은 버리고 다시 만든다. 만들기·읽기에 실패한 칸은 다시 시도하지 않는다.
 */
public final class EditorBlockEntities {
    private static final Logger LOG = LogUtils.getLogger();

    private final AreaEditorSession session;
    private final Long2ObjectOpenHashMap<BlockEntity> map = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet failed = new LongOpenHashSet();
    @Nullable
    private EditorLevel level;
    private boolean levelFailed;

    EditorBlockEntities(AreaEditorSession session) {
        this.session = session;
    }

    @Nullable
    private EditorLevel level() {
        if (level == null && !levelFailed) {
            var real = Minecraft.getInstance().level;
            if (real == null) return null;
            try {
                level = new EditorLevel(session, real);
            } catch (Throwable t) {
                levelFailed = true;
                LOG.warn("Minedraft editor: could not create the editor level for block entities", t);
            }
        }
        return level;
    }

    /** 이 칸의 블록 엔티티 (블록에 블록 엔티티가 없거나 만들 수 없으면 null). */
    @Nullable
    public BlockEntity get(BlockPos pos) {
        long key = pos.asLong();
        BlockState state = session.blockAt(pos.getX(), pos.getY(), pos.getZ(), false);
        if (!state.hasBlockEntity()) {
            if (!map.isEmpty()) map.remove(key);
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
        be = create(pos.immutable(), state);
        if (be == null) {
            failed.add(key);
            return null;
        }
        map.put(key, be);
        return be;
    }

    @Nullable
    private BlockEntity create(BlockPos pos, BlockState state) {
        EditorLevel lv = level();
        if (lv == null || !(state.getBlock() instanceof EntityBlock eb)) return null;
        try {
            BlockEntity be = eb.newBlockEntity(pos, state);
            if (be == null) return null;
            be.setLevel(lv);
            // 월드 블록 그대로면 서버가 보낸 동기화 데이터를 읽는다 (설계로 바꾼 칸은 기본 상태)
            CompoundTag tag = session.snapshot.blockEntityTag(pos.getX(), pos.getY(), pos.getZ());
            if (tag != null && session.worldAt(pos.getX(), pos.getY(), pos.getZ()).getBlock() == state.getBlock()) {
                be.handleUpdateTag(tag, lv.registryAccess());
            }
            return be;
        } catch (Throwable t) {
            LOG.debug("Minedraft editor: block entity for {} failed", state, t);
            return null;
        }
    }

    /** 칸이 바뀌었다 (다음에 찾을 때 다시 만든다). */
    public void invalidate(long pos) {
        map.remove(pos);
        failed.remove(pos);
    }

    /** 청크의 월드 사본이 새로 왔거나 빠졌다. */
    public void invalidateChunk(long chunk) {
        int cx = ChunkPos.getX(chunk), cz = ChunkPos.getZ(chunk);
        ObjectIterator<Long2ObjectMap.Entry<BlockEntity>> it = map.long2ObjectEntrySet().iterator();
        while (it.hasNext()) {
            long p = it.next().getLongKey();
            if (BlockPos.getX(p) >> 4 == cx && BlockPos.getZ(p) >> 4 == cz) it.remove();
        }
        failed.removeIf(p -> BlockPos.getX(p) >> 4 == cx && BlockPos.getZ(p) >> 4 == cz);
    }

    /** 만들어 둔 블록 엔티티 전부 (그린 섹션의 블록 엔티티는 메싱 때 만들어진다). */
    public void forEach(Consumer<BlockEntity> out) {
        for (BlockEntity be : map.values()) out.accept(be);
    }

    public int size() {
        return map.size();
    }

    public void clear() {
        map.clear();
        failed.clear();
    }
}
