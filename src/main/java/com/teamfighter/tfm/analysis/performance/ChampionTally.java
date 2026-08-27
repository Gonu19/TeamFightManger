package com.teamfighter.tfm.analysis.performance;

import com.teamfighter.tfm.analysis.shrink.Shrinkage;

/**
 * 한 챔피언의 출전 누적. 원시 카운트와 가중 합계를 둘 다 든다 (D15).
 *
 * <p>{@code counter.WeightedTally} 와 모양이 같지만 합치지 않았다. 이쪽은 앞으로
 * 밴 수·경기력 z값이 붙을 자리이고(D19), 저쪽은 쌍에 매인 값이다. 지금 같다는 이유로
 * 한 타입으로 묶으면 한쪽에만 필요한 필드가 생길 때 다른 쪽이 그걸 이고 다니게 된다.
 */
public record ChampionTally(
        int games,
        int wins,
        double weightedGames,
        double weightedWins,
        double sumSquaredWeights) {

    public static final ChampionTally EMPTY = new ChampionTally(0, 0, 0, 0, 0);

    public ChampionTally plus(boolean won, double weight) {
        return new ChampionTally(
                games + 1,
                wins + (won ? 1 : 0),
                weightedGames + weight,
                weightedWins + (won ? weight : 0),
                sumSquaredWeights + weight * weight);
    }

    public ChampionTally plus(ChampionTally other) {
        return new ChampionTally(
                games + other.games,
                wins + other.wins,
                weightedGames + other.weightedGames,
                weightedWins + other.weightedWins,
                sumSquaredWeights + other.sumSquaredWeights);
    }

    public double ess() {
        return Shrinkage.ess(weightedGames, sumSquaredWeights);
    }
}
