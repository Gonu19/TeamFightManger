package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.ReferencePoint;
import com.teamfighter.tfm.analysis.decay.DecayWeight;
import com.teamfighter.tfm.analysis.pair.PairObservation;
import com.teamfighter.tfm.analysis.pair.PerfMetric;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 쌍 효과 모형이 먹을 관측을 읽는다. <b>한 행 = 경기에 나온 챔피언 하나.</b>
 *
 * <h2>새로 파싱할 것이 없다</h2>
 *
 * {@code match_participant} 이 이미 딜·탱·힐·킬·데스·어시를 갖고 있다. D63 이 관측
 * 단위를 승패에서 출력으로 바꿀 때 <b>데이터를 더 모을 필요가 없었던</b> 이유가 이것이다 —
 * 통로는 처음부터 있었고 쓰지 않고 있었을 뿐이다.
 *
 * <h2>경기 단위로 통째로 읽는다</h2>
 *
 * 한 관측이 자기 동료 셋과 상대 넷을 알아야 하므로 경기를 조각내 읽을 수 없다.
 * 그래서 참가자를 경기 순서로 훑으며 여덟 행이 모일 때마다 한 경기를 만든다.
 * 실측 커리어 하나가 1,111경기 × 8 = 8,888행이라 메모리에 다 올려도 된다.
 *
 * <h2>공식전만 읽는다</h2>
 *
 * D63~D65 의 측정이 공식전 805경기로 이뤄졌고, 그 t 값과 상위 쌍 표가 이 앱 화면의
 * 근거다. 스크림을 섞으면 그 표와 다른 모집단이 된다 — 스크림은 팀 식별이 없고(D54)
 * 팀 강도 항이 안 켜지므로 모형의 모양 자체가 다르다.
 */
@Repository
public class PairObservationDao {

    /** 한 경기의 참가자 수. 4대4 고정이다 (V2). */
    private static final int PARTICIPANTS_PER_MATCH = 8;

    /**
     * 참가자를 경기 순서로. <b>진영과 팀을 같이 내려보낸다</b> —
     * 동료·상대를 가르려면 진영이 필요하고, 팀 강도 항에는 팀이 필요하다.
     *
     * <p>지표는 여섯 열을 한꺼번에 가져온다. 지표마다 따로 조회하면 같은 8,888행을
     * 여섯 번 읽는데, 그건 느린 게 아니라 <b>안 보이게 느린</b> 종류다.
     */
    private static final String PARTICIPANTS = """
            SELECT m.match_id, p.side, p.champion_id,
                   CASE WHEN p.side = 'BLUE' THEN m.blue_team_id ELSE m.red_team_id END AS team_id,
                   pt.seq AS patch_seq, p.change_count,
                   p.dealing, p.tanking, p.healing, p.kills, p.deaths, p.assists
            FROM match_record m
            JOIN match_participant p ON p.match_id = m.match_id
            LEFT JOIN patch pt ON pt.patch_id = m.patch_id
            WHERE m.slot_id = ? AND m.match_type = 'OFFICIAL' AND m.team_size = 4
            ORDER BY m.match_id, p.side, p.pick_order
            """;

    private final JdbcTemplate jdbc;

    public PairObservationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 그 커리어의 관측을 지표별로 묶어 돌려준다.
     *
     * <p>지표마다 목록이 따로인 이유는 모형이 지표 하나씩 도는 것이기 때문이다 —
     * 한 번 적합에 한 지표다. 대신 <b>읽기는 한 번</b>이다.
     *
     * @return 지표 → 관측 목록. 값이 비어 있는 참가자가 있는 경기는 통째로 빠진다
     */
    @Transactional(readOnly = true)
    public Map<PerfMetric, List<PairObservation>> load(int slotId,
                                                       ReferencePoint reference,
                                                       AnalysisConfig config) {
        List<Participant> buffer = new ArrayList<>(PARTICIPANTS_PER_MATCH);
        Map<PerfMetric, List<PairObservation>> out = new LinkedHashMap<>();
        for (PerfMetric metric : PerfMetric.values()) {
            out.put(metric, new ArrayList<>());
        }

        long[] currentMatch = {Long.MIN_VALUE};
        jdbc.query(PARTICIPANTS, rs -> {
            long matchId = rs.getLong("match_id");
            if (matchId != currentMatch[0]) {
                flush(buffer, out, reference, config);                // 앞 경기를 마감한다
                buffer.clear();
                currentMatch[0] = matchId;
            }
            buffer.add(new Participant(
                    rs.getString("side"),
                    rs.getInt("champion_id"),
                    nullableInt(rs, "team_id"),
                    nullableInt(rs, "patch_seq"),
                    rs.getInt("change_count"),
                    nullableInt(rs, "dealing"),
                    nullableInt(rs, "tanking"),
                    nullableInt(rs, "healing"),
                    nullableInt(rs, "kills"),
                    nullableInt(rs, "deaths"),
                    nullableInt(rs, "assists")));
        }, slotId);
        flush(buffer, out, reference, config);                        // 마지막 경기

        return out;
    }

    /**
     * 모인 여덟 행을 관측으로 바꾼다.
     *
     * <p><b>하나라도 비면 그 경기를 통째로 버린다.</b> 한 참가자의 딜이 NULL 인데
     * 나머지로 계산하면 그 경기의 동료·상대 구성이 실제와 달라진다 — 빠진 챔피언이
     * "없었던 것" 이 되어 남의 쌍이 만들어진다. 줄어든 표본은 그냥 "데이터가 적네" 로
     * 보이지만, 틀린 쌍은 화면에서 발견처럼 보인다.
     */
    private static void flush(List<Participant> match,
                              Map<PerfMetric, List<PairObservation>> out,
                              ReferencePoint reference, AnalysisConfig config) {
        if (match.size() != PARTICIPANTS_PER_MATCH) {
            return;
        }
        List<Participant> blue = match.stream().filter(p -> "BLUE".equals(p.side())).toList();
        List<Participant> red = match.stream().filter(p -> !"BLUE".equals(p.side())).toList();
        if (blue.size() != 4 || red.size() != 4) {
            return;
        }

        for (PerfMetric metric : PerfMetric.values()) {
            if (match.stream().anyMatch(p -> metric.of(p) == null)) {
                continue;                                             // 이 지표만 비었다면 이 지표만 건너뛴다
            }
            append(out.get(metric), blue, red, metric, reference, config);
            append(out.get(metric), red, blue, metric, reference, config);
        }
    }

    /**
     * 그 관측의 감쇠 가중치 (D15a · D78).
     *
     * <p><b>{@link DecayWeight#of} 를 쓴다 — {@code forPair} 가 아니다.</b> 한 관측의
     * 주인은 챔피언 하나이고, 그 행이 켜는 쌍 특성이 일곱 개(동료 셋 · 상대 넷)다.
     * 쌍마다 무게를 다르게 주려면 행을 쪼개야 하는데, 그러면 같은 출력값이 일곱 번
     * 세어져 표본이 부풀고 목표값 z 의 뜻이 무너진다.
     *
     * <p>대가는 분명하다: <b>상대 쪽이 너프돼서 낡은 쌍</b>은 그만큼 안 눌린다.
     * 다만 메타 항(경과 패치)은 양쪽에 똑같이 걸리므로 그 부분은 온전하다.
     */
    private static double decay(Participant p, ReferencePoint reference, AnalysisConfig config) {
        int elapsed = reference.elapsedPatchesFrom(p.patchSeq());
        int selfChanges = reference.selfChangesFrom(p.championId(), p.changeCount());
        return DecayWeight.of(selfChanges, elapsed, config);
    }

    private static void append(List<PairObservation> out, List<Participant> own,
                               List<Participant> opposing, PerfMetric metric,
                               ReferencePoint reference, AnalysisConfig config) {
        List<Integer> foes = opposing.stream().map(Participant::championId).toList();
        for (Participant p : own) {
            List<Integer> mates = own.stream()
                    .map(Participant::championId)
                    .filter(id -> id != p.championId())
                    .toList();
            // 팀을 모르면 null 이다. 그때는 팀 강도 항이 안 켜진다 — 0 을 쓰면
            // "팀 0" 이라는 가짜 팀이 생겨 팀 없는 경기가 전부 한 팀으로 묶인다.
            String teamKey = p.teamId() == null ? null : "t" + p.teamId();
            out.add(new PairObservation(p.championId(), teamKey, mates, foes,
                    metric.of(p).doubleValue(), decay(p, reference, config)));
        }
    }

    private static Integer nullableInt(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    /**
     * DB 에서 읽은 참가자 한 줄. 지표 여섯과 <b>감쇠의 재료 둘</b>을 들고 있다.
     *
     * @param patchSeq    경기에 적용 중이던 패치의 커리어 내 순번. 첫 패치 이전이면 {@code null}
     * @param changeCount 경기 <b>시점</b>의 누적 변경 횟수. 거리로 바꾸는 것은
     *                    {@link ReferencePoint#selfChangesFrom} 의 일이다 (D15a)
     */
    public record Participant(String side, int championId, Integer teamId,
                              Integer patchSeq, int changeCount,
                              Integer dealing, Integer tanking, Integer healing,
                              Integer kills, Integer deaths, Integer assists) {
    }
}
