-- 스키마 제약 검증. 읽기 전용이다 — 마지막에 전부 ROLLBACK 한다.
-- 실행:  chcp 65001 후
--        psql -U postgres -d tfm_test -f tests/verify_schema.sql
-- 대상 스키마: src/main/resources/db/migration/V1__init.sql
SET client_encoding = 'UTF8';

BEGIN;

CREATE TEMP TABLE _result (label text, expected text, actual text, ok boolean);

CREATE FUNCTION pg_temp.expect_fail(sql text, label text) RETURNS void AS $fn$
BEGIN
  BEGIN
    EXECUTE sql;
    INSERT INTO _result VALUES (label, '거부', '통과됨', false);
  EXCEPTION WHEN others THEN
    INSERT INTO _result VALUES (label, '거부', '거부됨', true);
  END;
END; $fn$ LANGUAGE plpgsql;

CREATE FUNCTION pg_temp.expect_ok(sql text, label text) RETURNS void AS $fn$
BEGIN
  BEGIN
    EXECUTE sql;
    INSERT INTO _result VALUES (label, '통과', '통과됨', true);
  EXCEPTION WHEN others THEN
    INSERT INTO _result VALUES (label, '통과', '거부됨: ' || SQLERRM, false);
  END;
END; $fn$ LANGUAGE plpgsql;

-- 픽스처
INSERT INTO champion (code, name_ko, category) VALUES
  ('Fighter','격투가','MELEE'), ('DarkMage','흑마술사','MAGICIAN'),
  ('Werewolf','늑대인간','ASSASSIN'), ('Chef','요리사','PRIEST'),
  ('Gunner','총잡이','RANGER');
INSERT INTO save_slot (slot_key, team_name) VALUES ('slot_test.tfm','테스트팀');
INSERT INTO patch (slot_id, season, day, seq)
  SELECT slot_id, 2025, 11, 1 FROM save_slot WHERE slot_key='slot_test.tfm';
INSERT INTO match_record (slot_id, match_type, source_game_id, season, day, winner_side)
  SELECT slot_id, 'OFFICIAL', 1, 2025, 12, 'BLUE' FROM save_slot WHERE slot_key='slot_test.tfm';


-- 마스터
SELECT pg_temp.expect_fail($q$INSERT INTO champion (code, name_ko, category) VALUES ('X','미분류',NULL)$q$,
  'champion.category NULL');


-- 밴/픽 — 팀당 밴 3 · 픽 4
SELECT pg_temp.expect_ok($q$INSERT INTO match_ban (match_id, side, ban_order, champion_id) VALUES ((SELECT max(match_id) FROM match_record),'BLUE',3,(SELECT champion_id FROM champion WHERE code='Werewolf'))$q$,
  'ban_order = 3 (규칙 상한)');

SELECT pg_temp.expect_fail($q$INSERT INTO match_ban (match_id, side, ban_order, champion_id) VALUES ((SELECT max(match_id) FROM match_record),'BLUE',4,(SELECT champion_id FROM champion WHERE code='Chef'))$q$,
  'ban_order = 4 (팀당 밴 3 초과)');

SELECT pg_temp.expect_fail($q$INSERT INTO match_participant (match_id, side, pick_order, champion_id) VALUES ((SELECT max(match_id) FROM match_record),'BLUE',5,(SELECT champion_id FROM champion WHERE code='Chef'))$q$,
  'pick_order = 5 (팀당 픽 4 초과)');

SELECT pg_temp.expect_ok($q$INSERT INTO match_participant (match_id, side, pick_order, champion_id) VALUES ((SELECT max(match_id) FROM match_record),'BLUE',1,(SELECT champion_id FROM champion WHERE code='Fighter'))$q$,
  'pick_order = 1 정상 적재');

SELECT pg_temp.expect_fail($q$INSERT INTO match_participant (match_id, side, pick_order, champion_id) VALUES ((SELECT max(match_id) FROM match_record),'RED',1,(SELECT champion_id FROM champion WHERE code='Fighter'))$q$,
  '한 경기에 같은 챔피언 2회 (D20)');

SELECT pg_temp.expect_fail($q$UPDATE match_record SET team_size = 5 WHERE match_id = (SELECT max(match_id) FROM match_record)$q$,
  'team_size = 5');


-- 집계 무결성
SELECT pg_temp.expect_fail($q$INSERT INTO champion_performance (scope, champion_id, games, wins) VALUES ('GLOBAL',(SELECT champion_id FROM champion WHERE code='Fighter'),3,5)$q$,
  'wins > games');

SELECT pg_temp.expect_fail($q$INSERT INTO champion_matchup (scope, champion_id, opponent_id) VALUES ('GLOBAL',(SELECT champion_id FROM champion WHERE code='Fighter'),(SELECT champion_id FROM champion WHERE code='Fighter'))$q$,
  '자기 자신과의 매치업');

SELECT pg_temp.expect_ok($q$INSERT INTO champion_performance (scope, champion_id, games, wins) VALUES ('GLOBAL',(SELECT champion_id FROM champion WHERE code='Fighter'),10,6)$q$,
  'GLOBAL 집계 1행 적재');

SELECT pg_temp.expect_fail($q$INSERT INTO champion_performance (scope, champion_id, games, wins) VALUES ('GLOBAL',(SELECT champion_id FROM champion WHERE code='Fighter'),20,11)$q$,
  'UNIQUE NULLS NOT DISTINCT (NULL 키 중복)');

SELECT pg_temp.expect_fail($q$INSERT INTO category_comp (comp_key, melee, ranger) VALUES ('BAD',2,1)$q$,
  '역할군 구성 합계 != 4');

SELECT pg_temp.expect_fail($q$INSERT INTO synergy_combo (combo_size, champion_key) VALUES (4,'1-2-3-4')$q$,
  '시너지 조합 크기 4 (D11)');


-- 드래프트 (D26)
SELECT pg_temp.expect_ok($q$INSERT INTO draft_session (scope, user_side) VALUES ('GLOBAL','BLUE')$q$,
  'GLOBAL 드래프트 세션 생성');

SELECT pg_temp.expect_fail($q$INSERT INTO draft_session (scope, patch_id) SELECT 'GLOBAL', patch_id FROM patch LIMIT 1$q$,
  'GLOBAL 인데 패치 지정 (D24)');

SELECT pg_temp.expect_ok($q$INSERT INTO draft_session (scope, slot_id, patch_id) SELECT 'CAREER', slot_id, patch_id FROM patch LIMIT 1$q$,
  'CAREER + 패치 지정');

SELECT pg_temp.expect_fail($q$UPDATE draft_session SET next_step = 16 WHERE session_id = (SELECT session_id FROM draft_session ORDER BY created_at LIMIT 1)$q$,
  'next_step = 16 (범위 초과)');

SELECT pg_temp.expect_ok($q$INSERT INTO draft_selection (session_id, step_no, champion_id) VALUES ((SELECT session_id FROM draft_session ORDER BY created_at LIMIT 1),1,(SELECT champion_id FROM champion WHERE code='Werewolf'))$q$,
  '드래프트 밴 1건 적재');

SELECT pg_temp.expect_fail($q$INSERT INTO draft_selection (session_id, step_no, champion_id) VALUES ((SELECT session_id FROM draft_session ORDER BY created_at LIMIT 1),5,(SELECT champion_id FROM champion WHERE code='Werewolf'))$q$,
  '밴된 챔피언을 다시 픽');

SELECT pg_temp.expect_fail($q$INSERT INTO draft_selection (session_id, step_no, champion_id) VALUES ((SELECT session_id FROM draft_session ORDER BY created_at LIMIT 1),15,(SELECT champion_id FROM champion WHERE code='Chef'))$q$,
  '스텝 15 선택 (드래프트는 14스텝)');


-- draft_step 시드 내용
INSERT INTO _result
SELECT 'draft_step 총 14스텝', '14', count(*)::text, count(*) = 14 FROM draft_step;

INSERT INTO _result
SELECT 'draft_step 진영별 밴3 픽4', 'BAN 3 / PICK 4',
       string_agg(action || ' ' || n, ' / ' ORDER BY action), bool_and(ok)
FROM (SELECT action::text AS action, count(*) AS n,
             count(*) = CASE WHEN action='BAN' THEN 3 ELSE 4 END AS ok
      FROM draft_step WHERE side='BLUE' GROUP BY action) t;

INSERT INTO _result
SELECT 'draft_step 진영 순서', 'BRBRBRRBRBRBBR',
       string_agg(CASE WHEN side='BLUE' THEN 'B' ELSE 'R' END, '' ORDER BY step_no),
       string_agg(CASE WHEN side='BLUE' THEN 'B' ELSE 'R' END, '' ORDER BY step_no) = 'BRBRBRRBRBRBBR'
FROM draft_step;

INSERT INTO _result
SELECT 'draft_step 액션 순서 (2차밴이 픽 뒤)', 'bbbbppppbbpppp',
       string_agg(CASE WHEN action='BAN' THEN 'b' ELSE 'p' END, '' ORDER BY step_no),
       string_agg(CASE WHEN action='BAN' THEN 'b' ELSE 'p' END, '' ORDER BY step_no) = 'bbbbppppbbpppp'
FROM draft_step;

-- 결과
SELECT CASE WHEN ok THEN 'PASS' ELSE '** FAIL **' END AS "결과",
       label AS "항목", expected AS "기대", actual AS "실제"
FROM _result ORDER BY ok, label;

SELECT count(*) FILTER (WHERE ok) AS "통과",
       count(*) FILTER (WHERE NOT ok) AS "실패",
       count(*) AS "전체"
FROM _result;

ROLLBACK;
