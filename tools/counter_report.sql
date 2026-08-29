-- 카운터 집계 결과를 눈으로 확인한다 (decisions/D14_*.md · D42).
--
-- 집계를 한 번 돌린 뒤에 실행한다:
--   gradlew.bat bootRun --args="--tfm.aggregate-on-start=true"
--
-- 실행:
--   chcp 65001
--   & 'C:\Program Files\PostgreSQL\16\bin\psql.exe' -U postgres -d tfm -f tools/counter_report.sql
--
-- 이 파일은 읽기 전용이다. 아무것도 쓰지 않는다.

\echo '=== 0. 집계 이력 ==='
SELECT agg_run_id, started_at, finished_at, min_sample, prior_strength, note
FROM agg_run
ORDER BY agg_run_id DESC
LIMIT 5;

\echo ''
\echo '=== 1. 스코프별 행 수 ==='
SELECT scope, include_scrim, count(*) AS pairs, count(DISTINCT champion_id) AS champions
FROM champion_matchup
GROUP BY scope, include_scrim
ORDER BY scope, include_scrim;

\echo ''
\echo '=== 2. 상성 이득 상위 20 (GLOBAL · 스크림 포함 · 표본 기준선 통과) ==='
\echo '    D14 의 주장: 여기 올라오는 것은 "센 챔피언" 이 아니라 "이 상대에게 강한 챔피언" 이다.'
SELECT c.code AS champion,
       o.code AS opponent,
       m.games,
       round(m.win_rate * 100, 1)          AS "실제%",
       round(m.expected_win_rate * 100, 1) AS "기대%",
       round(m.counter_effect * 100, 1)    AS "이득%p",
       round(m.ess, 1)                     AS ess
FROM champion_matchup m
JOIN champion c ON c.champion_id = m.champion_id
JOIN champion o ON o.champion_id = m.opponent_id
WHERE m.scope = 'GLOBAL' AND m.include_scrim
  AND m.games >= (SELECT value FROM analysis_config WHERE key = 'min_sample')
ORDER BY m.counter_effect DESC
LIMIT 20;

\echo ''
\echo '=== 3. 같은 조건을 원본 승률로 정렬하면 (비교용) ==='
\echo '    D14 가 예고한 대로라면 이쪽은 특정 챔피언이 상단을 도배해야 한다.'
SELECT c.code AS champion,
       o.code AS opponent,
       m.games,
       round(m.win_rate * 100, 1)       AS "실제%",
       round(m.counter_effect * 100, 1) AS "이득%p"
FROM champion_matchup m
JOIN champion c ON c.champion_id = m.champion_id
JOIN champion o ON o.champion_id = m.opponent_id
WHERE m.scope = 'GLOBAL' AND m.include_scrim
  AND m.games >= (SELECT value FROM analysis_config WHERE key = 'min_sample')
ORDER BY m.win_rate DESC
LIMIT 20;

\echo ''
\echo '=== 4. 두 정렬이 실제로 다른가 — 상단 20 의 겹침 ==='
WITH threshold AS (
    SELECT value AS min_sample FROM analysis_config WHERE key = 'min_sample'
), eligible AS (
    SELECT m.champion_id, m.opponent_id, m.win_rate, m.counter_effect
    FROM champion_matchup m, threshold t
    WHERE m.scope = 'GLOBAL' AND m.include_scrim AND m.games >= t.min_sample
), by_raw AS (
    SELECT champion_id, opponent_id FROM eligible ORDER BY win_rate DESC LIMIT 20
), by_effect AS (
    SELECT champion_id, opponent_id FROM eligible ORDER BY counter_effect DESC LIMIT 20
)
SELECT (SELECT count(*) FROM eligible)                     AS "기준통과 쌍",
       (SELECT count(*) FROM by_raw r JOIN by_effect e USING (champion_id, opponent_id))
                                                           AS "상단20 겹침";

\echo ''
\echo '=== 5. 유효표본수 분포 — D42 의 미결 (쌍 감쇠를 합산해서 ess 가 마르지 않는가) ==='
\echo '    합산 때문에 쌍은 단일 챔피언보다 두 배 빠르게 낡는다. 기준선을 못 넘는 쌍이'
\echo '    많으면 D42 를 최댓값으로 뒤집어야 한다.'
SELECT count(*)                                          AS "전체 쌍",
       count(*) FILTER (WHERE games >= 10)               AS "원시 10경기 이상",
       count(*) FILTER (WHERE ess >= 10)                 AS "ess 10 이상",
       round(avg(ess)::numeric, 2)                       AS "ess 평균",
       round(avg(ess / NULLIF(games, 0))::numeric, 3)    AS "ess/games 평균"
FROM champion_matchup
WHERE scope = 'GLOBAL' AND include_scrim;

\echo ''
\echo '=== 6. 감쇠가 얼마나 깎았나 — 원시 대비 가중 경기 수 ==='
SELECT scope,
       round(sum(games)::numeric, 0)          AS "원시 합",
       round(sum(weighted_games)::numeric, 0) AS "가중 합",
       round((sum(weighted_games) / NULLIF(sum(games), 0))::numeric, 3) AS "남은 비율"
FROM champion_matchup
WHERE include_scrim
GROUP BY scope
ORDER BY scope;

\echo ''
\echo '=== 6b. D49 미확인 — Werewolf vs Swordman 을 직접 찾는다 ==='
\echo '    D14 는 raw 정렬 예시로 84.0% (n=25) 를 들었는데 상위 20 에 안 보였다.'
SELECT c.code AS champion, o.code AS opponent,
       m.scope, m.include_scrim, m.games,
       round(m.win_rate * 100, 1)          AS "실제%",
       round(m.expected_win_rate * 100, 1) AS "기대%",
       round(m.counter_effect * 100, 1)    AS "이득%p"
FROM champion_matchup m
JOIN champion c ON c.champion_id = m.champion_id
JOIN champion o ON o.champion_id = m.opponent_id
WHERE c.code = 'Werewolf' AND o.code = 'Swordman'
ORDER BY m.scope, m.include_scrim;

\echo ''
\echo '=== 7. 커리어별 상위 5 (CAREER 스코프가 GLOBAL 과 다른가) ==='
SELECT s.slot_key,
       c.code AS champion,
       o.code AS opponent,
       m.games,
       round(m.counter_effect * 100, 1) AS "이득%p"
FROM (
    SELECT m.*,
           row_number() OVER (PARTITION BY m.slot_id ORDER BY m.counter_effect DESC) AS rn
    FROM champion_matchup m
    WHERE m.scope = 'CAREER' AND m.include_scrim
      AND m.games >= (SELECT value FROM analysis_config WHERE key = 'min_sample')
) m
JOIN save_slot s ON s.slot_id = m.slot_id
JOIN champion c ON c.champion_id = m.champion_id
JOIN champion o ON o.champion_id = m.opponent_id
WHERE m.rn <= 5
ORDER BY s.slot_key, m.counter_effect DESC;
