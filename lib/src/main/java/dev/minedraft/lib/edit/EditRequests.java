package dev.minedraft.lib.edit;

import dev.minedraft.lib.MinedraftLib;
import dev.minedraft.lib.area.Area;
import dev.minedraft.lib.area.AreaOp;
import dev.minedraft.lib.area.AreaOps;
import dev.minedraft.lib.util.CellIO;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/** 편집 요청 종류 목록(읽기 + 서버 처리기)과 Lib 기본 요청 (이름 바꾸기, 청크 바꾸기, 되돌리기, 다시 하기). */
public final class EditRequests {
    private EditRequests() {}

    /** 서버 처리기: 요청을 확정 연산과 되돌리기 기록으로 바꾼다. 오류면 번역 키 (적용하지 않는다). */
    public interface Handler<R extends EditRequest> {
        @Nullable
        String handle(EditContext ctx, R request);
    }

    private record Kind<R extends EditRequest>(Function<FriendlyByteBuf, R> reader, Handler<R> handler) {}

    private static final Map<ResourceLocation, Kind<?>> KINDS = new HashMap<>();

    public static <R extends EditRequest> void register(ResourceLocation type, Function<FriendlyByteBuf, R> reader, Handler<R> handler) {
        if (KINDS.putIfAbsent(type, new Kind<>(reader, handler)) != null) throw new IllegalStateException("Duplicate edit request " + type);
    }

    public static void write(FriendlyByteBuf buf, EditRequest req) {
        buf.writeResourceLocation(req.type());
        req.write(buf);
    }

    public static EditRequest read(FriendlyByteBuf buf) {
        ResourceLocation type = buf.readResourceLocation();
        Kind<?> k = KINDS.get(type);
        if (k == null) throw new IllegalArgumentException("Unknown edit request " + type);
        return k.reader().apply(buf);
    }

    @SuppressWarnings("unchecked")
    @Nullable
    public static String handle(EditContext ctx, EditRequest req) {
        Kind<EditRequest> k = (Kind<EditRequest>) KINDS.get(req.type());
        if (k == null) return "minedraftlib.msg.unknown_request";
        return k.handler().handle(ctx, req);
    }

    // ------------------------------------------------------------------
    // Lib 기본 요청
    // ------------------------------------------------------------------

    public static final ResourceLocation RENAME = MinedraftLib.rl("rename");
    public static final ResourceLocation CHUNK_CHANGE = MinedraftLib.rl("chunk_change");
    public static final ResourceLocation UNDO = MinedraftLib.rl("undo");
    public static final ResourceLocation REDO = MinedraftLib.rl("redo");
    /** Lib 기본 되돌리기 영역 (영역 이름). */
    public static final ResourceLocation DOMAIN = MinedraftLib.rl("area");

    public record Rename(String name) implements EditRequest {
        @Override
        public ResourceLocation type() {
            return RENAME;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(name);
        }
    }

    /** 청크 넣기·빼기를 한꺼번에 (범위 탭). 되돌리기 기록에 남지 않는다. */
    public record ChunkChange(long[] add, long[] remove) implements EditRequest {
        @Override
        public ResourceLocation type() {
            return CHUNK_CHANGE;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            CellIO.writePositions(buf, add);
            CellIO.writePositions(buf, remove);
        }
    }

    public record Undo() implements EditRequest {
        @Override
        public ResourceLocation type() {
            return UNDO;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
        }
    }

    public record Redo() implements EditRequest {
        @Override
        public ResourceLocation type() {
            return REDO;
        }

        @Override
        public void write(FriendlyByteBuf buf) {
        }
    }

    record AreaRenamed(String before, String after) implements UndoChange {
        @Override
        public ResourceLocation domain() {
            return DOMAIN;
        }
    }

    /** 이름 등 화면에 보이는 짧은 글을 다듬는다 (제어 문자·서식 기호 제거, 길이 제한). */
    public static String sanitize(String s, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < max; i++) {
            char c = s.charAt(i);
            if (c >= 32 && c != 127 && c != '§') b.append(c);
        }
        return b.toString().trim();
    }

    static {
        AreaOps.bootstrap();
        register(RENAME, b -> new Rename(b.readUtf(256)), (ctx, r) -> {
            String name = sanitize(r.name(), 48);
            if (name.isEmpty() || name.equals(ctx.area.name())) return null;
            ctx.undo.add(new AreaRenamed(ctx.area.name(), name));
            ctx.ops.add(new AreaOps.Rename(name));
            return null;
        });
        register(CHUNK_CHANGE, b -> new ChunkChange(CellIO.readPositions(b, 4096), CellIO.readPositions(b, 4096)), (ctx, c) -> {
            Area m = ctx.area;
            // 이미 반영된 것은 건너뛴다 (다른 편집자가 먼저 바꿨을 때)
            LongOpenHashSet add = new LongOpenHashSet(), remove = new LongOpenHashSet();
            for (long a : c.add()) if (!m.chunks().contains(a)) add.add(a);
            for (long r : c.remove()) if (m.chunks().contains(r)) remove.add(r);
            add.removeAll(remove);
            if (add.isEmpty() && remove.isEmpty()) return null;
            String error = m.checkChunkChange(add, remove);
            if (error != null) return error;
            for (long r : remove) ctx.ops.add(new AreaOps.ChunkRemove(r));
            for (long a : add) {
                ctx.ops.add(new AreaOps.ChunkAdd(a));
                ctx.addedChunks.add(a);
            }
            return null;
        });
        register(UNDO, b -> new Undo(), (ctx, r) -> ctx.history.undo(ctx.area, ctx.ops) ? null : "minedraftlib.msg.nothing_to_undo");
        register(REDO, b -> new Redo(), (ctx, r) -> ctx.history.redo(ctx.area, ctx.ops) ? null : "minedraftlib.msg.nothing_to_redo");
        UndoHistory.registerInverter(DOMAIN, (area, changes, out) -> {
            List<UndoChange> inverse = new java.util.ArrayList<>();
            for (int i = changes.size() - 1; i >= 0; i--) {
                if (changes.get(i) instanceof AreaRenamed r && area.name().equals(r.after())) {
                    out.add(new AreaOps.Rename(r.before()));
                    inverse.add(0, new AreaRenamed(r.after(), r.before()));
                }
            }
            return inverse;
        });
    }

    /** Lib 기본 요청을 등록해 둔다 (클래스 초기화). */
    public static void bootstrap() {}

    /** 확정 연산을 적용한다 (서버·클라이언트 공통). */
    public static void applyAll(Area area, List<AreaOp> ops) {
        for (AreaOp op : ops) op.apply(area);
    }
}
