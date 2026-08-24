---
title: 팀파이트 매니저 티어 분석 - DB 스키마 검토
created: 2026-08-10
updated: 2026-08-10
para:
project:
areas: []
status:
tags: [database, schema-design, teamfight-manager]
---

# DB 스키마 검토

> **[폐기됨 · 2026-08-10]** 이 문서는 "스크린샷 OCR로 데이터를 수집한다"는 전제로 작성됐다.
> 이후 세이브 파일에 경기 데이터가 전부 들어 있다는 것이 확인되어 수집 방식이 바뀌었고,
> 여기 적힌 스키마 판단은 대부분 더 이상 유효하지 않다 (라인 5종, OCR 적재 계층 등).
> 현재 설계는 [[decision.md]] 와 `schema.sql` 을 보라. 이 문서는 초기 검토 기록으로만 남긴다.

원본 DDL을 수정한 이유를 표로 정리했다. 결과물은 같은 폴더 `schema.sql`.

## 한 줄 요약

원본은 **"경기 기록"과 "집계 결과"의 경계는 잘 잡았다.** 고쳐야 할 건 세 덩어리다.

1. 밴 데이터가 아예 없다 → 밴률도, 밴픽 시뮬레이션도 계산할 근거가 없다
2. 집계 테이블이 패치별로 여러 행을 못 가진다 (PK에 patch_id 누락)
3. 비율을 int로, 패치를 double로, 이름을 char(4)로 — 타입이 데이터를 못 담는다

## 치명적인 것 (안 고치면 기능이 안 나옴)

| 원본 | 문제 | 수정 |
|---|---|---|
| 밴 테이블 없음 | `Champion_Performance.ban_rate` 를 채울 원천 데이터가 없다. 밴픽 시뮬도 불가 | `match_ban` 신설 (경기·진영·밴순서·챔피언) |
| `Champion_Performance` PK = `champion_id` | 챔피언당 딱 1행. 패치가 바뀌면 과거 통계를 덮어써야 한다. 패치 비교 자체가 불가능 | PK를 `(patch_id, champion_id, lane)` 으로 |
| `Champion_Stat` PK = `(stat_id, champion_id)` | `patch_id` 가 PK 밖이라 같은 챔피언의 패치별 스냅샷을 구분 못 한다 | PK `(champion_id, patch_id)`, `stat_id` 제거 |
| `Synergy_Analysis` 의 `champion_id1~4` | 2인·3인 조합을 못 담고, (A,B,C,D)와 (B,A,C,D)가 다른 행으로 중복 저장된다 | `synergy_combo` + `synergy_combo_member` + `synergy_stat` 로 정규화. 정렬된 `champion_key` 유니크로 순열 중복 차단 |
| 라인(포지션) 정보 없음 | "미드 A는 셌지만 탑 A는 약하다"를 표현 못 한다. 라인별 티어표가 lol.ps의 핵심인데 그게 안 나옴 | `match_participant.lane` 추가, 집계도 라인별 |
| 표본 수 컬럼 없음 | 3판 3승 챔피언이 승률 100%로 1티어가 된다 | 모든 집계 테이블에 `games`/`wins` 원시 카운트 + `is_reliable` |
| `Counter_Analysis` 에 경기 수 없음 | 위와 같은 문제 + 유니크 제약이 없어 같은 조합이 여러 행 생긴다 | `champion_matchup` 으로 재작성, `games`/`wins` + 유니크 |

## 타입 문제

| 원본 | 문제 | 수정 |
|---|---|---|
| `patch_id double` | 부동소수 키는 비교가 불안정하고, `1.10` 이 `1.9` 보다 작게 정렬된다 | `patch_id int` 대리키 + `version text` + `sort_key int` |
| `win_rate / pick_rate / ban_rate int` | 정수라 52.7% 를 못 담는다. 애초에 카운트에서 유도되는 값이라 따로 저장하면 어긋난다 | 원시 카운트 저장 + `GENERATED ALWAYS AS ... STORED` 유도 컬럼 |
| `Champion_info.name char(4)` | 한글 4자 넘는 이름이 잘린다. char 는 뒤를 공백으로 채워서 비교도 성가시다 | `text` |
| `main_role / sub_role char(4)` | 역할군 값이 코드에 흩어진다. 오타를 DB가 못 막는다 | `champion_class` 룩업 테이블 + FK |
| `side / winner_side / is_winner char(4)` | 'BLUE','blue','B' 가 다 들어간다 | `team_side` ENUM, `is_winner` 는 컬럼 삭제 |
| `is_active double` | 참/거짓에 실수형 | `boolean` |
| `release_data int` | 오타(date), 그리고 날짜를 정수로 | `released_on date` |
| `as` 컬럼명 | SQL 예약어라 인용부호 없이는 못 쓴다 | `attack_speed`. `range`→`attack_range`, `cd`→`cooldown` |
| `conment` | 오타 | `note` |

## 무결성·중복 관련

| 항목 | 내용 |
|---|---|
| FK 누락 | `patch_id`, `Counter_Analysis`/`Synergy_Analysis`의 챔피언, `Match_Participant.champion_id` 에 FK가 없었다. 전부 추가 |
| `is_winner` 중복 | `Match_Record.winner_side` + `side` 로 유도되는 값이라, 따로 저장하면 어긋날 수 있다. 컬럼 대신 `v_match_participant` 뷰로 제공 |
| `Champion_Stat.is_current` | `patch.is_active` 와 같은 사실을 두 군데 적는 구조. 제거 |
| 같은 경기에 같은 챔피언 | 막는 제약이 없었다. `match_participant(match_id, champion_id)` 유니크 추가 |
| 한 팀에 미드 2명 | 막는 제약이 없었다. PK `(match_id, side, lane)` 자체가 막아준다 |
| `participant_id` | 의미 없는 대리키. 자연키가 이미 있어서 제거 |

## OCR 때문에 추가한 것

스크린샷 인식은 **틀린다**는 전제로 설계했다.

| 테이블 | 역할 |
|---|---|
| `ingest_image` | 원본 이미지 + OCR 원문(`ocr_raw` jsonb) 보관. 파서를 고쳤을 때 전량 재파싱이 가능해야 한다. `file_hash` 유니크로 같은 스샷 재업로드 차단 |
| `ingest_field_review` | 필드별 인식 원문 / 확정값 / 교정 여부. 나중에 "어떤 챔피언 이름을 자주 틀리는지"를 측정할 수 있다 |
| `champion_alias` | '가렝' 같은 오인식을 챔피언에 매핑하는 사전. 사람이 한 번 고칠 때마다 쌓아서 다음부터는 자동 보정 |
| `match_record.is_confirmed` | 사람이 확인한 경기만 통계에 넣는다. 이게 없으면 OCR 오류가 티어표를 오염시킨다 |
| `match_record.dedup_key` | 다른 각도로 찍은 같은 경기의 중복 적재 방지 |

## 판단이 필요했던 부분

- **라인 값 5종 고정 (`TOP/JUNGLE/MID/BOT/SUPPORT`)** — 팀파이트 매니저 기준. 게임 쪽이 다르면 ENUM만 바꾸면 된다.
- **카운터 양방향 저장** — A vs B와 B vs A를 둘 다 적재한다. 저장 공간은 2배지만 조회 쿼리가 단순해진다. 배치가 한 번에 만드니 불일치 위험은 없다.
- **`champion_matchup.scope`** — "같은 라인 맞대결"과 "적팀에 같이 있었다"는 완전히 다른 숫자다. 원본은 구분이 없어 섞였다.
- **집계 테이블 유지 (실시간 계산 안 함)** — 경기 수가 늘면 매 요청마다 집계하는 건 못 버틴다. 배치로 채운다.
- **`champion_performance.lane` NULL = 전체** — PG15의 `UNIQUE NULLS NOT DISTINCT` 로 중복을 막는다. PG14 이하로 갈 일이 있으면 `COALESCE(lane::text,'ALL')` 표현식 유니크 인덱스로 바꿔야 한다.

## 아직 안 정한 것

- **티어 산정식** — `tier_score` 컬럼만 뚫어놨다. 표본이 적을 때 승률을 전체 평균 쪽으로 당기는 보정(베이지안 평활)이 필요한데, 실제 데이터가 좀 쌓인 뒤에 정하는 게 맞다.
- **`is_reliable` 기준선** — 우선 20경기로 두고 데이터 보고 조정.

---

[[teamFighterManger.README]]
