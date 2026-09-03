package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.scrim.PairCoverage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 커리어 하나에서 <b>어떤 듀오를 이미 봤나</b>.
 *
 * <h2>왜 필요한가 — 티어는 "아는 것" 만 말한다</h2>
 *
 * 티어와 쌍 효과 화면은 관측이 충분한 것만 보여준다. 그래서 화면을 아무리 봐도
 * <b>안 해 본 조합은 영영 안 보인다.</b> 실측(슬롯 1): 듀오 780쌍 중 공식전으로 아는
 * 것이 107쌍(14%)이고, 305쌍(39%)은 공식전에도 스크림에도 <b>한 번도</b> 같이 안 나왔다.
 *
 * <p>그 305쌍은 "나쁜 조합" 이 아니라 <b>모르는 조합</b>이다 (D62: 없는 게 아니라 안 보이는
 * 것이다). 스크림은 그것을 알아보라고 있는 자리다.
 *
 * <h2>두 축을 따로 센다</h2>
 *
 * <ul>
 *   <li><b>공식전</b> — {@code champion_pair_effect} 에 행이 있나. 그 표는 관측
 *       {@code MIN_OBSERVATIONS} 이상만 담으므로, 행이 있다는 것은 곧 "말할 수 있다" 다.
 *       그리고 이 표는 <b>공식전만</b>으로 만들어진다 ({@code PairObservationDao} 가
 *       {@code match_type = 'OFFICIAL'} 로 거른다)</li>
 *   <li><b>스크림</b> — 원본 행을 직접 센다. 스크림은 집계에 안 들어가므로 셀 곳이
 *       여기밖에 없다. 한 번이라도 같이 나왔으면 "해 봤다" 다</li>
 * </ul>
 *
 * <p><b>같은 편만 본다.</b> 스크림에서 시험하는 것은 내 덱이지 상대 조합이 아니다.
 */
@Repository
public class PairCoverageDao {

    /**
     * 공식전으로 아는 듀오.
     *
     * <p>{@code side='ALLY'} 만 본다. 지표가 여섯이라 같은 쌍이 여러 행으로 있으므로
     * {@code DISTINCT} 로 접고, 방향도 접는다 — 쌍 효과는 방향이 있지만(A→B 와 B→A 가
     * 다른 행) "같이 뽑아 봤나" 에는 방향이 없다.
     */
    private static final String OFFICIAL = """
            SELECT DISTINCT
                   LEAST(subject_champion_id, other_champion_id)    AS lo,
                   GREATEST(subject_champion_id, other_champion_id) AS hi
            FROM champion_pair_effect
            WHERE scope = 'CAREER' AND slot_id = ? AND patch_id IS NULL AND side = 'ALLY'
            """;

    /**
     * 스크림에서 같은 편으로 같이 나온 횟수.
     *
     * <p>자기 조인이라 {@code a.champion_id < b.champion_id} 로 한쪽만 만든다 —
     * 안 그러면 같은 쌍이 두 번 세어진다.
     */
    private static final String SCRIM = """
            SELECT a.champion_id AS lo, b.champion_id AS hi, count(*) AS times
            FROM match_participant a
            JOIN match_participant b
              ON b.match_id = a.match_id AND b.side = a.side
             AND b.champion_id > a.champion_id
            JOIN match_record m ON m.match_id = a.match_id
            WHERE m.slot_id = ? AND m.match_type = 'SCRIM'
            GROUP BY a.champion_id, b.champion_id
            """;

    private final JdbcTemplate jdbc;

    public PairCoverageDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PairCoverage load(int slotId) {
        List<long[]> official = new ArrayList<>();
        jdbc.query(OFFICIAL, rs -> {
            official.add(new long[]{rs.getInt("lo"), rs.getInt("hi"), 0});
        }, slotId);

        List<long[]> scrim = new ArrayList<>();
        jdbc.query(SCRIM, rs -> {
            scrim.add(new long[]{rs.getInt("lo"), rs.getInt("hi"), rs.getInt("times")});
        }, slotId);

        return PairCoverage.of(official, scrim);
    }
}
