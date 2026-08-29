package com.teamfighter.tfm.story;

/**
 * {@link Notability} 가 {@link MatchBrief} 만으로는 알 수 없는 것들.
 *
 * <p><b>전부 {@code null} 이 될 수 있다.</b> 새 커리어 1일차에는 순위도 전적도 없고,
 * 그때도 기사는 나와야 한다. 모르는 값은 주목도를 <b>올리지도 내리지도 않는다</b> —
 * 없는 근거로 기사를 키우면 그게 곧 창작이 사실 위로 올라오는 것이다.
 *
 * <p>이 레코드를 채우는 일(순위 조회·상대 전적·강도 추정)은 DB 를 아는 쪽의 몫이다.
 * {@code Notability} 자체는 순수하게 남는다.
 *
 * @param playerTeamId    플레이어 팀 번호. 보통 {@code 0} 이다 (D54)
 * @param blueRank        매치 시점 블루팀 순위 (1이 1위)
 * @param redRank         같음
 * @param leagueSize      그 리그의 팀 수. 순위 차를 정규화하는 데 쓴다
 * @param blueWinProbability 사전 예측 승률. 업셋 판정에 쓴다.
 *                        <b>지금은 채울 곳이 없다</b> — 팀 강도 모형이 아직 없다
 * @param rivalry         라이벌전인가. 상대 전적이 충분하고 접전이었을 때
 */
public record NotabilityContext(
        Integer playerTeamId,
        Integer blueRank,
        Integer redRank,
        Integer leagueSize,
        Double blueWinProbability,
        boolean rivalry) {

    /** 아무것도 모르는 맥락. 플레이어 팀만 알 때 쓴다. */
    public static NotabilityContext unknown(Integer playerTeamId) {
        return new NotabilityContext(playerTeamId, null, null, null, null, false);
    }

    /** 순위 정보가 다 있는가. 하나라도 없으면 순위 항을 쓰지 않는다. */
    public boolean hasStandings() {
        return blueRank != null && redRank != null && leagueSize != null && leagueSize > 1;
    }
}
