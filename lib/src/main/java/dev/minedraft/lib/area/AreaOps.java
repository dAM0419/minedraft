package dev.minedraft.lib.area;

import dev.minedraft.lib.MinedraftLib;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/** 영역 변경 종류 목록과 Lib 기본 변경 (이름, 청크 넣기·빼기). */
public final class AreaOps {
    private AreaOps() {}

    private static final Map<ResourceLocation, Function<FriendlyByteBuf, AreaOp>> READERS = new HashMap<>();

    public static void register(ResourceLocation type, Function<FriendlyByteBuf, AreaOp> reader) {
        if (READERS.putIfAbsent(type, reader) != null) throw new IllegalStateException("Duplicate area op " + type);
    }

    public static void write(FriendlyByteBuf buf, AreaOp op) {
        buf.writeResourceLocation(op.type());
        op.write(buf);
    }

    public static AreaOp read(FriendlyByteBuf buf) {
        ResourceLocation type = buf.readResourceLocation();
        Function<FriendlyByteBuf, AreaOp> reader = READERS.get(type);
        if (reader == null) throw new IllegalArgumentException("Unknown area op " + type);
        return reader.apply(buf);
    }

    // ------------------------------------------------------------------

    public static final ResourceLocation RENAME = MinedraftLib.rl("rename");
    public static final ResourceLocation CHUNK_ADD = MinedraftLib.rl("chunk_add");
    public static final ResourceLocation CHUNK_REMOVE = MinedraftLib.rl("chunk_remove");

    public record Rename(String name) implements AreaOp {
        @Override
        public ResourceLocation type() {
            return RENAME;
        }

        @Override
        public void apply(Area a) {
            a.rename(name);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(name);
        }
    }

    public record ChunkAdd(long chunk) implements AreaOp {
        @Override
        public ResourceLocation type() {
            return CHUNK_ADD;
        }

        @Override
        public void apply(Area a) {
            a.addChunk(chunk);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeLong(chunk);
        }
    }

    public record ChunkRemove(long chunk) implements AreaOp {
        @Override
        public ResourceLocation type() {
            return CHUNK_REMOVE;
        }

        @Override
        public void apply(Area a) {
            a.removeChunk(chunk);
        }

        @Override
        public void write(FriendlyByteBuf buf) {
            buf.writeLong(chunk);
        }
    }

    static {
        register(RENAME, b -> new Rename(b.readUtf()));
        register(CHUNK_ADD, b -> new ChunkAdd(b.readLong()));
        register(CHUNK_REMOVE, b -> new ChunkRemove(b.readLong()));
    }

    /** Lib 기본 변경을 등록해 둔다 (클래스 초기화). */
    public static void bootstrap() {}
}
