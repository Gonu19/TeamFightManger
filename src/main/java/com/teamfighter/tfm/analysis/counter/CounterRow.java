package com.teamfighter.tfm.analysis.counter;

/**
 * {@code champion_matchup} 한 행. 방향이 있다 — {@code (champion, opponent)}.
 *
 * <p>세 가지 승률을 함께 들고 다닌다. 화면이 셋을 다 쓰기 때문이다 (D14) —
 * <b>실제 승률 · 기대 승률 · 상성 이득.</b> 정렬은 상성 이득으로 하되, 사용자는
 * 원본 승률과 경기 수를 보고 판단한다 (D10).
 */
public record CounterRow(
        int championId,
        int opponentId,
        int games,
        int wins,
        double weightedGames,
        double weightedWins,
        double ess,
        /** 두 챔피언 기저 강도만으로 예측되는 승률 (Bradley-Terry). */
        double expectedWinRate,
        /** 1단 축소를 거친 추정. 목표값은 기대 승률이다. */
        double adjustedWinRate,
        /** {@code adjusted − expected}. 챔피언 강도를 걷어낸 진짜 상성. 정렬은 이걸로. */
        double counterEffect) {

    /** 원본 승률. 표본 기준 판정과 화면 표시는 이쪽 원시 카운트를 쓴다 (D15). */
    public double rawWinRate() {
        return games == 0 ? 0 : (double) wins / games;
    }
}
