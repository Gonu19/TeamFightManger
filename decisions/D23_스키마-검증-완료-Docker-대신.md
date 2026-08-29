---
title: "D23. 스키마 검증 완료 · Docker 대신 로컬 Postgres (2026-08-12)"
---

## D23. 스키마 검증 완료 · Docker 대신 로컬 Postgres (2026-08-12)

**검증.** `schema.sql` 을 PostgreSQL 16.14 에 실제 적용했다. **오류 0, 첫 시도 통과.**

| 항목 | 결과 |
|---|---|
| 객체 | 테이블 19 · 뷰 2 · ENUM 4 · 생성열 6 · 인덱스 39 |
| `UNIQUE NULLS NOT DISTINCT` | 4개 인덱스 적용. NULL 중복 실제 차단 확인 |
| 생성열 | 44/58 → 0.7586, pick 0.58, ban 0.10 |
| `v_match_participant.is_winner` | BLUE t / RED f |
| `team_size=5` · `wins>games` · `ban_order=4` | 전부 거부 |
| 한 경기 같은 챔피언 · 같은 pick_order | 전부 거부 |

가장 위험했던 `UNIQUE NULLS NOT DISTINCT`(PG15+)가 통과했다.
**설계 검증 라운드 하나를 실행 테스트로 대체했다** — 검증 칸이 20KB DDL 을 읽고 추론할 필요가 없다.

**Docker 를 쓰지 않는다.** 이 PC 에서 Docker Desktop 엔진이 기동되지 않는다.
`docker-desktop` WSL 배포판은 직접 띄우면 올라오지만 데몬이 파이프를 열지 않는다.

| 영향 | 조치 |
|---|---|
| Docker Compose 로 Postgres | ~~WSL 에 PGDG 저장소로 PG16 직접 설치~~ → **D27 에서 Windows 네이티브 설치로 변경** |
| Testcontainers | **제거.** 로컬 `tfm_test` DB + 트랜잭션 롤백 격리 (유지) |

대가는 환경 재현성이다. 다만 이 프로젝트는 `java -jar` 로컬 실행이 전제(D22)라 Docker 가
필수인 구간이 없다.

**뒤집힐 조건.** Docker 가 살아나면 Testcontainers 로 되돌릴 수 있다. 그때까지 테스트가
로컬 DB 상태에 의존하지 않도록 롤백 격리를 지킨다.

### 2차 검증 (2026-08-25) — 밴픽 모델 추가 후 재실행

D26 의 드래프트 테이블과 D24/D28 의 제약을 넣은 뒤 Windows 네이티브 PG16.15 에 다시 적용했다.
**적용 오류 0, 제약 검증 24/24 통과.**

| 항목 | 결과 |
|---|---|
| 객체 | 테이블 21 · 뷰 3 · ENUM 5 · 생성열 6 · `UNIQUE NULLS NOT DISTINCT` 4 |
| 시드 | `analysis_config` 6행 · `draft_step` 14행 |
| `ban_order` | 3 통과 / **4 거부** (팀당 밴 3) |
| `pick_order` | 1 통과 / 5 거부 |
| `draft_step` 순서 | 진영 `BRBRBRRBRBRBBR` · 액션 `bbbbppppbbpppp` — 2차 밴이 픽 뒤에 온다 |
| 드래프트 중복 | 밴한 챔피언 재픽 거부 · 스텝 15 거부 · `next_step=16` 거부 |
| D24 | `GLOBAL`+패치 거부 / `CAREER`+패치 통과 |
| 기존 제약 | `wins>games` · `team_size=5` · 같은 챔피언 2회 · 자기 매치업 · 역할군 합≠4 · 시너지 크기 4 전부 거부 |

**일회성 확인이 아니라 스크립트로 남겼다** — `tests/verify_schema.sql`. 픽스처를 넣고
위반을 시도한 뒤 `ROLLBACK` 한다. 스키마를 고칠 때마다 다시 돌린다.
D23 이 "검증 라운드를 실행 테스트로 대체했다"고 한 것의 반복 가능한 버전이다.

---

