package com.teamfighter.tfm.analysis.counter;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.MatchObservation;
import com.teamfighter.tfm.analysis.ReferencePoint;
import com.teamfighter.tfm.analysis.decay.DecayWeight;
import com.teamfighter.tfm.analysis.strength.BradleyTerry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 경기를 카운터 관측으로 편다 (D14).
 *
 * <p>라인이 없는 게임이라 카운터의 정의는 <b>"적팀에 같이 있었다"</b> 하나뿐이다 (D4).
 * 4v4 한 경기에서 승리 팀 4명 × 패배 팀 4명 = 관측 16개가 나온다.
 *
 * <p><b>이 전개를 SQL 이 아니라 여기 둔 이유.</b> 조인 하나만 어긋나도 아군끼리 쌍이
 * 생기거나 양방향 가중치가 달라지는데, 결과는 여전히 0~1 사이의 그럴듯한 승률이다.
 * 순수 함수로 두면 DB 없이 변조로 확인할 수 있다. 경기당 관측이 16개라 1,200경기여도
 * 2만 행 남짓이므로 메모리로 가져와도 부담이 없다.
 */
public final class MatchupAggregator {

    private MatchupAggregator() {
    }

    public static MatchupAggregate aggregate(
            Collection<MatchObservation> matches, ReferencePoint reference, AnalysisConfig config) {

        Map<PairKey, WeightedTally> pairs = new HashMap<>();
        List<BradleyTerry.Outcome> outcomes = new ArrayList<>();

        for (MatchObservation match : matches) {
            int elapsed = reference.elapsedPatchesFrom(match.patchSeq());

            for (MatchObservation.Participant winner : match.winners()) {
                int winnerChanges =
                        reference.selfChangesFrom(winner.championId(), winner.changeCountAtMatch());

                for (MatchObservation.Participant loser : match.losers()) {
                    int loserChanges =
                            reference.selfChangesFrom(loser.championId(), loser.changeCountAtMatch());
                    double weight =
                            DecayWeight.forPair(winnerChanges, loserChanges, elapsed, config);

                    PairKey won = new PairKey(winner.championId(), loser.championId());
                    accumulate(pairs, won, true, weight);
                    accumulate(pairs, won.reversed(), false, weight);

                    outcomes.add(new BradleyTerry.Outcome(
                            winner.championId(), loser.championId(), weight));
                }
            }
        }
        return new MatchupAggregate(pairs, outcomes);
    }

    private static void accumulate(
            Map<PairKey, WeightedTally> pairs, PairKey key, boolean won, double weight) {
        pairs.merge(key, WeightedTally.EMPTY.plus(won, weight),
                (existing, ignored) -> existing.plus(won, weight));
    }
}
