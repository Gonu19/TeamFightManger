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
 * <p>정렬과 순위만 여기서 한다. 그건 계산이 아니라 <b>보여주는 순서</b>이고, SQL 로
 * 내리면 화면마다 다른 순서를 쓰게 된다.
 */
@Repository
public class StatsDao {

    /**
     * 티어 목록.
     *
     * <p><b>추정 승률로 정렬한다</b>(D50). {@code tier_grade} 는 아직 NULL 이고
     * 앞으로도 한동안 그렇다 — 컷라인을 못 정했기 때문이다(D51 · OPEN.md). 화면은
     * 그 칸에 등급 대신 <b>순위</b>를 그린다.
     *
     * <p>출전이 0인 챔피언도 가져온다. 목록에서 빠지면 "이 챔피언은 어디 갔지" 가 되고,
     * 그 답이 화면 어디에도 없다. 대신 순위를 안 받는다.
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

    /** 전체 목록. 순위는 40여 명 전부를 놓고 매긴 것이다. */
    @Transactional(readOnly = true)
    public List<TierRow> tier(int slotId, boolean includeScrim) {
        return tier(slotId, includeScrim, null);
    }

    /**
     * 역할군 하나만. <b>순위도 그 역할군 안에서</b> 다시 매긴다.
     *
     * <p>거른 목록에 전체 순위를 그대로 얹으면 "1위, 4위, 9위…" 가 되는데, 그건 순위가
     * 아니라 <b>구멍 난 목록</b>으로 읽힌다. 전사 탭에서 묻는 것은 "전사 중 몇 등인가"
     * 이고, 화면이 그 말을 그대로 쓴다 — 그래야 두 순위가 헷갈리지 않는다.
     *
     * @param category {@code null} 이면 거르지 않는다 (전체 탭)
     */
    @Transactional(readOnly = true)
    public List<TierRow> tier(int slotId, boolean includeScrim, String category) {
        List<TierRow> rows = jdbc.query(TIER, (rs, rowNum) -> new TierRow(
                TierRow.UNRANKED,
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

        List<TierRow> shown = category == null ? rows
                : rows.stream().filter(row -> category.equals(row.category())).toList();

        // 순위는 화면의 것이라 뷰 모델이 매긴다. 여기서 한 번 더 적으면 두 규칙이 생긴다.
        return TierRow.rank(shown);
    }

    /**
     * 챔피언 하나 — <b>티어 목록과 같은 줄</b>을 돌려준다.
     *
     * <p>이름만 따로 조회하지 않는 이유는 순위 때문이다. 순위는 목록 전체를 봐야 나오는
     * 값이라 여기서 따로 계산하면 <b>티어 화면과 다른 순위</b>가 나올 수 있고, 그때
     * 어느 쪽이 맞는지 화면만 봐서는 알 수 없다. 순위의 정의는 한 곳에만 둔다.
     *
     * <p>순위는 <b>그 챔피언의 역할군 안에서</b>다. 티어 화면의 역할군 탭이 보여주는
     * 것과 같은 순위여야 두 화면이 같은 말을 한다.
     *
     * <p>커리어를 안 고르면(슬롯이 아직 없다) 성적 없이 이름만 돌려준다. 그때 화면은
     * 승률 칸에 "—" 를 그린다.
     */
    @Transactional(readOnly = true)
    public Optional<TierRow> champion(String code, Integer slotId) {
        if (slotId != null) {
            Optional<TierRow> overall = tier(slotId, false).stream()
                    .filter(row -> row.code().equals(code))
                    .findFirst();
            if (overall.isPresent()) {
                // 역할군 안에서 다시 매긴 순위로 바꿔 준다. 목록을 한 번 더 훑지만
                // 40여 줄이고, 순위 규칙이 한 곳에만 있는 값이 그 비용보다 크다.
                String category = overall.get().category();
                return tier(slotId, false, category).stream()
                        .filter(row -> row.code().equals(code))
                        .findFirst()
                        .or(() -> overall);
            }
        }
        List<TierRow> found = jdbc.query(CHAMPION, (rs, rowNum) -> new TierRow(
                TierRow.UNRANKED, rs.getInt("champion_id"), rs.getString("code"),
                rs.getString("name_ko"), rs.getString("category"),
                0, 0, null, null, null, null, null), code);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * 그 챔피언의 동료 효과 또는 상대 효과.
     *
     * <p><b>효과 크기 순</b>으로 돌려준다 — 딜과 죽음 중 큰 쪽이 기준이다
     * ({@link PairRow#magnitude()} 가 왜 그런지 적어 뒀다). 화면이 이것을 세 묶음으로
     * 갈라 담을 때는 묶음마다 다시 줄을 세운다 ({@link PairRow#rankValue()}).
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
