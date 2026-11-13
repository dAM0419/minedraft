package dev.minedraft.lib.space;

import dev.minedraft.lib.MinedraftLib;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;
import dev.minedraft.lib.area.AreaOps;
import dev.minedraft.lib.edit.EditContext;
import dev.minedraft.lib.edit.EditRequest;
import dev.minedraft.lib.edit.EditRequests;
import dev.minedraft.lib.edit.UndoChange;
import dev.minedraft.lib.edit.UndoHistory;
import dev.minedraft.lib.util.CellIO;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 공간 태그 칠하기: 요청(클라이언트 → 서버), 확정 연산(서버 → 모두), 되돌리기. */
public final class SpaceTagEdits {
    private SpaceTagEdits() {}

    /** 한 번에 칠할 수 있는 최대 칸 수. */
    public static final int MAX_CELLS = 65_536;

    public static final ResourceLocation PAINT = MinedraftLib.rl("paint_tags");
    public static final ResourceLocation SET = MinedraftLib.rl("set_tags");
    public static final ResourceLocation DOMAIN = MinedraftLib.rl("space_tags");

    /** 편집 요청: 한 묶음에서 칸들에 태그 하나를 칠한다 (tag null = 그 묶음 태그 지우기). group 은 되돌리기 묶음 번호. */
    public record Paint(int group, long[] pos, ResourceLocation tagGroup, @Nullable SpaceTag tag) implements EditRequest {
        @Override
        public ResourceLocation type() {
            return PAINT;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(group);
            CellIO.writePositions(buf, pos);
            buf.writeResourceLocation(tagGroup);
            SpaceTag.write(buf, tag);
        }
    }

    /** 확정 연산: 한 묶음에서 칸마다 태그 (null = 지움). */
    public record Set(ResourceLocation tagGroup, long[] pos, SpaceTag[] tags) implements AreaOp {
        @Override
        public ResourceLocation type() {
            return SET;
        }

        @Override
        public void apply(Area area) {
            SpaceTagLayer l = SpaceTagLayer.of(area);
            if (l != null) l.set(tagGroup, pos, tags);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeResourceLocation(tagGroup);
            CellIO.writePositions(buf, pos);
            for (SpaceTag t : tags) SpaceTag.write(buf, t);
        }
    }

    record TagChange(ResourceLocation tagGroup, long pos, @Nullable SpaceTag before, @Nullable SpaceTag after) implements UndoChange {
        @Override
        public ResourceLocation domain() {
            return DOMAIN;
        }
    }

    @Nullable
    private static String paint(EditContext ctx, Paint req) {
        Area area = ctx.area;
        SpaceTagLayer layer = SpaceTagLayer.of(area);
        if (layer == null) return "minedraftlib.msg.no_tags";
        TagGroup group = area.type.tagGroup(req.tagGroup());
        SpaceTag tag = req.tag();
        if (group == null || (tag != null && !tag.group().equals(group.id()))) return "minedraftlib.msg.tag_not_allowed";
        TagPolicy policy = group.policy();
        if (tag == null ? !policy.canErase(area) : !policy.paintable(area, group).contains(tag)) return "minedraftlib.msg.tag_not_allowed";
        List<Long> pos = new ArrayList<>();
        for (long p : req.pos()) {
            if (!area.isInside(p)) continue;
            SpaceTag before = layer.tagAt(p, group.id());
            if (before == tag) continue;
            if (!policy.allows(area, p, tag)) continue;
            pos.add(p);
            ctx.undo.add(new TagChange(group.id(), p, before, tag));
        }
        if (pos.isEmpty()) return null;
        long[] arr = new long[pos.size()];
        SpaceTag[] tags = new SpaceTag[arr.length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = pos.get(i);
            tags[i] = tag;
        }
        ctx.ops.add(new Set(group.id(), arr, tags));
        return null;
    }

    static {
        AreaOps.register(SET, buf -> {
            ResourceLocation g = buf.readResourceLocation();
            long[] pos = CellIO.readPositions(buf, MAX_CELLS);
            SpaceTag[] tags = new SpaceTag[pos.length];
            for (int i = 0; i < pos.length; i++) tags[i] = SpaceTag.read(buf);
            return new Set(g, pos, tags);
        });
        EditRequests.register(PAINT, buf -> {
            int group = buf.readVarInt();
            long[] pos = CellIO.readPositions(buf, MAX_CELLS);
            ResourceLocation g = buf.readResourceLocation();
            return new Paint(group, pos, g, SpaceTag.read(buf));
        }, SpaceTagEdits::paint);
        // 되돌리기: 내가 칠한 뒤 아무도 바꾸지 않은 칸만 되돌린다
        UndoHistory.registerInverter(DOMAIN, (area, changes, out) -> {
            SpaceTagLayer layer = SpaceTagLayer.of(area);
            List<UndoChange> inverse = new ArrayList<>();
            if (layer == null) return inverse;
            // 묶음별로 모아 연산 하나씩
            java.util.Map<ResourceLocation, List<Long>> pos = new java.util.LinkedHashMap<>();
            java.util.Map<ResourceLocation, List<SpaceTag>> tags = new java.util.LinkedHashMap<>();
            for (int i = changes.size() - 1; i >= 0; i--) {
                if (!(changes.get(i) instanceof TagChange c)) continue;
                if (layer.tagAt(c.pos(), c.tagGroup()) != c.after()) continue;
                pos.computeIfAbsent(c.tagGroup(), k -> new ArrayList<>()).add(c.pos());
                tags.computeIfAbsent(c.tagGroup(), k -> new ArrayList<>()).add(c.before());
                inverse.add(0, new TagChange(c.tagGroup(), c.pos(), c.after(), c.before()));
            }
            for (var e : pos.entrySet()) {
                long[] arr = new long[e.getValue().size()];
                for (int i = 0; i < arr.length; i++) arr[i] = e.getValue().get(i);
                out.add(new Set(e.getKey(), arr, tags.get(e.getKey()).toArray(new SpaceTag[0])));
            }
            return inverse;
        });
    }

    /** 등록해 둔다 (클래스 초기화). */
    public static void bootstrap() {}
}
