package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.scrim.CounterPick;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>메타 상위를 잡는 픽</b>을 읽는다.
 *
 * <h2>여기서 계산하지 않는다</h2>
 *
 * 승률도 쌍 효과도 집계가 만들어 둔 값을 그대로 꺼낸다 (D46). 이 질의가 하는 일은
 * 두 표를 잇고 <b>문턱으로 자르는 것</b>뿐이다.
 *
 * <h2>부호를 뒤집으면 정반대를 추천한다</h2>
 *
 * {@code side='FOE'} 인 행의 {@code DEATH} 효과가 <b>양수</b>라는 것은
 * "그 상대가 맞은편에 있을 때 <b>이 챔피언(subject)이 더 죽는다</b>" 는 뜻이다.
 * 그러므로 <b>{@code other} 가 {@code subject} 를 잡는다.</b>
 *
 * <p>{@code DEALING} 으로 가르면 정확히 거꾸로 읽힌다 — 상대 쪽 딜 상승은
 * "내가 강하다" 가 아니라 "저쪽이 내 딜을 받아낸다" 는 뜻이기 때문이다 (D64 결정 3).
 * 그래서 이 질의는 {@code DEATH} 만 본다.
 */
@Repository
public class CounterCandidateDao {

    /**
     * 메타 상위와 그것을 잡는 픽.
     *
     * <p><b>상위는 날것 승률이 아니라 추정 승률로 고른다</b>는 규칙이 화면에 있는데,
     * 여기서는 그 순서를 다시 만들지 않는다 — 호출자가 티어 목록의 순서대로 챔피언
     * 번호를 넘겨 주면 이 질의는 <b>그 번호에 달린 카운터만</b> 꺼낸다.
     * 순서를 두 곳에서 만들면 화면과 추천이 다른 "1위" 를 말하게 된다.
     *
     * <p>{@code include_scrim} 을 안 건다. 쌍 효과 표는 공식전만으로 만들어지고
     * 언제나 {@code false} 로 저장된다 ({@code AggregationService}).
     */
    private static final String COUNTERS = """
            SELECT e.subject_champion_id                   AS target_id,
                   t.name_ko                               AS target_name,
                   o.champion_id, o.code, o.name_ko, o.category::text AS category,
                   e.effect, e.observations,
                   COALESCE(p.games, 0)                    AS games
            FROM champion_pair_effect e
            JOIN champion t ON t.champion_id = e.subject_champion_id
            JOIN champion o ON o.champion_id = e.other_champion_id
            LEFT JOIN champion_performance p
                   ON p.champion_id = o.champion_id
                  AND p.scope = 'CAREER' AND p.slot_id = e.slot_id
                  AND p.patch_id IS NULL AND p.include_scrim = false
            WHERE e.scope = 'CAREER' AND e.slot_id = ? AND e.patch_id IS NULL
              AND e.side = 'FOE' AND e.metric = 'DEATH'
              AND e.subject_champion_id = ANY (?)
              AND e.effect > ?
            ORDER BY e.subject_champion_id, e.effect DESC
            """;

    private final JdbcTemplate jdbc;

    public CounterCandidateDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param targets 메타 상위 챔피언 번호. 비어 있으면 빈 목록
     * @param signal  이 σ 를 넘어야 카운터라고 부른다. 화면의 묶음과 <b>같은 문턱</b>을
     *                써야 한다 — 다르면 "여기서는 카운터인데 챔피언 화면에는 없는" 줄이 생긴다
     */
    @Transactional(readOnly = true)
    public List<CounterPick> load(int slotId, List<Integer> targets, double signal) {
        if (targets == null || targets.isEmpty()) {
            return List.of();
        }
        Integer[] ids = targets.toArray(new Integer[0]);
        List<CounterPick> out = new ArrayList<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement(COUNTERS);
            ps.setInt(1, slotId);
            ps.setArray(2, con.createArrayOf("integer", ids));
            ps.setDouble(3, signal);
            return ps;
        }, rs -> {
            out.add(new CounterPick(
                    rs.getInt("champion_id"),
                    rs.getString("code"),
                    rs.getString("name_ko"),
                    rs.getString("category"),
                    rs.getInt("games"),
                    rs.getString("target_name"),
                    rs.getBigDecimal("effect").doubleValue(),
                    rs.getInt("observations")));
        });
        return out;
    }
}
