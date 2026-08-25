-- 팀 인원 4명 고정 — 픽 4 · 밴 3 (2026-08-25, decision.md D35)
--
-- V1 은 팀 인원을 2~4 로 열어뒀다. 실측 데이터에 2v2 / 3v3 스크림이 섞여 있었기 때문이다.
-- 그러나 그것은 규칙이 아니라 구 데이터의 예외다. 규칙은 4v4 고정이고,
-- 조건이 다른 경기의 승률을 같은 표에 섞을 수 없다.
--
-- 비4v4 경기는 적재 단계에서 제외한다. 이 제약은 그 규칙을 DB 가 강제하게 만든다.
-- 코드가 실수해도 여기서 막힌다.

SET client_encoding = 'UTF8';

ALTER TABLE match_record
  DROP CONSTRAINT IF EXISTS match_record_team_size_check;

ALTER TABLE match_record
  ADD CONSTRAINT match_record_team_size_check CHECK (team_size = 4);

COMMENT ON COLUMN match_record.team_size IS
  '팀 인원. 4 고정 (D35). 비4v4 경기는 적재하지 않는다.';

COMMENT ON COLUMN match_ban.ban_order IS
  '팀 안에서 몇 번째 밴인지. 1~3 고정 (D35). "정확히 3개" 는 행 개수 제약이라 CHECK 로 표현할 수 없다 — 적재에서 막는다.';
