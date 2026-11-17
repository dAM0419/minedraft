# 08. 수치표

모든 수치의 기본값이다. 본문의 `§키` 는 여기의 키다. 구현에서는 `Params` 한 곳이 설정 파일(`civilizeandgovern-server.toml`)에서 읽는다. 값은 임시이며, 3단계의 재시뮬레이션과 플레이 시험으로 고친다.

"근거"가 시뮬인 값은 `03-economy.md` 11절에서 검증한 것이다.

| 키 | 기본값 | 단위 | 뜻 | 근거 |
|---|---|---|---|---|
| `zone.headroom` | 3 | 칸 | 칠한 칸 위로 구역에 드는 높이 | |
| `load.off_delay_ticks` | 200 | 틱 | 플레이어가 떠난 뒤 로딩을 푸는 지연 | |
| `perf.settle_batch` | 20 | 명 | 결산을 한 틱에 처리하는 주민 수 | |
| `perf.max_citizens` | 200 | 명 | 도시당 주민 절대 상한 | |
| `perf.path_cooldown_ticks` | 40 | 틱 | 주민당 경로 재탐색 최소 간격 | |
| `perf.path_far` | 48 | 블록 | 이보다 멀면 경유지로 나눠 간다 | |
| `ai.flee_range` | 8 | 블록 | 몬스터를 보고 달아나는 거리 | |
| `ai.path_fail_max` | 3 | 회 | 같은 목적지 실패 허용 횟수 | |
| `traits.subversion_mean` | 35 | | 영입 주민의 불온성 평균 | |
| `traits.inherit_sd` | 12 | | 유전 변이의 표준편차 | |
| `traits.sub_rumor_k` | 0.3 | | 소문 심각도당 불온성 상승 | |
| `social.relations_max` | 30 | 명 | 주민당 호감 기록 수 | |
| `social.meets_per_day` | 3 | 회 | 주민당 하루 만남 수 | |
| `social.affinity_base` | 2 | | 만남당 호감 기본 상승 | |
| `needs.hunger_decay` | 40 | /일 | 허기 감소 | |
| `needs.food_restore` | 4 | | 바닐라 영양값당 허기 회복 | |
| `needs.meal_target` | 80 | | 한 끼에 채우는 허기 | |
| `needs.meal_max_items` | 3 | 개 | 한 끼에 먹는 최대 개수 | |
| `needs.starve_days` | 5 | 일 | 허기 0이 이만큼 이어지면 사망 | 시뮬 |
| `needs.starve_grace_days` | 28 | 일 | 영입·출생 뒤 아사하지 않는 기간 | 시뮬 |
| `needs.housing_space_full` | 16 | 칸/명 | 공간 점수가 꽉 차는 1인당 면적 | |
| `needs.housing_furn_full` | 150 | 값/명 | 가구 점수가 꽉 차는 1인당 가구 가치 | |
| `needs.housing_crowd_penalty` | 12 | | 같은 집의 다른 가구 하나당 감점 | |
| `needs.luxury_decay` | 8 | /일 | | |
| `needs.interest_decay` | 6 | /일 | | |
| `needs.interest_book` | 30 | | 책 한 권의 흥미 | |
| `needs.safety_unsolved` | 6 | | 미해결 사건 하나당 안전 목표 감점 | |
| `needs.safety_patrol` | 20 | | 순찰 밀도 1의 안전 목표 가점 | |
| `needs.trust_base` | 70 | | 신뢰 목표의 바탕 | |
| `needs.trust_tax` | 0.6 | /%p | 소득세율 1%p당 신뢰 목표 감점 | |
| `needs.trust_infl` | 2 | | 주간 물가 상승 1%당 신뢰 목표 감점 | |
| `life.adult_age` | 10 | 일 | 성년 | |
| `life.elder_age` | 100 | 일 | 노년 | |
| `life.lifespan_mean` | 120 | 일 | 평균 수명 | |
| `life.lifespan_sd` | 10 | 일 | 수명 표준편차 | |
| `life.elder_work_mult` | 0.7 | | 노년의 작업 속도 배수 | |
| `life.coming_of_age_share` | 0.1 | | 성년이 될 때 부모 지갑에서 받는 비율 | |
| `life.recruit_age_max` | 40 | 일 | 영입 주민 나이의 위쪽 끝 | |
| `life.orphan_stipend` | 20 | /일 | 보호자 없는 미성년 지원금(× P) | |
| `marriage.affinity_min` | 60 | | 혼인에 필요한 서로의 호감 | |
| `marriage.wait_days` | 14 | 일 | 물품을 모으며 기다리는 기간 | |
| `marriage.cooldown_days` | 10 | 일 | 구혼을 접은 뒤 쉬는 기간 | |
| `birth.chance` | 0.25 | /일 | 조건이 맞는 날의 출산 확률 | |
| `birth.max_parent_age` | 80 | 일 | 부모 나이 상한 | |
| `birth.cooldown_days` | 12 | 일 | 출산 간격 | |
| `value.craft_markup` | 0.1 | | 가공품 기준가의 덧값 | |
| `value.day_wage` | 100 | 동화 | 기준 일당. 기준가 산정의 바탕 | |
| `value.work_ticks` | 8500 | 틱 | 하루 작업 틱(오전 4500 + 오후 4000) | |
| `order.deposit` | 0.5 | | 주문 선금 비율 | 시뮬 |
| `order.timeout_days` | 4 | 일 | 주문품이 이 안에 안 되면 환불 | |
| `order.pickup_days` | 5 | 일 | 완성 뒤 이 안에 안 찾아가면 매물로 | |
| `order.queue_up` | 3 | 건 | 대기 주문이 이 이상이면 호가 인상 | 시뮬 |
| `order.queue_max` | 8 | 건 | 제작자 한 명의 대기 주문 상한 | |
| `fund.buy_k` | 0.5 | | 시 수매가(기준가 × P 대비) | 시뮬 |
| `fund.living_mult` | 1.5 | | 생계비의 식비 대비 배수 | 시뮬 |
| `container.home_per_household` | 2 | 개 | 가구가 집에서 잡는 컨테이너 수 | |
| `locker.slots` | 27 | 칸 | 사물함 인벤토리 | |
| `vault.slots` | 270 | 칸 | 금고 하나의 창고 | |
| `mint.coins_per_ingot` | 9 | 동화 | 주조대에서 구리 주괴 1개로 발행되는 금액. 구리 주괴의 기준가(9)와 맞췄다 | D-56 |
| `map.refresh_cooldown_ticks` | 200 | 틱 | 지도 갱신 쿨다운 | |
| `map.max_tables` | 5 | 개 | 한 변에 붙일 수 있는 지도 테이블 수 | |
| `price.step` | 0.05 | | 호가 조정 폭 | 시뮬 |
| `price.target_days` | 3 | 일 | 목표 재고(판매 평활의 며칠치) | 시뮬 |
| `price.target_min_units` | 2 | 개 | 목표 재고의 하한 | 시뮬 |
| `price.band_low` | 0.5 | | 이 배수 밑이면 인상 | 시뮬 |
| `price.band_high` | 1.5 | | 이 배수 위면 인하 | 시뮬 |
| `price.floor_k` | 0.5 | | 호가 배수의 하한 | 시뮬 |
| `price.ceil_k` | 2.0 | | 호가 배수의 상한 | 시뮬 |
| `price.cost_margin` | 1.05 | | 재료비 하한의 배수 | 시뮬 |
| `price.idle_mult` | 2 | | 목표의 이 배수를 넘으면 그 품목 작업을 쉼 | 시뮬 |
| `price.money_ref` | 300 | 동화/성인 | P = 1일 때의 성인 1인당 지갑(가구 중앙값) | 시뮬 |
| `price.anchor_days` | 14 | 일 | P의 평활 기간 | 시뮬 |
| `market.dist_scale` | 256 | 블록 | 거리가 이만큼이면 값이 2배로 느껴짐 | |
| `budget.food_days` | 2 | 일 | 집에 확보하는 식량 | 시뮬 |
| `budget.daily_fraction` | 0.25 | | 여윳돈 중 하루에 쓰는 비율 | 시뮬 |
| `pay.guard` | 30 | /일 | 경비병 기본 일급(× P 아님, 시장이 고침) | |
| `pay.aide` | 40 | /일 | 부관 기본 일급 | |
| `pay.strike_days` | 3 | 일 | 이만큼 못 받으면 일을 멈춤 | |
| `house.land_value` | 8 | 값/칸 | 집값의 땅값 | |
| `house.rent_ratio` | 0.02 | /주 | 시 임대료 기본값(집값 대비) | |
| `house.private_rent_ratio` | 0.03 | /주 | 주민 간 집세의 시작값(집값 대비) | |
| `house.buyout_multiple` | 30 | 주 | 매수 청구 배수의 기본값 | D-29 |
| `house.wallet_share` | 0.5 | | 주택 구매에 보태는 여윳돈 비율 | |
| `house.squat_days` | 5 | 일 | 무주택이 이만큼 이어지면 무단 점유 후보 | |
| `recruit.min_grant` | 50 | 동화 | 정착금 하한 | |
| `job.deposit_stacks` | 4 | 묶음 | 이만큼 차면 사물함에 넣음 | |
| `job.tool_wear_scale` | 0.25 | | 작업당 내구도 소모 확률 | |
| `job.no_tool_days` | 3 | 일 | 도구 없이 이만큼 지나면 물자 요청 | |
| `job.spare_tools` | 1 | 개 | 필수 도구마다 두는 예비품 수 | |
| `job.milk_per_cow` | 2 | 회/일 | | |
| `job.cells_per_animal` | 6 | 칸 | 동물 목표 마릿수의 면적 | |
| `job.cells_per_tree` | 9 | 칸 | 나무 목표 그루의 면적 | |
| `job.repair_markup` | 1.3 | | 수리비의 재료 대비 배수 | |
| `skill.xp2` | 60 | | 견습 | |
| `skill.xp3` | 200 | | 숙련 | |
| `skill.xp4` | 500 | | 전문 | |
| `skill.xp5` | 1200 | | 장인 | |
| `skill.book_xp` | 40 | | 직업 서적 한 권 | |
| `skill.book_cooldown_days` | 5 | 일 | | |
| `merchant.markup` | 0.15 | | 상인의 최소 마진 | |
| `merchant.drop_days` | 7 | 일 | 안 팔리는 품목의 매입 중단 기간 | |
| `mine.branch_spacing` | 3 | 칸 | 가지갱 간격 | 구 기획 |
| `mine.branch_length` | 16 | 칸 | 가지갱 길이 | |
| `mine.segment_length` | 8 | 칸 | 본갱 조각 길이 | |
| `mine.advance_ratio` | 0.8 | | 끝단이 이만큼 파이면 다음 조각 | 구 기획 |
| `mine.vein_max` | 24 | 칸 | 광맥 따라 파기 상한 | |
| `mine.exhaust_warn` | 0.8 | | 새 부지 요청을 올리는 진척 | 구 기획 |
| `mine.torch_spacing` | 8 | 칸 | | |
| `mine.miners_no_manager` | 2 | 명 | 관리자 없는 광산의 광부 정원 | |
| `mine.miners_per_layer` | 3 | 명 | 관리자 있는 광산의 층당 광부 | |
| `mine.return_margin` | 1.2 | | 귀환 시간의 여유 배수 | |
| `mine.min_work_ratio` | 0.5 | | 작업 시간이 이 밑이면 새 부지 요청 | |
| `jobchange.preferred_mult` | 1.5 | | 희망 직업 점수의 선호 가산 | |
| `jobchange.income_ratio` | 0.6 | | 이직을 생각하는 소득 비율 | 시뮬 |
| `jobchange.poor_weeks` | 2 | 주 | 그 상태가 이어져야 하는 기간 | |
| `jobchange.cooldown_days` | 7 | 일 | 반려 뒤 재요청 금지 | |
| `request.deadline_days` | 3 | 일 | 요청 기한 | |
| `request.retry_days` | 5 | 일 | 일터 요청 재시도 간격 | |
| `request.supply_days` | 3 | 일 | 필수품을 못 구한 날수 | |
| `crime.threshold` | 50 | | 범행 문턱 | |
| `crime.prob_scale` | 50 | | 초과분을 확률로 바꾸는 눈금 | |
| `crime.deter_max` | 40 | | 억제의 최댓값 | |
| `crime.want_bonus` | 20 | | 궁핍의 압력 가산 | |
| `crime.grudge_bonus` | 10 | | 원한의 압력 가산 | |
| `crime.grudge_min` | 50 | | 원한으로 치는 호감의 절댓값 | |
| `crime.cooldown_days` | 3 | 일 | 범행 뒤 쉬는 기간 | |
| `crime.skill_stealth` | 2 | | 범죄 숙련 1당 교활함 가산 | |
| `crime.solve_default` | 0.5 | | 사건이 없을 때의 해결률 | |
| `witness.range` | 16 | 블록 | | |
| `witness.base` | 0.8 | | 목격 기본 확률 | |
| `report.base` | 0.9 | | 신고 기본 확률 | |
| `invest.base` | 0.25 | /일 | 수사 진행 기본값 | |
| `case.cold_days` | 14 | 일 | 미제가 되는 기한 | |
| `audit.days` | 2 | 일 | 감사 기간 | |
| `audit.base` | 0.7 | | 감사 발각 기본 확률 | |
| `audit.lookback_weeks` | 4 | 주 | 감사가 거슬러 보는 기간 | |
| `theft.max_stacks` | 2 | 묶음 | | |
| `fraud.notice` | 0.3 | | 속여 팔기를 알아챌 확률 | |
| `assault.injury_days` | 2 | 일 | | |
| `assault.death_chance` | 0.03 | | | |
| `robbery.max` | 100 | 동화 | 강도 한 번의 상한(× P) | |
| `libel.trust_hit` | 4 | | 벽보를 읽은 주민의 신뢰 감소 | |
| `embezzle.rate` | 0.2 | | 조합 수입 중 빼돌리는 비율 | |
| `smuggle.markup` | 1.5 | | 밀매 호가 배수 | |
| `coll.join_discontent` | 45 | | 집단에 드는 불만 문턱 | |
| `coll.min_size` | 4 | 명 | 집단 성립 최소 인원 | |
| `coll.riot_violence` | 50 | | 폭동으로 번지는 평균 폭력성 | |
| `coll.demand_days` | 3 | 일 | 요구 기한 | |
| `coll.hold_days` | 14 | 일 | 약속을 유지해야 하는 기간 | |
| `coll.max_days` | 10 | 일 | 태업·시위의 최장 기간 | |
| `riot.loot_share` | 0.3 | | 폭동이 가져가는 금고 잔고 비율 | |
| `riot.vault_break_ticks` | 600 | 틱 | 폭동이 금고 문을 여는 데 걸리는 시간 | |
| `cartel.min_share` | 0.6 | | 담합 성립에 필요한 판매자 비율 | |
| `cartel.max_mult` | 2.5 | | 담합 호가 상한(기준값 × P 대비) | |
| `gang.recruit_sub` | 55 | | 포섭 대상의 불온성 | |
| `gang.territory_radius` | 48 | 블록 | 세력권 | |
| `gang.extort_rate` | 0.15 | | 보호비(주간 매출 대비) | |
| `gang.bribe_mult` | 1.5 | | 뇌물(주급 대비) | |
| `gang.frame_chance` | 0.2 | | 매수된 경비병이 누명을 씌울 확률 | |
| `guard.chunks_per_guard` | 6 | 청크 | 순찰 밀도 1에 필요한 경비병당 청크 | |
| `guard.night_share` | 0.5 | | 밤 순찰 비율 | |
| `jail.food_cost` | 20 | /일 | 구금 1명의 식비(× P) | |
| `aide.capacity_base` | 3 | 건/일 | 행정 부관의 기본 처리량 | |
| `aide.chunks_bonus` | 4 | 청크 | 부관 한 명의 관리 청크 가산 | |
| `rumor.sev_murder` | 40 | | 심각도 | |
| `rumor.sev_unjust_major` | 35 | | | |
| `rumor.sev_assault` | 25 | | | |
| `rumor.sev_theft` | 15 | | | |
| `rumor.sev_property` | 15 | | | |
| `rumor.sev_unjust_minor` | 10 | | | |
| `rumor.spread_base` | 0.3 | | 만남당 전파 기본 확률 | |
| `rumor.lifetime_days` | 30 | 일 | | |
| `rumor.comp_per_sev` | 5 | 동화 | 배상액의 심각도당 값(× P) | |
| `rumor.hush_per_sev` | 3 | 동화 | 입막음 돈의 심각도당 값(× P) | |
| `outsider.hostile_days` | 3 | 일 | 외부인 범죄자에 대한 경비병의 적대 기간 | |
