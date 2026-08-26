package com.teamfighter.tfm.analysis.strength;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Bradley-Terry 기저 강도 적합을 못 박는다 (D14).
 *
 * <p>DB 는 필요 없다 — 승패 목록에서 숫자를 뽑는 일뿐이다.
 *
 * <p>이 값이 하는 일은 하나다: <b>"챔피언 자체 강도만으로 예상되는 승률"</b> 을 만들어
 * 실제 승률에서 빼는 것. 여기가 틀리면 카운터표가 "센 챔피언 목록" 으로 되돌아간다 —
 * 그런데 그 표도 그럴듯해 보이기 때문에 눈으로는 못 잡는다.
 */
class BradleyTerryTest {

    private static final double EPS = 1e-9;

    private static List<BradleyTerry.Outcome> repeat(int winner, int loser, int times) {
        List<BradleyTerry.Outcome> out = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            out.add(new BradleyTerry.Outcome(winner, loser, 1.0));
        }
        return out;
    }

    @Test
    @DisplayName("5승 5패면 두 강도가 같다")
    void fit_symmetricDataGivesEqualStrength() {
        List<BradleyTerry.Outcome> outcomes = new ArrayList<>(repeat(1, 2, 5));
        outcomes.addAll(repeat(2, 1, 5));

        Map<Integer, Double> s = BradleyTerry.fit(outcomes);

        assertThat(s.get(1)).isCloseTo(s.get(2), within(1e-6));
    }

    @Test
    @DisplayName("기대 승률은 강도의 비다 — 같은 강도면 0.5")
    void expected_equalStrengthIsCoinFlip() {
        assertThat(BradleyTerry.expected(1.0, 1.0)).isCloseTo(0.5, within(EPS));
        assertThat(BradleyTerry.expected(3.7, 3.7)).isCloseTo(0.5, within(EPS));
    }

    @Test
    @DisplayName("강도를 승산으로 주면 D14 의 공식과 정확히 같은 값이 나온다")
    void expected_matchesDocumentedFormula() {
        double pa = 0.6;
        double pb = 0.4;
        double documented = pa * (1 - pb) / (pa * (1 - pb) + pb * (1 - pa));

        double actual = BradleyTerry.expected(pa / (1 - pa), pb / (1 - pb));

        // 변조: expected 를 s_A/(s_A+s_B) 대신 단순 평균이나 로지스틱 차이로 바꾸면 어긋난다.
        assertThat(actual).isCloseTo(documented, within(EPS));
        assertThat(actual).isCloseTo(0.6923076923, within(1e-9));
    }

    @Test
    @DisplayName("강도를 전부 같은 배로 키워도 기대 승률은 안 변한다 — 절대 크기에는 의미가 없다")
    void expected_isScaleInvariant() {
        assertThat(BradleyTerry.expected(2.0, 6.0))
                .isCloseTo(BradleyTerry.expected(20.0, 60.0), within(EPS));
    }

    @Test
    @DisplayName("A>B, B>C 면 강도도 A>B>C 로 줄 선다")
    void fit_isTransitive() {
        List<BradleyTerry.Outcome> outcomes = new ArrayList<>(repeat(1, 2, 8));
        outcomes.addAll(repeat(2, 1, 2));
        outcomes.addAll(repeat(2, 3, 8));
        outcomes.addAll(repeat(3, 2, 2));

        Map<Integer, Double> s = BradleyTerry.fit(outcomes);

        assertThat(s.get(1)).isGreaterThan(s.get(2));
        assertThat(s.get(2)).isGreaterThan(s.get(3));
    }

    @Test
    @DisplayName("전승 챔피언도 강도가 유한하다 — 정규화가 없으면 발산한다")
    void fit_undefeatedChampionStaysFinite() {
        // 변조: 가상 관측(정규화)을 빼면 s(1) 이 무한대로 발산하거나 반복 한도에서 예외가 난다.
        Map<Integer, Double> s = BradleyTerry.fit(repeat(1, 2, 10));

        assertThat(s.get(1)).isFinite().isPositive();
        assertThat(s.get(2)).isFinite().isPositive();
        assertThat(s.get(1)).isGreaterThan(s.get(2));
    }

    @Test
    @DisplayName("서로 붙은 적 없는 두 무리도 각각 계산된다 — 비교 그래프가 끊겨도 죽지 않는다")
    void fit_handlesDisconnectedGroups() {
        List<BradleyTerry.Outcome> outcomes = new ArrayList<>(repeat(1, 2, 6));
        outcomes.addAll(repeat(3, 4, 6));

        Map<Integer, Double> s = BradleyTerry.fit(outcomes);

        assertThat(s).containsOnlyKeys(1, 2, 3, 4);
        assertThat(s.values()).allMatch(v -> Double.isFinite(v) && v > 0);
        assertThat(s.get(1)).isGreaterThan(s.get(2));
        assertThat(s.get(3)).isGreaterThan(s.get(4));
    }

    @Test
    @DisplayName("가중치가 낮으면 같은 승패라도 강도 차이가 덜 벌어진다 — 감쇠가 실제로 먹힌다")
    void fit_respectsWeights() {
        List<BradleyTerry.Outcome> heavy = new ArrayList<>();
        List<BradleyTerry.Outcome> light = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            heavy.add(new BradleyTerry.Outcome(1, 2, 1.0));
            light.add(new BradleyTerry.Outcome(1, 2, 0.1));
        }

        double heavyRatio = ratio(BradleyTerry.fit(heavy));
        double lightRatio = ratio(BradleyTerry.fit(light));

        // 변조: weight 를 무시하고 1.0 으로 세면 두 값이 같아진다.
        assertThat(lightRatio).isLessThan(heavyRatio);
    }

    private static double ratio(Map<Integer, Double> s) {
        return s.get(1) / s.get(2);
    }

    @Test
    @DisplayName("입력 순서가 달라도 같은 결과가 나온다")
    void fit_isOrderIndependent() {
        List<BradleyTerry.Outcome> outcomes = new ArrayList<>(repeat(1, 2, 7));
        outcomes.addAll(repeat(2, 3, 5));
        outcomes.addAll(repeat(3, 1, 3));
        Map<Integer, Double> first = BradleyTerry.fit(outcomes);

        List<BradleyTerry.Outcome> shuffled = new ArrayList<>(outcomes);
        Collections.shuffle(shuffled, new java.util.Random(42));
        Map<Integer, Double> second = BradleyTerry.fit(shuffled);

        for (Integer id : first.keySet()) {
            assertThat(second.get(id)).isCloseTo(first.get(id), within(1e-9));
        }
    }

    @Test
    @DisplayName("빈 입력은 빈 결과다 — 예외로 기동을 막지 않는다")
    void fit_emptyInputGivesEmptyResult() {
        assertThat(BradleyTerry.fit(List.of())).isEmpty();
    }

    @Test
    @DisplayName("자기 자신을 이긴 관측은 던진다 — 같은 경기에 같은 챔피언은 못 나온다")
    void outcome_selfMatchThrows() {
        assertThatThrownBy(() -> new BradleyTerry.Outcome(1, 1, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("가중치가 0 이하이거나 유한하지 않으면 던진다 — 조용히 표본이 사라진다")
    void outcome_nonPositiveWeightThrows() {
        assertThatThrownBy(() -> new BradleyTerry.Outcome(1, 2, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BradleyTerry.Outcome(1, 2, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BradleyTerry.Outcome(1, 2, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("기대 승률 계산에 0 이하 강도를 넣으면 던진다")
    void expected_nonPositiveStrengthThrows() {
        assertThatThrownBy(() -> BradleyTerry.expected(0.0, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BradleyTerry.expected(1.0, -1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
