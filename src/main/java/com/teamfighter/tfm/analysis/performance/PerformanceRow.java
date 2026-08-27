package com.teamfighter.tfm.analysis.performance;

/**
 * {@code champion_performance} 한 행 — 티어표의 한 줄.
 *
 * <p>승률·픽률·밴률을 여기서 계산하지 않는다. 셋 다 DB 의 생성 컬럼이다 — 카운트에서
 * 비율을 뽑는 규칙이 한 군데에만 있어야 한다. 이 타입이 나르는 것은 <b>카운트와 분모</b>다.
 *
 * <p>분모가 둘인 이유는 밴이 공식전에만 있기 때문이다 (D50).
 */
public record PerformanceRow(
        int championId,
        int games,
        int wins,
        int bans,
        /** 픽률의 분모. 해당 스코프의 총 경기 수. */
        int matchCount,
        /** 밴률의 분모. 해당 스코프의 공식전 수. */
        int banMatchCount,
        double weightedGames,
        double weightedWins,
        double ess,
        /** 1단 축소를 거친 추정. 목표값은 0.5 다 — 강도 보정을 하지 않는다 (D14). */
        double adjustedWinRate,
        /** 아직 매기지 않는다. 컷라인을 분포를 보고 정해야 한다. */
        String tierGrade) {

    public double rawWinRate() {
        return games == 0 ? 0 : (double) wins / games;
    }
}
