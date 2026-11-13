package dev.designandbuild.core;

/**
 * 설계 칸의 목적 태그.
 * NONE 은 "태그 없음"으로, 지형 층에서는 공기(철거)에만 쓰인다.
 */
public enum DesignTag {
    NONE, FLOOR, PATH, STRUCTURE;

    private static final DesignTag[] VALUES = values();

    public static DesignTag byId(int id) {
        return id >= 0 && id < VALUES.length ? VALUES[id] : NONE;
    }

    public static void writeAll(net.minecraft.network.FriendlyByteBuf buf, DesignTag[] tags) {
        for (DesignTag t : tags) buf.writeByte(t.ordinal());
    }

    public static DesignTag[] readAll(net.minecraft.network.FriendlyByteBuf buf, int n) {
        DesignTag[] tags = new DesignTag[n];
        for (int i = 0; i < n; i++) tags[i] = byId(buf.readByte());
        return tags;
    }

    /** Lib 공간 태그 (NONE = 없음). */
    @org.jetbrains.annotations.Nullable
    public dev.minedraft.lib.space.SpaceTag space() {
        return switch (this) {
            case FLOOR -> DabTags.FLOOR;
            case PATH -> DabTags.PATH;
            case STRUCTURE -> DabTags.STRUCTURE;
            default -> null;
        };
    }

    /** Lib 공간 태그에서 (DaB 태그가 아니면 NONE). */
    public static DesignTag of(@org.jetbrains.annotations.Nullable dev.minedraft.lib.space.SpaceTag t) {
        if (t == DabTags.FLOOR) return FLOOR;
        if (t == DabTags.PATH) return PATH;
        if (t == DabTags.STRUCTURE) return STRUCTURE;
        return NONE;
    }

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
