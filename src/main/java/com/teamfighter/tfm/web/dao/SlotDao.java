package com.teamfighter.tfm.web.dao;

import com.teamfighter.tfm.web.view.SlotOption;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/**
 * 커리어 목록 — <b>세 화면이 같은 곳에서 읽는다</b>.
 *
 * <h2>왜 생겼나 — 티어만 다른 곳을 보고 있었다</h2>
 *
 * D79 는 "세 화면 모두 적재된 것 전부를 그린다" 로 정했는데 티어만 안 고쳤다.
 * 티어는 {@code SELECT DISTINCT slot_id FROM champion_performance} 를 쓰고 있었고,
 * 그건 <b>집계 결과</b> 표다. 새 커리어를 만들어 적재해도 집계를 안 돌렸으면
 * 행이 없으므로 <b>티어 화면의 고르개에서 통째로 사라진다.</b>
 *
 * <p>실물에서 그렇게 됐다: 슬롯 4가 연대기·갤러리에는 뜨는데 티어에만 없었다.
 * 사용자에게 그것은 "적재가 안 됐나" 로 읽힌다 — 적재는 됐고 집계가 안 됐을 뿐인데.
 *
 * <p>그래서 목록의 출처를 {@code save_slot} 하나로 모은다. "내용이 있는가" 는
 * 화면마다 뜻이 다르므로 각자 판단해 {@link SlotOption#filled()} 로 넘긴다.
 */
@Repository
public class SlotDao {

    /**
     * 커리어와 그 <b>플레이어 팀 이름</b>.
     *
     * <p>이름은 {@code team.is_player} 에서 온다. {@code save_slot.label}·
     * {@code team_name} 은 스키마에 자리만 있고 아무도 안 채운다 — 없는 값을 읽어
     * 빈 칸을 만드는 대신 실재하는 것을 쓴다.
     *
     * <p>이름은 <b>스칼라 서브질의</b>로 붙인다. 조인이 아닌 이유는 행 수 때문이다:
     * {@code is_player} 는 유일성 제약이 아니라 그냥 불리언 칸이라, 한 커리어에
     * 플레이어 팀이 둘 이상 들어가면 조인은 그 커리어를 <b>고르개에 두 줄로</b> 그린다.
     * 서브질의는 커리어당 한 줄을 문법으로 보장한다 — 목록의 길이가 데이터의 상태에
     * 따라 달라지지 않는다.
     *
     * <p>서브질의라서 <b>팀이 아직 없는 새 커리어도 목록에 남는다</b> (이름만 null).
     * 안 그러면 첫 공식전을 치르기 전까지 그 커리어가 안 보이고, 그게 바로
     * 이 클래스가 고치려는 증상이다. 팀은 공식전에서만 식별된다 (D54).
     */
    private static final String SLOTS = """
            SELECT s.slot_id,
                   (SELECT t.name FROM team t
                     WHERE t.slot_id = s.slot_id AND t.is_player
                     ORDER BY t.team_id LIMIT 1) AS team_name
            FROM save_slot s
            ORDER BY s.slot_id
            """;

    private final JdbcTemplate jdbc;

    public SlotDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 적재된 커리어 전부.
     *
     * @param filled 이 화면이 "내용 있음" 으로 치는 슬롯. 나머지는 고르개에
     *               "(비어 있음)" 으로 찍힌다
     */
    @Transactional(readOnly = true)
    public List<SlotOption> options(Set<Integer> filled) {
        return jdbc.query(SLOTS, (rs, rowNum) -> {
            int slotId = rs.getInt("slot_id");
            return new SlotOption(slotId, rs.getString("team_name"), filled.contains(slotId));
        });
    }
}
