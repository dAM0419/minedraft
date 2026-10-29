package dev.designandbuild.net;

/** 메시지 종류 번호. 0 은 분할 조각. */
public final class Msg {
    private Msg() {}

    public static final int PART = 0;

    // ---- 클라이언트 → 서버 ----
    public static final int C_OPEN_EDITOR = 1;
    public static final int C_START_DESIGN = 2;
    public static final int C_CLOSE_EDITOR = 3;
    public static final int C_EDIT = 4;
    public static final int C_CURSOR = 5;
    public static final int C_SUBSCRIBE = 6;
    public static final int C_UNSUBSCRIBE = 7;
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
    public static final int S_EDITOR_BEGIN = 1;
    public static final int S_SNAPSHOT = 2;
    public static final int S_EDITOR_READY = 3;
    public static final int S_APPLIED = 4;
    public static final int S_REJECTED = 5;
    public static final int S_MODEL_FULL = 6;
    public static final int S_EDITORS = 7;
    public static final int S_CURSOR = 8;
    public static final int S_CLOSE_EDITOR = 9;
    public static final int S_LIBRARY = 10;
    public static final int S_TEMPLATE = 11;
    public static final int S_TABLE_INFO = 12;
    public static final int S_TOAST = 13;
    public static final int S_INSTANT_STATE = 14;
    public static final int S_HAMMER_RESULT = 15;
    public static final int S_TOAST_N = 16;
    public static final int S_HAMMER_FAIL = 17;
}
