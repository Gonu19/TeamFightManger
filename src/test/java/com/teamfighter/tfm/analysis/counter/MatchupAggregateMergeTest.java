package com.teamfighter.tfm.analysis.counter;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.MatchObservation;
import com.teamfighter.tfm.analysis.ReferencePoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 슬롯을 합치는 일을 못 박는다 — {@code GLOBAL} 스코프 (D45).
 *
 * <p>DB 는 필요 없다.
 *
 * <p>슬롯마다 패치 역사가 따로 생성되므로 슬롯을 가로지르는 패치 축이 없다(D24).
 * 그래서 {@code GLOBAL} 은 <b>슬롯별로 그 슬롯의 기준 시점으로 감쇠한 뒤 합산한다.</b>
 * 합치고 나서 감쇠하면 어느 커리어의 몇 번째 패치를 기준으로 삼아야 할지 정할 수 없다.
 */
class MatchupAggregateMergeTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(10, 24, 15, 3, 2, 12);

    private static MatchObservation match(long id, Integer patchSeq, int winner, int loser) {
        return new MatchObservation(id, patchSeq,
                List.of(new MatchObservation.Participant(winner, 0)),
                List.of(new MatchObservation.Participant(loser, 0)));
    }

    @Test
    @DisplayName("두 집계를 합치면 원시 카운트와 가중 합계가 더해진다")
    void merge_addsCounts() {
        MatchupAggregate first = MatchupAggregator.aggregate(
                List.of(match(1L, 0, 1, 2)), new ReferencePoint(0, Map.of()), CONFIG);
        MatchupAggregate second = MatchupAggregator.aggregate(
                List.of(match(2L, 0, 1, 2)), new ReferencePoint(0, Map.of()), CONFIG);

        MatchupAggregate merged = MatchupAggregate.merge(List.of(first, second));

        WeightedTally tally = merged.pairs().get(new PairKey(1, 2));
        assertThat(tally.games()).isEqualTo(2);
        assertThat(tally.wins()).isEqualTo(2);
        assertThat(tally.weightedGames()).isCloseTo(2.0, within(1e-12));
    }

    @Test
    @DisplayName("Bradley-Terry 관측도 이어붙는다 — 기저 강도는 전체를 보고 잡아야 한다")
    void merge_concatenatesOutcomes() {
        MatchupAggregate first = MatchupAggregator.aggregate(
                List.of(match(1L, 0, 1, 2)), new ReferencePoint(0, Map.of()), CONFIG);
        MatchupAggregate second = MatchupAggregator.aggregate(
                List.of(match(2L, 0, 3, 4)), new ReferencePoint(0, Map.of()), CONFIG);

        MatchupAggregate merged = MatchupAggregate.merge(List.of(first, second));

        // 변조: 한쪽 관측만 남기면 다른 커리어의 챔피언이 강도 없이 남아
        //       CounterCalculator 가 던진다. 그래도 여기서 개수로 먼저 잡는다.
        assertThat(merged.outcomes()).hasSize(2);
    }

    @Test
    @DisplayName("슬롯마다 자기 기준 시점으로 깎인 값이 합쳐진다 — 합친 뒤에 감쇠하지 않는다 (D45)")
    void merge_preservesPerSlotDecay() {
        // 커리어 A: 기준이 경기와 같은 패치라 안 깎인다.
        MatchupAggregate fresh = MatchupAggregator.aggregate(
                List.of(match(1L, 5, 1, 2)), new ReferencePoint(5, Map.of()), CONFIG);
        // 커리어 B: 기준이 12패치 뒤라 절반이 된다.
        MatchupAggregate faded = MatchupAggregator.aggregate(
                List.of(match(2L, 0, 1, 2)), new ReferencePoint(12, Map.of()), CONFIG);

        MatchupAggregate merged = MatchupAggregate.merge(List.of(fresh, faded));

        WeightedTally tally = merged.pairs().get(new PairKey(1, 2));
        // 변조: 두 슬롯의 경기를 한 목록으로 합쳐 하나의 기준 시점으로 감쇠하면
        //       1.5 가 아닌 다른 값이 나온다. 원시 games 는 2로 같아서 안 잡힌다.
        assertThat(tally.games()).isEqualTo(2);
        assertThat(tally.weightedGames()).isCloseTo(1.5, within(1e-12));
    }

    @Test
    @DisplayName("유효표본수도 합산된 가중치로 계산된다")
    void merge_effectiveSampleSizeUsesCombinedWeights() {
        MatchupAggregate fresh = MatchupAggregator.aggregate(
                List.of(match(1L, 5, 1, 2)), new ReferencePoint(5, Map.of()), CONFIG);
        MatchupAggregate faded = MatchupAggregator.aggregate(
                List.of(match(2L, 0, 1, 2)), new ReferencePoint(12, Map.of()), CONFIG);

        WeightedTally tally = MatchupAggregate.merge(List.of(fresh, faded))
                .pairs().get(new PairKey(1, 2));

        // (1 + 0.5)² / (1² + 0.5²) = 2.25 / 1.25 = 1.8
        assertThat(tally.ess()).isCloseTo(1.8, within(1e-12));
    }

    @Test
    @DisplayName("빈 목록을 합치면 빈 집계다")
    void merge_emptyList() {
        MatchupAggregate merged = MatchupAggregate.merge(List.of());

        assertThat(merged.pairs()).isEmpty();
        assertThat(merged.outcomes()).isEmpty();
    }

    @Test
    @DisplayName("하나만 합치면 그것 그대로다")
    void merge_singleAggregate() {
        MatchupAggregate only = MatchupAggregator.aggregate(
                List.of(match(1L, 0, 1, 2)), new ReferencePoint(0, Map.of()), CONFIG);

        MatchupAggregate merged = MatchupAggregate.merge(List.of(only));

        assertThat(merged.pairs()).isEqualTo(only.pairs());
        assertThat(merged.outcomes()).hasSize(1);
    }
}
