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

    public String key() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
