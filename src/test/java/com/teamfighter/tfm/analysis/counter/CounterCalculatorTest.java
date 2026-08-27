package com.teamfighter.tfm.analysis.counter;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import com.teamfighter.tfm.analysis.MatchObservation;
import com.teamfighter.tfm.analysis.ReferencePoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 카운터 최종 계산을 못 박는다 — <b>챔피언 강도를 빼고 본 상성</b> (D14).
 *
 * <p>DB 는 필요 없다. 여기가 이 프로젝트에서 가장 조용히 틀릴 수 있는 자리다.
 * 챔피언 강도를 안 걷어내면 결과는 카운터표가 아니라 <b>센 챔피언 목록</b>이 되는데,
 * 그 표도 그럴듯하게 읽힌다 — "Werewolf 가 거의 모든 상대에서 상위" 는 틀린 게 아니라
 * 질문에 답을 안 한 것이다.
 */
class CounterCalculatorTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(10, 24, 15, 3, 2, 12);
    private static final ReferencePoint NOW = new ReferencePoint(0, Map.of());

    /** 감쇠가 끼어들지 않는 1대1 경기. 이 테스트가 보는 것은 감쇠가 아니라 강도 보정이다. */
    private static void addMatches(
            List<MatchObservation> out, int winner, int loser, int times) {
        for (int i = 0; i < times; i++) {
            out.add(new MatchObservation(out.size() + 1L, 0,
                    List.of(new MatchObservation.Participant(winner, 0)),
                    List.of(new MatchObservation.Participant(loser, 0))));
        }
    }

    /**
     * 챔피언 1 은 강하고 2 는 약한데, <b>둘 다 챔피언 3 을 상대로는 똑같이 75%</b> 다.
     * raw 승률로는 구별할 수 없고, 강도를 걷어내야만 2 의 상성이 드러난다.
     */
    private static List<CounterRow> strongAndWeakAgainstTheSameOpponent() {
        List<MatchObservation> matches = new ArrayList<>();
        addMatches(matches, 1, 4, 30);
        addMatches(matches, 4, 1, 10);
        addMatches(matches, 1, 5, 30);
        addMatches(matches, 5, 1, 10);
        addMatches(matches, 2, 4, 10);
        addMatches(matches, 4, 2, 30);
        addMatches(matches, 2, 5, 10);
        addMatches(matches, 5, 2, 30);
        addMatches(matches, 1, 3, 15);
        addMatches(matches, 3, 1, 5);
        addMatches(matches, 2, 3, 15);
        addMatches(matches, 3, 2, 5);
        addMatches(matches, 3, 4, 20);
        addMatches(matches, 4, 3, 20);
        addMatches(matches, 3, 5, 20);
        addMatches(matches, 5, 3, 20);
        addMatches(matches, 4, 5, 20);
        addMatches(matches, 5, 4, 20);

        return CounterCalculator.calculate(
                MatchupAggregator.aggregate(matches, NOW, CONFIG), CONFIG);
    }

    private static CounterRow find(List<CounterRow> rows, int championId, int opponentId) {
        return rows.stream()
                .filter(r -> r.championId() == championId && r.opponentId() == opponentId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("쌍이 없다: " + championId + " vs " + opponentId));
    }

    @Test
    @DisplayName("raw 승률이 같아도 약한 챔피언의 상성 이득이 더 크다 — 이게 D14 의 전부다")
    void calculate_removesChampionStrengthFromMatchup() {
        List<CounterRow> rows = strongAndWeakAgainstTheSameOpponent();

        CounterRow strongVsThree = find(rows, 1, 3);
        CounterRow weakVsThree = find(rows, 2, 3);

        // 원본 승률은 완전히 같다 — raw 정렬로는 두 쌍을 구별할 수 없다.
        assertThat(strongVsThree.rawWinRate()).isCloseTo(0.75, within(1e-12));
        assertThat(weakVsThree.rawWinRate()).isCloseTo(0.75, within(1e-12));

        // 변조: counter_effect 를 adjusted 그대로(기대 승률을 안 뺌) 두면 두 값이 같아진다.
        assertThat(weakVsThree.counterEffect()).isGreaterThan(strongVsThree.counterEffect());
    }

    @Test
    @DisplayName("센 챔피언의 기대 승률이 더 높다 — 그만큼이 상성이 아니라 강도다")
    void calculate_expectedReflectsBaseStrength() {
        List<CounterRow> rows = strongAndWeakAgainstTheSameOpponent();

        assertThat(find(rows, 1, 3).expectedWinRate())
                .isGreaterThan(find(rows, 2, 3).expectedWinRate());
    }

    @Test
    @DisplayName("상성 이득 = 축소추정 − 기대 승률. 항등식이 정확히 성립한다")
    void calculate_counterEffectIsAdjustedMinusExpected() {
        for (CounterRow row : strongAndWeakAgainstTheSameOpponent()) {
            assertThat(row.counterEffect())
                    .isCloseTo(row.adjustedWinRate() - row.expectedWinRate(), within(1e-12));
        }
    }

    @Test
    @DisplayName("양방향의 상성 이득은 부호만 반대다 — 한쪽의 이득은 다른 쪽의 손해다")
    void calculate_counterEffectIsAntisymmetric() {
        List<CounterRow> rows = strongAndWeakAgainstTheSameOpponent();

        // 변조: 축소의 목표값을 방향마다 따로(예: 둘 다 0.5) 두면 이 대칭이 깨진다.
        assertThat(find(rows, 1, 3).counterEffect())
                .isCloseTo(-find(rows, 3, 1).counterEffect(), within(1e-9));
        assertThat(find(rows, 1, 3).adjustedWinRate() + find(rows, 3, 1).adjustedWinRate())
                .isCloseTo(1.0, within(1e-9));
    }

    @Test
    @DisplayName("표본이 적으면 추정이 기대 승률 쪽으로 당겨진다 — 상성 이득이 거의 0 이 된다")
    void calculate_smallSampleShrinksTowardExpected() {
        List<MatchObservation> few = new ArrayList<>();
        addMatches(few, 1, 2, 2);
        List<CounterRow> rows = CounterCalculator.calculate(
                MatchupAggregator.aggregate(few, NOW, CONFIG), CONFIG);

        CounterRow row = find(rows, 1, 2);

        assertThat(row.rawWinRate()).isEqualTo(1.0);
        // 변조: 축소를 건너뛰고 raw 를 adjusted 로 쓰면 상성 이득이 크게 나온다.
        //       2경기 100% 가 상위로 올라오는 것이 정확히 D9·D10 이 막으려던 것이다.
        assertThat(Math.abs(row.counterEffect())).isLessThan(0.1);
    }

    @Test
    @DisplayName("원시 카운트와 가중 합계를 둘 다 내려보낸다 — 화면이 둘 다 쓴다 (D24)")
    void calculate_carriesRawAndWeightedCounts() {
        CounterRow row = find(strongAndWeakAgainstTheSameOpponent(), 1, 3);

        assertThat(row.games()).isEqualTo(20);
        assertThat(row.wins()).isEqualTo(15);
        assertThat(row.weightedGames()).isCloseTo(20.0, within(1e-9));
        assertThat(row.ess()).isCloseTo(20.0, within(1e-9));
    }

    @Test
    @DisplayName("실제로 붙은 쌍만 양방향으로 나온다 — 안 붙은 쌍을 지어내지 않는다")
    void calculate_emitsEveryObservedPairBothWays() {
        List<CounterRow> rows = strongAndWeakAgainstTheSameOpponent();

        // 챔피언 1 과 2 는 서로 만난 적이 없다. 관측된 쌍은 9개 → 양방향 18행.
        // 변조: 등장한 챔피언 전체로 곱집합을 만들면 20행이 되고, 붙은 적 없는 쌍에도
        //       기대 승률만으로 채워진 상성이 생긴다 — 근거 없는 값이 표에 올라온다.
        assertThat(rows).hasSize(18);
        assertThat(rows).noneMatch(row -> row.championId() == 1 && row.opponentId() == 2);
        assertThat(rows).allSatisfy(row ->
                assertThat(row.championId()).isNotEqualTo(row.opponentId()));
    }

    @Test
    @DisplayName("추정 승률과 기대 승률은 항상 0~1 안에 있다")
    void calculate_ratesStayInRange() {
        for (CounterRow row : strongAndWeakAgainstTheSameOpponent()) {
            assertThat(row.adjustedWinRate()).isBetween(0.0, 1.0);
            assertThat(row.expectedWinRate()).isBetween(0.0, 1.0);
        }
    }

    @Test
    @DisplayName("쌍에는 있는데 BT 관측에 없는 챔피언을 만나면 던진다 — 조용히 평균 강도로 채우지 않는다")
    void calculate_pairWithoutStrengthThrows() {
        // aggregate() 를 거치면 쌍과 관측이 같은 루프에서 만들어져 이런 상태가 안 나온다.
        // 그런데 MatchupAggregate 는 공개 타입이라 만들 수 있고, 앞으로 관측 쪽에만
        // 필터가 붙으면 실제로 이렇게 된다.
        MatchupAggregate inconsistent = new MatchupAggregate(
                Map.of(new PairKey(1, 2), WeightedTally.EMPTY.plus(true, 1.0)),
                List.of());

        // 변조: 없는 강도를 1.0 으로 채우면 붙은 적도 없는 근거로 기대 승률 0.5 가 나오고,
        //       상성 이득이 그럴듯한 값으로 표에 올라온다.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> CounterCalculator.calculate(inconsistent, CONFIG))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1");
    }

    @Test
    @DisplayName("빈 입력은 빈 결과다")
    void calculate_emptyInput() {
        MatchupAggregate empty = MatchupAggregator.aggregate(List.of(), NOW, CONFIG);

        assertThat(CounterCalculator.calculate(empty, CONFIG)).isEmpty();
    }
}
