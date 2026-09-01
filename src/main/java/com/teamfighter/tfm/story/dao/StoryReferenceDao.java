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
            SELECT game_team_id, team_id, name, is_player FROM team WHERE slot_id = ? ORDER BY game_team_id
            """;

    /**
     * 코드와 한글 이름을 <b>같이</b> 읽는다.
     *
     * <p>전에는 코드만 읽었다 — brief 의 픽이 코드라 대조 어휘를 거기 맞추려던 것인데,
     * 그러면 기사가 "DuelBlader와 Demon을" 이라고 써서 몰입이 깨졌다. 이제 렌더러가
     * 한글로 쓰고 대조도 한글로 본다 ({@link StoryReference#championNames()} · D80).
     */
    private static final String CHAMPIONS = """
            SELECT code, name_ko FROM champion ORDER BY champion_id
            """;

    /**
     * 선수 이름표. {@code name} 이 NULL 인 선수(이름 풀에 없는 번호)는 빼고 읽는다 —
     * 이름이 없으면 렌더러가 번호를 적고, 번호는 이 표에서 찾을 필요가 없다.
     */
    private static final String ATHLETES = """
            SELECT game_athlete_id, name FROM athlete
            WHERE slot_id = ? AND name IS NOT NULL
            ORDER BY game_athlete_id
            """;

    private final JdbcTemplate jdbc;

    public StoryReferenceDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /*
     * slotIds() 는 여기 있었다. 화면의 커리어 목록을 뽑는 자리였는데, 같은 질의를
     * 통계 화면도 따로 갖고 있었고 그쪽만 집계 결과 표를 봐서 새 커리어를 놓쳤다.
     * 목록의 출처를 하나로 모으면서 com.teamfighter.tfm.web.dao.SlotDao 로 옮겼다 (D82).
     * 이 클래스는 기사가 쓸 사실을 읽는 곳이지 화면의 고르개를 채우는 곳이 아니다.
     */

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
        Integer[] playerGameTeamId = {null};                                    // 배열인 이유: 람다 안에서 대입하려면 사실상 final 이 아니어야 한다

        jdbc.query(TEAMS, rs -> {
            int gameTeamId = rs.getInt("game_team_id");
            teamIds.put(gameTeamId, rs.getInt("team_id"));
            String name = rs.getString("name");
            if (name != null && !name.isBlank()) {
                teamNames.put(gameTeamId, name);
                names.add(name);
            }
            if (rs.getBoolean("is_player")) {                                   // 커리어당 한 팀이다. 둘이면 적재가 깨진 것이므로 먼저 만난 팀을 조용히 쓰지 않는다
                if (playerGameTeamId[0] != null) {
                    throw new IllegalStateException("슬롯 " + slotId + " 에 플레이어 팀이 둘이다: "
                            + playerGameTeamId[0] + " · " + gameTeamId + " — 적재를 다시 돌린다");
                }
                playerGameTeamId[0] = gameTeamId;
            }
        }, slotId);

        // 코드 → 한글 이름. 렌더러도 대조도 이 표 하나를 거친다 — 둘이 다른 어휘를
        // 쓰면 기사에 나온 챔피언이 안 잡혀 검사가 조용히 죽는다 (D80).
        Map<String, String> champions = new LinkedHashMap<>();
        jdbc.query(CHAMPIONS, rs -> {
            champions.put(rs.getString("code"), rs.getString("name_ko"));
        });

        Map<Integer, String> athletes = new LinkedHashMap<>();
        jdbc.query(ATHLETES, rs -> {
            athletes.put(rs.getInt("game_athlete_id"), rs.getString("name"));
        }, slotId);

        return new StoryReference(slotId, playerGameTeamId[0], teamIds, teamNames, champions, names, athletes);
    }
}
