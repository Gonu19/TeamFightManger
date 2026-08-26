package com.teamfighter.tfm.analysis.decay;

import com.teamfighter.tfm.analysis.AnalysisConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * 이중 감쇠 {@code w = 0.5^(자기변경/2) × 0.5^(경과패치/12)} 를 못 박는다 (D15a).
 *
 * <p>DB 는 필요 없다 — 순수 함수다.
 *
 * <p><b>두 인자는 모두 "기준 시점까지 흐른 양"이다.</b> 경기 시점의 누적값이 아니다.
 * 여기를 뒤집으면 최신 경기가 가장 세게 깎이고 오래된 경기가 살아남아, 감쇠가 정확히
 * 반대로 동작한다. 그런데도 값은 (0,1] 안에 있어서 눈으로는 멀쩡해 보인다.
 */
class DecayWeightTest {

    private static final AnalysisConfig CONFIG = new AnalysisConfig(10, 24, 15, 3, 2, 12);
    private static final double EPS = 1e-12;

    @Test
    @DisplayName("아무것도 흐르지 않았으면 1.0 — 기준 시점 자신의 경기는 안 깎인다")
    void of_noElapseIsFullWeight() {
        assertThat(DecayWeight.of(0, 0, CONFIG)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("자기 변경이 반감기(2회)만큼이면 정확히 절반")
    void of_selfHalfLifeHalvesWeight() {
        assertThat(DecayWeight.of(2, 0, CONFIG)).isCloseTo(0.5, within(EPS));
        assertThat(DecayWeight.of(4, 0, CONFIG)).isCloseTo(0.25, within(EPS));
    }

    @Test
    @DisplayName("경과 패치가 반감기(12패치)만큼이면 정확히 절반")
    void of_metaHalfLifeHalvesWeight() {
        assertThat(DecayWeight.of(0, 12, CONFIG)).isCloseTo(0.5, within(EPS));
        assertThat(DecayWeight.of(0, 24, CONFIG)).isCloseTo(0.25, within(EPS));
    }

    @Test
    @DisplayName("두 항은 곱해진다 — 더하거나 한쪽만 쓰면 값이 달라진다")
    void of_termsMultiply() {
        // 변조: 두 항을 곱 대신 합의 평균으로 바꾸면 0.5 가 나와 이 단언이 깨진다.
        //       한쪽 항만 쓰면 0.5 가 나온다. 곱해야만 0.25 다.
        assertThat(DecayWeight.of(2, 12, CONFIG)).isCloseTo(0.25, within(EPS));
    }

    @Test
    @DisplayName("같은 횟수면 자기 변경이 메타 변화보다 세게 깎는다 — 1차 효과와 2차 효과 (D15a)")
    void of_selfDecaysFasterThanMeta() {
        // 변조: 두 반감기를 서로 바꿔 넣으면(자기 12 · 메타 2) 이 부등호가 뒤집힌다.
        //       위 테스트들만으로는 그 변조가 안 잡힌다 — 각각 자기 반감기에서 0.5 를 내기 때문이다.
        assertThat(DecayWeight.of(6, 0, CONFIG)).isLessThan(DecayWeight.of(0, 6, CONFIG));
    }

    @Test
    @DisplayName("흐를수록 단조 감소한다")
    void of_isMonotonicallyDecreasing() {
        double previous = DecayWeight.of(0, 0, CONFIG);
        for (int i = 1; i <= 30; i++) {
            double self = DecayWeight.of(i, 0, CONFIG);
            double meta = DecayWeight.of(0, i, CONFIG);
            assertThat(self).isLessThan(previous);
            assertThat(meta).isLessThan(previous);
            previous = Math.max(self, meta);
        }
    }

    @Test
    @DisplayName("항상 (0,1] 안에 있고 NaN 이 되지 않는다")
    void of_staysInUnitRange() {
        for (int self = 0; self <= 50; self++) {
            for (int elapsed = 0; elapsed <= 200; elapsed += 10) {
                double w = DecayWeight.of(self, elapsed, CONFIG);
                assertThat(w).isNotNaN();
                assertThat(w).isGreaterThanOrEqualTo(0.0).isLessThanOrEqualTo(1.0);
            }
        }
    }

    @Test
    @DisplayName("음수 인자는 던진다 — 기준 시점이 경기보다 앞이라는 뜻이라 호출자 버그다")
    void of_negativeArgumentsThrow() {
        // 변조: 가드를 지우면 0.5^(-1) = 2.0 이 나와 오래된 경기가 최신보다 무거워진다.
        assertThatThrownBy(() -> DecayWeight.of(-1, 0, CONFIG))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecayWeight.of(0, -1, CONFIG))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("쌍의 가중치는 두 챔피언의 변경을 합쳐서 본다 — 어느 쪽이 바뀌어도 그 상성은 낡는다")
    void forPair_sumsBothChampionChanges() {
        assertThat(DecayWeight.forPair(1, 1, 0, CONFIG))
                .isCloseTo(DecayWeight.of(2, 0, CONFIG), within(EPS));
    }

    @Test
    @DisplayName("쌍은 한쪽만 볼 때보다 반드시 더 세게 깎인다")
    void forPair_isStricterThanSingleChampion() {
        // 변조: 합 대신 최댓값을 쓰면 이 단언이 깨진다(둘 다 2면 max 도 2라 같아진다).
        assertThat(DecayWeight.forPair(2, 2, 0, CONFIG))
                .isLessThan(DecayWeight.of(2, 0, CONFIG));
    }
}
