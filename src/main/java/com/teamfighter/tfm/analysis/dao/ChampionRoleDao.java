package com.teamfighter.tfm.analysis.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 챔피언 → 역할군 번호.
 *
 * <h2>원본은 시드 하나뿐이다</h2>
 *
 * 역할군은 챔피언의 <b>고정 속성</b>이고 원본은 {@code V3__seed_champions.sql} 이다(D05).
 * 자바 쪽에 표를 복제하면 원본이 둘이 되고, 시드가 바뀌면 이 코드가 조용히 옛 매핑으로
 * 재게 된다. 그래서 DB 에서 읽는다 — {@code tools/perf_champion.py} 가 같은 이유로
 * 시드 파일을 직접 파싱하는 것과 같은 규칙이다.
 *
 * <h2>번호로 바꾸는 이유</h2>
 *
 * 설계행렬의 특성 열쇠가 {@code int} 세 개다. 역할군을 문자열로 들고 다니면 열쇠가
 * 두 모양이 되고, 그러면 특성 번호를 매기는 자리마다 분기가 생긴다.
 * <b>번호가 무엇인지는 중요하지 않다</b> — 같은 역할군이 같은 번호이기만 하면 된다.
 */
@Repository
public class ChampionRoleDao {

    /**
     * 역할군을 이름순으로 번호 매긴다. {@code DENSE_RANK} 라 값이 안정적이다 —
     * 챔피언이 늘어도 같은 역할군은 같은 번호를 받는다.
     */
    private static final String ROLES = """
            SELECT champion_id,
                   DENSE_RANK() OVER (ORDER BY category) - 1 AS role_no
            FROM champion
            ORDER BY champion_id
            """;

    private final JdbcTemplate jdbc;

    public ChampionRoleDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<Integer, Integer> load() {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        jdbc.query(ROLES, rs -> {
            out.put(rs.getInt("champion_id"), rs.getInt("role_no"));
        });
        return out;
    }
}
