package dev.minedraft.lib.net;

import dev.minedraft.lib.MinedraftLib;
import dev.minedraft.lib.server.LibServerNet;

/** Minedraft Lib 통신 통로 (minedraftlib:net). 영역 편집창·동기화·구독 메시지가 오간다. */
public final class LibNet {
    private LibNet() {}

    public static final Channel CHANNEL = new Channel(MinedraftLib.rl("net"), LibServerNet::receive);

    // ---- 클라이언트 → 서버 ----
    public static final int C_CLOSE_EDITOR = 1;
    /** [영역 UUID][요청 번호][요청] */
    public static final int C_EDIT = 2;
    /** [영역 UUID][커서 바이트] (편집창이 정한 형식, 그대로 전달) */
    public static final int C_CURSOR = 3;
    /** [영역 UUID][알고 있는 버전]: 영역 변경을 받기 시작 (편집창 없이, 예: 투영) */
    public static final int C_SUBSCRIBE = 4;
    public static final int C_UNSUBSCRIBE = 5;

    // ---- 서버 → 클라이언트 ----
    /** [기준 블록 위치][영역 전체] */
    public static final int S_EDITOR_BEGIN = 1;
    /** [영역 UUID][청크 스냅샷] */
    public static final int S_SNAPSHOT = 2;
    public static final int S_EDITOR_READY = 3;
    /** [영역 UUID][버전][한 사람][요청 번호][연산 수][연산 …] */
    public static final int S_APPLIED = 4;
    /** [영역 UUID][요청 번호][번역 키] */
    public static final int S_REJECTED = 5;
    /** [영역 전체]: 사본을 통째로 바꾼다 */
    public static final int S_AREA_FULL = 6;
    public static final int S_EDITORS = 7;
    public static final int S_CURSOR = 8;
    /** [번역 키] */
    public static final int S_CLOSE_EDITOR = 9;
    /** [번역 키] */
    public static final int S_TOAST = 10;
}
