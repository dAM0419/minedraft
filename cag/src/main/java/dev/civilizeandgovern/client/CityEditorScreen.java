package dev.civilizeandgovern.client;

import dev.civilizeandgovern.zone.CagZones;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.client.ClientAreas;
import dev.minedraft.lib.client.editor.AreaEditorScreen;
import dev.minedraft.lib.client.editor.ui.RangeTab;
import dev.minedraft.lib.client.editor.ui.ToolButton;
import dev.minedraft.lib.space.SpaceTag;
import dev.minedraft.lib.space.SpaceTagEdits;
import dev.minedraft.lib.space.SpaceTagLayer;
import dev.minedraft.lib.space.TagGroup;
import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * 도시의 구역 편집창 (Minedraft Lib 편집창). 도구는 공간 지정 하나, 오른쪽 창은 범위(관리 청크) 탭 하나.
 * 구역 범위 손잡이: 고른 구역(개체)의 경계 상자를 끌어 늘리고 줄인다 (DaB 구조물 범위와 같은 방식).
 */
public final class CityEditorScreen extends AreaEditorScreen {
    private final CityEditorSession s;

    // 고른 구역의 경계 상자 캐시 (개체 칸 조회는 묶음 전체를 훑으므로 매 프레임 하지 않는다)
    private int cacheInst = -1, cacheCells = -1, cacheAge;
    @Nullable
    private Box cacheBox;

    public CityEditorScreen(CityEditorSession session) {
        super(Component.translatable("civilizeandgovern.editor.title"), session);
        this.s = session;
        panelTabs.add(new RangeTab(this));
        ToolButton tags = tagToolButton();
        if (tags != null) tools.add(tags);
        assembleParts(null);
        setTagTool(true);
    }

    /** 도시는 편집 중에도 돌아가야 한다. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // 구역 범위 손잡이
    // ------------------------------------------------------------------

    /** 지금 고른 구역 개체 (구역 태그를 칠하는 중이고, 그 태그의 개체를 골랐을 때만). */
    @Nullable
    private SpaceTagLayer.Instance selectedZone() {
        if (!tagTool.active) return null;
        SpaceTag t = tagTool.current();
        TagGroup g = tagTool.currentGroup();
        if (t == null || g == null || !g.id().equals(CagZones.ZONES.id())) return null;
        int id = tagTool.selectedInstance(g.id());
        SpaceTagLayer l = SpaceTagLayer.of(s.area);
        SpaceTagLayer.Instance in = id <= 0 || l == null ? null : l.instance(g.id(), id);
        return in != null && in.tag == t ? in : null;
    }

    @Override
    @Nullable
    protected Box tagRangeBox() {
        SpaceTagLayer.Instance in = selectedZone();
        if (in == null) return null;
        // 개체가 바뀌었거나, 칸 수가 바뀌었거나, 10프레임이 지났으면 다시 잰다
        if (in.id != cacheInst || in.cells() != cacheCells || ++cacheAge > 10) {
            cacheInst = in.id;
            cacheCells = in.cells();
            cacheAge = 0;
            cacheBox = bounds(in.id);
        }
        return cacheBox;
    }

    @Nullable
    private Box bounds(int inst) {
        SpaceTagLayer l = SpaceTagLayer.of(s.area);
        if (l == null) return null;
        long[] cells = l.instanceCells(CagZones.ZONES.id(), inst);
        if (cells.length == 0) return null;
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (long p : cells) {
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            if (x < x0) x0 = x;
            if (y < y0) y0 = y;
            if (z < z0) z0 = z;
            if (x > x1) x1 = x;
            if (y > y1) y1 = y;
            if (z > z1) z1 = z;
        }
        return new Box(x0, y0, z0, x1, y1, z1);
    }

    @Override
    protected int tagRangeColor() {
        SpaceTagLayer.Instance in = selectedZone();
        return in == null ? 0xFFFFFFFF : 0xFF000000 | in.tag.color();
    }

    @Override
    protected Component tagRangeSizeLabel(Box b) {
        return Component.translatable("civilizeandgovern.editor.range_size", b.sizeX(), b.sizeY(), b.sizeZ());
    }

    @Override
    protected Component tagRangeReleaseHint() {
        return Component.translatable("civilizeandgovern.editor.range_release");
    }

    @Override
    protected Component tagRangeHandleHint() {
        return Component.translatable("civilizeandgovern.editor.range_handle");
    }

    /**
     * 구역 범위를 nb 로: 상자 안의 모든 칸(공기 포함: 건물 안쪽까지)을 이 구역에 넣고, 상자 밖의 이 구역 칸은 뺀다.
     * 상자 안에 있던 다른 구역의 칸은 이 구역으로 넘어온다(한 칸에 구역 하나). 되돌리기 한 번.
     */
    @Override
    protected void applyTagRange(Box nb) {
        SpaceTagLayer.Instance in = selectedZone();
        SpaceTagLayer l = SpaceTagLayer.of(s.area);
        if (in == null || l == null) return;
        TagGroup g = CagZones.ZONES;
        Area a = s.area;
        int undo = ClientAreas.nextGroup();
        LongOpenHashSet mine = new LongOpenHashSet(l.instanceCells(g.id(), in.id));
        // 빼기: 상자 밖의 이 구역 칸
        LongArrayList out = new LongArrayList();
        for (long p : mine) if (!nb.contains(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p))) out.add(p);
        // 넣기: 상자 안에서 아직 이 구역이 아닌 칸
        LongArrayList add = new LongArrayList();
        for (int x = nb.minX(); x <= nb.maxX(); x++)
            for (int y = nb.minY(); y <= nb.maxY(); y++)
                for (int z = nb.minZ(); z <= nb.maxZ(); z++) {
                    long p = BlockPos.asLong(x, y, z);
                    if (mine.contains(p) || !a.isInside(p) || !tagAllows(g, p, in.tag)) continue;
                    add.add(p);
                }
        // 넣기를 먼저 보낸다: 빼기가 먼저 가서 개체의 칸이 0이 되면 개체가 사라져 넣기가 거부된다
        send(undo, add, in.tag, in.id);
        send(undo, out, null, in.id);
        cacheAge = 99; // 다음 프레임에 경계 상자를 다시 잰다
    }

    /** Lib 의 칠하기 요청 하나에 실을 수 있는 칸 수로 나눠 보낸다 (같은 되돌리기 묶음). */
    private void send(int undo, LongArrayList cells, @Nullable SpaceTag tag, int inst) {
        int max = SpaceTagEdits.MAX_CELLS;
        for (int i = 0; i < cells.size(); i += max) {
            long[] part = cells.subList(i, Math.min(cells.size(), i + max)).toLongArray();
            s.send(new SpaceTagEdits.Paint(undo, part, CagZones.ZONES.id(), tag, inst));
        }
    }
}
