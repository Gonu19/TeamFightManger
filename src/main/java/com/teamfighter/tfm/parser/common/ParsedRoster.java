package com.teamfighter.tfm.parser.common;

import java.util.Map;
import java.util.Optional;

/**
 * {@code common.data} 한 벌에서 뽑아낸 팀 이름표.
 *
 * <p><b>이것은 세이브가 아니다.</b> 프로필 공용 파일이라 커리어와 무관하게 언제든 바뀐다 —
 * 사용자가 게임에서 팀을 커스터마이즈하면 그 자리에서 갈린다. 그래서 화면에서 실시간으로
 * 읽지 않고 <b>적재 시점에 슬롯별로 스냅샷</b>한다 (D55).
 *
 * @param profileName     프로필 이름. 예: {@code JACKIE Custom 121.146 (1)}
 * @param playerTeamName  플레이어 팀 이름. {@code game_team_id = 0} 이다
 * @param playerCoachName 플레이어 감독 이름
 * @param aiTeamNames     {@code game_team_id → 팀 이름}. 플레이어 팀(0)은 들어 있지 않다
 */
public record ParsedRoster(
        String profileName,
        String playerTeamName,
        String playerCoachName,
        Map<Integer, String> aiTeamNames) {

    /** 플레이어 팀의 게임 내 번호. 게임이 0 을 플레이어에게 고정으로 준다. */
    public static final int PLAYER_TEAM_ID = 0;

    public ParsedRoster {
        aiTeamNames = Map.copyOf(aiTeamNames);
    }

    /**
     * 번호로 팀 이름을 찾는다. 0 은 플레이어 팀이라 딕셔너리가 아니라 {@code CommonStore} 에서 온다.
     *
     * <p>이름이 비어 있으면 {@link Optional#empty()} 다 — 빈 문자열을 이름으로 두면
     * 화면에 이름 없는 팀이 이름 있는 팀처럼 보인다.
     */
    public Optional<String> nameOf(Integer gameTeamId) {
        if (gameTeamId == null) {
            return Optional.empty();
        }
        String name = gameTeamId == PLAYER_TEAM_ID ? playerTeamName : aiTeamNames.get(gameTeamId);
        return (name == null || name.isBlank()) ? Optional.empty() : Optional.of(name);
    }

    /** 이름을 아는 팀 수. 플레이어 팀을 포함한다. */
    public int size() {
        return aiTeamNames.size() + (playerTeamName == null || playerTeamName.isBlank() ? 0 : 1);
    }
}
