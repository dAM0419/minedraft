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

    /** 편집 요청: 칸들에 태그 하나를 칠한다 (tag null = 지우기). */
    public record Paint(int group, long[] pos, @Nullable SpaceTag tag) implements EditRequest {
        @Override
        public ResourceLocation type() {
            return PAINT;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(group);
            CellIO.writePositions(buf, pos);
            SpaceTag.write(buf, tag);
        }
    }

    /** 확정 연산: 칸마다 태그 (null = 지움). */
    public record Set(long[] pos, SpaceTag[] tags) implements AreaOp {
        @Override
        public ResourceLocation type() {
            return SET;
        }

        @Override
        public void apply(Area area) {
            SpaceTagLayer l = SpaceTagLayer.of(area);
            if (l != null) l.set(pos, tags);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            CellIO.writePositions(buf, pos);
            for (SpaceTag t : tags) SpaceTag.write(buf, t);
        }
    }

    record TagChange(long pos, @Nullable SpaceTag before, @Nullable SpaceTag after) implements UndoChange {
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
        TagPolicy policy = area.type.tagPolicy();
        SpaceTag tag = req.tag();
        if (tag == null ? !policy.canErase(area) : !policy.paintable(area).contains(tag)) return "minedraftlib.msg.tag_not_allowed";
        List<Long> pos = new ArrayList<>();
        for (long p : req.pos()) {
            if (!area.isInside(p)) continue;
            SpaceTag before = layer.tagAt(p);
            if (before == tag) continue;
            if (!policy.allows(area, p, tag)) continue;
            pos.add(p);
            ctx.undo.add(new TagChange(p, before, tag));
        }
        if (pos.isEmpty()) return null;
        long[] arr = new long[pos.size()];
        SpaceTag[] tags = new SpaceTag[arr.length];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = pos.get(i);
            tags[i] = tag;
        }
        ctx.ops.add(new Set(arr, tags));
        return null;
    }

    static {
        AreaOps.register(SET, buf -> {
            long[] pos = CellIO.readPositions(buf, MAX_CELLS);
            SpaceTag[] tags = new SpaceTag[pos.length];
            for (int i = 0; i < pos.length; i++) tags[i] = SpaceTag.read(buf);
            return new Set(pos, tags);
        });
        EditRequests.register(PAINT, buf -> {
            int group = buf.readVarInt();
            long[] pos = CellIO.readPositions(buf, MAX_CELLS);
            return new Paint(group, pos, SpaceTag.read(buf));
        }, SpaceTagEdits::paint);
        // 되돌리기: 내가 칠한 뒤 아무도 바꾸지 않은 칸만 되돌린다
        UndoHistory.registerInverter(DOMAIN, (area, changes, out) -> {
            SpaceTagLayer layer = SpaceTagLayer.of(area);
            List<UndoChange> inverse = new ArrayList<>();
            if (layer == null) return inverse;
            List<Long> pos = new ArrayList<>();
            List<SpaceTag> tags = new ArrayList<>();
            for (int i = changes.size() - 1; i >= 0; i--) {
                if (!(changes.get(i) instanceof TagChange c)) continue;
                if (layer.tagAt(c.pos()) != c.after()) continue;
                pos.add(c.pos());
                tags.add(c.before());
                inverse.add(0, new TagChange(c.pos(), c.after(), c.before()));
            }
            if (!pos.isEmpty()) {
                long[] arr = new long[pos.size()];
                for (int i = 0; i < arr.length; i++) arr[i] = pos.get(i);
                out.add(new Set(arr, tags.toArray(new SpaceTag[0])));
            }
            return inverse;
        });
    }

    /** 등록해 둔다 (클래스 초기화). */
    public static void bootstrap() {}
}
