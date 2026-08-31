-- 기사 종류 (2026-08-31, decisions/D70_*.md)
--
-- V8 은 "기사 = 매치 하나" 를 전제로 짰다. 유일 키가
-- (slot_id, season, day, blue_team_id, red_team_id) 인 것이 그 전제다.
--
-- 라운드 총평은 그 틀에 안 들어간다. 하루치 여러 경기를 한 편으로 쓰므로 팀 두 개를
-- 고를 수 없다. 그래서 종류를 컬럼으로 두고, 매치가 없는 기사에는 팀·스코어를 비운다.

SET client_encoding = 'UTF8';


-- 기사 종류.
--   MATCH  매치 하나. 지금까지의 전부다
--   ROUND  그날(시즌·일) 전체를 훑는 총평
--
-- ENUM 으로 두는 이유는 값이 코드와 짝이기 때문이다. text 로 두면 오타가 새 종류가 되고,
-- 화면은 그것을 조용히 빈 목록으로 그린다.
CREATE TYPE article_kind AS ENUM ('MATCH', 'ROUND');

-- 기존 행은 전부 매치 기사다. DEFAULT 로 채운 뒤 기본값을 뗀다 —
-- 남겨두면 새 코드가 종류를 안 넘겨도 조용히 MATCH 가 되는데, 그건 새 종류를 추가할 때
-- 가장 찾기 어려운 버그가 된다.
ALTER TABLE article ADD COLUMN kind article_kind NOT NULL DEFAULT 'MATCH';
ALTER TABLE article ALTER COLUMN kind DROP DEFAULT;

-- 라운드 총평에는 대전 상대가 없다.
ALTER TABLE article ALTER COLUMN blue_team_id DROP NOT NULL;
ALTER TABLE article ALTER COLUMN red_team_id  DROP NOT NULL;
ALTER TABLE article ALTER COLUMN blue_score   DROP NOT NULL;
ALTER TABLE article ALTER COLUMN red_score    DROP NOT NULL;
ALTER TABLE article ALTER COLUMN blue_kill    DROP NOT NULL;
ALTER TABLE article ALTER COLUMN red_kill     DROP NOT NULL;

-- 종류마다 채워야 하는 것이 다르다. 이건 한 행 안의 값 비교라 CHECK 로 표현된다 —
-- 트리거를 두지 않는다는 규칙(D35)을 어기지 않는다.
ALTER TABLE article ADD CONSTRAINT article_match_needs_teams CHECK (
  kind <> 'MATCH' OR (
    blue_team_id IS NOT NULL AND red_team_id IS NOT NULL
    AND blue_score IS NOT NULL AND red_score IS NOT NULL
    AND blue_kill  IS NOT NULL AND red_kill  IS NOT NULL)
);

ALTER TABLE article ADD CONSTRAINT article_round_has_no_teams CHECK (
  kind <> 'ROUND' OR (blue_team_id IS NULL AND red_team_id IS NULL)
);


-- 유일 키를 종류까지 포함해 다시 짠다.
--
-- NULLS NOT DISTINCT 가 핵심이다. 보통의 UNIQUE 는 NULL 을 서로 다른 값으로 보기 때문에,
-- 팀이 NULL 인 총평은 같은 날짜에 무한히 쌓인다 — 버튼을 누를 때마다 한 편씩 는다.
-- 이 절이 있어야 "하루에 총평 한 편" 이 제약으로 선다. (D50 의 champion_matchup 이
-- 같은 이유로 같은 절을 쓴다.)
ALTER TABLE article DROP CONSTRAINT article_slot_id_season_day_blue_team_id_red_team_id_key;

ALTER TABLE article ADD CONSTRAINT article_identity_key
  UNIQUE NULLS NOT DISTINCT (slot_id, kind, season, day, blue_team_id, red_team_id);

COMMENT ON COLUMN article.kind IS
  'MATCH = 매치 하나 · ROUND = 그날 전체 총평 (D70)';
