package com.teamfighter.tfm.parser.common;

/**
 * 세이브 안의 팀 하나. <b>커리어 시점의 신원</b>이다 (D56).
 *
 * <p>{@code common.data} 의 이름표와 다르다. 저쪽은 <b>지금</b> 프로필의 로스터라
 * 사용자가 게임에서 커스터마이즈하면 갈리지만, 이건 세이브 안에 있어서
 * 그 커리어를 플레이할 때의 값 그대로다.
 *
 * @param id      {@code game_team_id}. 0 이 플레이어 팀이다
 * @param nameKey {@code useKey} 면 이름 그 자체, 아니면 로컬라이제이션 키
 *                (예: {@code team.name.pro.team8})
 * @param useKey  {@code nameKey} 를 이름으로 그대로 쓸지. 커스터마이즈된 팀이 그렇다
 */
public record ParsedTeamInfo(Integer id, String nameKey, boolean useKey) {

    /** 키가 아니라 이름이 직접 들어 있으면 그 이름. 아니면 {@code null}. */
    public String literalName() {
        return useKey && nameKey != null && !nameKey.isBlank() ? nameKey : null;
    }

    /** 로컬라이제이션 키. 이름이 직접 들어 있으면 {@code null}. */
    public String localizationKey() {
        return useKey ? null : (nameKey == null || nameKey.isBlank() ? null : nameKey);
    }
}
