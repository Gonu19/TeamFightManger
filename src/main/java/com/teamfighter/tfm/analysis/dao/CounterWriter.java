package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.AggScope;
import com.teamfighter.tfm.analysis.counter.CounterRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

/**
 * 계산된 카운터를 {@code champion_matchup} 에 넣는다.
 *
 * <p><b>업서트다.</b> 집계는 경기가 쌓일 때마다 다시 돌고, 그때마다 같은 쌍의 행을 덮어써야
 * 한다. 지우고 다시 넣지 않는 이유는 그 사이에 화면이 조회하면 표가 비어 보이기 때문이다.
 *
 * <p><b>유일키에 NULL 이 들어간다.</b> {@code GLOBAL} 행은 {@code slot_id} 와
 * {@code patch_id} 가 NULL 이고, 1단 축소 결과(전체 누적)는 {@code patch_id} 만 NULL 이다.
 * 보통의 UNIQUE 는 NULL 을 서로 다른 값으로 보기 때문에 같은 쌍이 무한히 쌓인다 —
 * 스키마가 {@code UNIQUE NULLS NOT DISTINCT} 를 쓰는 이유이고, {@code ON CONFLICT} 의
 * 컬럼 목록도 그 제약과 정확히 같아야 추론이 된다.
 */
@Repository
public class CounterWriter {

    private static final String UPSERT = """
            INSERT INTO champion_matchup
                (scope, slot_id, patch_id, champion_id, opponent_id, include_scrim,
                 games, wins, weighted_games, weighted_wins, ess,
                 expected_win_rate, adjusted_win_rate, counter_effect, agg_run_id)
            VALUES (CAST(? AS agg_scope), ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?, ?)
            ON CONFLICT (scope, slot_id, patch_id, champion_id, opponent_id, include_scrim)
            DO UPDATE SET
                games             = EXCLUDED.games,
                wins              = EXCLUDED.wins,
                weighted_games    = EXCLUDED.weighted_games,
                weighted_wins     = EXCLUDED.weighted_wins,
                ess               = EXCLUDED.ess,
                expected_win_rate = EXCLUDED.expected_win_rate,
                adjusted_win_rate = EXCLUDED.adjusted_win_rate,
                counter_effect    = EXCLUDED.counter_effect,
                agg_run_id        = EXCLUDED.agg_run_id
            """;

    private final JdbcTemplate jdbc;

    public CounterWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param slotId  {@code CAREER} 면 슬롯, {@code GLOBAL} 이면 {@code null}
     * @param patchId 전체 누적(1단)이면 {@code null}, 특정 패치 추정(2단)이면 그 패치
     */
    public int write(
            AggScope scope,
            Integer slotId,
            Integer patchId,
            boolean includeScrim,
            long aggRunId,
            List<CounterRow> rows) {

        List<Object[]> batch = rows.stream()
                .map(row -> new Object[] {
                        scope.name(), slotId, patchId, row.championId(), row.opponentId(),
                        includeScrim,
                        row.games(), row.wins(), row.weightedGames(), row.weightedWins(),
                        row.ess(), row.expectedWinRate(), row.adjustedWinRate(),
                        row.counterEffect(), aggRunId })
                .toList();

        int[] types = {
                Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER,
                Types.BOOLEAN,
                Types.INTEGER, Types.INTEGER, Types.NUMERIC, Types.NUMERIC, Types.NUMERIC,
                Types.NUMERIC, Types.NUMERIC, Types.NUMERIC, Types.BIGINT };

        int[] written = jdbc.batchUpdate(UPSERT, batch, types);
        return written.length;
    }
}
