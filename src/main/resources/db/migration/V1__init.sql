-- 팀파이트 매니저 티어 분석 웹 - DB 스키마
-- PostgreSQL 16 (Windows 네이티브 설치). Docker/WSL/Supabase 사용 안 함 (decision.md D23/D27)
-- 설계 근거는 같은 폴더 decision.md, 세이브 파일 구조는 savefile.md 참고
--
-- 핵심 전제 (전부 실제 세이브 파일 해석으로 확인됨)
--   * 공식전은 4v4 고정 (픽 4, 챔피언 8). 라인·포지션 개념 없음
--   * 게임 규칙은 팀당 밴 3 · 픽 4 로 고정이다 (양팀 합계 밴 6 · 픽 8)
--     단 챔피언 풀이 작던 커리어 초반 데이터에는 팀당 밴 2 인 경기가 있다.
--     규칙이 가변인 것이 아니라 과거 데이터의 예외다. 파서는 배열 길이를 상수로 가정하지 말 것
--   * 드래프트 순서는 14스텝 고정. draft_step 테이블이 원본 (decision.md D26)
--   * 스크림은 2v2 / 3v3 / 4v4 가 섞인다. 집계는 team_size = 4 만 쓴다
--   * 세이브는 현재 시즌 경기만 보관 → 시즌 넘어가면 소실. 누적은 이 DB가 담당
--   * 패치는 커리어(세이브 슬롯)마다 고유하게 생성됨 → 슬롯 간 패치 공유 불가
--   * 스크림에는 시점(Season/Day) 정보가 없음 → 워처가 관측 시점을 붙인다
--   * 현재 게임 내 날짜는 TodayData.<Time>k__BackingField 에서 읽는다.
--     max(GameStat.Day) 로 추정하면 최대 5일 뒤처진다 (D18)
--   * 이벤트전은 경기 데이터를 남기지 않는다 → 별도 제외 처리 불필요 (D16)
--   * GameStat.ChampStat 순서는 BluePick+RedPick 과 20% 어긋난다 → 이름 매칭 필수 (D20)
--   * 게임은 다전제다. 스케줄 1건에 세트 2~5개. 분석 단위는 세트

-- 이 파일은 UTF-8 이다. psql 의 client_encoding 은 Windows 콘솔 코드페이지(한글이면 UHC)를
-- 따라가므로, 이 줄이 없으면 주석의 한글에서 인코딩 오류로 적재가 중단된다.
SET client_encoding = 'UTF8';


-- =====================================================================
-- 0. 공통 타입
-- =====================================================================

CREATE TYPE team_side         AS ENUM ('BLUE', 'RED');
CREATE TYPE match_type        AS ENUM ('OFFICIAL', 'SCRIM');
CREATE TYPE champion_category AS ENUM ('MELEE', 'RANGER', 'MAGICIAN', 'PRIEST', 'ASSASSIN');
-- GLOBAL: 모든 슬롯 합산(패치 무시). CAREER: 슬롯 내부(패치·버전 반영)
CREATE TYPE agg_scope         AS ENUM ('GLOBAL', 'CAREER');

CREATE OR REPLACE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
  NEW.updated_at = now();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;


-- =====================================================================
-- 1. 분석 설정
--    임계값을 코드에 박지 않는다. 데이터가 쌓이면 조정해야 하는 값들이다.
-- =====================================================================

CREATE TABLE analysis_config (
  key         text PRIMARY KEY,
  value       numeric     NOT NULL,
  note        text,
  updated_at  timestamptz NOT NULL DEFAULT now()
);

INSERT INTO analysis_config (key, value, note) VALUES
  ('min_sample',            10, '이 경기 수 미만인 조합은 화면에 노출하지 않는다. 실측상 7경기 이상이면 우연에 의한 100%/0% 승률이 사라진다'),
  ('prior_strength_k0',     24, '1단 축소. 전체 누적 추정을 기저 기대값 쪽으로 당기는 강도'),
  ('prior_strength_k1',     15, '2단 축소. 패치별 추정을 전체 누적 추정 쪽으로 당기는 강도'),
  ('synergy_max_size',       3, '시너지 조합 최대 크기. 4인 챔피언 조합은 최다 9경기라 집계하지 않는다'),
  ('self_decay_half_life',   2, '자기 변경 감쇠 반감기. 해당 챔피언이 패치로 바뀐 횟수 기준. 1차 효과라 짧다'),
  ('meta_decay_half_life',  12, '메타 변화 감쇠 반감기(경과 패치 수). 다른 챔피언 변경의 간접 영향. 2차 효과라 길다');


-- =====================================================================
-- 2. 마스터 데이터
-- =====================================================================

-- 세이브 슬롯 = 하나의 커리어. 패치 역사가 슬롯마다 다르므로 분석 스코프의 단위가 된다.
CREATE TABLE save_slot (
  slot_id       int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slot_key      text        NOT NULL UNIQUE,   -- 파일명 (slot_638683925954242004.tfm)
  label         text,                          -- 사용자가 붙이는 이름
  team_name     text,                          -- SavePreview.Name
  first_seen_at timestamptz NOT NULL DEFAULT now(),
  last_ingest_at timestamptz,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER save_slot_set_updated_at BEFORE UPDATE ON save_slot
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();


CREATE TABLE champion (
  champion_id int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  code        text NOT NULL UNIQUE,            -- 세이브에 그대로 들어있는 값. 'Jiangshi'
  name_ko     text NOT NULL,
  -- 세이브에 없다. 게임 에셋(ItemChampionCategoryConfig)에 있으며 시드로 넣는다.
  category    champion_category NOT NULL,
  image_url   text,
  is_playable boolean NOT NULL DEFAULT true,
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER champion_set_updated_at BEFORE UPDATE ON champion
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();


-- 팀. TeamID 는 슬롯 안에서만 유효한 번호다(0 = 플레이어 팀).
CREATE TABLE team (
  team_id      int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slot_id      int  NOT NULL REFERENCES save_slot(slot_id) ON DELETE CASCADE,
  game_team_id int  NOT NULL,
  name         text,
  is_player    boolean NOT NULL DEFAULT false,
  UNIQUE (slot_id, game_team_id)
);


-- =====================================================================
-- 3. 패치
--    PatchNews.Date(Season, Day) 로 시점이, PatchNews.Patches 로 변경 내용이 온다.
--    사용자 입력은 필요 없다.
-- =====================================================================

CREATE TABLE patch (
  patch_id   int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slot_id    int  NOT NULL REFERENCES save_slot(slot_id) ON DELETE CASCADE,
  season     int  NOT NULL,
  day        int  NOT NULL,
  seq        int  NOT NULL,               -- 커리어 안에서의 순번
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (slot_id, season, day),
  UNIQUE (slot_id, seq)
);


-- 패치 하나가 건드린 챔피언과 스탯 변화량. PatchData 그대로.
-- champion_version 계산의 원천이다: "이 챔피언이 그 시점까지 몇 번 바뀌었나".
CREATE TABLE champion_patch_event (
  patch_id     int NOT NULL REFERENCES patch(patch_id)       ON DELETE CASCADE,
  champion_id  int NOT NULL REFERENCES champion(champion_id) ON DELETE CASCADE,
  attack       int NOT NULL DEFAULT 0,
  magic        int NOT NULL DEFAULT 0,
  defence      int NOT NULL DEFAULT 0,
  max_hp       int NOT NULL DEFAULT 0,
  attack_speed int NOT NULL DEFAULT 0,
  skill_cool   int NOT NULL DEFAULT 0,
  move_speed   int NOT NULL DEFAULT 0,
  is_new       boolean NOT NULL DEFAULT false,   -- PatchNews.NewChamps
  PRIMARY KEY (patch_id, champion_id)
);
CREATE INDEX champion_patch_event_champ_idx ON champion_patch_event (champion_id);


-- 경기 시점에서의 챔피언 변경 횟수. 감쇠 가중치 w = 0.5^(변경횟수/h) 의 지수로 쓴다.
-- (하드 컷으로 그룹을 쪼개지 않는다 — 그러면 표본이 0~38%만 남는다. decision.md D15 참고)
CREATE VIEW v_champion_version AS
SELECT p.slot_id, e.champion_id, p.season, p.day,
       row_number() OVER (PARTITION BY p.slot_id, e.champion_id ORDER BY p.seq) AS version
FROM champion_patch_event e
JOIN patch p USING (patch_id);


-- =====================================================================
-- 4. 경기 기록
-- =====================================================================

CREATE TABLE match_record (
  match_id       bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slot_id        int        NOT NULL REFERENCES save_slot(slot_id) ON DELETE CASCADE,
  match_type     match_type NOT NULL,
  source_game_id int        NOT NULL,        -- GameStat.ID / ScrimStat.ID (슬롯 안에서만 유일)

  -- OFFICIAL 에만 있다. SCRIM 은 세이브에 시점 정보가 없어 NULL.
  season         int,
  day            int,
  set_no         int,
  schedule_id    int,
  patch_id       int        REFERENCES patch(patch_id),

  -- 워처가 스크림을 처음 발견한 시점의 게임 내 Day. 게임이 주지 않는 값을 우리가 만든다.
  -- day 만으로는 커리어가 시즌을 넘길 때 패치를 배정할 수 없다.
  -- TodayData 가 SeasonTime{Season, Day, Run} 이므로 season 도 함께 읽는다.
  observed_season int,
  observed_day   int,
  observed_at    timestamptz,

  -- 팀당 인원. 공식전은 항상 4, 스크림은 2·3·4 가 섞인다.
  -- 카운터·시너지·역할군 집계는 team_size = 4 인 경기만 쓴다(조건이 다르면 승률을 섞을 수 없다).
  team_size      smallint   NOT NULL DEFAULT 4 CHECK (team_size BETWEEN 2 AND 4),

  blue_team_id   int        REFERENCES team(team_id),
  red_team_id    int        REFERENCES team(team_id),
  blue_score     int,
  red_score      int,
  winner_side    team_side  NOT NULL,
  is_overtime    boolean    NOT NULL DEFAULT false,
  is_sudden_death boolean   NOT NULL DEFAULT false,

  ingested_at    timestamptz NOT NULL DEFAULT now(),

  UNIQUE (slot_id, match_type, source_game_id),
  CONSTRAINT match_official_has_date CHECK (
    match_type <> 'OFFICIAL' OR (season IS NOT NULL AND day IS NOT NULL)
  )
);
CREATE INDEX match_record_slot_idx  ON match_record (slot_id, season, day);
CREATE INDEX match_record_patch_idx ON match_record (patch_id);


CREATE TABLE match_participant (
  match_id      bigint    NOT NULL REFERENCES match_record(match_id) ON DELETE CASCADE,
  side          team_side NOT NULL,
  pick_order    smallint  NOT NULL CHECK (pick_order BETWEEN 1 AND 4),
  champion_id   int       NOT NULL REFERENCES champion(champion_id),
  -- 이 경기 시점까지 해당 챔피언이 패치로 바뀐 횟수. 감쇠 가중치 계산에 쓴다.
  -- 주의: GameStat.ChampStat 순서는 BluePick+RedPick 과 20% 어긋난다(D20).
  --       반드시 챔피언 이름으로 매칭해서 side/pick_order 를 정할 것.
  change_count  smallint NOT NULL DEFAULT 0,
  athlete_id    int,                       -- AthleteMatchStat.AthleteID
  kills         smallint,
  deaths        smallint,
  assists       smallint,
  dealing       int,
  tanking       int,
  healing       int,
  live_duration int,
  PRIMARY KEY (match_id, side, pick_order)
);
CREATE UNIQUE INDEX match_participant_unique_champ ON match_participant (match_id, champion_id);
CREATE INDEX match_participant_champion_idx ON match_participant (champion_id);


-- 스크림에는 밴이 없다. OFFICIAL 만 행이 생긴다.
-- 팀당 밴 수는 2 또는 3 으로 고정이 아니다. 여유를 두고 상한만 건다.
CREATE TABLE match_ban (
  match_id    bigint    NOT NULL REFERENCES match_record(match_id) ON DELETE CASCADE,
  side        team_side NOT NULL,
  ban_order   smallint  NOT NULL CHECK (ban_order BETWEEN 1 AND 3),
  champion_id int       NOT NULL REFERENCES champion(champion_id),
  PRIMARY KEY (match_id, side, ban_order)
);
CREATE UNIQUE INDEX match_ban_unique_champ ON match_ban (match_id, champion_id);
CREATE INDEX match_ban_champion_idx ON match_ban (champion_id);


CREATE VIEW v_match_participant AS
SELECT p.*, m.slot_id, m.match_type, m.season, m.day, m.patch_id,
       (m.winner_side = p.side) AS is_winner
FROM match_participant p
JOIN match_record m USING (match_id);


-- =====================================================================
-- 5. 적재 이력
-- =====================================================================

CREATE TABLE ingest_run (
  run_id        bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  slot_id       int         REFERENCES save_slot(slot_id) ON DELETE CASCADE,
  file_hash     text        NOT NULL,       -- sha256. 같은 내용 재파싱 방지
  file_size     bigint,
  parser_version text       NOT NULL,
  started_at    timestamptz NOT NULL DEFAULT now(),
  finished_at   timestamptz,
  new_matches   int         NOT NULL DEFAULT 0,
  new_scrims    int         NOT NULL DEFAULT 0,
  error_message text,
  UNIQUE (slot_id, file_hash)
);


-- =====================================================================
-- 6. 집계
--    비율은 저장하지 않고 카운트만 저장한다. 표시값은 축소추정으로 따로 계산.
--    scope=GLOBAL 은 슬롯 합산(패치 무시), scope=CAREER 는 슬롯 내부(버전 반영).
-- =====================================================================

CREATE TABLE agg_run (
  agg_run_id  bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  started_at  timestamptz NOT NULL DEFAULT now(),
  finished_at timestamptz,
  min_sample     numeric,
  prior_strength numeric,
  note        text
);


CREATE TABLE champion_performance (
  scope        agg_scope NOT NULL,
  slot_id      int       REFERENCES save_slot(slot_id) ON DELETE CASCADE,  -- GLOBAL 이면 NULL
  patch_id     int       REFERENCES patch(patch_id)    ON DELETE CASCADE,  -- GLOBAL 이면 NULL
  champion_id  int       NOT NULL REFERENCES champion(champion_id) ON DELETE CASCADE,
  include_scrim boolean  NOT NULL DEFAULT true,

  games        int NOT NULL DEFAULT 0,     -- 원시 카운트. 표본 기준 판정과 화면 표시용
  wins         int NOT NULL DEFAULT 0,
  bans         int NOT NULL DEFAULT 0,
  match_count  int NOT NULL DEFAULT 0,     -- 분모. 해당 스코프의 총 경기 수

  -- 감쇠 가중 합계. 승률 계산과 정렬은 이쪽을 쓴다 (decision.md D15)
  weighted_games numeric(10,3) NOT NULL DEFAULT 0,
  weighted_wins  numeric(10,3) NOT NULL DEFAULT 0,
  ess            numeric(10,3),            -- (Σw)² / Σw². 신뢰구간용 유효표본수

  -- 경기력 평균(그 챔피언 자신의 분포 기준 z). 티어 점수에는 안 들어가고
  -- 축소 목표값 산출과 챔피언 성격 표시에 쓴다 (decision.md D19)
  z_deal numeric(6,3),
  z_tank numeric(6,3),
  z_heal numeric(6,3),
  z_kda  numeric(6,3),

  win_rate  numeric(6,4) GENERATED ALWAYS AS
              (CASE WHEN games > 0 THEN wins::numeric / games END) STORED,
  pick_rate numeric(6,4) GENERATED ALWAYS AS
              (CASE WHEN match_count > 0 THEN games::numeric / match_count END) STORED,
  ban_rate  numeric(6,4) GENERATED ALWAYS AS
              (CASE WHEN match_count > 0 THEN bans::numeric / match_count END) STORED,

  -- 2단 축소를 거친 최종 추정. 원시 카운트가 아니라 weighted_* 를 쓰고,
  -- 축소 목표는 전체 평균이 아니라 챔피언 강도 기대값이다 (decision.md D14/D15).
  -- 순위 정렬은 항상 이 값으로 한다.
  adjusted_win_rate numeric(6,4),
  tier_grade  text CHECK (tier_grade IN ('S+','S','A','B','C','D')),
  agg_run_id  bigint REFERENCES agg_run(agg_run_id),

  CONSTRAINT champion_performance_wins_le_games CHECK (wins <= games),
  UNIQUE NULLS NOT DISTINCT (scope, slot_id, patch_id, champion_id, include_scrim)
);
CREATE INDEX champion_performance_rank_idx
  ON champion_performance (scope, slot_id, patch_id, adjusted_win_rate DESC);


-- 카운터. 라인이 없으므로 "적팀에 같이 있었다"가 유일한 정의다.
-- patch_id 가 NULL 이면 전체 누적(1단), 값이 있으면 그 패치 추정(2단).
-- 버전을 키에 넣지 않는다 — 감쇠 가중치로 처리한다 (decision.md D15).
CREATE TABLE champion_matchup (
  scope        agg_scope NOT NULL,
  slot_id      int       REFERENCES save_slot(slot_id) ON DELETE CASCADE,
  patch_id     int       REFERENCES patch(patch_id)    ON DELETE CASCADE,
  champion_id  int       NOT NULL REFERENCES champion(champion_id) ON DELETE CASCADE,
  opponent_id  int       NOT NULL REFERENCES champion(champion_id) ON DELETE CASCADE,
  include_scrim boolean  NOT NULL DEFAULT true,

  games int NOT NULL DEFAULT 0,
  wins  int NOT NULL DEFAULT 0,
  weighted_games numeric(10,3) NOT NULL DEFAULT 0,
  weighted_wins  numeric(10,3) NOT NULL DEFAULT 0,
  ess            numeric(10,3),

  win_rate numeric(6,4) GENERATED ALWAYS AS
             (CASE WHEN games > 0 THEN wins::numeric / games END) STORED,

  -- 두 챔피언 기저 강도만으로 예측되는 승률 (Bradley-Terry)
  expected_win_rate numeric(6,4),
  -- 2단 축소를 거친 최종 추정
  adjusted_win_rate numeric(6,4),
  -- adjusted - expected. 챔피언 강도를 걷어낸 진짜 상성. 정렬은 이걸로 (D14)
  counter_effect    numeric(6,4),
  -- 경기력 기반 조기 지표. 표본이 승률 판정에 못 미칠 때 상성 의심 신호로 쓴다 (D19)
  z_deal numeric(6,3),
  z_tank numeric(6,3),

  agg_run_id bigint REFERENCES agg_run(agg_run_id),

  CONSTRAINT champion_matchup_not_self CHECK (champion_id <> opponent_id),
  CONSTRAINT champion_matchup_wins_le_games CHECK (wins <= games),
  UNIQUE NULLS NOT DISTINCT
    (scope, slot_id, patch_id, champion_id, opponent_id, include_scrim)
);
-- A→B, B→A 양방향 모두 적재한다(조회 단순화를 위한 의도된 중복).
CREATE INDEX champion_matchup_lookup_idx
  ON champion_matchup (scope, slot_id, patch_id, champion_id, counter_effect DESC);


-- 시너지 조합. 크기 2~3만 집계한다(4인 챔피언 조합은 최다 9경기).
CREATE TABLE synergy_combo (
  combo_id     bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  combo_size   smallint NOT NULL CHECK (combo_size BETWEEN 2 AND 3),
  champion_key text     NOT NULL UNIQUE      -- champion_id 오름차순 '-' 조인. 순열 중복 차단
);

CREATE TABLE synergy_combo_member (
  combo_id    bigint NOT NULL REFERENCES synergy_combo(combo_id) ON DELETE CASCADE,
  champion_id int    NOT NULL REFERENCES champion(champion_id)   ON DELETE CASCADE,
  PRIMARY KEY (combo_id, champion_id)
);
CREATE INDEX synergy_combo_member_champion_idx ON synergy_combo_member (champion_id);

CREATE TABLE synergy_stat (
  scope      agg_scope NOT NULL,
  slot_id    int       REFERENCES save_slot(slot_id)     ON DELETE CASCADE,
  patch_id   int       REFERENCES patch(patch_id)        ON DELETE CASCADE,
  combo_id   bigint    NOT NULL REFERENCES synergy_combo(combo_id) ON DELETE CASCADE,
  include_scrim boolean NOT NULL DEFAULT true,
  games int NOT NULL DEFAULT 0,
  wins  int NOT NULL DEFAULT 0,
  weighted_games numeric(10,3) NOT NULL DEFAULT 0,
  weighted_wins  numeric(10,3) NOT NULL DEFAULT 0,
  ess            numeric(10,3),
  win_rate numeric(6,4) GENERATED ALWAYS AS
             (CASE WHEN games > 0 THEN wins::numeric / games END) STORED,
  expected_win_rate numeric(6,4),   -- 구성원 기저 강도로 예측되는 승률
  adjusted_win_rate numeric(6,4),
  synergy_effect    numeric(6,4),   -- adjusted - expected. 정렬은 이걸로
  agg_run_id bigint REFERENCES agg_run(agg_run_id),
  CONSTRAINT synergy_stat_wins_le_games CHECK (wins <= games),
  UNIQUE NULLS NOT DISTINCT (scope, slot_id, patch_id, combo_id, include_scrim)
);
CREATE INDEX synergy_stat_rank_idx ON synergy_stat (scope, slot_id, patch_id, synergy_effect DESC);


-- 역할군 4인 구성. 챔피언 4인 조합(91,390가지)은 표본이 안 나오지만
-- 역할군 4자리 중복조합은 70가지뿐이라 조합당 평균 30경기 이상이 확보된다.
CREATE TABLE category_comp (
  comp_id   int GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  comp_key  text NOT NULL UNIQUE,     -- 'MELEE2-MAGICIAN1-PRIEST1' 정규화 문자열
  melee     smallint NOT NULL DEFAULT 0,
  ranger    smallint NOT NULL DEFAULT 0,
  magician  smallint NOT NULL DEFAULT 0,
  priest    smallint NOT NULL DEFAULT 0,
  assassin  smallint NOT NULL DEFAULT 0,
  CONSTRAINT category_comp_size CHECK (melee + ranger + magician + priest + assassin = 4)
);

CREATE TABLE category_comp_stat (
  scope     agg_scope NOT NULL,
  slot_id   int       REFERENCES save_slot(slot_id) ON DELETE CASCADE,
  patch_id  int       REFERENCES patch(patch_id)    ON DELETE CASCADE,
  comp_id   int       NOT NULL REFERENCES category_comp(comp_id) ON DELETE CASCADE,
  include_scrim boolean NOT NULL DEFAULT true,
  games int NOT NULL DEFAULT 0,
  wins  int NOT NULL DEFAULT 0,
  weighted_games numeric(10,3) NOT NULL DEFAULT 0,
  weighted_wins  numeric(10,3) NOT NULL DEFAULT 0,
  ess            numeric(10,3),
  win_rate numeric(6,4) GENERATED ALWAYS AS
             (CASE WHEN games > 0 THEN wins::numeric / games END) STORED,
  adjusted_win_rate numeric(6,4),
  agg_run_id bigint REFERENCES agg_run(agg_run_id),
  UNIQUE NULLS NOT DISTINCT (scope, slot_id, patch_id, comp_id, include_scrim)
);


-- =====================================================================
-- 7. 밴픽 시뮬레이터
--    드래프트 순서는 게임 규칙이라 데이터가 아니라 상수다. 다만 코드에 박지 않고
--    draft_step 에 둔다 — 시뮬 엔진·검증·화면이 같은 원본을 봐야 하기 때문이다.
-- =====================================================================

CREATE TYPE draft_action AS ENUM ('BAN', 'PICK');

-- 14스텝 고정. 팀당 밴 3 · 픽 4.
-- 전반부(B1·R2·B1)와 후반부(R1·B2·R1)가 진영을 바꿔 대칭이다.
-- step 9,10 의 2차 밴이 픽 4개가 공개된 뒤에 온다 — 밴 추천은 그 정보를 조건으로 받아야 한다.
CREATE TABLE draft_step (
  step_no   smallint     PRIMARY KEY CHECK (step_no BETWEEN 1 AND 14),
  side      team_side    NOT NULL,
  action    draft_action NOT NULL,
  -- 그 진영 안에서 몇 번째 밴/픽인지. match_ban.ban_order / match_participant.pick_order 와 대응
  side_seq  smallint     NOT NULL,
  phase     smallint     NOT NULL,   -- 1:1차밴 2:1차픽 3:2차밴 4:2차픽
  UNIQUE (side, action, side_seq)
);

INSERT INTO draft_step (step_no, side, action, side_seq, phase) VALUES
  ( 1,'BLUE','BAN' ,1,1), ( 2,'RED' ,'BAN' ,1,1),
  ( 3,'BLUE','BAN' ,2,1), ( 4,'RED' ,'BAN' ,2,1),
  ( 5,'BLUE','PICK',1,2), ( 6,'RED' ,'PICK',1,2),
  ( 7,'RED' ,'PICK',2,2), ( 8,'BLUE','PICK',2,2),
  ( 9,'RED' ,'BAN' ,3,3), (10,'BLUE','BAN' ,3,3),
  (11,'RED' ,'PICK',3,4), (12,'BLUE','PICK',3,4),
  (13,'BLUE','PICK',4,4), (14,'RED' ,'PICK',4,4);

-- 커리어 초반(챔피언 풀이 작을 때)에는 팀당 밴이 2개다. 그 경기는 phase 3 이 없는 것으로 본다.
-- 적재 시 밴 배열 길이로 판정하고, 시뮬은 항상 3밴 규칙으로만 돌린다.


CREATE TABLE draft_session (
  session_id  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  slot_id     int  REFERENCES save_slot(slot_id),
  -- 통계 스코프. 어떤 추정치로 추천할지 결정한다.
  scope       agg_scope NOT NULL DEFAULT 'GLOBAL',
  patch_id    int  REFERENCES patch(patch_id),   -- NULL = 전체 누적
  owner       text,                              -- Spring Security principal name
  title       text,
  -- 사용자가 어느 진영인지. 추천 대상 결정.
  user_side   team_side NOT NULL DEFAULT 'BLUE',
  -- 다음에 진행할 스텝. 15 면 드래프트 종료.
  next_step   smallint  NOT NULL DEFAULT 1 CHECK (next_step BETWEEN 1 AND 15),
  created_at  timestamptz NOT NULL DEFAULT now(),
  updated_at  timestamptz NOT NULL DEFAULT now(),

  -- GLOBAL 은 슬롯을 합산하므로 패치 축이 없다 (decision.md D24)
  CONSTRAINT draft_session_global_has_no_patch CHECK (scope <> 'GLOBAL' OR patch_id IS NULL)
);

CREATE TRIGGER draft_session_set_updated_at BEFORE UPDATE ON draft_session
  FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- 확정된 밴/픽. jsonb 한 덩어리로 두지 않는다 — 챔피언 중복 금지를 DB가 막아야 한다.
CREATE TABLE draft_selection (
  session_id  uuid     NOT NULL REFERENCES draft_session(session_id) ON DELETE CASCADE,
  step_no     smallint NOT NULL REFERENCES draft_step(step_no),
  champion_id int      NOT NULL REFERENCES champion(champion_id),
  -- 확정 시점에 엔진이 매긴 점수. 사후에 "왜 이걸 골랐나"를 되짚기 위해 남긴다.
  score       numeric(6,4),
  decided_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (session_id, step_no)
);
-- 한 드래프트에서 같은 챔피언은 밴이든 픽이든 한 번만 등장한다.
CREATE UNIQUE INDEX draft_selection_unique_champ
  ON draft_selection (session_id, champion_id);

CREATE VIEW v_draft_board AS
SELECT s.session_id, s.slot_id, s.scope, s.patch_id, s.user_side, s.next_step,
       d.step_no, d.side, d.action, d.side_seq, d.phase,
       sel.champion_id, c.code AS champion_code, c.name_ko, c.category, sel.score
FROM draft_session s
JOIN draft_step d ON true
LEFT JOIN draft_selection sel ON sel.session_id = s.session_id AND sel.step_no = d.step_no
LEFT JOIN champion c ON c.champion_id = sel.champion_id;
