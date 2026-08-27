-- 밴률의 분모를 공식전 수로 분리한다 (2026-08-27, decision.md D50)
--
-- V1 은 ban_rate 를 bans / match_count 로 만들었다. 그런데 밴은 공식전에만 있고
-- (match_ban 주석: "스크림에는 밴이 없다. OFFICIAL 만 행이 생긴다")
-- match_count 는 스크림을 포함한 총 경기 수다.
--
-- 그래서 include_scrim = true 인 행에서는 분자에 없는 경기가 분모에만 들어간다.
-- 공식 698 · 스크림 413 이므로 밴률이 실제의 63% 로 축소된다. 예외도 NULL 도 아니고
-- 그냥 낮은 숫자라 화면에서는 "이 챔피언은 밴을 잘 안 당하네" 로 읽힌다.
--
-- 주석으로 남기지 않고 컬럼을 나눈다 — 문서에만 적힌 규칙은 지켜지지 않는다.
-- pick_rate 는 그대로 match_count 를 쓴다. 픽은 스크림에도 있으므로 그쪽은 맞다.
--
-- champion_performance 는 아직 한 번도 채워진 적이 없어 옮길 데이터가 없다.

SET client_encoding = 'UTF8';

ALTER TABLE champion_performance DROP COLUMN ban_rate;

ALTER TABLE champion_performance
  ADD COLUMN ban_match_count int NOT NULL DEFAULT 0;

ALTER TABLE champion_performance
  ADD COLUMN ban_rate numeric(6,4) GENERATED ALWAYS AS
    (CASE WHEN ban_match_count > 0 THEN bans::numeric / ban_match_count END) STORED;

COMMENT ON COLUMN champion_performance.match_count IS
  '픽률의 분모. 해당 스코프의 총 경기 수(스크림 포함 여부는 include_scrim 을 따른다).';

COMMENT ON COLUMN champion_performance.ban_match_count IS
  '밴률의 분모. 해당 스코프의 공식전 수. 밴은 공식전에만 있으므로 match_count 와 다르다 (D50).';
