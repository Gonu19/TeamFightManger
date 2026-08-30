package com.teamfighter.tfm.story.dao;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 기사 한 편을 쓰기 전에 필요한 것을 슬롯 단위로 한 번에 읽는다.
 *
 * <p><b>매치마다 읽지 않는다.</b> 커리어 한 벌이면 팀 56행·챔피언 40행이고, 매치 109편을
 * 쓰는 동안 값이 바뀌지 않는다. 매치마다 읽으면 조회 200번이 모델 호출 사이사이에 끼는데,
 * 그건 느린 게 아니라 <b>안 보이게 느린</b> 종류다.
 *
 * <p>그래서 {@link StoryReference} 는 불변이고, 부르는 쪽이 한 번 받아 들고 돈다.
 * 적재가 도중에 팀을 추가하면 이 스냅샷은 그것을 모른다 — 새 팀의 기사를 쓰려면
 * 다시 읽어야 한다. 기사 생성은 적재가 끝난 뒤에 도는 작업이라 그 편이 맞다.
 */
@Repository
public class StoryReferenceDao {

    private static final String TEAMS = """
            SELECT game_team_id, team_id, name FROM team WHERE slot_id = ? ORDER BY game_team_id
            """;

    /**
     * 세이브에 든 코드 그대로다. 한글 이름({@code name_ko})이 아니다 —
     * {@link StoryReference#championCodes()} 가 이유를 적어 뒀다.
     */
    private static final String CHAMPION_CODES = """
            SELECT code FROM champion ORDER BY champion_id
            """;

    private final JdbcTemplate jdbc;

    public StoryReferenceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 슬롯의 세이브 파일명. {@code slot_key} 는 파일명 그대로 저장돼 있다(D28) —
     * 적재가 {@code SlotFile.slotKeyOf} 로 넣은 값이고, 그래서 세이브 폴더에 이어 붙이면
     * 경로가 된다.
     *
     * <p>기사 생성이 이걸 필요로 하는 이유는 <b>DB 에 매치 일정이 없기</b> 때문이다.
     * 적재는 경기(세트)만 넣는다 — 매치({@code MatchSchedule})는 기사가 생기기 전에
     * 필요가 없어서 스키마에 자리가 없다. 그래서 기사를 쓰려면 세이브를 다시 읽어야 한다.
     *
     * @throws IllegalStateException 없는 슬롯. 화면이 준 번호가 DB 에 없다는 뜻이라
     *                               조용히 넘어가면 "왜 아무 일도 안 일어나지" 가 된다
     */
    @Transactional(readOnly = true)
    public String slotKey(int slotId) {
        List<String> found = jdbc.queryForList(
                "SELECT slot_key FROM save_slot WHERE slot_id = ?", String.class, slotId);
        if (found.isEmpty()) {
            throw new IllegalStateException("슬롯 " + slotId + " 이 없다");
        }
        return found.get(0);
    }

    @Transactional(readOnly = true)
    public StoryReference load(int slotId) {
        Map<Integer, Integer> teamIds = new LinkedHashMap<>();
        Map<Integer, String> teamNames = new LinkedHashMap<>();
        Set<String> names = new LinkedHashSet<>();

        jdbc.query(TEAMS, rs -> {
            int gameTeamId = rs.getInt("game_team_id");
            teamIds.put(gameTeamId, rs.getInt("team_id"));
            String name = rs.getString("name");
            if (name != null && !name.isBlank()) {
                teamNames.put(gameTeamId, name);
                names.add(name);
            }
        }, slotId);

        Set<String> champions = new LinkedHashSet<>(
                jdbc.queryForList(CHAMPION_CODES, String.class));

        return new StoryReference(slotId, teamIds, teamNames, champions, names);
    }
}
