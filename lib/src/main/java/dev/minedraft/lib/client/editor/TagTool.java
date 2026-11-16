package dev.minedraft.lib.client.editor;

import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.client.ClientAreas;
import dev.minedraft.lib.client.ui.Ui;
import dev.minedraft.lib.space.SpaceTag;
import dev.minedraft.lib.space.SpaceTagEdits;
import dev.minedraft.lib.space.SpaceTagLayer;
import dev.minedraft.lib.space.TagGroup;
import dev.minedraft.lib.space.TagPolicy;
import net.minecraft.resources.ResourceLocation;
import dev.minedraft.lib.util.Box;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 공간 지정 도구 (Lib 공통): 커서 아래 칸에 붓 크기만큼 공간 태그를 칠한다. 끌면 이어서 칠하고, 한 번 끈 것은 되돌리기 한 번.
 * 한 번에 한 묶음({@link TagGroup})만 칠한다: 다른 묶음의 태그는 그대로 남아 겹친다.
 * 칠할 수 있는 태그·칸은 묶음의 {@link TagPolicy} 가 정한다 (서버가 다시 거른다).
 * <ul>
 *     <li>좌클릭·끌기: 칠하기 &nbsp; 휠클릭: 커서 칸의 태그 집기 &nbsp; Alt+휠: 붓 크기</li>
 * </ul>
 * 개체 묶음({@link TagGroup#instanced}): 고른 개체가 있으면 그 개체에 칸을 넣는다. 고른 개체가 없으면 끌기를 시작한 칸이
 * 같은 태그의 개체일 때 그 개체를 골라 넓히고, 아니면 새 개체를 만든다 (끌기 한 번 = 개체 하나, 만든 개체가 골라진다).
 * 휠클릭은 개체도 집는다. 모드는 {@link #selectInstance} 로 자기 목록에서 고른 개체를 넘길 수 있다.
 */
public final class TagTool {
    public static final int MAX_BRUSH = 32;

    private final AreaEditorScreen screen;
    /** 켜져 있으면 모드 도구 대신 이 도구가 뷰포트 입력을 받는다. */
    public boolean active;
    /** 칠할 태그. eraseGroup 이 있으면 그 묶음 태그 지우기. */
    @Nullable
    public SpaceTag tag;
    @Nullable
    public ResourceLocation eraseGroup;
    /** 붓 범위: 가로(X)·높이(Y)·세로(Z) 칸 수. 태그 목록의 범위 줄에서 축마다 바꾼다 (Alt+휠은 셋을 함께). */
    public int brushX = 1, brushY = 1, brushZ = 1;

    /** 개체 묶음에서 고른 개체 (묶음 ID → 번호). 없으면 새 개체를 만든다. */
    private final java.util.Map<ResourceLocation, Integer> selectedInstance = new java.util.HashMap<>();
    /** 방금 만든 개체를 고르려고 기다리는 중: 이 칸에 번호가 붙으면 그 개체를 고른다. */
    private long awaitPos;
    @Nullable
    private ResourceLocation awaitGroup;

    private boolean stroking;
    private int group;
    /** 이번 끌기에서 넣을 개체 (개체 묶음이 아니면 0). */
    private int strokeInstance;
    private final LongOpenHashSet done = new LongOpenHashSet();

    TagTool(AreaEditorScreen screen) {
        this.screen = screen;
    }

    private Area area() {
        return screen.session().area;
    }

    /** 이 영역에서 공간 지정을 쓸 수 있는가. */
    public boolean available() {
        return area().type.hasTags() && SpaceTagLayer.of(area()) != null;
    }

    /** 영역의 태그 묶음 (붙은 순서). */
    public java.util.Collection<TagGroup> groups() {
        return area().type.groups();
    }

    public List<SpaceTag> paintable(TagGroup g) {
        return g.policy().paintable(area(), g);
    }

    public boolean canErase(TagGroup g) {
        return g.policy().canErase(area());
    }

    /** 지금 다루는 묶음. */
    @Nullable
    public TagGroup currentGroup() {
        if (eraseGroup != null) {
            TagGroup g = area().type.tagGroup(eraseGroup);
            if (g != null && canErase(g)) return g;
            eraseGroup = null;
        }
        SpaceTag t = current();
        return t == null ? null : area().type.tagGroup(t.group());
    }

    /** 칠할 태그 (지우기면 null). 고른 게 없거나 칠할 수 없게 되었으면 첫 묶음의 첫 태그. */
    @Nullable
    public SpaceTag current() {
        if (eraseGroup != null) return null;
        if (tag != null) {
            TagGroup g = area().type.tagGroup(tag.group());
            if (g != null && paintable(g).contains(tag)) return tag;
        }
        tag = null;
        for (TagGroup g : groups()) {
            List<SpaceTag> list = paintable(g);
            if (!list.isEmpty()) return tag = list.get(0);
        }
        return null;
    }

    public void select(SpaceTag t) {
        tag = t;
        eraseGroup = null;
    }

    /** 이 묶음에서 고른 개체 (없거나 사라졌으면 0). */
    public int selectedInstance(ResourceLocation group) {
        resolveAwait();
        Integer id = selectedInstance.get(group);
        if (id == null) return 0;
        SpaceTagLayer l = SpaceTagLayer.of(area());
        if (l == null || l.instance(group, id) == null) {
            selectedInstance.remove(group);
            return 0;
        }
        return id;
    }

    /** 개체를 고른다 (0 = 고르지 않음: 다음 끌기는 새 개체). 그 개체의 태그도 칠할 태그로 고른다. */
    public void selectInstance(ResourceLocation group, int id) {
        awaitGroup = null;
        SpaceTagLayer l = SpaceTagLayer.of(area());
        SpaceTagLayer.Instance in = l == null || id <= 0 ? null : l.instance(group, id);
        if (in == null) {
            selectedInstance.remove(group);
            return;
        }
        selectedInstance.put(group, id);
        select(in.tag);
    }

    /** 방금 만든 개체에 번호가 붙었으면 고른다 (서버가 번호를 매겨 돌려준 뒤). */
    private void resolveAwait() {
        if (awaitGroup == null) return;
        SpaceTagLayer l = SpaceTagLayer.of(area());
        int id = l == null ? 0 : l.instanceAt(awaitPos, awaitGroup);
        if (id == 0) return;
        ResourceLocation g = awaitGroup;
        awaitGroup = null;
        selectedInstance.put(g, id);
    }

    public void selectErase(TagGroup g) {
        eraseGroup = g.id();
    }

    public void setActive(boolean on) {
        active = on && available();
        if (!active) stroking = false;
    }

    // ------------------------------------------------------------------
    // 붓
    // ------------------------------------------------------------------

    /** 붓이 덮는 상자: 가로·세로는 커서 칸 가운데, 높이는 커서 칸에서 아래로. */
    public Box brushBox(BlockPos p) {
        int lx = (brushX - 1) / 2, lz = (brushZ - 1) / 2;
        return new Box(p.getX() - lx, p.getY() - (brushY - 1), p.getZ() - lz,
                p.getX() - lx + brushX - 1, p.getY(), p.getZ() - lz + brushZ - 1);
    }

    /** 붓 안에서 실제로 바뀌는 칸 (영역 안, 정책이 허락, 이미 같은 태그가 아님). */
    public long[] targets(Box b) {
        Area a = area();
        SpaceTagLayer l = SpaceTagLayer.of(a);
        TagGroup g = currentGroup();
        if (l == null || g == null) return new long[0];
        SpaceTag t = current();
        ResourceLocation gid = g.id();
        // 개체 묶음: 끄는 중이면 이번 끌기의 개체, 아니면 고른 개체 (없으면 새 개체 = 모든 칸이 바뀐다)
        int inst = !g.instanced() ? 0 : stroking ? strokeInstance : selectedInstance(gid);
        LongArrayList out = new LongArrayList();
        for (int x = b.minX(); x <= b.maxX(); x++)
            for (int y = b.minY(); y <= b.maxY(); y++)
                for (int z = b.minZ(); z <= b.maxZ(); z++) {
                    long p = BlockPos.asLong(x, y, z);
                    if (!a.isInside(p) || !screen.tagAllows(g, p, t)) continue;
                    SpaceTag before = l.tagAt(p, gid);
                    if (t == null) {
                        if (before == null || (inst > 0 && l.instanceAt(p, gid) != inst)) continue;
                    } else if (before == t && (!g.instanced() || (inst > 0 && l.instanceAt(p, gid) == inst))) {
                        continue;
                    }
                    out.add(p);
                }
        return out.toLongArray();
    }

    // ------------------------------------------------------------------
    // 입력 (편집창이 부른다)
    // ------------------------------------------------------------------

    void clicked(int button, @Nullable AreaEditorSession.Hit h) {
        if (button == 2) { // 휠클릭: 태그 집기
            if (h == null) return;
            // 지금 묶음의 태그를 먼저, 없으면 그 칸의 아무 칠할 수 있는 태그
            SpaceTagLayer l = SpaceTagLayer.of(area());
            if (l == null) return;
            List<SpaceTag> here = l.tagsAt(h.pos().asLong());
            TagGroup cg = currentGroup();
            SpaceTag pick = null;
            for (SpaceTag t : here) {
                TagGroup g = area().type.tagGroup(t.group());
                if (g == null || !paintable(g).contains(t)) continue;
                if (pick == null || (cg != null && t.group().equals(cg.id()))) pick = t;
            }
            if (pick != null) {
                select(pick);
                TagGroup pg = area().type.tagGroup(pick.group());
                if (pg != null && pg.instanced()) selectInstance(pg.id(), l.instanceAt(h.pos().asLong(), pg.id()));
            } else if (cg != null && cg.instanced()) {
                selectInstance(cg.id(), 0); // 빈 칸을 집으면 고른 개체를 놓는다
            }
            return;
        }
        if (button != 0) return;
        if (ClientAreas.readOnly()) {
            screen.session().toast(Component.translatable("minedraftlib.msg.no_permission"));
            return;
        }
        if (h == null) {
            screen.session().toast(Component.translatable("minedraftlib.msg.no_target"));
            return;
        }
        group = ClientAreas.nextGroup();
        done.clear();
        strokeInstance = 0;
        awaitGroup = null;
        TagGroup g = currentGroup();
        if (g != null && g.instanced()) {
            SpaceTagLayer l = SpaceTagLayer.of(area());
            SpaceTag t = current();
            int sel = selectedInstance(g.id());
            if (t == null) {
                strokeInstance = sel; // 지우기: 고른 개체가 있으면 그 개체의 칸만
            } else {
                SpaceTagLayer.Instance in = sel == 0 || l == null ? null : l.instance(g.id(), sel);
                if (in != null && in.tag == t) {
                    strokeInstance = sel;
                } else if (l != null && l.tagAt(h.pos().asLong(), g.id()) == t && l.instanceAt(h.pos().asLong(), g.id()) > 0) {
                    // 같은 태그의 개체 위에서 시작: 그 개체를 골라 넓힌다
                    strokeInstance = l.instanceAt(h.pos().asLong(), g.id());
                    selectedInstance.put(g.id(), strokeInstance);
                } else {
                    strokeInstance = SpaceTagEdits.NEW_INSTANCE;
                    selectedInstance.remove(g.id());
                }
            }
        }
        stroking = true;
        paintAt(h);
    }

    /** 끄는 중이면 커서가 지나간 칸을 칠한다 (편집창이 커서를 갱신할 때마다). */
    void hovered(@Nullable AreaEditorSession.Hit h) {
        if (stroking && h != null) paintAt(h);
    }

    void released() {
        stroking = false;
        done.clear();
    }

    boolean isStroking() {
        return stroking;
    }

    /** 세 축을 함께 (Alt+휠). */
    public void brushScroll(double delta) {
        int d = delta > 0 ? 1 : -1;
        for (int axis = 0; axis < 3; axis++) brushAdd(axis, d);
    }

    /** 한 축만 (0 = X, 1 = Y, 2 = Z). */
    public void brushAdd(int axis, int d) {
        if (axis == 0) brushX = Mth.clamp(brushX + d, 1, MAX_BRUSH);
        else if (axis == 1) brushY = Mth.clamp(brushY + d, 1, MAX_BRUSH);
        else brushZ = Mth.clamp(brushZ + d, 1, MAX_BRUSH);
    }

    private void paintAt(AreaEditorSession.Hit h) {
        long[] t = targets(brushBox(h.pos()));
        LongArrayList send = new LongArrayList();
        for (long p : t) if (done.add(p)) send.add(p);
        if (send.isEmpty()) return;
        TagGroup g = currentGroup();
        if (g == null) return;
        long[] cells = send.toLongArray();
        // 모드가 직접 처리하는 태그 (DaB 구조물: 칸을 구조물에 넣는다)
        if (screen.paintTags(g, current(), cells, group)) return;
        if (strokeInstance == SpaceTagEdits.NEW_INSTANCE && awaitGroup == null) {
            awaitGroup = g.id();   // 서버가 번호를 매겨 돌려주면 그 개체를 고른다
            awaitPos = cells[0];
        }
        screen.session().send(new SpaceTagEdits.Paint(group, cells, g.id(), current(), strokeInstance));
    }

    // ------------------------------------------------------------------
    // 표시
    // ------------------------------------------------------------------

    /** 붓 테두리 색 (ARGB). */
    int color() {
        SpaceTag t = current();
        return t == null ? Ui.DEMOLISH : 0xFF000000 | t.color();
    }

    List<Component> chip(AreaEditorSession.Hit h) {
        List<Component> lines = new ArrayList<>();
        SpaceTag t = current();
        String name = t == null ? Component.translatable("minedraftlib.tag.erase").getString() : Component.translatable(t.nameKey()).getString();
        int n = targets(brushBox(h.pos())).length;
        lines.add(Component.literal(name + " · " + brushX + "×" + brushY + "×" + brushZ + " · ")
                .append(Component.translatable("minedraftlib.editor.tag_cells", n)));
        SpaceTagLayer l = SpaceTagLayer.of(area());
        if (l != null) {
            List<SpaceTag> here = l.tagsAt(h.pos().asLong());
            if (!here.isEmpty()) {
                StringBuilder b = new StringBuilder();
                for (SpaceTag x : here) {
                    if (b.length() > 0) b.append(", ");
                    b.append(Component.translatable(x.nameKey()).getString());
                    int in = l.instanceAt(h.pos().asLong(), x.group());
                    if (in > 0) b.append(' ').append(screen.tagInstanceName(x.group(), in));
                }
                lines.add(Component.translatable("minedraftlib.editor.tag_here", b.toString()));
            }
        }
        return lines;
    }
}
