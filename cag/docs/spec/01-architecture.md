# 01. 구조

## 1. 패키지

```
dev.civilizeandgovern
  CaG                모드 진입점, 이벤트 버스 연결
  registry           블록, 아이템, 엔티티, 메뉴, 데이터 컴포넌트 등록
  config             Params (08-params.md 의 키를 읽는 한 곳)
  city               City, CityManager(SavedData), CityClock, ChunkLoader, CityPermission, CityLevel, CityStats
  zone               ZoneKind, ZoneData, ZoneIndex (Lib 연동)
  citizen            CitizenRecord, Household, Needs, Traits, CitizenEntity
  citizen.ai         Schedule, 행동(Behavior) 구현, 경로 보조
  economy            Ledger, ItemValues, ItemProps, SaleBox, Listing, PriceRule, Market, Treasury, Tax, Housing, Budget
  job                JobDef, JobRegistry, Skill, JobAssignment, JobChange
  job.task           작업 기본 동작(04-jobs.md 3절)
  job.mine           MinePlan, MineGenerator, MineCoop
  crime              CrimeDef, Resolve, CrimeExec, CaseBook, Witness, Rumor, Collective, Gang
  gov                Request, RequestBox, GuardDuty, Aide, Rule, RuleEngine, Intel, Punishment
  net                패킷과 공개 자료형(…View)
  client             화면, 렌더러
  data               데이터팩 로더, datagen
  compat             다른 모드가 있을 때만 여는 정의
```

의존 방향: `net`·`client` → 나머지. `crime`·`gov` → `economy`·`citizen`·`job`. `economy` → `citizen`. `citizen`·`job` → `city`·`zone`. 거꾸로는 참조하지 않는다.

## 2. 등록물

| 종류 | id | 설명 | 단계 |
|---|---|---|---|
| 블록 | `plaza_block` | 광장 블록. 도시의 시작점. 블록 엔티티가 도시 id를 가진다. 우클릭하면 도시 패널 | 1 |
| 블록 | `map_table` | 지도 테이블. 도시를 색으로 그린 평면 지도 | 1 |
| 블록 | `locker` | 사물함. 작업구역의 작업함·판매함·주문 창구 | 2 |
| 블록 | `vault_block`, `vault_casing` | 금고 블록과 금고 외벽. 금고 블록을 철 블록 26개로 감싸면 도시 금고 | 4 |
| 아이템 | `coin`, `coin_10`, `coin_100` | 동화. 데이터 컴포넌트 `city_id`(int)와 `currency_name`(표시용)을 가진다. 다른 도시의 동화는 그 도시에서 안 통한다 | 1 |
| 엔티티 | `citizen` | 주민 | 2 |
| 메뉴 | `city_panel`, `map_table`, `locker`, `vault`, `recruit`, `citizen_info` | 11절 화면 참고 | 1~4 |
| 아이템 | `job_book` | 직업 서적. 컴포넌트 `job_id`. 사서가 만든다 | 5 |
| 포션 효과 | (없음) | 바닐라 효과만 쓴다 | |

CaG가 새로 넣는 블록과 아이템은 이것이 전부다. 나머지는 바닐라 것을 쓴다. 블록의 자세한 명세는 `09-blocks.md`.

## 3. 주민 엔티티

- `CitizenEntity extends PathfinderMob`. 나이·직업·성향은 엔티티에 두지 않고 명부(`CitizenRecord`)에 둔다. 엔티티는 `citizenId`(UUID = 엔티티 UUID)와 `cityId` 만 저장한다.
- 인벤토리: `SimpleContainer(18)` + 손에 든 도구 한 칸. 엔티티 NBT에 저장한다.
- `setPersistenceRequired()`. 자연 소멸하지 않는다.
- **명부와 엔티티의 대조:** 도시가 켜질 때와 결산 때 한다.
  - 명부에 살아 있는데 엔티티가 없다 → 그 주민의 침대 옆(없으면 광장 블록 옆)에 다시 만든다. 인벤토리는 잃는다(기록에 남긴다).
  - 엔티티는 있는데 명부에 없다 → 엔티티를 지운다.
- **죽음:** `LivingDeathEvent` 에서 명부에 사망을 기록한다(사인: 노환, 몬스터, 플레이어, 주민, 사고). 가해자가 플레이어면 `06-governance.md` 7절로 넘긴다.
- **영입 변환:** 바닐라 `Villager`(성체, 떠돌이 상인 제외)를 지우고 같은 자리에 `CitizenEntity` 를 만든다. 이름표 이름을 잇는다.
- **렌더:** 바닐라 주민 모델을 다시 쓴다. 직업마다 바닐라 직업 옷 텍스처를 대응시킨다(`JobDef.skin`). 미성년은 0.5배 크기.
- **바닐라 몹과의 관계:**
  - `EntityJoinLevelEvent` 에서 좀비 계열에 `NearestAttackableTargetGoal<CitizenEntity>` 를 붙인다.
  - 좀비에게 죽어도 좀비 주민이 되지 않는다(D-28).
  - 철 골렘, 습격(raid), 바닐라 소문과는 엮지 않는다.
- **플레이어 조작:**
  - 빈손 우클릭 → 주민 정보 화면(공개 정보).
  - 아이템을 들고 Shift+우클릭 → 그 아이템 묶음을 건넨다. 받은 것은 그 가구의 것이 된다.
  - 동화를 들고 Shift+우클릭 → 그 가구 지갑에 넣는다.
  - 주민 정보 화면의 "돈 건네기" → 금고 잔고에서 그 가구 지갑으로 바로 보낸다(`GIFT`, 권한 `TREASURY_SPEND`). 금고 블록이 없어도 된다.

## 4. 저장

`CityManager extends SavedData`, 차원마다 하나. 도시 하나의 NBT 구조:

```
City {
  dataVersion: int            // 구조가 바뀌면 올리고 이주 코드를 둔다
  id: int
  name: string
  founder: UUID
  seed: long
  areaId: (Lib 영역 식별자)
  plazaPos: BlockPos
  clock { cityDay: long, lastDayTime: long }
  currency { name: string }
  treasury { balance: long, issuedTotal: long, log: [ {day, amount, reason} … 최근 200건 ] }
  policy { incomeTaxRate, buyoutMultiple, allowPrivateRent: bool, prohibitedItems[], forceLoad: bool, salaries{guard, aideAdmin, aideIntel},
           purchase { enabled, autoIssue, autoRelease, dailyBudget, itemsOff[] }, citySale { item → price } }
  priceAnchor: double         // P
  zones: [ ZoneData ]
  citizens: [ CitizenRecord ]
  households: [ Household ]
  containers: [ ContainerClaim ]
  market { listings: [ Listing ], orders: [ Order ], itemStats{…} }
  vaults: [ { controllerPos, front, formedDay } ]     // 창고 내용물은 금고 블록 엔티티가 가진다
  map: CityMap                                       // 지도 테이블의 지형 층 (09-blocks.md 3절)
  requests: [ Request ]
  cases: [ Case ]
  rumors: [ Rumor ]
  collectives: [ Collective ]
  gangs: [ Gang ]
  rules: [ Rule ]
  council: [ {player UUID, permissions bitset} ]
  stats: 최근 60일의 일일 지표
}
```

- 각 레코드의 필드는 해당 명세 파일에 있다.
- 저장은 `setDirty()` 로 바닐라 저장 주기에 맡긴다.
- 구역이 칠해진 칸 자체는 Lib의 `SpaceTagLayer` 가 저장한다. CaG는 (태그, 개체 번호)에 붙는 부가 정보만 `ZoneData` 로 저장한다.

## 5. Lib 연동

- **영역 타입** `civilizeandgovern:city`: `maxChunks` 는 시 레벨이 정한다(`06-governance.md` 5절). `initialRadius` 0(광장 블록이 있는 청크 하나). 청크 추가는 Lib 편집창의 청크 편집으로 한다.
- **태그 그룹** `civilizeandgovern:zone`: 개체 번호 사용(`instanced = true`), 그룹 안 배타.
  - 태그: 직업 구역마다 하나(`04-jobs.md` 의 `zone_tag`) + `residence`, `plaza`, `jail`, `post`.
  - 태그는 모드 초기화 때 코드에서 전부 등록한다(D-34). 다른 모드 호환으로 구역을 더하려면 초기화 때 CaG의 등록 API를 부른다.
- **구역 안 판정:** 칸 `c` 가 구역 `Z` 에 속한다 ⇔ `c` 가 `Z` 로 칠해졌거나, `c` 아래 1~`§zone.headroom` 칸 안에 `Z` 로 칠해진 칸이 있다(가장 가까운 아래 칸이 이긴다).
- **면적:** 그 개체가 칠해진 서로 다른 (x, z) 기둥의 수.
- **`ZoneIndex`:** 청크 → 그 청크에 걸친 구역 개체 목록, 개체 → 칸 목록·면적·경계 상자의 캐시. Lib의 태그 변경 알림을 받아 갱신한다. 위치로 구역을 찾는 일은 전부 여기를 거친다.
- **`ZoneData`:** `{ tag, instance, name, ownerHousehold?(거주구역), rent(시 소유 거주구역의 주간 임대료), forSale: bool, salePrice?, assignees[](작업구역), tenants[](거주구역의 가구), jobParams{…}(직업별: 목장의 동물 종 등) }`
- **구역 삭제:** 편집창에서 개체의 칸을 전부 지우면 그 구역이 사라진다. 배정된 주민은 무직이 되고, 살던 가구는 무주택이 된다. 가구 소유 거주구역은 편집창에서 지울 수 없다(서버가 거부: 그 칸의 태그 변경 요청을 되돌린다).
- **편집 권한:** `AreaAccess.register(cityType, rule)`. `EDIT_ZONES` 권한이 있으면 `EDIT`, 그 밖의 의원은 `VIEW`, 외부인은 `NONE`.
- **구역 이름:** 편집창의 `tagInstanceName` 을 덮어써 `ZoneData.name` 을 보여 준다.
- **편집창 일시정지:** `isPauseScreen` 은 false.
- **L8(스냅숏 갱신):** 편집창이 열린 동안 주민이 바꾼 블록은 새로 고침 단추로 반영한다. 2단계 전에 Lib에 넣는다.

## 6. 시계

- `cityDay`: 도시가 켜진 채로 맞은 새벽의 수. 수명, 세금, 쿨다운은 전부 이 값으로 센다.
- **새벽 감지:** 서버 틱마다 월드의 `dayTime / 24000` 몫이 `lastDayTime / 24000` 몫보다 크면 결산을 한 번 돈다. 여러 날을 건너뛰어도 한 번만 돈다.
- **밤 건너뜀 감지:** 한 틱에 `dayTime` 이 100 넘게 뛰면 `onTimeSkip` 을 부른다(D-27).
- **요일:** `cityDay % 7`. 0~6. 세금일은 6.

| 구간 (하루 틱) | 이름 | 주민이 하는 일 |
|---|---|---|
| 0 ~ 1000 | 아침 | 기상, 식사 |
| 1000 ~ 5500 | 오전 | 작업 |
| 5500 ~ 6500 | 낮 | 식사(허기가 낮을 때만), 휴식 |
| 6500 ~ 10500 | 오후 | 작업 |
| 10500 ~ 12000 | 저녁 | 시 수매(대상 가구만), 장보기(주문 넣기와 받기 포함), 납세, 사교 |
| 12000 ~ 13000 | 귀가 | 귀가, 식사 |
| 13000 ~ 24000 | 밤 | 수면. 범죄 실행 창은 14000 ~ 22000 |

## 7. 결산 순서

새벽마다 아래 순서로 한 번 돈다. 주민 수가 많으면 6~9단계를 주민 `§perf.settle_batch` 명씩 여러 틱에 나눠 돈다. 나눠 도는 동안 엔티티 행동은 전날 계획을 따른다.

| 순서 | 단계 | 내용 | 명세 |
|---|---|---|---|
| 0 | 날짜 | `cityDay++`, 난수 준비, 명부와 엔티티 대조 | 01 |
| 1 | 추상 처리 | 전날 실행 창을 놓친 구매·주문 넣기와 받기·시 수매·범죄·납부를 결과만 처리 | 불변식 6 |
| 2 | 생애 | 나이 +1, 성년·노년 전환, 자연사, 아사, 상속 | 02 |
| 3 | 시장 | 전날 판매 집계, 판매량 평활, 목표 재고, 물가 기준선, 호가 배수 조정, 주문의 기한 처리 | 03 |
| 4 | 금고 | 공직 봉급 지급. 광산 조합의 전날 수입 나누기. 세금일이면 주간 장부를 닫고 고지서 발부. 세금일 다음 날이면 자동 납부와 체납 처리 | 03, 04 |
| 5 | 욕구 | 하루 단위 욕구 갱신, 행복도, 통계 | 02 |
| 6 | 예산 | 가구별 예산 편성 | 03 |
| 7 | 가구 결정 | 장보기 목록, 요청 올리기, 구혼·혼인·출산, 주택 매수, 이직 | 02, 03, 04 |
| 8 | 범죄 | 범행 결심, 집단 행동 진행, 조직 진행 | 05 |
| 9 | 치안 | 수사 진행, 소문 전파(하루 몫), 사건 만료 | 05, 06 |
| 10 | 행정 | 요청 기한 만료, 부관 규칙 실행, 정보 보고 | 06 |
| 11 | 작업 계획 | 주민별 오늘의 작업 대기열 | 04 |
| 12 | 마무리 | 시 레벨 갱신, 공개 지표 전송, 저장 표시 | 06 |

순서의 이유: 생애(2)가 가구 구성을 바꾸므로 예산(6)보다 앞이다. 시장(3)이 값을 정해야 예산이 식비를 안다. 봉급(4)이 들어와야 예산이 맞다. 범죄(8)는 그날의 욕구(5)를 본다.

## 8. 청크 로딩

- `ChunkLoader` 가 20틱마다 도시별로 판정한다.
  - `active` = 강제 로딩이 켜져 있다 OR 어떤 플레이어의 청크와 어떤 관리 청크의 체비쇼프 거리가 서버 시뮬레이션 거리 이하다.
  - 꺼져 있는데 `active` → 모든 관리 청크에 강제 로딩 표를 건다(엔티티까지 도는 등급).
  - 켜져 있는데 `active` 가 아닌 상태가 `§load.off_delay_ticks` 이어짐 → 전부 푼다.
- 판정은 **플레이어 위치**로 한다. 청크가 로딩됐는지로 판정하면 CaG가 건 표 때문에 영영 안 꺼진다.
- 구현 수단: NeoForge의 청크 표 제어(`TicketController`)를 쓴다. 정확한 API 이름과 등록 이벤트는 1단계에서 21.1 소스로 확인한다. 안 되면 `ServerLevel.setChunkForced` 로 대신한다.
- 도시가 꺼질 때: 진행 중인 작업은 중단 상태로 둔다(다시 켜지면 작업 계획부터 다시).
- 광산은 관리 청크 안의 구역 아래로만 자라므로 따로 다루지 않는다.

## 9. 네트워크

원칙: 서버 → 클라이언트는 공개 자료형(`…View`)만 보낸다(불변식 3). 클라이언트 → 서버 패킷은 처리 전에 권한을 검사한다(불변식 9).

| 방향 | 패킷 | 내용 | 권한 |
|---|---|---|---|
| S→C | `CityOverview` | 이름, 인구, 평균 행복도, P, 통화량, 금고 잔고, 시 레벨 | 의원 |
| S→C | `CitizenList` / `CitizenView` | 이름, 나이 단계, 직업, 가구, 거주, 공개 정보 | 의원 |
| S→C | `MarketView` | 품목별 최저 호가, 매물 수, 전날 판매량 | 누구나(도시 안) |
| S→C | `RequestList`, `CaseList` | 알려진 필드만 | 의원 |
| S→C | `IntelReport` | 정보 부관이 연 필드 | 의원 |
| C→S | `FoundCity` | 이름, 화폐 이름 | 광장 블록을 놓은 사람 |
| C→S | `Recruit` | 대상 엔티티, 정착금 | `RECRUIT` |
| C→S | `AssignJob`, `AssignHome` | 주민/가구, 구역 | `ASSIGN` |
| C→S | `HandleRequest` | 요청 id, 수락/반려 | `HANDLE_REQUESTS` |
| C→S | `Judge` | 사건 id, 처벌 | `JUDGE` |
| C→S | `Issue` | 금액 | `ISSUE` |
| C→S | `SetPolicy` | 세율, 임대료, 매수 배수, 금지 품목, 봉급 | `SET_TAX` |
| C→S | `TreasuryWithdraw` / `Deposit` | 금액(동화 아이템과 교환). 금고 화면에서만 | `TREASURY_SPEND` |
| C→S | `BuyFromLocker` | 사물함, 품목, 수량 (금고 돈으로) | `TREASURY_SPEND` |
| C→S | `PlaceOrder` | 사물함, 품목, 수량 (금고 돈으로 발주) | `TREASURY_SPEND` |
| C→S | `VaultTransfer` | 금고, 창고 칸 이동·꺼내기·넣기 | `TREASURY_SPEND` |
| C→S | `SetPurchasePolicy`, `SetCitySale` | 수매 설정, 판매 목록과 값 | `SET_TAX` |
| C→S | `RefreshMap` | 지도의 지형 층 갱신 | `EDIT_ZONES` |
| S→C | `CityMapData` | 지형 층 바이트와 구역 층, 판 번호 | 도시 안 누구나 |
| C→S | `Appoint`, `SetGuardDuty` | 주민, 공직·임무 | `APPOINT` |
| C→S | `EditRules` | 규칙 목록 | `RULES` |
| C→S | `SetForceLoad` | 켬/끔 | `FORCE_LOAD` |
| C→S | `SetCouncil` | 플레이어, 권한 | `COUNCIL` |

## 10. 권한

`CityPermission`: `EDIT_ZONES`, `RECRUIT`, `ASSIGN`, `HANDLE_REQUESTS`, `JUDGE`, `TREASURY_SPEND`, `SET_TAX`, `ISSUE`, `APPOINT`, `RULES`, `FORCE_LOAD`, `COUNCIL`.

- 도시를 세운 플레이어가 시장이고 전부 가진다. 시장은 한 명이다.
- 의원은 시장이 준 것만 가진다. `COUNCIL` 은 시장만 가진다.
- 권한이 하나도 없는 플레이어는 외부인이다.

## 11. 화면

| 화면 | 여는 법 | 내용 | 단계 |
|---|---|---|---|
| 도시 세우기 | 주인 없는 광장 블록 우클릭 | 도시 이름, 화폐 이름 입력 | 1 |
| 도시 패널 | 광장 블록 우클릭 | 탭: 개요, 주민, 구역(Lib 편집창 열기), 시장, 금고, 요청, 사건, 공직, 규칙, 의회, 설정 | 1부터 탭이 늘어남 |
| 영입 | 관리 청크 안 바닐라 주민을 Shift+우클릭 (`RECRUIT` 권한) | 정착금 입력(금고 잔고에서 나감) | 2 |
| 주민 정보 | 주민 빈손 우클릭 | 공개 정보, 직업·집 배정 단추 | 2 |
| 사물함 | 사물함 우클릭 | 탭: 매물(구매), 주문(발주와 수령), 내용물(보기, 꺼내기는 경고) | 2~3 |
| 지도 | 지도 테이블 Shift+우클릭 (그냥 우클릭은 도시 패널) | 큰 지도, 범례, 구역 정보, 갱신 단추 | 1 |
| 금고 | 금고 앞면 우클릭 | 탭: 잔고, 창고, 수매, 판매 | 4 |
| 구역 편집 | 패널의 구역 탭 | Lib 편집창. 구역 선택 시 옆에 구역 정보(배정, 임대료, 판매 여부) | 1 |

## 12. 성능 예산

- 주민 수 상한은 시 레벨이 정하고, 절대 상한은 `§perf.max_citizens`.
- 경로 탐색: 주민 한 명이 `§perf.path_cooldown_ticks` 안에 다시 탐색하지 않는다. 목적지가 `§perf.path_far` 보다 멀면 길 태그(DaB)나 구역 경유지를 거쳐 나눠 간다.
- 목격 판정은 범죄 실행 순간에만, 반경 안 주민에게만 한다.
- 결산은 주민 수에 선형이다. 주민 쌍을 도는 계산(호감, 소문)은 "오늘 실제로 마주친 쌍"만 다룬다.

## 13. 테스트

- **단위:** `economy`, `crime` 의 수식과 결산 단계는 월드 없이 도는 순수 함수로 짠다. 시뮬레이터(`sim/market_sim.py`)의 결과와 같은 방향인지 본다.
- **게임 테스트(NeoForge GameTest):** 단계마다 `07-stages.md` 의 인수 기준을 하나씩 테스트로 만든다.
- **불변식 검사:** 결산 끝에 디버그 모드에서 돈의 합(불변식 1)을 검사한다.
