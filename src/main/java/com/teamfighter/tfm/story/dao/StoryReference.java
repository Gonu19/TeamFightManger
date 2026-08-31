package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.NameBook;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 슬롯 하나가 기사를 쓰는 데 필요한 <b>이름표와 대조 어휘</b>.
 *
 * <p>{@link NameBook} 을 구현한다 — 그래서 이제 기사에 "팀 33" 대신 실명이 나온다.
 *
 * <p><b>키는 세이브의 {@code game_team_id} 다. DB 의 {@code team_id} 가 아니다.</b>
 * {@code MatchBrief} 가 세이브에서 온 번호를 들고 있고, 렌더러·프롬프트·대조가 전부 그 번호로
 * 이름을 묻기 때문이다. 저장할 때만 DB 번호가 필요하고, 그 변환이 {@link #teamId(Integer)} 다.
 * 두 번호 공간을 한 자리에서 다루므로 여기가 섞이면 조용히 남의 팀 기사가 된다 —
 * 그래서 모르는 번호에는 {@code null} 이 아니라 예외로 답한다.
 *
 * @param championCodes 세이브에 든 코드 그대로다({@code 'Jiangshi'}). 한글 이름을 쓰면
 *                      brief 의 픽({@code ParsedGame} 에서 온 코드)과 어휘가 갈려서,
 *                      이 매치에 <b>실제로 나온</b> 챔피언이 "없는 챔피언" 으로 잡힌다
 * @param teamNames     커리어의 팀 이름 전체. 이 매치에 없는 팀을 기사가 부르면 모순이다
 * @param playerGameTeamId 플레이어 팀의 <b>세이브 번호</b>다. 보통 0 이다(D54).
 *                      커리어에 {@code is_player} 팀이 없으면 {@code null} 이고, 그때는 주목도의
 *                      "내 팀" 항이 통째로 빠진다 — 0점이 아니라 판단에서 제외다
 */
public record StoryReference(
        int slotId,
        Integer playerGameTeamId,
        Map<Integer, Integer> teamIdByGameTeamId,
        Map<Integer, String> teamNameByGameTeamId,
        Set<String> championCodes,
        Set<String> teamNames,
        Map<Integer, String> athleteNameByGameAthleteId) implements NameBook {

    public StoryReference {
        teamIdByGameTeamId = Map.copyOf(teamIdByGameTeamId);
        teamNameByGameTeamId = Map.copyOf(teamNameByGameTeamId);
        championCodes = Set.copyOf(championCodes);
        teamNames = Set.copyOf(teamNames);
        athleteNameByGameAthleteId = Map.copyOf(athleteNameByGameAthleteId);
    }

    /**
     * 커리어의 선수 이름 전부. {@code FactCheck} 가 <b>이 매치에 없는 선수</b>를
     * 가려내는 데 쓴다 — 목록에 없는 낱말은 건드리지 않는다는 규칙이 그대로 적용된다.
     */
    public Set<String> athleteNames() {
        return Set.copyOf(athleteNameByGameAthleteId.values());
    }

    /** 선수 이름. 모르면 {@code null} — 렌더러가 번호를 적는다 (D57). */
    @Override
    public String athleteName(Integer athleteId) {
        return athleteId == null ? null : athleteNameByGameAthleteId.get(athleteId);
    }

    /** 모르면 {@code null}. 렌더러가 번호를 그대로 적는다 (D57). */
    @Override
    public String teamName(Integer gameTeamId) {
        return gameTeamId == null ? null : teamNameByGameTeamId.get(gameTeamId);
    }

    /**
     * 항상 {@code null} 이다. <b>대회 이름표가 DB 에 없다.</b>
     *
     * <p>팀 이름은 {@code team_name_seed} 로 손으로 넣었지만(D56) 대회는 아직 그 작업을 안 했다.
     * 여기서 키를 그럴듯하게 다듬어 돌려주면(예: {@code competition.name.spring} → "spring")
     * 그게 진짜 대회 이름인지 기계가 만든 문자열인지 화면도 기사도 구분할 수 없다.
     */
    @Override
    public String competitionName(String key) {
        return null;
    }

    /**
     * 세이브 번호를 DB 번호로 바꾼다.
     *
     * @throws IllegalStateException 그 슬롯에 없는 팀. 적재가 빠뜨렸다는 뜻이고,
     *                               조용히 넘어가면 남의 팀 번호로 기사가 저장된다
     */
    public int teamId(Integer gameTeamId) {
        Objects.requireNonNull(gameTeamId, "gameTeamId");
        Integer teamId = teamIdByGameTeamId.get(gameTeamId);
        if (teamId == null) {
            throw new IllegalStateException(
                    "슬롯 " + slotId + " 에 세이브 팀 번호 " + gameTeamId + " 가 없다 — 적재를 먼저 돌린다");
        }
        return teamId;
    }
}
