---
title: 팀파이트 매니저 세이브 파일 구조
created: 2026-08-10
updated: 2026-08-10
para:
project:
areas: []
status:
tags: [teamfight-manager, reverse-engineering, savefile, binaryformatter]
---

# 세이브 파일 구조

실제 세이브 3개를 파싱해서 확인한 내용이다. 추측이 아니라 관측값이다.
파서는 `tools/nrbf.py`.

## 위치와 형식

```
%USERPROFILE%\AppData\LocalLow\samoyed\Teamfight Manager\
├── slot_638064443900084435.tfm   2.8MB
├── slot_638377270248153191.tfm   2.7MB
├── slot_638683925954242004.tfm   2.9MB
├── slot_638683925954242004.tfm_backup   2.75MB  ← 직전 저장본
├── common.data                   106KB   팀 로고, 코치, AI팀 데이터
└── Player.log

**`*.tfm_backup` 이 같이 존재한다.** 게임이 저장할 때 직전 내용을 남긴다.
크기·수정시각이 본 파일과 같아서 내용이 거의 동일하다.

**워처는 `slot_*.tfm` 만 잡아야 한다.** `slot_*.tfm*` 로 글롭을 걸면 백업이 별도 슬롯으로
등록되어 같은 커리어가 두 벌 적재된다. `file_hash` 중복 검사는 `slot_id` 안에서만 도는데
백업은 `slot_key` 가 달라 다른 슬롯이 되므로 **걸러지지 않는다.**
```

**.NET BinaryFormatter(MS-NRBF) 포맷.** 암호화 없고 압축 없다. 타입명과 필드명이 파일 안에 그대로 들어 있어서 자기 자신을 설명한다.

게임은 Mono 빌드라 `<게임설치경로>\Teamfight Manager_Data\Managed\Scripts.dll` 에 관리 어셈블리가 그대로 있다. C#에서 이 DLL을 참조하면 `BinaryFormatter.Deserialize()` 한 줄로 타입이 붙은 객체가 나온다. 다만 BinaryFormatter는 .NET 9에서 제거됐으므로 .NET Framework 4.8 또는 .NET 8 이 필요하다. 파이썬으로 직접 파싱해도 되고, 실제로 그렇게 했다.

## 파일은 스트림 3개가 이어붙어 있다

헤더 시그니처 `00 01 00 00 00 FF FF FF FF 01 00 00 00 00 00 00 00` 를 찾아 경계를 잡는다.

| # | 크기 | 객체 | 내용 |
|---|---|---|---|
| 0 | 3KB | 4 | `SavePreview` — 팀명, 연도, 리그레벨, 순위, 승/패, 골드, 플레이시간, 로고 PNG |
| 1 | 1.35MB | 45,657 | **본 세이브. 경기 데이터 전부** |
| 2 | 1.5MB | 88,286 | `Banpick.*` AI 모델 가중치 |

**스트림 2는 통계가 아니라 게임 AI 의 학습 가중치다.** 다만 **평평한 덩어리가 아니라 구조가 있다.**
(2026-08-25 재조사. 이전 기록은 `LineModel` 88,154개만 보고 "쓸 수 없다"고 적었는데, 그것은 잎사귀만 본 것이었다.)

```
Banpick.MultiNetwork  Count=212
├─ Counters      10 × 2500셀   0 아닌 값 1,178~1,548
├─ Pairs         10 × 2500셀   1,026~1,490
├─ StatTiers     10 ×   64셀   40
├─ StrategyTiers 10 ×  450셀   120~306
└─ Props         10 × 2500셀   573~830
```

셀 하나가 `LineModel {A: float, B: float}` 이다. 2500 = 50×50 으로 **챔피언 쌍 행렬**의 모양이고
(관측 챔피언은 40종, 나머지는 여유분으로 보인다), 이름 그대로라면 `Counters` 는 상성,
`Pairs` 는 조합이다. **우리가 경기 데이터로 계산하려는 바로 그 두 가지다.**

**아직 모르는 것:** 셀 인덱스와 챔피언의 대응, `A`/`B` 의 의미, 10개 모델이 무엇으로 나뉘는지,
`Count=212` 가 학습 표본 수인지. 자세한 판단은 `decisions/D32_*.md`.

파싱 시간: 전체 3스트림 0.39초, 경기 스트림만 0.15초.

## 경기 데이터

### GameStat — 공식 경기 (리그·대회)

슬롯당 233~289건.

```
ScheduleID, Season, Day, Set, BlueTeamID, RedTeamID,
BlueScore, RedScore, WinTeam,
BlueBan[3], BluePick[4], RedBan[3], RedPick[4],
ChampStat[8], IsOvertime, IsSuddenDeath, ID
```

실제 값 예시:

```
S2025 Day7 Set2 | 32번팀 vs 31번팀 | 21:13 | WinTeam=0(BLUE)
BlueBan : Berserker, Clown, DuelBlader
BluePick: Gunner, Ninja, Bard, Monk
RedBan  : MagicKnight, Werewolf, PlagueDoctor
RedPick : Knight, Lancer, Gambler, Taoist
```

- `WinTeam` 은 `TeamType` enum: **0 = BLUE, 1 = RED**
- `ChampStat` 은 `List<AthleteMatchStat>`

**배열 길이는 고정이 아니다.** 슬롯 3개(805경기) 실측:

| 항목 | 관측된 길이 |
|---|---|
| `BluePick`/`RedPick` | **항상 4** (805/805) |
| `BlueBan`/`RedBan` | **팀당 3 이 기본. 커리어 초반에는 2.** 아래 참고 |
| `ChampStat` | 항상 8 (공식전) |

**게임 규칙은 팀당 밴 3 · 픽 4 로 고정이다 (양팀 합계 밴 6 · 픽 8).**
가변 규칙이 아니다. **팀 인원 4명 · 픽 4 · 밴 3 이 고정이다 (D35).**

다만 **챔피언 풀이 작던 커리어 초반 데이터에는 팀당 밴이 2개**로 남아 있다. slot 638064(등장 챔피언 31종,
다른 슬롯은 40종)에서 정확히 패치 경계로 갈린다:

```
Day  7~15 : 밴 2개  107경기
Day 22~39 : 밴 3개  171경기     ← Day16 패치(챔피언 추가) 이후
```

`_items` 원본이 `['MagicKnight','Monk',None,None]`(`_size`=2)이라 잘림이 아니라 실제로 2개다.

**규칙은 3으로 짜되, 파서는 배열 길이를 상수로 가정하지 말 것.**
과거 2밴 경기를 읽을 때 인덱스 3을 건드리면 깨진다.

**그 경기들도 적재하지 않는다 (D35).** 픽은 양쪽 4개씩이고 ChampStat 도 8명이라
승률 자체는 성립하지만, 우리는 `4v4 · 픽 4 · 밴 3` 한 가지 형식만 다룬다.
실측 107경기(slot 638064, Day 7~15)가 여기 해당하고, 나머지 두 슬롯은 0건이다. 그리고 밴률을 커리어 초반 구간과 이후 구간에
걸쳐 단순 비교하면 안 된다 — 경기당 밴 총량이 4개와 6개로 달라서 분모의 의미가 다르다.

### ScrimStat — 연습경기

슬롯당 117~177건.

```
TeamID, BlueScore, RedScore, BlueStat[4], RedStat[4], ID
```

- 밴이 없다
- **구 데이터에 4v4 가 아닌 스크림이 있다.** slot 638064 는 151건 중 2명(2v2) 17건, 3명(3v3) 21건, 4명 113건.
  나머지 두 슬롯은 전부 4명. 규칙은 4v4 고정이므로 이것은 예외다 —
  **4v4 가 아닌 스크림은 적재하지 않는다** (D35). 조건이 다른 경기의 승률을 같은 표에 섞을 수 없다.
  스크림에는 밴이 없다 — 인원만 본다
- `BlueStat`/`RedStat` 은 `List<MatchStat>` (GameStat 과 달리 AthleteMatchStat 이 아니다)
- **Season / Day 가 없다.** ID도 GameStat과 별도 공간(0~176)이라 시점을 역추적할 수 없다
- 승패는 `BlueScore` vs `RedScore` 비교로 판정

### MatchStat / AthleteMatchStat

```
MatchStat        : Champion, Kill, Death, Assist, Dealing, Tanking, Healing, LiveDuration
AthleteMatchStat : Stat(MatchStat), AthleteID
```

**함정: `ChampStat` 순서를 믿으면 안 된다.** 805경기 중 640건(79.5%)만
`BluePick + RedPick` 순서와 일치한다. 인덱스로 매칭하면 20%에서 진영이 뒤바뀐다.
**반드시 챔피언 이름으로 매칭할 것.** 한 경기에 같은 챔피언이 두 번 나오지 않으므로 안전하다.

### 다전제

스케줄 1건에 `GameStat` 이 2~5개 붙는다 (109개 스케줄 → 294세트).
세트마다 밴픽이 따로 있으므로 **분석 단위는 세트**다.

피어리스(이전 세트 챔피언 사용 금지)가 아니다 — 세트 간 챔피언 재사용률이 26.8%로,
랜덤 기대치(약 18%)보다 오히려 높다. 선호 챔피언을 반복해서 뽑는 정상 드래프트다.
`BanpickModeData = { Mode: 0, ApplyRound: 1 }`.

### 드래프트 순서

세이브에는 순서가 저장되지 않는다. 게임 규칙이라 상수이고, 스키마의 `draft_step` 이 원본이다.

| phase | 스텝 |
|---|---|
| 1차 밴 | 1.블루 · 2.레드 · 3.블루 · 4.레드 |
| 1차 픽 | 5.블루 · 6.레드 · 7.레드 · 8.블루 |
| 2차 밴 | 9.레드 · 10.블루 |
| 2차 픽 | 11.레드 · 12.블루 · 13.블루 · 14.레드 |

전반부 `B1·R2·B1` 과 후반부 `R1·B2·R1` 이 진영을 바꿔 대칭이다.
**2차 밴이 픽 4개 공개 뒤에 온다** — 밴픽 시뮬에서 이 두 밴은 성격이 다르다 (decisions/D26_*.md).

**주의: `BluePick[4]` 배열 순서가 실제 픽 순서라는 증거는 없다.** `ChampStat` 순서를 믿으면
안 되는 것과 같은 종류의 가정이다. 검증 전까지 배열 인덱스는 슬롯 번호로만 쓴다 (D25).

단 같은 매치의 세트들은 같은 두 팀이라 완전히 독립 표본은 아니다. 유효표본수가 약간 부풀려진다.

### 이벤트전 — 데이터를 남기지 않는다

`MatchSchedule.EventMatch` 가 붙은 스케줄이 이벤트전이다.
`EventMatchData = { Type: EventMatchType, Category: ChampionCategory }` 로,
역할군 제약이 걸리는 특수 룰로 보인다.

실제로 치러보고 확인한 결과:

```
이벤트 스케줄 ID109  Progress 0.0 → 1.0, 스코어 0:1
GameStat   294 → 294 (+0)
ScrimStat  180 → 180 (+0)
```

**밴픽·챔피언·개인스탯이 전혀 저장되지 않는다.** 스케줄에 스코어만 남는다.
따라서 통계 오염 걱정도, 제외 로직도 필요 없다.

(주의: `ScheduleID` 는 대회별로 ID 공간이 따로다. MatchSchedule 190건인데 ID 범위는 0~113.
`GameStat.ScheduleID` 만으로 스케줄을 조인하면 엉뚱한 것에 붙을 수 있다.)

## 현재 게임 내 날짜

`TodayData` (세이브에 1개)

```
<Time>k__BackingField : SeasonTime(Season, Day, Run)
Hour, Minute, Gold, PlayerCount, Days, ...
```

**`max(GameStat.Day)` 로 현재 날짜를 추정하면 안 된다.** 이벤트전은 GameStat 을 남기지
않고 스크림에는 Day 가 없어서, 공식 경기가 없는 날에는 값이 멈춘다. 실측 사례:

```
TodayData.Time      : S2025 Day 46 18:00   ← 실제
max(GameStat.Day)   : 41                   ← 5일 뒤처짐
```

스크림 타임스탬프 보정이 이 값에 의존하므로 중요하다.

## 저장 시점

**게임이 자동 저장한다. 사용자가 저장을 누를 필요가 없다.**

`tools/watch_save.py` 실측:

```
23:37:46  스크림 +1                          파싱 0.46s
23:39:18  스크림 +1        (직전 +92.6s)      파싱 0.45s
23:47:46  변화 없음, 파일 +2,942B (+507.4s)   파싱 0.42s
00:02:11  스크림 +1        (+66.6s)          파싱 0.45s
```

- 스크림 한 판이 끝날 때마다 즉시 쓰기 (66~93초 간격)
- **게임 실행 중에도 파일이 잠기지 않는다.** 파싱 에러 0건
- 쓰기 3~4건 중 1건은 경기 증가가 없다 (훈련·골드·뉴스 등)
- 쓰기 도중 읽으면 스트림이 잘리므로 1.5초 디바운스가 필요하다

## 결정적인 제약 두 가지

### 세이브는 현재 시즌 경기만 보관한다

| 슬롯 | GameStat | 시즌 |
|---|---|---|
| 638064… | 278 | 2026만 |
| 638377… | 233 | 2025만 |
| 638683… | 289 | 2025만 |

시즌이 넘어가면 이전 시즌 경기 기록이 사라진다. **누적은 게임이 해주지 않는다.**
(단 뉴스 이력은 커리어 시작까지 남아 있어서 패치 역사는 2021년부터 조회된다.)

### 패치는 커리어마다 고유하게 생성된다

```
slot_638064: S2021 Day14, 신규 챔피언 Sniper/IceMage 추가
slot_638377: S2021 Day10, 신규 챔피언 없이 10챔 밸런스만 변경
```

날짜도 내용도 다르다. 롤처럼 "패치 14.3"을 전 유저가 공유하는 구조가 아니다.
**패치 기준 분석은 한 세이브 안에서만 유효하다.**

## 패치 데이터

### PatchNews

```
NewChamps[], Patches[](PatchData), Date(SeasonTime), TitleKey, ...
SeasonTime : Season, Day, Run
PatchData  : Name, Attack, Magic, Defence, MaxHp, AttackSpeed, SkillCool, MoveSpeed
```

`Date` 에 적용 시점이, `Patches` 에 변경 내용이 들어 있다. **사용자 입력이 필요 없다.**

관측된 패치 주기:

```
S2025-a : Day 11, 16, 26   (시즌 내 3회)
S2025-b : Day 11, 16, 26   (3회)
S2026   : Day 16           (1회)
```

패치당 6~10챔 변경. 시즌 내내 한 번도 안 바뀌는 챔피언이 40종 중 21~25종이다.

## 역할군

`ChampionCategory` enum. 게임 로컬라이제이션(`sharedassets0.assets` 내 JSON)에서 확인:

| 값 | 코드 | 한국어 |
|---|---|---|
| 0 | `total` | 전체 |
| 1 | `melee` | 전사 |
| 2 | `ranger` | 원거리 |
| 3 | `magician` | 마법사 |
| 4 | `priest` | 전투 보조 |
| 5 | `assassin` | 암살자 |

**주의: `Athlete.Category` 는 선수의 주특기지 챔피언 분류가 아니다.**
챔피언→역할군 매핑은 세이브에 없고 게임 에셋(`ItemChampionCategoryConfig`)에 있다. 40종뿐이고 거의 변하지 않는 정적 데이터이므로 시드 파일로 한 번 만들면 된다.

## 게임이 이미 가진 것

- **챔피언 통계 화면** — 순위 / 경기 수 / 승률 / 밴픽률 / 선택·금지 횟수 / 평점 / 딜·탱·힐, 탭은 `현재 패치` / `시즌 전체`
- `ChampionRankStat` — Name, GameCount, PickCount, BanCount, WinCount, KDA, Deal/Heal/Tank
- `CompetitionChampionStat` — 대회별 챔피언 스탯 (25개 대회 × 40챔)
- `MetaChampion` — 메타 챔피언과 그 카운터의 상대전적. 예: `Ninja ← PlagueDoctor 6승 0패`

즉 **단순 승률·밴픽률 표는 게임이 이미 보여준다.** 웹이 가져갈 자리는 시즌 누적, 카운터 매트릭스, 시너지, 밴픽 시뮬이다.

## 등장 챔피언 (관측된 40종)

```
Archer BarrierMagician Bard Berserker BoomerangHunter Chef Clown Dancer
DarkMage Demon DuelBlader Executioner Exorcist Fighter Gambler Ghost
Gunner IceMage Illusionist Jiangshi Knight Lancer LightningMage MagicKnight
Monk Necromancer Ninja Ogre PlagueDoctor PoisonDartHunter Priest Pyromancer
Pythoness Shadowmancer ShieldBearer Sniper Swordman Taoist Vampire Werewolf
```

---

[[teamFighterManger.README]]
