---
title: 팀파이트 매니저 티어 분석 - 파일 구조
created: 2026-08-25
updated: 2026-08-28
para:
project:
areas: []
status:
tags: [teamfight-manager, architecture, structure]
---

# 파일 구조

패키지를 **기능/도메인으로 나눈다.** 타입별(`controller/`, `service/`, `dto/`)로 나누지 않는다.
경계가 곧 규칙이 되도록 배치했다 — 문서에만 적힌 규칙은 지켜지지 않는다.

```
A:\project\TeamFighter\
├─ build.gradle · settings.gradle · gradle.properties
├─ gradlew.bat · gradle/wrapper/            Gradle 9.5.1
├─ .gitignore
│
├─ *.md                                     설계 문서 (루트 유지)
│   └─ architecture.md                      계층 경계 · 화면 지도 · 외부 의존
├─ fixtures/                                세이브 · common.data 스냅샷 (라이브 파일 대신)
├─ tools/                                   측정 스크립트 · *_report.sql · pair_audit.sql
├─ tests/
│   ├─ verify_schema.sql                    스키마 제약 검증 24/24
│   └─ baseline/*.json                       ★ 언어 무관 골든 파일
│
├─ src/main/java/com/teamfighter/tfm/
│   ├─ TfmApplication.java
│   ├─ config/                              설정 로딩, 데이터소스 (보안 없음 — D41)
│   ├─ parser/          ★ Spring 의존 0
│   │   ├─ nrbf/                            NrbfReader · RecordType · ObjectGraph
│   │   ├─ save/                            SaveFileParser · GameStat/Scrim/PatchNews 매퍼
│   │   ├─ common/    TeamInfoParser        세이브의 팀 신원 — 1순위 (D56)
│   │   │             AthleteParser         세이브의 선수 · 이름은 인덱스로 (D58)
│   │   │             CommonDataParser      common.data 이름표 — 폴백 (D55)
│   │   └─ model/                           파서 출력 DTO (DB 무관)
│   ├─ ingest/          JPA
│   │   ├─ watcher/                         SaveWatcher · SlotPathResolver · StartupCatchUp · TfmProperties
│   │   ├─ entity/ · repository/
│   │   ├─ IngestService · ParticipantMatcher · PatchAssigner
│   │   ├─ SlotRegistry · TeamRegistry       슬롯은 실패해도 남고, 팀은 같이 사라진다 (D54)
│   │   ├─ TeamNaming                     이름 출처 우선순위 — 순수 함수 (D56)
│   │   ├─ AthleteLoader                  선수 적재 — 스냅샷 갱신 (D58)
│   │   ├─ ReingestRunner · IngestConfiguration   tfm.reingest-on-start (기본 꺼짐, 수리용)
│   ├─ analysis/        JdbcTemplate. 순수 계산 계층에는 Spring 의존이 없다
│   │   ├─ AnalysisConfig                   analysis_config 여섯 키 (D44)
│   │   ├─ ReferencePoint                   추정 시점 · 거리 계산 (D24·D45)
│   │   ├─ MatchObservation                 집계 입력 단위 (승리 팀 / 패배 팀)
│   │   ├─ AggScope                         CAREER 만 생산한다 (D53)
│   │   ├─ AggregationService               집계 한 바퀴 — 카운터+티어 (D47·D50·D53)
│   │   ├─ AggregationRunner · AnalysisConfiguration   tfm.aggregate-on-start (기본 꺼짐)
│   │   ├─ decay/       DecayWeight         이중 감쇠        (D15a·D42)
│   │   ├─ shrink/      Shrinkage           2단 축소·유효표본 (D15b)
│   │   ├─ strength/    BradleyTerry        기저 강도        (D14·D43)
│   │   ├─ counter/     MatchupAggregator   경기 → 쌍 전개 · 누적 (D14)
│   │   │                CounterCalculator   BT 기대승률 · 1단 축소 · 상성 이득
│   │   ├─ performance/ ChampionTallyAggregator   챔피언별 출전 누적
│   │   │                PerformanceCalculator     티어 — 강도 보정 없음 (D50)
│   │   ├─ synergy/                          아직 없음
│   │   └─ dao/         MatchObservationDao · AnalysisConfigDao (D46: 행만 꺼낸다)
│   │                    CounterWriter · PerformanceWriter · AggRunRecorder
│   ├─ story/           기사·댓글 (D61). 집계와 링크로 잇지 않는다
│   │   ├─ MatchBrief                       사실 — 세트 합 = 스케줄 스코어를 강제
│   │   ├─ Notability · NotabilityContext   해석 — 아는 축만으로 분량을 정한다
│   │   ├─ SeasonBook                       순위·업셋·라이벌. 미래를 안 본다
│   │   ├─ BriefRenderer · NameBook         프롬프트와 화면이 같은 문자열
│   │   ├─ StoryPrompts · StoryRequest · StoryClient   창작 — 두 목소리
│   │   ├─ HttpStoryClient · StoryProperties          JDK HttpClient + Jackson 3
│   │   ├─ FactCheck · FactCheckResult      대조 — 모순 / 미확인 2등급
│   │   ├─ ArticleDraft                     fact_status 를 타입이 계산한다
│   │   ├─ ArticleWriter                    넷을 잇기만 한다. @Transactional 없음
│   │   ├─ StoryConfiguration               플래그를 켜야 빈이 생긴다 (D61)
│   │   └─ dao/         ArticleDao          업서트 — 재생성이 갱신이 된다
│   │                    ArticleView · ArticleCard    상세용 · 목록용
│   │                    StoryReference · StoryReferenceDao  이름표 (NameBook 구현)
│   ├─ draft/           밴픽 시뮬            (banpick.md)
│   │   ├─ DraftStateMachine · DraftSessionService · EvidenceGate
│   │   ├─ score/                           PickScorer · BanScorer · ScoreBreakdown
│   │   └─ dao/
│   ├─ web/
│   │   ├─ StoryController                  /story · /story/{id} — 있다
│   │   ├─ 컨트롤러: Tier · Champion · Synergy · Gaps · Draft · Slot
│   │   ├─ sse/                             실시간 갱신
│   │   └─ view/                            서버가 계산해 내려보내는 표시 모델
│   └─ common/
│
├─ src/main/resources/
│   ├─ application.yml                      비밀번호는 환경변수로만
│   ├─ db/migration/                        ★ 스키마·시드의 유일한 원본
│   │   ├─ V1__init.sql · V2__fixed_team_size.sql
│   │   ├─ V3__seed_champions.sql           챔피언 40종
│   │   ├─ V4__ban_rate_denominator.sql     밴률 분모 분리 (D50)
│   │   ├─ V5__current_patch_basis.sql      메타 반감기 2 · GLOBAL 중단 (D53)
│   │   ├─ V6__team_identity.sql          team.name_key · 팀 이름 시드 52 (D56)
│   │   ├─ V7__athlete.sql                athlete · 선수 이름 풀 551 (D58)
│   │   └─ V8__article.sql                article · comment · finding (D61)
│   ├─ templates/story/                    list.html · detail.html
│   ├─ templates/ + templates/fragments/    (통계 화면은 아직)
│   └─ static/css/tfm.css · static/js
│
└─ src/test/java/...                        main 과 거울 구조
```

## 왜 이렇게 나눴나

**`parser/` 에 Spring 의존을 0 으로 둔다.** 이 프로젝트에서 조용히 틀릴 위험이 가장 큰 곳이다
— Java 의 `byte` 는 부호가 있는데 NRBF 는 unsigned 를 다루므로 `& 0xFF` 를 빼먹으면
예외 없이 틀린 값이 나온다. Spring 컨텍스트 없이 순수 JUnit 으로 골든 파일과 대조할 수 있어야
그 검증이 빠르고 확실하다. 파서는 라이브러리처럼 취급한다.

**`ingest/`(JPA)와 `analysis/`(JdbcTemplate)를 패키지로 가른다.** D22 가 정한 규칙인데,
같은 패키지에 두면 지켜지지 않는다. 가중합·2단 축소·백분위 컷·`NULLS NOT DISTINCT` 업서트는
Hibernate 가 약한 영역이라 JdbcTemplate 으로 가고, 경계를 파일 위치로 강제한다.

**`draft/` 를 `analysis/` 아래에 두지 않는다.** 시뮬레이터는 집계값을 **소비**할 뿐
생산하지 않는다. 아래에 두면 시뮬이 집계 로직을 직접 부르고 싶어진다.

**`web/view/` 가 따로 있다.** 디자인 요구가 "표본에 따른 시각적 약화, 신뢰구간 막대, 티어 뱃지를
서버에서 계산해 내려보낸다"이다. 클라이언트가 통계를 다시 계산하지 않는다는 규칙을
템플릿 안 로직이 아니라 별도 모델로 만들어 지킨다.

## `schema.sql` 을 옮긴 이유

`src/main/resources/db/migration/V1__init.sql` **하나만 둔다.** 루트의 `schema.sql` 은 없앴다.

문서가 `schema.sql` 을 "스키마의 기준"이라 부르는데 Flyway 마이그레이션 사본을 따로 두면
440줄 DDL 이 두 벌이 되어 반드시 어긋난다. 어느 쪽이 진짜인지 애매해지는 순간
"바꾸면 마이그레이션을 추가한다"는 규칙이 무너진다.

psql 로 직접 적용할 때도 이 경로를 쓰면 된다.

```powershell
chcp 65001
& 'C:\Program Files\PostgreSQL\16\bin\psql.exe' -U postgres -d tfm_test `
  -v ON_ERROR_STOP=1 -f src/main/resources/db/migration/V1__init.sql
```

## 확인된 빌드

Spring Boot 4.1.1 · Framework 7.0.9 · Thymeleaf 3.1.5 · Gradle 9.5.1 · Java 21.
`gradlew.bat build` 성공, `bootJar` 생성 확인 (D31).

## 아직 만들지 않은 것

빈 클래스 파일을 미리 깔지 않는다. 위 트리는 **목표 구조**이고, 각 파일은 실제로 필요할 때 만든다.

지금 있는 것은 `parser/` · `common/` · `ingest/`(워처 포함)와 `analysis/` 의 카운터 경로
전체다 — 순수 계산 계층(`decay/` · `shrink/` · `strength/` · `counter/`)과 조회·쓰기(`dao/`),
그리고 진입점(`AggregationService`) — 이제 티어(`performance/`)도 같은 바퀴에서 만든다.

`analysis/` 에서 아직 없는 것은 `synergy/`(2·3인 조합 · 역할군 4인)와, 2단 축소를 실제로
쓰는 패치별 집계다. 지금 저장하는 것은 1단(전체 누적, `patch_id IS NULL`)뿐이다.
`performance/` 는 티어·픽률·밴률까지 만들지만 **경기력 z값과 티어 등급은 비워 둔다** —
둘 다 근거가 아직 없다 (D50).

`story/` 는 파서에서 화면까지 이어졌다 — `/story` 와 `/story/{id}` 가 돈다.
`web/` 에 있는 것은 그 둘뿐이고 통계 화면(`/tier` · `/champion` · `/gaps` · `/teams`)과
`draft/` 는 아직 없다. 상단 탭도 통계 화면이 생길 때 붙인다 — 갈 곳이 하나뿐인 탭은
탭으로 안 읽힌다.

---

[[teamFighterManger.README]]
