# Minedraft

**Minecraft 1.21.1 · NeoForge** 모드 묶음.

| 하위 프로젝트 | 모드 | 하는 일 |
|---|---|---|
| `lib` | Minedraft Lib (`minedraftlib`) | 쿼터뷰 영역 편집창, 청크 영역, 월드 스냅샷, 공간 태그, 동시 편집·되돌리기·저장 |
| `dab` | DaB, Design and Build (`designandbuild`) | 도면·도면 테이블, 블록 설계, 구조물 배치, 월드 투영과 단계별 건설 |

DaB 는 Lib 위에서 동작하므로 두 jar 를 함께 설치해요.

## 빌드

```
gradlew.bat build        # Windows
./gradlew build          # macOS / Linux
```

결과물: `lib/build/libs/minedraftlib-<버전>.jar`, `dab/build/libs/designandbuild-<버전>.jar`

## 진행 상황

라이브러리 분리 중이에요. 단계: 뼈대 → 공용 부품 이동 → 데이터(영역 / 층) 분리 → 편집창 분리 → Lib 시험 명령.
DaB 0.2.20 까지의 기록은 [dAM0419/designandbuild](https://github.com/dAM0419/designandbuild) 에 있어요.

## 라이선스

CC0-1.0
