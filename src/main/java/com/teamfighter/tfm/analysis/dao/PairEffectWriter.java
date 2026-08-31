package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.AggScope;
import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Effect;
import com.teamfighter.tfm.analysis.pair.PerfMetric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/**
 * 쌍 효과를 저장한다. <b>지표 하나 · 커리어 하나가 한 덩어리</b>다.
 *
 * <h2>지우고 다시 넣는다</h2>
 *
 * 업서트가 아니다. 모형을 다시 적합하면 <b>쌍의 목록 자체가 바뀐다</b> — 관측이 20회를
 * 넘긴 쌍이 새로 들어오고, 반대로 릿지가 세지면 빠지는 쌍도 있다. 업서트로 덮으면
 * 지난번에만 있던 쌍이 <b>영원히 남는다</b>. 그 행은 지금 데이터로는 계산된 적이 없는데
 * 화면에서는 나머지와 똑같이 보인다.
 *
 * <p>같은 이유로 {@code champion_matchup}(승률 기반)과 다른 방식이다. 저쪽은 쌍 목록이
 * 고정이라 업서트가 맞는다.
 */
@Repository
public class PairEffectWriter {

    private static final String DELETE = """
            DELETE FROM champion_pair_effect
            WHERE scope = CAST(? AS agg_scope) AND slot_id = ? AND patch_id IS NULL
              AND include_scrim = ? AND metric = CAST(? AS perf_metric)
            """;

    private static final String INSERT = """
            INSERT INTO champion_pair_effect
                (scope, slot_id, patch_id, include_scrim, side,
                 subject_champion_id, other_champion_id, metric, effect, observations)
            VALUES (CAST(? AS agg_scope), ?, NULL, ?, CAST(? AS pair_side),
                    ?, ?, CAST(? AS perf_metric), ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public PairEffectWriter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 그 커리어·그 지표의 효과를 통째로 갈아 끼운다.
     *
     * @param includeScrim 스크림을 섞었는가. 지금은 언제나 {@code false} 다 —
     *                     D63~D65 의 측정이 공식전으로 이뤄졌기 때문이다
     */
    @Transactional
    public void replace(int slotId, PerfMetric metric, boolean includeScrim,
                        List<Effect> effects) {
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(effects, "effects");

        jdbc.update(DELETE, AggScope.CAREER.name(), slotId, includeScrim, metric.name());

        // 배치로 넣는다. 커리어 하나에 쌍이 수천 개라 한 줄씩 왕복하면 그 자체가 느리다.
        jdbc.batchUpdate(INSERT, effects, effects.size(), (ps, effect) -> {
            ps.setString(1, AggScope.CAREER.name());
            ps.setInt(2, slotId);
            ps.setBoolean(3, includeScrim);
            ps.setString(4, effect.side().name());
            ps.setInt(5, effect.subjectChampionId());
            ps.setInt(6, effect.otherChampionId());
            ps.setString(7, metric.name());
            ps.setBigDecimal(8, java.math.BigDecimal.valueOf(effect.effect())
                    .setScale(3, java.math.RoundingMode.HALF_UP));
            ps.setInt(9, effect.observations());
        });
    }
}
