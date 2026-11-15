package dev.minedraft.lib.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Off-grid 모드(블록 겹치기·칸 안 이동) 읽기 연동. 공식 API 가 없어 공개 클래스를 런타임에 찾아 부른다.
 * Off-grid 가 없거나 구조가 바뀌어 찾지 못하면 꺼진다 (게임은 그대로).
 *
 * 한 칸의 Off-grid 데이터: 기준 블록의 칸 안 이동값 + 겹친 블록 목록(각자 상태·이동값·블록 엔티티 데이터).
 * 월드 사본에 담아 편집창이 같은 모습으로 그리게 한다. 설계에는 담지 않는다.
 */
public final class OffgridCompat {
    private static final Logger LOG = LogUtils.getLogger();

    private OffgridCompat() {}

    /** 겹친 블록 하나. */
    public record Extra(BlockState state, float ox, float oy, float oz, @Nullable CompoundTag tag) {}

    /** 한 칸: 기준 블록 이동값 + 겹친 블록. */
    public record CellData(float ox, float oy, float oz, List<Extra> extras) {
        public boolean hostMoved() {
            return ox != 0 || oy != 0 || oz != 0;
        }
    }

    public interface Visitor {
        void cell(long pos, CellData cell);
    }

    private static boolean looked, ok;
    private static Method indexOf, getChunk, getCells, cellOffset, cellOverlays, ovState, ovOffset, ovTag, ovSave;

    private static boolean init() {
        if (looked) return ok;
        looked = true;
        try {
            Class<?> index = Class.forName("dev.tazer.offgrid.world.OffgridIndex");
            Class<?> chunk = Class.forName("dev.tazer.offgrid.world.OffgridChunk");
            Class<?> cell = Class.forName("dev.tazer.offgrid.world.Cell");
            Class<?> overlay = Class.forName("dev.tazer.offgrid.world.Overlay");
            indexOf = index.getMethod("of", net.minecraft.world.level.BlockGetter.class);
            getChunk = index.getMethod("getChunk", long.class);
            getCells = chunk.getMethod("getCells");
            cellOffset = cell.getMethod("offset");
            cellOverlays = cell.getMethod("overlays");
            ovState = overlay.getMethod("getState");
            ovOffset = overlay.getMethod("getOffset");
            ovTag = overlay.getMethod("getBlockEntityTag");
            try {
                ovSave = overlay.getMethod("saveBlockEntity");
            } catch (NoSuchMethodException ignored) {
                ovSave = null;
            }
            ok = true;
            LOG.info("Minedraft: Off-grid found, overlapping blocks will show in editors");
        } catch (ClassNotFoundException e) {
            ok = false; // Off-grid 없음
        } catch (Throwable t) {
            ok = false;
            LOG.warn("Minedraft: Off-grid found but its structure changed; overlapping blocks won't show in editors", t);
        }
        return ok;
    }

    public static boolean available() {
        return init();
    }

    /** 청크의 Off-grid 칸을 돈다 (서버). 실패하면 아무것도 하지 않는다. */
    public static void forEachCell(Level level, int cx, int cz, Visitor out) {
        if (!init()) return;
        try {
            Object index = indexOf.invoke(null, level);
            if (index == null) return;
            Object chunk = getChunk.invoke(index, ChunkPos.asLong(cx, cz));
            if (chunk == null) return;
            Object cells = getCells.invoke(chunk);
            if (!(cells instanceof it.unimi.dsi.fastutil.longs.Long2ObjectMap<?> map)) return;
            for (it.unimi.dsi.fastutil.longs.Long2ObjectMap.Entry<?> e : map.long2ObjectEntrySet()) {
                CellData cd = read(e.getValue());
                if (cd != null) out.cell(e.getLongKey(), cd);
            }
        } catch (Throwable t) {
            ok = false;
            LOG.warn("Minedraft: reading Off-grid cells failed; disabled", t);
        }
    }

    @Nullable
    private static CellData read(Object cell) throws Exception {
        if (cell == null) return null;
        Vec3 off = (Vec3) cellOffset.invoke(cell);
        List<?> overlays = (List<?>) cellOverlays.invoke(cell);
        java.util.ArrayList<Extra> extras = new java.util.ArrayList<>();
        if (overlays != null) {
            for (Object o : overlays) {
                BlockState st = (BlockState) ovState.invoke(o);
                if (st == null || st.isAir()) continue;
                Vec3 v = (Vec3) ovOffset.invoke(o);
                CompoundTag tag = null;
                try {
                    if (ovSave != null) tag = (CompoundTag) ovSave.invoke(o);
                } catch (Throwable ignored) {
                }
                if (tag == null) {
                    try {
                        tag = (CompoundTag) ovTag.invoke(o);
                    } catch (Throwable ignored) {
                    }
                }
                extras.add(new Extra(st, v == null ? 0 : (float) v.x, v == null ? 0 : (float) v.y, v == null ? 0 : (float) v.z, tag));
            }
        }
        float ox = off == null ? 0 : (float) off.x, oy = off == null ? 0 : (float) off.y, oz = off == null ? 0 : (float) off.z;
        if (extras.isEmpty() && ox == 0 && oy == 0 && oz == 0) return null;
        return new CellData(ox, oy, oz, List.copyOf(extras));
    }
}
