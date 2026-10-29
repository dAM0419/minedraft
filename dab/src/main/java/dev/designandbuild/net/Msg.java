package dev.designandbuild.net;

/** DaB 메시지 종류 번호 (DaB 통로). 편집창·동기화·구독 메시지는 Minedraft Lib 통로(LibNet)로 오간다. */
public final class Msg {
    private Msg() {}

    /** 0 은 조각 (Channel.PART) 으로 예약. */

    // ---- 클라이언트 → 서버 ----
    public static final int C_OPEN_EDITOR = 1;
    public static final int C_START_DESIGN = 2;
    public static final int C_LIBRARY_REQ = 8;
    public static final int C_TEMPLATE_REQ = 9;
    public static final int C_TABLE_INFO_REQ = 10;
    public static final int C_TABLE_RENAME = 11;
    public static final int C_VIEW_STATE = 12;
    public static final int C_INSTANT_APPLY = 13;
    public static final int C_INSTANT_UNDO = 14;
    public static final int C_INSTANT_QUERY = 15;
    public static final int C_HAMMER = 16;

    // ---- 서버 → 클라이언트 ----
    public static final int S_LIBRARY = 10;
    public static final int S_TEMPLATE = 11;
    public static final int S_TABLE_INFO = 12;
    public static final int S_TOAST = 13;
    public static final int S_INSTANT_STATE = 14;
    public static final int S_HAMMER_RESULT = 15;
    public static final int S_TOAST_N = 16;
    public static final int S_HAMMER_FAIL = 17;
}
