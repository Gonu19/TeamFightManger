-- 챔피언 티어 집계 결과를 눈으로 확인한다 (decision.md D21 · D50).
--
-- 집계를 한 번 돌린 뒤에 실행한다:
--   gradlew.bat bootRun --args="--tfm.aggregate-on-start=true"
--
-- 실행:
--   chcp 65001
--   & 'C:\Program Files\PostgreSQL\16\bin\psql.exe' -U postgres -d tfm -f tools/tier_report.sql
--
-- 질의 3·4 가 이 리포트의 목적이다. 티어 등급 컷라인이 아직 미결인데, 근거 없이 정하지
-- 않으려면 분포를 먼저 봐야 한다 (decision.md "아직 안 정한 것").
--
-- 이 파일은 읽기 전용이다. 아무것도 쓰지 않는다.

\echo '=== 1. 티어 상위 15 (GLOBAL · 스크림 포함) ==='
\echo '    정렬은 추정 승률로 한다. 원본 승률과 경기 수를 함께 띄운다 (D10).'
SELECT c.code AS champion,
       p.games,
       round(p.win_rate * 100, 1)          AS "실제%",
       round(p.adjusted_win_rate * 100, 1) AS "추정%",
       round(p.pick_rate * 100, 1)         AS "픽률%",
       round(p.ban_rate * 100, 1)          AS "밴률%",
       round(p.ess, 1)                     AS ess
FROM champion_performance p
JOIN champion c ON c.champion_id = p.champion_id
WHERE p.scope = 'GLOBAL' AND p.include_scrim
ORDER BY p.adjusted_win_rate DESC
LIMIT 15;

\echo ''
\echo '=== 2. 티어 하위 10 ==='
SELECT c.code AS champion,
       p.games,
       round(p.win_rate * 100, 1)          AS "실제%",
       round(p.adjusted_win_rate * 100, 1) AS "추정%",
       round(p.pick_rate * 100, 1)         AS "픽률%"
FROM champion_performance p
JOIN champion c ON c.champion_id = p.champion_id
WHERE p.scope = 'GLOBAL' AND p.include_scrim
ORDER BY p.adjusted_win_rate ASC
LIMIT 10;

\echo ''
\echo '=== 3. 추정 승률 분포 — 티어 컷라인을 정하기 위한 재료 ==='
\echo '    D14 주변의 측정은 기저 승률 표준편차를 3.6%p 로 봤다. 그게 맞으면 고정 컷'
\echo '    (55% 이상 S)은 대부분을 한 등급에 몰아넣는다.'
SELECT count(*)                                                       AS "챔피언",
       round((avg(adjusted_win_rate) * 100)::numeric, 2)              AS "평균%",
       round((stddev_samp(adjusted_win_rate) * 100)::numeric, 2)      AS "표준편차%p",
       round((min(adjusted_win_rate) * 100)::numeric, 1)              AS "최소%",
       round((max(adjusted_win_rate) * 100)::numeric, 1)              AS "최대%",
       count(*) FILTER (WHERE adjusted_win_rate >= 0.55)              AS "55% 이상",
       count(*) FILTER (WHERE adjusted_win_rate < 0.45)               AS "45% 미만"
FROM champion_performance
WHERE scope = 'GLOBAL' AND include_scrim;

\echo ''
\echo '=== 4. 백분위 — 등급을 백분위로 자르면 몇 %p 간격이 되나 ==='
SELECT round((percentile_cont(0.05) WITHIN GROUP (ORDER BY adjusted_win_rate) * 100)::numeric, 1) AS "5%",
       round((percentile_cont(0.25) WITHIN GROUP (ORDER BY adjusted_win_rate) * 100)::numeric, 1) AS "25%",
       round((percentile_cont(0.50) WITHIN GROUP (ORDER BY adjusted_win_rate) * 100)::numeric, 1) AS "50%",
       round((percentile_cont(0.75) WITHIN GROUP (ORDER BY adjusted_win_rate) * 100)::numeric, 1) AS "75%",
       round((percentile_cont(0.95) WITHIN GROUP (ORDER BY adjusted_win_rate) * 100)::numeric, 1) AS "95%"
FROM champion_performance
WHERE scope = 'GLOBAL' AND include_scrim;

\echo ''
\echo '=== 5. 밴률 상위 10 — 분모가 공식전 수인지 확인한다 (D50) ==='
\echo '    match_count 로 나눴다면 스크림이 분모에 섞여 밴률이 실제의 63% 로 나온다.'
SELECT c.code AS champion,
       p.bans,
       p.ban_match_count                   AS "공식전 수",
       p.match_count                       AS "전체 경기",
       round(p.ban_rate * 100, 1)          AS "밴률%",
       round(p.pick_rate * 100, 1)         AS "픽률%"
FROM champion_performance p
JOIN champion c ON c.champion_id = p.champion_id
WHERE p.scope = 'GLOBAL' AND p.include_scrim
ORDER BY p.bans DESC
LIMIT 10;

\echo ''
\echo '=== 6. 아무도 안 뽑는데 밴당하는 챔피언 — 티어표가 보여줘야 하는 것 ==='
SELECT c.code AS champion, p.games, p.bans,
       round(p.ban_rate * 100, 1) AS "밴률%"
FROM champion_performance p
JOIN champion c ON c.champion_id = p.champion_id
WHERE p.scope = 'GLOBAL' AND p.include_scrim
  AND p.bans > 0
ORDER BY (p.bans::numeric / NULLIF(p.games, 0)) DESC NULLS FIRST
LIMIT 10;

\echo ''
\echo '=== 7. 검산 — 픽률 합계는 8 이어야 한다 (한 경기에 챔피언 8명) ==='
\echo '    승률 합계는 정확히 50% 여야 한다. 티어 축소의 목표값이 0.5 인 근거다.'
SELECT scope, include_scrim,
       round(sum(pick_rate)::numeric, 3)                              AS "픽률 합",
       sum(games)                                                     AS "출전 합",
       sum(wins)                                                      AS "승리 합",
       round((sum(wins)::numeric / NULLIF(sum(games), 0) * 100), 3)   AS "전체 승률%"
FROM champion_performance
WHERE scope = 'GLOBAL'
GROUP BY scope, include_scrim
ORDER BY include_scrim;

\echo ''
\echo '=== 8. 스크림을 빼면 순위가 바뀌나 — 두 벌을 계산해 두는 이유 (D47) ==='
SELECT c.code AS champion,
       round(t.adjusted_win_rate * 100, 1) AS "포함%",
       round(f.adjusted_win_rate * 100, 1) AS "공식만%",
       round((t.adjusted_win_rate - f.adjusted_win_rate) * 100, 1) AS "차이%p"
FROM champion_performance t
JOIN champion_performance f
  ON f.champion_id = t.champion_id AND f.scope = 'GLOBAL' AND NOT f.include_scrim
JOIN champion c ON c.champion_id = t.champion_id
WHERE t.scope = 'GLOBAL' AND t.include_scrim
ORDER BY abs(t.adjusted_win_rate - f.adjusted_win_rate) DESC
LIMIT 10;

\echo ''
\echo '=== 9. 관측된 흩어짐 중 얼마가 진짜인가 — 컷라인을 정하기 전에 (D9 와 같은 질문) ==='
\echo '    사분위 간격이 3.6%p 인데 챔피언당 평균 222경기면 표본 오차만으로 ±3.4%p 가'
\echo '    흩어진다. 관측 분산에서 표본 노이즈를 빼야 진짜 강도 차이가 남는다.'
\echo '    진짜 분산이 작으면 백분위 등급은 노이즈에 등급을 매기는 것이 된다.'
WITH observed AS (
    SELECT champion_id,
           games,
           wins::numeric / games AS raw
    FROM champion_performance
    WHERE scope = 'GLOBAL' AND include_scrim AND games > 0
), decomposed AS (
    SELECT count(*)                                  AS champions,
           avg(games)                                AS avg_games,
           var_samp(raw)                             AS observed_var,
           avg(raw * (1 - raw) / games)              AS noise_var,
           avg(raw * (1 - raw))                      AS avg_pq
    FROM observed
)
SELECT champions                                                       AS "챔피언",
       round(avg_games, 0)                                             AS "평균 경기",
       round((sqrt(observed_var) * 100)::numeric, 2)                   AS "관측 표준편차%p",
       round((sqrt(noise_var) * 100)::numeric, 2)                      AS "표본 노이즈%p",
       CASE WHEN observed_var > noise_var
            THEN round((sqrt(observed_var - noise_var) * 100)::numeric, 2)
            ELSE NULL END                                              AS "진짜 강도차%p",
       CASE WHEN observed_var > noise_var
            THEN round((avg_pq / (observed_var - noise_var))::numeric, 0)
            ELSE NULL END                                              AS "통계적 최적 k0",
       round((noise_var / observed_var * 100)::numeric, 1)             AS "노이즈 비중%"
FROM decomposed;

\echo ''
\echo '=== 10. 현재 k0(24)로 충분히 눌렸나 — 최적 k0 와 비교한다 ==='
\echo '    질의 9 의 "통계적 최적 k0" 가 24 보다 훨씬 크면 지금 추정은 덜 눌린 것이고,'
\echo '    티어표의 흩어짐이 실제보다 과장돼 있다는 뜻이다 (D10 이 카운터에서 k=74 를'
\echo '    역산하고도 실무적 절충으로 24 를 쓴 것과 같은 자리).'
SELECT round(value, 0) AS "현재 k0", 'analysis_config' AS 출처
FROM analysis_config WHERE key = 'prior_strength_k0';
