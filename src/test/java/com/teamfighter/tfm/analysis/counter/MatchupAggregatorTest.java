package com.teamfighter.tfm.analysis.counter;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.MatchObservation;
import com.teamfighter.tfm.analysis.ReferencePoint;
import com.teamfighter.tfm.analysis.decay.DecayWeight;
import com.teamfighter.tfm.analysis.strength.BradleyTerry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 경기를 카운터 관측으로 펴는 일을 못 박는다 (D14).
 *
 * <p>DB 는 필요 없다. <b>이 전개를 SQL 이 아니라 순수 함수에 둔 이유가 여기 있다</b> —
 * "적팀끼리만 쌍을 만든다" 나 "양방향 가중치가 같다" 같은 것은 조인 하나만 어긋나도 깨지는데,
 * SQL 안에 있으면 실제 DB 없이는 확인할 수 없고 결과는 여전히 그럴듯한 승률로 나온다.
 *
 * <p>라인이 없는 게임이라 카운터의 정의는 <b>"적팀에 같이 있었다"</b> 하나뿐이다 (D4).
 */
class MatchupAggregatorTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(10, 24, 15, 3, 2, 12);
    private static final ReferencePoint NOW = new ReferencePoint(0, Map.of());

    private static MatchObservation.Participant p(int championId) {
        return new MatchObservation.Participant(championId, 0);
    }

    /** 승리 팀 1·2·3·4 / 패배 팀 5·6·7·8. */
    private static MatchObservation fourVersusFour() {
        return new MatchObservation(1L, 0,
                List.of(p(1), p(2), p(3), p(4)),
                List.of(p(5), p(6), p(7), p(8)));
    }

    @Test
    @DisplayName("4v4 한 경기에서 관측이 16개 나온다 — 승리 팀 4명 × 패배 팀 4명")
    void aggregate_fourVersusFourGivesSixteenOutcomes() {
        MatchupAggregate result = MatchupAggregator.aggregate(List.of(fourVersusFour()), NOW, CONFIG);

        assertThat(result.outcomes()).hasSize(16);
    }

    @Test
    @DisplayName("쌍은 양방향으로 적재된다 — 조회를 단순하게 하려는 의도된 중복이다")
    void aggregate_storesBothDirections() {
        MatchupAggregate result = MatchupAggregator.aggregate(List.of(fourVersusFour()), NOW, CONFIG);

        assertThat(result.pairs()).hasSize(32);
    }

    @Test
    @DisplayName("승패가 방향에 맞게 들어간다 — 이긴 쪽만 wins 가 1 이다")
    void aggregate_winsFollowDirection() {
        MatchupAggregate result = MatchupAggregator.aggregate(List.of(fourVersusFour()), NOW, CONFIG);

        WeightedTally winnerSide = result.pairs().get(new PairKey(1, 5));
        WeightedTally loserSide = result.pairs().get(new PairKey(5, 1));

        // 변조: 양방향에 똑같이 wins 를 1 로 넣으면 모든 쌍의 승률이 100% 가 된다.
        assertThat(winnerSide.games()).isEqualTo(1);
        assertThat(winnerSide.wins()).isEqualTo(1);
        assertThat(loserSide.games()).isEqualTo(1);
        assertThat(loserSide.wins()).isZero();
    }

    @Test
    @DisplayName("같은 팀 챔피언끼리는 쌍이 만들어지지 않는다 — 카운터는 적팀 관계다")
    void aggregate_neverPairsTeammates() {
        MatchupAggregate result = MatchupAggregator.aggregate(List.of(fourVersusFour()), NOW, CONFIG);

        // 변조: 참가자 전체를 한 목록으로 합쳐 모든 쌍을 만들면 아군끼리도 카운터가 생긴다.
        //       개수만 세는 위 테스트는 그 변조를 못 잡는다(개수가 늘 뿐 여전히 그럴듯하다).
        for (PairKey key : result.pairs().keySet()) {
            boolean bothWinners = key.championId() <= 4 && key.opponentId() <= 4;
            boolean bothLosers = key.championId() >= 5 && key.opponentId() >= 5;
            assertThat(bothWinners || bothLosers)
                    .as("같은 팀 쌍이 생겼다: " + key)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("양방향의 가중치는 같다 — 한 관측이 방향에 따라 다른 무게를 가질 수 없다")
    void aggregate_weightIsSymmetric() {
        MatchupAggregate result = MatchupAggregator.aggregate(List.of(fourVersusFour()), NOW, CONFIG);

        assertThat(result.pairs().get(new PairKey(1, 5)).weightedGames())
                .isCloseTo(result.pairs().get(new PairKey(5, 1)).weightedGames(), within(1e-12));
    }

    @Test
    @DisplayName("감쇠가 실제로 먹힌다 — 기준 시점에서 먼 경기가 가볍다")
    void aggregate_appliesDecay() {
        MatchObservation match = new MatchObservation(2L, 0, List.of(p(1)), List.of(p(5)));
        ReferencePoint distant = new ReferencePoint(24, Map.of());

        double recent = MatchupAggregator.aggregate(List.of(match), NOW, CONFIG)
                .pairs().get(new PairKey(1, 5)).weightedGames();
        double faded = MatchupAggregator.aggregate(List.of(match), distant, CONFIG)
                .pairs().get(new PairKey(1, 5)).weightedGames();

        // 변조: 가중치를 늘 1.0 으로 두면 두 값이 같아진다. 원시 games 만 보면 안 잡힌다.
        assertThat(recent).isCloseTo(1.0, within(1e-12));
        assertThat(faded).isCloseTo(0.25, within(1e-12));
    }

    @Test
    @DisplayName("두 챔피언의 변경 횟수를 합쳐 쌍의 가중치를 만든다 (D42)")
    void aggregate_usesPairDecay() {
        MatchObservation match = new MatchObservation(3L, 4,
                List.of(new MatchObservation.Participant(1, 0)),
                List.of(new MatchObservation.Participant(5, 0)));
        ReferencePoint ref = new ReferencePoint(4, Map.of(1, 1, 5, 1));

        double weighted = MatchupAggregator.aggregate(List.of(match), ref, CONFIG)
                .pairs().get(new PairKey(1, 5)).weightedGames();

        // 변조: 한쪽 챔피언의 변경만 보면 0.5^(1/2)=0.707 이 나온다. 합치면 0.5^(2/2)=0.5 다.
        assertThat(weighted).isCloseTo(DecayWeight.forPair(1, 1, 0, CONFIG), within(1e-12));
        assertThat(weighted).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("같은 쌍이 여러 경기에 나오면 누적된다")
    void aggregate_accumulatesAcrossMatches() {
        MatchObservation first = new MatchObservation(1L, 0, List.of(p(1)), List.of(p(5)));
        MatchObservation second = new MatchObservation(2L, 0, List.of(p(5)), List.of(p(1)));

        MatchupAggregate result = MatchupAggregator.aggregate(List.of(first, second), NOW, CONFIG);

        WeightedTally tally = result.pairs().get(new PairKey(1, 5));
        assertThat(tally.games()).isEqualTo(2);
        assertThat(tally.wins()).isEqualTo(1);
    }

    @Test
    @DisplayName("Bradley-Terry 관측의 가중치는 쌍의 가중치와 같다")
    void aggregate_outcomesCarryTheSameWeight() {
        MatchObservation match = new MatchObservation(1L, 0, List.of(p(1)), List.of(p(5)));
        ReferencePoint distant = new ReferencePoint(12, Map.of());

        MatchupAggregate result = MatchupAggregator.aggregate(List.of(match), distant, CONFIG);

        BradleyTerry.Outcome outcome = result.outcomes().get(0);
        assertThat(outcome.winnerId()).isEqualTo(1);
        assertThat(outcome.loserId()).isEqualTo(5);
        assertThat(outcome.weight()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("유효표본수는 가중치가 흩어질수록 경기 수보다 작아진다")
    void aggregate_effectiveSampleSizeReflectsSpread() {
        MatchObservation recent = new MatchObservation(1L, 12, List.of(p(1)), List.of(p(5)));
        MatchObservation old = new MatchObservation(2L, 0, List.of(p(1)), List.of(p(5)));
        ReferencePoint ref = new ReferencePoint(12, Map.of());

        WeightedTally tally = MatchupAggregator.aggregate(List.of(recent, old), ref, CONFIG)
                .pairs().get(new PairKey(1, 5));

        assertThat(tally.games()).isEqualTo(2);
        assertThat(tally.ess()).isLessThan(2.0).isGreaterThan(1.0);
    }

    @Test
    @DisplayName("패치가 배정되지 않은 경기도 집계된다 — 가장 오래된 데이터로 취급한다")
    void aggregate_handlesMatchesWithoutPatch() {
        MatchObservation match = new MatchObservation(1L, null, List.of(p(1)), List.of(p(5)));
        ReferencePoint ref = new ReferencePoint(12, Map.of());

        WeightedTally tally = MatchupAggregator.aggregate(List.of(match), ref, CONFIG)
                .pairs().get(new PairKey(1, 5));

        assertThat(tally.games()).isEqualTo(1);
        assertThat(tally.weightedGames()).isCloseTo(0.5, within(1e-12));
    }

    @Test
    @DisplayName("빈 입력은 빈 결과다")
    void aggregate_emptyInput() {
        MatchupAggregate result = MatchupAggregator.aggregate(List.of(), NOW, CONFIG);

        assertThat(result.pairs()).isEmpty();
        assertThat(result.outcomes()).isEmpty();
    }
}
