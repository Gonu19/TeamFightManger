-- 특정 두 챔피언의 대면 표본을 뜯어본다 (decision.md D49 의 미확인).
--
-- D14 는 Werewolf vs Swordman 을 25경기 84.0% 로 인용했는데, 우리 집계는 13경기 69.2% 다.
-- 승률이 아니라 표본 수가 절반이다. 전체 규모는 맞으므로(기준 통과 1,174쌍 vs 문서 1,204쌍)
-- 이 쌍만 특이한 것이다.
--
-- 실행:
--   chcp 65001
--   & 'C:\Program Files\PostgreSQL\16\bin\psql.exe' -U postgres -d tfm -f tools/pair_audit.sql
--
-- 다른 쌍을 보려면 아래 \set 두 줄만 바꾼다.
-- 이 파일은 읽기 전용이다. 아무것도 쓰지 않는다.

\set champ_a 'Werewolf'
\set champ_b 'Swordman'

\echo '=== A. 두 챔피언이 같이 나온 경기를 진영별로 가른다 ==='
\echo '    적팀 수가 우리 집계의 games 와 같아야 한다.'
\echo '    "둘 다 등장" 이 문서의 25 에 가깝다면, 옛 측정은 진영을 안 가른 것이다.'
WITH pair AS (
    SELECT m.match_id, m.match_type, m.team_size, m.slot_id,
           a.side AS side_a, b.side AS side_b
    FROM match_record m
    JOIN match_participant a ON a.match_id = m.match_id
    JOIN match_participant b ON b.match_id = m.match_id
    JOIN champion ca ON ca.champion_id = a.champion_id AND ca.code = :'champ_a'
    JOIN champion cb ON cb.champion_id = b.champion_id AND cb.code = :'champ_b'
)
SELECT count(*)                                    AS "둘 다 등장",
       count(*) FILTER (WHERE side_a <> side_b)    AS "적팀 (카운터)",
       count(*) FILTER (WHERE side_a =  side_b)    AS "같은 팀 (시너지)"
FROM pair;

\echo ''
\echo '=== B. 경기 종류·인원별로 나눠 본다 — D35 제외가 원인인지 ==='
\echo '    team_size 는 CHECK 로 4 고정이라 4 만 나와야 한다. 적재 단계에서 걸러진'
\echo '    비4v4 경기는 여기 아예 없다 — 그게 25 와 13 의 차이일 수 있다.'
WITH pair AS (
    SELECT m.match_id, m.match_type, m.team_size,
           a.side AS side_a, b.side AS side_b
    FROM match_record m
    JOIN match_participant a ON a.match_id = m.match_id
    JOIN match_participant b ON b.match_id = m.match_id
    JOIN champion ca ON ca.champion_id = a.champion_id AND ca.code = :'champ_a'
    JOIN champion cb ON cb.champion_id = b.champion_id AND cb.code = :'champ_b'
)
SELECT match_type, team_size,
       count(*) FILTER (WHERE side_a <> side_b) AS "적팀",
       count(*) FILTER (WHERE side_a =  side_b) AS "같은 팀"
FROM pair
GROUP BY match_type, team_size
ORDER BY match_type, team_size;

\echo ''
\echo '=== C. 커리어별로 나눠 본다 — 특정 커리어에 몰려 있는지 ==='
WITH pair AS (
    SELECT m.slot_id, a.side AS side_a, b.side AS side_b
    FROM match_record m
    JOIN match_participant a ON a.match_id = m.match_id
    JOIN match_participant b ON b.match_id = m.match_id
    JOIN champion ca ON ca.champion_id = a.champion_id AND ca.code = :'champ_a'
    JOIN champion cb ON cb.champion_id = b.champion_id AND cb.code = :'champ_b'
)
SELECT s.slot_key,
       count(*) FILTER (WHERE side_a <> side_b) AS "적팀",
       count(*) FILTER (WHERE side_a =  side_b) AS "같은 팀"
FROM pair
JOIN save_slot s USING (slot_id)
GROUP BY s.slot_key
ORDER BY s.slot_key;

\echo ''
\echo '=== D. 두 챔피언이 각각 몇 경기에 나왔나 — 픽률 자체가 낮은 건지 ==='
SELECT c.code,
       count(*) AS "출전 경기",
       count(*) FILTER (WHERE m.match_type = 'OFFICIAL') AS "공식",
       count(*) FILTER (WHERE m.match_type = 'SCRIM')    AS "스크림"
FROM match_participant p
JOIN match_record m ON m.match_id = p.match_id
JOIN champion c ON c.champion_id = p.champion_id
WHERE c.code IN (:'champ_a', :'champ_b')
GROUP BY c.code
ORDER BY c.code;

\echo ''
\echo '=== E. 진영 배정이 건전한지 — 전체 차원의 검산 (D20) ==='
\echo '    한 경기의 참가자는 4+4 여야 한다. 진영이 뒤바뀌었다면 여기서는 안 보이지만,'
\echo '    한쪽으로 쏠린 경기가 있으면 매칭이 깨진 것이다.'
SELECT blue_count, red_count, count(*) AS matches
FROM (
    SELECT p.match_id,
           count(*) FILTER (WHERE p.side = 'BLUE') AS blue_count,
           count(*) FILTER (WHERE p.side = 'RED')  AS red_count
    FROM match_participant p
    GROUP BY p.match_id
) t
GROUP BY blue_count, red_count
ORDER BY matches DESC;

\echo ''
\echo '=== F. 승리 진영 분포 — 한쪽으로 심하게 쏠리면 배정을 의심한다 ==='
SELECT match_type, winner_side, count(*) AS matches
FROM match_record
GROUP BY match_type, winner_side
ORDER BY match_type, winner_side;
