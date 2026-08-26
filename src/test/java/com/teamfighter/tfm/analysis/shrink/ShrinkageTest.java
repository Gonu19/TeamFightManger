package com.teamfighter.tfm.analysis.shrink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 2단 축소를 못 박는다 (D15b).
 *
 * <p>DB 는 필요 없다 — 순수 함수다.
 *
 * <p>축소 대상이 3단 계단을 이룬다: <b>패치 추정 → 전체 누적 추정 → 챔피언 강도 기대값.</b>
 * 데이터가 없을수록 위로 올라가며 "모르면 아는 것에 가깝게" 답한다. 그 계단이 실제로
 * 이어지는지가 이 테스트의 본론이다 — 한 칸이 끊겨도 값은 여전히 0~1 사이라 그럴듯해 보인다.
 */
class ShrinkageTest {

    private static final double K0 = 24;
    private static final double K1 = 15;
    private static final double EPS = 1e-12;

    @Test
    @DisplayName("1단 — 데이터가 없으면 기대값 그대로다")
    void overall_noDataFallsBackToExpected() {
        assertThat(Shrinkage.overall(0, 0, 0.428, K0)).isCloseTo(0.428, within(EPS));
    }

    @Test
    @DisplayName("1단 — 관측이 k0 만큼이면 관측과 기대값의 정확히 중간이다")
    void overall_atPriorStrengthLandsHalfway() {
        // 24경기 전승(1.0) + k0=24 · 기대 0.5 → (24 + 12) / 48 = 0.75
        assertThat(Shrinkage.overall(24, 24, 0.5, K0)).isCloseTo(0.75, within(EPS));
    }

    @Test
    @DisplayName("1단 — 데이터가 많아질수록 관측 승률로 수렴한다")
    void overall_convergesToObservedWithMoreData() {
        double small = Shrinkage.overall(10, 10, 0.5, K0);
        double large = Shrinkage.overall(1000, 1000, 0.5, K0);

        assertThat(large).isGreaterThan(small);
        assertThat(large).isCloseTo(1.0, within(0.03));
    }

    @Test
    @DisplayName("1단 — 추정은 항상 관측과 기대값 사이에 있다")
    void overall_staysBetweenObservedAndExpected() {
        for (int games = 1; games <= 60; games++) {
            double observed = 0.9;
            double expected = 0.4;
            double adjusted = Shrinkage.overall(games * observed, games, expected, K0);

            // 변조: 분모에서 k0 를 빼면(=raw 승률) 이 단언은 여전히 통과한다 — 경계값이기 때문이다.
            //       그래서 위의 "정확히 중간" 테스트가 반드시 짝을 이뤄야 한다.
            assertThat(adjusted).isBetween(expected, observed);
        }
    }

    @Test
    @DisplayName("2단 — 그 패치에 데이터가 없으면 전체 누적 추정 그대로다")
    void byPatch_noPatchDataFallsBackToOverall() {
        assertThat(Shrinkage.byPatch(0, 0, 0.541, K1)).isCloseTo(0.541, within(EPS));
    }

    @Test
    @DisplayName("2단 — 패치 데이터가 많으면 그 패치 관측이 지배한다")
    void byPatch_patchDataDominatesWhenPlentiful() {
        double sparse = Shrinkage.byPatch(5, 5, 0.5, K1);
        double plenty = Shrinkage.byPatch(200, 200, 0.5, K1);

        assertThat(plenty).isGreaterThan(sparse);
        assertThat(plenty).isCloseTo(1.0, within(0.08));
    }

    @Test
    @DisplayName("3단 계단 — 패치도 전체도 비어 있으면 챔피언 강도 기대값까지 올라간다")
    void twoStage_emptyAtEveryLevelReachesExpected() {
        double expected = 0.428;

        double overall = Shrinkage.overall(0, 0, expected, K0);
        double patch = Shrinkage.byPatch(0, 0, overall, K1);

        // 변조: 1단의 목표값을 기대값 대신 상수 0.5 로 바꾸면 이 단언이 깨진다.
        //       "모르면 0.5" 와 "모르면 챔피언 강도" 는 다른 답이다 (D14).
        assertThat(patch).isCloseTo(expected, within(EPS));
    }

    @Test
    @DisplayName("3단 계단 — 패치가 비고 전체만 있으면 전체 관측 쪽으로 간다")
    void twoStage_patchEmptyUsesOverallObservation() {
        double overall = Shrinkage.overall(40, 50, 0.4, K0);
        double patch = Shrinkage.byPatch(0, 0, overall, K1);

        assertThat(patch).isCloseTo(overall, within(EPS));
        assertThat(patch).isGreaterThan(0.4);
    }

    @Test
    @DisplayName("승수가 경기 수를 넘으면 던진다 — 조인이 잘못되면 조용히 승률 100% 초과가 나온다")
    void overall_winsAboveGamesThrows() {
        assertThatThrownBy(() -> Shrinkage.overall(11, 10, 0.5, K0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Shrinkage.byPatch(11, 10, 0.5, K1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("음수 표본이나 범위 밖 목표값은 던진다")
    void overall_invalidInputThrows() {
        assertThatThrownBy(() -> Shrinkage.overall(-1, 10, 0.5, K0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Shrinkage.overall(1, -10, 0.5, K0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Shrinkage.overall(1, 10, 1.5, K0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Shrinkage.overall(1, 10, 0.5, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("표본도 0 이고 축소 강도도 0 이면 던진다 — 0/0 이 조용히 NaN 으로 새어 나간다")
    void overall_zeroSampleAndZeroPriorThrows() {
        // 변조: 이 가드를 지우면 NaN 이 그대로 adjusted_win_rate 에 저장되고,
        //       정렬에서 NaN 은 비교 결과가 전부 false 라 순위표가 조용히 뒤죽박죽이 된다.
        assertThatThrownBy(() -> Shrinkage.overall(0, 0, 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("축소 강도가 0 이면 관측 승률 그대로다")
    void overall_zeroPriorGivesRawRate() {
        assertThat(Shrinkage.overall(7, 10, 0.5, 0)).isCloseTo(0.7, within(EPS));
    }

    @Test
    @DisplayName("유효표본수 — 가중치가 전부 1 이면 경기 수와 같다")
    void ess_uniformWeightsEqualCount() {
        assertThat(Shrinkage.ess(10, 10)).isCloseTo(10.0, within(EPS));
    }

    @Test
    @DisplayName("유효표본수 — 가중치가 흩어지면 경기 수보다 작다")
    void ess_unevenWeightsShrinkBelowCount() {
        // w = [1, 0.5, 0.25] → Σw = 1.75, Σw² = 1.3125 → ess = 2.333 < 3
        double sumW = 1 + 0.5 + 0.25;
        double sumW2 = 1 + 0.25 + 0.0625;

        double ess = Shrinkage.ess(sumW, sumW2);

        // 변조: ess 를 Σw 로 바꾸면 1.75 가 나와 이 단언이 깨진다.
        assertThat(ess).isCloseTo(2.3333333333, within(1e-9));
        assertThat(ess).isLessThan(3.0);
    }

    @Test
    @DisplayName("유효표본수 — 표본이 없으면 0 이다")
    void ess_emptySampleIsZero() {
        assertThat(Shrinkage.ess(0, 0)).isEqualTo(0.0);
    }

    @Test
    @DisplayName("유효표본수 — 음수 입력은 던진다")
    void ess_negativeInputThrows() {
        assertThatThrownBy(() -> Shrinkage.ess(-1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Shrinkage.ess(1, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
