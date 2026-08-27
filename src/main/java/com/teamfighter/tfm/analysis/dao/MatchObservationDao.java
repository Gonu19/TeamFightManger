package com.teamfighter.tfm.analysis.dao;

import com.teamfighter.tfm.analysis.MatchObservation;
import com.teamfighter.tfm.analysis.ReferencePoint;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 집계가 먹을 관측을 DB 에서 꺼낸다 (D22: 집계는 JdbcTemplate).
 *
 * <p><b>SQL 은 행을 꺼내는 일만 한다.</b> 쌍 전개도 감쇠도 하지 않는다 — 그건
 * {@code MatchupAggregator} 가 순수 함수로 하고, 그래야 DB 없이 변조로 검증할 수 있다.
 * 경기 1,200건이라 전부 메모리에 올려도 몇 MB 다.
 *
 * <p><b>슬롯 단위로만 조회한다.</b> 패치 역사가 슬롯마다 따로 생성되므로 감쇠 기준도
 * 슬롯마다 다르다. {@code GLOBAL} 스코프는 슬롯별로 각각 감쇠한 뒤 합산한다 (D45).
 *
 * <p>{@code team_size = 4} 로 거른다. 인원이 다르면 승률을 섞을 수 없다 — 스크림에는
 * 2·3인 경기가 있다 (D35).
 */
@Repository
public class MatchObservationDao {

    private static final String SLOT_IDS = "SELECT slot_id FROM save_slot ORDER BY slot_id";

    private static final String LATEST_PATCH_SEQ = """
            SELECT COALESCE(MAX(seq), 0) FROM patch WHERE slot_id = ?
            """;

    /**
     * 기준 순번까지 각 챔피언이 패치로 바뀐 누적 횟수.
     *
     * <p>세는 방식이 적재({@code SaveLoader} 가 만드는 {@code PatchAssigner})와 같아야 한다.
     * 저쪽은 {@code champion_patch_event} 를 <b>전부</b> 센다 — {@code is_new} 도 뺀 적이 없다.
     * 여기서만 걸러내면 두 누적의 기준이 어긋나서 그 차이가 감쇠 지수로 들어간다.
     * 부호도 크기도 아무 의미가 없는 값이 되는데, 결과는 여전히 (0,1] 안이라 안 보인다.
     */
    private static final String CHANGE_COUNTS = """
            SELECT e.champion_id, COUNT(*) AS change_count
            FROM champion_patch_event e
            JOIN patch p ON p.patch_id = e.patch_id
            WHERE p.slot_id = ? AND p.seq <= ?
            GROUP BY e.champion_id
            """;

    /**
     * 참가자를 경기 순서로 꺼낸다. 진영이 아니라 승패로 내려보낸다 — 집계는 진영을 안 쓴다.
     *
     * <p>{@code patch_id} 가 NULL 인 경기(첫 패치 이전)도 가져온다. LEFT JOIN 이 아니라
     * INNER JOIN 이면 커리어 시작 직후의 경기가 통째로 사라지는데, 줄어든 표본은
     * 그냥 "데이터가 적네" 로 보인다.
     */
    private static final String PARTICIPANTS = """
            SELECT m.match_id,
                   p.seq AS patch_seq,
                   mp.champion_id,
                   mp.change_count,
                   (m.winner_side = mp.side) AS is_winner
            FROM match_record m
            JOIN match_participant mp ON mp.match_id = m.match_id
            LEFT JOIN patch p ON p.patch_id = m.patch_id
            WHERE m.slot_id = ?
              AND m.team_size = 4
              AND (CAST(? AS boolean) OR m.match_type = 'OFFICIAL')
            ORDER BY m.match_id
            """;

    /**
     * 챔피언별 피밴 수. <b>공식전만 센다</b> — 스크림에는 밴이 없다.
     *
     * <p>{@code include_scrim} 을 인자로 받지 않는 이유가 그것이다. 스크림을 포함해도
     * 밴 수는 변하지 않는다. 바뀌는 것은 밴률의 <b>분모</b>뿐이고, 그래서 분모를 따로
     * 저장한다 (D50).
     *
     * <p>{@code team_size = 4} 로 거르는 것은 경기 조회와 같은 조건이어야 하기 때문이다.
     * 여기만 조건이 빠지면 표에 없는 경기의 밴이 밴률 분자에 들어간다.
     */
    private static final String BAN_COUNTS = """
            SELECT b.champion_id, count(*) AS bans
            FROM match_ban b
            JOIN match_record m ON m.match_id = b.match_id
            WHERE m.slot_id = ?
              AND m.team_size = 4
              AND m.match_type = 'OFFICIAL'
            GROUP BY b.champion_id
            """;

    private final JdbcTemplate jdbc;

    public MatchObservationDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<Integer, Integer> banCounts(int slotId) {
        Map<Integer, Integer> bans = new HashMap<>();
        jdbc.query(BAN_COUNTS,
                rs -> {
                    bans.put(rs.getInt("champion_id"), rs.getInt("bans"));
                },
                slotId);
        return bans;
    }

    public List<Integer> slotIds() {
        return jdbc.queryForList(SLOT_IDS, Integer.class);
    }

    /** 그 슬롯의 마지막 패치를 기준 시점으로 삼는다. 기본 화면이 보는 시점이다. */
    public ReferencePoint latestReference(int slotId) {
        Integer latest = jdbc.queryForObject(LATEST_PATCH_SEQ, Integer.class, slotId);
        return referenceAtSeq(slotId, latest == null ? 0 : latest);
    }

    /** 사용자가 특정 패치를 골랐을 때의 기준 시점 (D24). */
    public ReferencePoint referenceAtSeq(int slotId, int patchSeq) {
        Map<Integer, Integer> changeCounts = new HashMap<>();
        jdbc.query(CHANGE_COUNTS,
                rs -> {
                    changeCounts.put(rs.getInt("champion_id"), rs.getInt("change_count"));
                },
                slotId, patchSeq);
        return new ReferencePoint(patchSeq, changeCounts);
    }

    public List<MatchObservation> loadMatches(int slotId, boolean includeScrim) {
        List<MatchObservation> matches = new ArrayList<>();
        MatchBuilder builder = new MatchBuilder();

        jdbc.query(PARTICIPANTS,
                rs -> {
                    long matchId = rs.getLong("match_id");
                    int patchSeq = rs.getInt("patch_seq");
                    Integer seq = rs.wasNull() ? null : patchSeq;

                    builder.startOrContinue(matchId, seq, matches);
                    builder.add(
                            rs.getBoolean("is_winner"),
                            new MatchObservation.Participant(
                                    rs.getInt("champion_id"), rs.getInt("change_count")));
                },
                slotId, includeScrim);

        builder.flush(matches);
        return matches;
    }

    /**
     * 행이 경기 단위로 이어져 오므로 경기가 바뀌는 순간 하나를 완성한다.
     *
     * <p>{@code MatchObservation} 의 생성자가 한쪽 팀이 비었거나 챔피언이 중복되면 던진다.
     * 조인이 어긋나면 조용히 이상한 승률이 나오는 대신 여기서 시끄럽게 죽는다.
     */
    private static final class MatchBuilder {
        private Long matchId;
        private Integer patchSeq;
        private final List<MatchObservation.Participant> winners = new ArrayList<>(4);
        private final List<MatchObservation.Participant> losers = new ArrayList<>(4);

        void startOrContinue(long nextMatchId, Integer nextPatchSeq, List<MatchObservation> out) {
            if (matchId != null && matchId != nextMatchId) {
                flush(out);
            }
            matchId = nextMatchId;
            patchSeq = nextPatchSeq;
        }

        void add(boolean isWinner, MatchObservation.Participant participant) {
            (isWinner ? winners : losers).add(participant);
        }

        void flush(List<MatchObservation> out) {
            if (matchId == null) {
                return;
            }
            out.add(new MatchObservation(matchId, patchSeq, List.copyOf(winners), List.copyOf(losers)));
            winners.clear();
            losers.clear();
            matchId = null;
        }
    }
}
