package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.AggScope;
import com.teamfighter.tfm.analysis.performance.PerformanceRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;

/**
 * 계산된 티어를 {@code champion_performance} 에 넣는다.
 *
 * <p>{@code CounterWriter} 와 같은 업서트 규칙이다 — 유일키에 NULL 이 들어가므로
 * {@code ON CONFLICT} 컬럼 목록이 {@code UNIQUE NULLS NOT DISTINCT} 제약과 정확히 같아야
 * 한다. 다르면 집계를 돌릴 때마다 같은 챔피언이 한 벌씩 쌓이고, 화면은 그중 아무거나
 * 하나를 보여준다.
 *
 * <p>경기력 z값({@code z_deal} 등)은 아직 쓰지 않는다 — 값을 만드는 회귀식이 미결이라
 * (D19) 지금 채우면 근거 없는 숫자가 화면에 오른다. NULL 로 둔다.
 */
@Repository
public class PerformanceWriter {

    private static final String UPSERT = """
            INSERT INTO champion_performance
                (scope, slot_id, patch_id, champion_id, include_scrim,
                 games, wins, bans, match_count, ban_match_count,
                 weighted_games, weighted_wins, ess,
                 adjusted_win_rate, tier_grade, agg_run_id)
            VALUES (CAST(? AS agg_scope), ?, ?, ?, ?,
                    ?, ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?)
            ON CONFLICT (scope, slot_id, patch_id, champion_id, include_scrim)
            DO UPDATE SET
                games             = EXCLUDED.games,
                wins              = EXCLUDED.wins,
                bans              = EXCLUDED.bans,
                match_count       = EXCLUDED.match_count,
                ban_match_count   = EXCLUDED.ban_match_count,
                weighted_games    = EXCLUDED.weighted_games,
                weighted_wins     = EXCLUDED.weighted_wins,
                ess               = EXCLUDED.ess,
                adjusted_win_rate = EXCLUDED.adjusted_win_rate,
                tier_grade        = EXCLUDED.tier_grade,
                agg_run_id        = EXCLUDED.agg_run_id
            """;

    private final JdbcTemplate jdbc;

    public PerformanceWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int write(
            AggScope scope,
            Integer slotId,
            Integer patchId,
            boolean includeScrim,
            long aggRunId,
            List<PerformanceRow> rows) {

        List<Object[]> batch = rows.stream()
                .map(row -> new Object[] {
                        scope.name(), slotId, patchId, row.championId(), includeScrim,
                        row.games(), row.wins(), row.bans(),
                        row.matchCount(), row.banMatchCount(),
                        row.weightedGames(), row.weightedWins(), row.ess(),
                        row.adjustedWinRate(), row.tierGrade(), aggRunId })
                .toList();

        int[] types = {
                Types.VARCHAR, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.BOOLEAN,
                Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER, Types.INTEGER,
                Types.NUMERIC, Types.NUMERIC, Types.NUMERIC,
                Types.NUMERIC, Types.VARCHAR, Types.BIGINT };

        return jdbc.batchUpdate(UPSERT, batch, types).length;
    }
}
