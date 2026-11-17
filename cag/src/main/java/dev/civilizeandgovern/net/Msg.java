package dev.civilizeandgovern.net;

/** 메시지 종류 (0 은 Lib Channel 이 조각용으로 쓴다). 명세 01-architecture.md 9절. */
public final class Msg {
    private Msg() {}

    // 클라이언트 → 서버
    public static final int C_FOUND = 1;        // 광장 위치, 도시 이름, 화폐 이름
    public static final int C_PANEL = 2;        // 도시 id: 도시 패널 자료 요청
    public static final int C_OPEN_EDITOR = 3;  // 도시 id: 구역 편집창 열기
    public static final int C_MINT = 4;         // 주조대 위치, 녹일 구리 주괴 수 (발행. D-56)
    public static final int C_FORCE_LOAD = 5;   // 도시 id, 켬/끔
    public static final int C_COUNCIL = 6;      // 도시 id, 플레이어 이름, 권한 비트 (0 = 의원에서 뺌)

    public static final int C_MOVE_PLAZA = 7;   // 새 광장 블록 위치: 광장을 이곳으로 옮긴다 (D-55)
    public static final int C_ALLOW_DISSOLVE = 8; // 도시 id, 켬/끔: 도시 해체 허용
    public static final int C_MINT_TAKE = 9;    // 주조대 위치: 넣어 둔 구리를 도로 꺼낸다

    // 서버 → 클라이언트
    public static final int S_FOUND_SCREEN = 1; // 광장 위치: 도시 세우기 화면을 연다
    public static final int S_PANEL = 2;        // 열기 여부, CityView
    public static final int S_MOVE_SCREEN = 3;  // 새 광장 블록 위치, 도시 이름: 광장 옮기기 확인 화면
    public static final int S_MINT = 4;         // 열기 여부, MintView
}
