package com.teamfighter.tfm.analysis.pair;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 가중 릿지가 <b>무게 없이 돌던 때와 같은 답</b>을 내는가, 그리고 무게가 실제로 미는가.
 *
 * <p>이 시험이 있는 이유는 D78 이 바꾼 것이 갱신식의 <b>분모</b>이기 때문이다.
 * 분모가 "행의 개수" 에서 "무게의 합" 으로 바뀌었는데, 무게가 전부 1 이면 두 값이 같다.
 * 그 등가가 깨지면 D63~D65 의 측정(t 22.11 · 상위 쌍 표)이 더 이상 이 앱의 화면을
 * 설명하지 못하는데, <b>숫자는 여전히 그럴듯하게 나온다.</b>
 */
class RidgeFitTest {

    /** 특성 둘, 행마다 하나씩만 켜진다 — 계수를 손으로 검산할 수 있는 가장 단순한 모양. */
    private static RidgeFit.Row row(int feature, double target, double weight) {
        return new RidgeFit.Row(new int[] {feature}, target, weight);
    }

    @Test
    @DisplayName("무게가 전부 1 이면 무게를 안 쓰던 때와 같은 답이다")
    void unitWeightsReproduceTheUnweightedFit() {
        // λ=0, 특성 하나에 행 넷이면 답은 그냥 평균이다.
        List<RidgeFit.Row> rows = List.of(
                row(0, 2.0, 1.0), row(0, 4.0, 1.0), row(0, 6.0, 1.0), row(0, 8.0, 1.0));

        double[] theta = RidgeFit.fit(rows, 1, new double[] {0.0});

        assertThat(theta[0]).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("무게 생략 생성자는 무게 1 이다")
    void theShorthandConstructorMeansWeightOne() {
        // 시험·교차검증이 이 입구를 쓴다. 여기가 1 이 아니면 감쇠가 안 걸려야 할 자리에
        // 조용히 걸린다.
        assertThat(new RidgeFit.Row(new int[] {0}, 1.0).weight()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("무게는 가중평균으로 민다 — 무거운 행 쪽으로 계수가 끌린다")
    void weightsPullTheCoefficientTowardTheHeavyRows() {
        // 값 0 이 무게 1, 값 10 이 무게 3 → 가중평균 7.5. 산술평균이면 5 다.
        List<RidgeFit.Row> rows = List.of(row(0, 0.0, 1.0), row(0, 10.0, 3.0));

        double[] theta = RidgeFit.fit(rows, 1, new double[] {0.0});

        assertThat(theta[0]).isCloseTo(7.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    @DisplayName("무게를 반으로 줄이면 행을 반으로 줄인 것과 같다")
    void halvingEveryWeightIsTheSameAsHalvingTheSampleAgainstTheRidge() {
        // λ 가 고정된 채 무게가 줄면 계수는 릿지 쪽(0)으로 더 끌린다. 이것이
        // "오래된 쌍이 조용해진다" 의 정확한 뜻이다.
        List<RidgeFit.Row> heavy = List.of(row(0, 10.0, 1.0), row(0, 10.0, 1.0));
        List<RidgeFit.Row> light = List.of(row(0, 10.0, 0.5), row(0, 10.0, 0.5));

        double[] withHeavy = RidgeFit.fit(heavy, 1, new double[] {2.0});
        double[] withLight = RidgeFit.fit(light, 1, new double[] {2.0});

        // 무거운 쪽: 20/(2+2) = 5.  가벼운 쪽: 10/(1+2) = 3.33…
        assertThat(withHeavy[0]).isCloseTo(5.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(withLight[0]).isCloseTo(10.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(withLight[0]).isLessThan(withHeavy[0]);
    }

    @Test
    @DisplayName("여러 특성이 겹쳐도 가중 최소제곱의 답으로 수렴한다")
    void overlappingFeaturesConvergeToTheWeightedLeastSquaresSolution() {
        // 두 특성이 함께 켜진 행이 있으면 좌표하강이 여러 번 돌아야 한다.
        // λ 를 크게 줘서 답이 유일해지도록 하고, 무게를 바꿔 결과가 움직이는지만 본다.
        List<RidgeFit.Row> rows = new ArrayList<>();
        rows.add(new RidgeFit.Row(new int[] {0, 1}, 6.0, 1.0));
        rows.add(new RidgeFit.Row(new int[] {0}, 2.0, 1.0));
        rows.add(new RidgeFit.Row(new int[] {1}, 4.0, 1.0));

        double[] even = RidgeFit.fit(rows, 2, new double[] {1.0, 1.0});

        List<RidgeFit.Row> tilted = new ArrayList<>(rows);
        tilted.set(1, new RidgeFit.Row(new int[] {0}, 2.0, 0.01));   // 두 번째 행을 거의 지운다

        double[] skewed = RidgeFit.fit(tilted, 2, new double[] {1.0, 1.0});

        // 특성 0 을 지지하던 행이 사라졌으니 그 계수는 움직여야 한다.
        assertThat(skewed[0]).isNotCloseTo(even[0], org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    @DisplayName("음수 무게는 만들 수 없다")
    void negativeWeightsAreRejected() {
        // 음수는 잔차를 반대로 밀어 적합이 발산한다. D15a 가 경고한 뺄셈 뒤집기가
        // 여기까지 흘러오면 값이 (0,1] 밖으로 나가는데, 눈으로는 못 잡는다.
        assertThatThrownBy(() -> new RidgeFit.Row(new int[] {0}, 1.0, -0.5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RidgeFit.Row(new int[] {0}, 1.0, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("관측 가중치는 (0,1] 밖을 거부한다")
    void observationWeightsMustBeADecay() {
        // 0 이면 그 행이 적합에 아무 기여도 안 하는데 관측 수에는 세어진다 —
        // 화면이 "40경기" 라고 말하면서 실제로는 0경기로 적합한 값이 나간다.
        assertThatThrownBy(() -> new PairObservation(1, "t1", List.of(2), List.of(3), 5.0, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        // 1 을 넘으면 감쇠가 아니라 증폭이다.
        assertThatThrownBy(() -> new PairObservation(1, "t1", List.of(2), List.of(3), 5.0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(new PairObservation(1, "t1", List.of(2), List.of(3), 5.0).weight())
                .isEqualTo(1.0);
    }
}
