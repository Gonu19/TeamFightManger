package com.teamfighter.tfm.web.dao;

import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Side;
import com.teamfighter.tfm.analysis.pair.PerfMetric;
import com.teamfighter.tfm.web.view.PairRow;
import com.teamfighter.tfm.web.view.TierRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 통계 화면이 읽는 것. <b>여기서 계산하지 않는다.</b>
 *
 * <p>티어의 추정 승률도 쌍 효과도 집계가 이미 만들어 둔 값이다. 화면이 다시 계산하면
 * 집계와 화면이 다른 답을 할 수 있고, 그때 틀린 쪽은 <b>안 보인다</b> — 둘 다 그럴듯한
 * 숫자이기 때문이다. 기사 화면이 {@code fact_status} 를 다시 세지 않는 것과 같은 규칙이다.
 *
 * <p>정렬만 여기서 한다. 그건 계산이 아니라 <b>보여주는 순서</b>이고, SQL 로 내리면
 * 화면마다 다른 순서를 쓰게 된다.
 */
@Repository
public class StatsDao {

    /**
     * 티어 목록.
     *
     * <p><b>추정 승률로 정렬한다</b>(D50). {@code tier_grade} 는 아직 NULL 이다 —
     * 컷라인을 못 정했기 때문이고(D51 · OPEN.md), 그 사실을 화면이 "미정" 으로 말한다.
     *
     * <p>출전이 0인 챔피언도 가져온다. 목록에서 빠지면 "이 챔피언은 어디 갔지" 가 되고,
     * 그 답이 화면 어디에도 없다.
     */
    private static final String TIER = """
            SELECT c.champion_id, c.code, c.name_ko, c.category::text AS category,
                   COALESCE(p.games, 0) AS games, COALESCE(p.wins, 0) AS wins,
                   p.win_rate, p.adjusted_win_rate, p.pick_rate, p.ban_rate, p.tier_grade
            FROM champion c
            LEFT JOIN champion_performance p
                   ON p.champion_id = c.champion_id
                  AND p.scope = 'CAREER' AND p.slot_id = ? AND p.patch_id IS NULL
                  AND p.include_scrim = ?
            WHERE c.is_playable
            ORDER BY p.adjusted_win_rate DESC NULLS LAST, c.code
            """;

    private static final String CHAMPION = """
            SELECT champion_id, code, name_ko, category::text AS category
            FROM champion WHERE code = ?
            """;

    /**
     * 그 챔피언의 쌍 효과 전부. <b>지표를 한꺼번에</b> 가져온다 — 화면이 벡터를
     * 그리기 때문이다(D65 결정 1). 지표마다 조회하면 여섯 번 왕복한다.
     */
    private static final String PAIRS = """
            SELECT o.code, o.name_ko, o.category::text AS category,
                   e.metric::text AS metric, e.effect, e.observations
            FROM champion_pair_effect e
            JOIN champion o ON o.champion_id = e.other_champion_id
            WHERE e.scope = 'CAREER' AND e.slot_id = ? AND e.patch_id IS NULL
              AND e.include_scrim = false
              AND e.subject_champion_id = ? AND e.side = CAST(? AS pair_side)
            ORDER BY o.code
            """;

    /** 쌍 효과가 있는 커리어. 화면이 기본 슬롯을 고를 때 쓴다. */
    private static final String SLOTS = """
            SELECT DISTINCT slot_id FROM champion_performance
            WHERE scope = 'CAREER' AND slot_id IS NOT NULL ORDER BY slot_id
            """;

    private final JdbcTemplate jdbc;

    public StatsDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<Integer> slots() {
        return jdbc.queryForList(SLOTS, Integer.class);
    }

    @Transactional(readOnly = true)
    public List<TierRow> tier(int slotId, boolean includeScrim) {
        return jdbc.query(TIER, (rs, rowNum) -> new TierRow(
                rs.getInt("champion_id"),
                rs.getString("code"),
                rs.getString("name_ko"),
                rs.getString("category"),
                rs.getInt("games"),
                rs.getInt("wins"),
                rs.getBigDecimal("win_rate"),
                rs.getBigDecimal("adjusted_win_rate"),
                rs.getBigDecimal("pick_rate"),
                rs.getBigDecimal("ban_rate"),
                rs.getString("tier_grade")), slotId, includeScrim);
    }

    @Transactional(readOnly = true)
    public Optional<TierRow> champion(String code) {
        List<TierRow> found = jdbc.query(CHAMPION, (rs, rowNum) -> new TierRow(
                rs.getInt("champion_id"), rs.getString("code"), rs.getString("name_ko"),
                rs.getString("category"), 0, 0, null, null, null, null, null), code);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 그 챔피언의 동료 효과 또는 상대 효과.
     *
     * <p><b>효과 크기 순</b>으로 돌려준다 — 딜과 죽음 중 큰 쪽이 기준이다
     * ({@link PairRow#magnitude()} 가 왜 그런지 적어 뒀다).
     */
    @Transactional(readOnly = true)
    public List<PairRow> pairs(int slotId, int championId, Side side) {
        Map<String, PairRow.Builder> builders = new LinkedHashMap<>();
        Map<String, String[]> names = new LinkedHashMap<>();
        Map<String, Integer> observations = new LinkedHashMap<>();

        jdbc.query(PAIRS, rs -> {
            String code = rs.getString("code");
            builders.computeIfAbsent(code, key -> new PairRow.Builder())
                    .put(PerfMetric.valueOf(rs.getString("metric")), rs.getBigDecimal("effect"));
            names.putIfAbsent(code, new String[] {
                    rs.getString("name_ko"), rs.getString("category")});
            observations.putIfAbsent(code, rs.getInt("observations"));
        }, slotId, championId, side.name());

        List<PairRow> rows = new ArrayList<>();
        builders.forEach((code, builder) -> rows.add(builder.build(
                side, code, names.get(code)[0], names.get(code)[1], observations.get(code))));

        rows.sort(Comparator.comparingDouble(PairRow::magnitude).reversed());
        return List.copyOf(rows);
    }
}
