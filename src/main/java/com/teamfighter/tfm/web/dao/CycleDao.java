package com.teamfighter.tfm.web.dao;

import com.teamfighter.tfm.web.view.CycleRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 연대기 화면이 읽는 것 — <b>매치 하나가 사이클의 어디까지 왔나</b>.
 *
 * <h2>세트를 매치로 묶는다</h2>
 *
 * {@code match_record} 한 행은 <b>세트</b>다. 매치는 (시즌 · 일 · 두 팀) 이 같은 세트의
 * 묶음이고, 그 넷이 {@code ArticleKey} 와 같은 신원이다 (대회마다 ID 공간이 따로라
 * {@code schedule_id} 는 못 쓴다 — 실측 190건이 114개 값에 겹친다).
 *
 * <h2>팀을 정렬해 묶는다</h2>
 *
 * {@code LEAST}/{@code GREATEST} 로 두 팀을 <b>무순</b>으로 만든다. 세트의 진영이 매치
 * 기준과 반대인 경우가 실측 294세트 중 122건이라, 진영을 그대로 두면 같은 매치가
 * 두 줄로 갈린다. 기사·갤러리를 붙일 때도 같은 정렬을 걸어야 짝이 맞는다 —
 * 저쪽은 세이브의 진영 그대로 저장돼 있다.
 *
 * <h2>스칼라 서브질의로 붙인다</h2>
 *
 * 갤러리는 매치 하나에 <b>쌓인다</b>(D72 결정 5). {@code LEFT JOIN} 하면 갤이 셋이면
 * 매치 줄이 셋으로 늘어난다 — 목록이 조용히 부풀고, 그 부풀음은 "경기를 많이 했네" 로
 * 보인다. 그래서 개수와 최신 번호만 스칼라로 꺼낸다.
 */
@Repository
public class CycleDao {

    /**
     * 그 커리어의 매치 전부, 최근순.
     *
     * <p>공식전만이다. 스크림은 시점 정보가 없어({@code season}·{@code day} 가 NULL)
     * 매치로 묶을 수가 없고, 기사·갤러리도 공식전만 쓴다.
     */
    private static final String CYCLE = """
            WITH m AS (
              SELECT slot_id, season, day,
                     LEAST(blue_team_id, red_team_id)    AS home_id,
                     GREATEST(blue_team_id, red_team_id) AS away_id,
                     count(*) AS sets,
                     count(*) FILTER (
                       WHERE (CASE WHEN winner_side = 'BLUE' THEN blue_team_id ELSE red_team_id END)
                             = LEAST(blue_team_id, red_team_id)) AS home_wins,
                     max(ingested_at) AS ingested_at
              FROM match_record
              WHERE slot_id = ?
                AND match_type = 'OFFICIAL'
                AND season IS NOT NULL AND day IS NOT NULL
                AND blue_team_id IS NOT NULL AND red_team_id IS NOT NULL
              GROUP BY slot_id, season, day,
                       LEAST(blue_team_id, red_team_id), GREATEST(blue_team_id, red_team_id)
            )
            SELECT m.season, m.day, m.home_id, m.away_id, m.sets, m.home_wins, m.ingested_at,
                   h.name AS home_name, a.name AS away_name,
                   h.is_player AS home_is_player, a.is_player AS away_is_player,
                   (SELECT ar.article_id FROM article ar
                     WHERE ar.slot_id = m.slot_id AND ar.kind = 'MATCH'
                       AND ar.season = m.season AND ar.day = m.day
                       AND LEAST(ar.blue_team_id, ar.red_team_id) = m.home_id
                       AND GREATEST(ar.blue_team_id, ar.red_team_id) = m.away_id) AS article_id,
                   (SELECT max(g.batch_id) FROM gallery_batch g
                     WHERE g.slot_id = m.slot_id
                       AND g.season = m.season AND g.day = m.day
                       AND LEAST(g.blue_team_id, g.red_team_id) = m.home_id
                       AND GREATEST(g.blue_team_id, g.red_team_id) = m.away_id) AS batch_id,
                   (SELECT count(*) FROM gallery_batch g
                     WHERE g.slot_id = m.slot_id
                       AND g.season = m.season AND g.day = m.day
                       AND LEAST(g.blue_team_id, g.red_team_id) = m.home_id
                       AND GREATEST(g.blue_team_id, g.red_team_id) = m.away_id) AS batches
            FROM m
            JOIN team h ON h.team_id = m.home_id
            JOIN team a ON a.team_id = m.away_id
            ORDER BY m.season DESC, m.day DESC, m.home_id
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public CycleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public List<CycleRow> matches(int slotId, int limit) {
        return jdbc.query(CYCLE, (rs, rowNum) -> new CycleRow(
                rs.getInt("season"),
                rs.getInt("day"),
                rs.getInt("home_id"),
                rs.getInt("away_id"),
                rs.getString("home_name"),
                rs.getString("away_name"),
                rs.getBoolean("home_is_player"),
                rs.getBoolean("away_is_player"),
                rs.getInt("sets"),
                rs.getInt("home_wins"),
                nullableLong(rs, "article_id"),
                nullableLong(rs, "batch_id"),
                rs.getInt("batches"),
                rs.getObject("ingested_at", java.time.OffsetDateTime.class)), slotId, limit);
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
