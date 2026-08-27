package com.teamfighter.tfm.analysis.counter;

import com.teamfighter.tfm.analysis.shrink.Shrinkage;

/**
 * 한 쌍(또는 조합)의 누적. <b>원시 카운트와 가중 합계를 둘 다 들고 있다</b> (D15).
 *
 * <p>둘 다 필요한 이유가 다르다 — 표본 기준선 10경기 판정(D9)과 화면 표시는 원시 카운트로,
 * 승률과 정렬은 가중값으로 한다. 하나로 합치면 "12경기 · 추정 54.1%" 처럼 읽히는 화면을
 * 만들 수 없다 (D24).
 *
 * <p>불변이다. 누적은 새 값을 만들어 돌려준다.
 */
public record WeightedTally(
        int games,
        int wins,
        double weightedGames,
        double weightedWins,
        /** 유효표본수 계산에 쓰는 Σw². 가중치가 한쪽에 몰릴수록 실제 정보량은 경기 수보다 작다. */
        double sumSquaredWeights) {

    public static final WeightedTally EMPTY = new WeightedTally(0, 0, 0, 0, 0);

    public WeightedTally plus(boolean won, double weight) {
        return new WeightedTally(
                games + 1,
                wins + (won ? 1 : 0),
                weightedGames + weight,
                weightedWins + (won ? weight : 0),
                sumSquaredWeights + weight * weight);
    }

    /** 다른 누적과 합친다. 슬롯을 가로질러 합산할 때 쓴다 (D45). */
    public WeightedTally plus(WeightedTally other) {
        return new WeightedTally(
                games + other.games,
                wins + other.wins,
                weightedGames + other.weightedGames,
                weightedWins + other.weightedWins,
                sumSquaredWeights + other.sumSquaredWeights);
    }

    /** 유효표본수 {@code (Σw)² / Σw²}. 신뢰구간은 원시 경기 수가 아니라 이 값으로 잡는다. */
    public double ess() {
        return Shrinkage.ess(weightedGames, sumSquaredWeights);
    }
}
