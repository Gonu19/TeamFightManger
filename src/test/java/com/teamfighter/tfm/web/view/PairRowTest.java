package com.teamfighter.tfm.web.view;

import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Side;
import com.teamfighter.tfm.analysis.pair.PerfMetric;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 화면 문구가 <b>숫자를 거꾸로 읽지 않는가</b>.
 *
 * <p>이 시험이 있는 이유는 실물에서 한 번 틀렸기 때문이다 — 동료 줄에 "간접 카운터" 가
 * 찍혔다. 카운터는 상대에게 쓰는 말이고, 같은 숫자라도 편이 다르면 뜻이 다르다.
 * 그런 오류는 숫자가 멀쩡하므로 화면을 봐도 안 보인다.
 *
 * <p>여기서 지키는 것은 D64 결정 3 과 D65 결정 1 이다: <b>부호의 뜻은 지표마다 다르고,
 * 지표 하나가 아니라 묶음이 현상을 말한다.</b>
 */
class PairRowTest {

    private static PairRow row(Side side, double dealing, double death, double tanking) {
        Map<PerfMetric, BigDecimal> effects = new LinkedHashMap<>();
        effects.put(PerfMetric.DEALING, BigDecimal.valueOf(dealing));
        effects.put(PerfMetric.DEATH, BigDecimal.valueOf(death));
        effects.put(PerfMetric.TANKING, BigDecimal.valueOf(tanking));
        return new PairRow(side, "Other", "상대", "MELEE", 40, effects);
    }

    @Test
    @DisplayName("동료 줄에는 '카운터' 라는 말을 쓰지 않는다")
    void alliesAreNeverCalledCounters() {
        // 실물에서 이렇게 틀렸다. 같은 숫자가 상대 쪽이면 카운터가 맞지만
        // 동료 쪽이면 "옆에 두면 서로 방해한다" 이다.
        PairRow ally = row(Side.ALLY, -0.20, +0.24, +0.19);

        assertThat(ally.signature()).doesNotContain("카운터");
        assertThat(ally.signature()).contains("역시너지");
    }

    @Test
    @DisplayName("같은 숫자라도 상대 쪽이면 간접 카운터다")
    void theSameNumbersReadDifferentlyOnTheFoeSide() {
        PairRow foe = row(Side.FOE, -0.20, +0.24, +0.19);

        assertThat(foe.signature()).isEqualTo("간접 카운터 — 더 맞고, 못 뚫고, 죽는다");
    }

    @Test
    @DisplayName("죽음↓ · 탱↓ 은 어그로 분산이다 — D65 가 몽크×늑대인간에서 읽은 서명")
    void deathDownAndTankingDownIsAggroSpread() {
        // 실측: Monk ← 동료 Werewolf 죽음 −0.315 · 탱킹 −0.235 (D65 ③)
        PairRow ally = row(Side.ALLY, +0.10, -0.31, -0.24);

        assertThat(ally.signature()).isEqualTo("어그로 분산 — 옆에서 대신 맞아준다");
    }

    @Test
    @DisplayName("죽음↓ 인데 탱은 그대로면 힐 보호다")
    void deathDownWithFlatTankingIsHealing() {
        PairRow ally = row(Side.ALLY, -0.11, -0.28, -0.07);

        assertThat(ally.signature()).isEqualTo("힐 보호 — 맞는 양은 그대론데 덜 죽는다");
    }

    @Test
    @DisplayName("상대의 가한피해 상승을 '내가 강하다' 로 읽지 않는다")
    void risingDamageAgainstAFoeIsNotStrength() {
        // D64 결정 3 이 경고한 자리다. Chef 가 적팀에 있으면 Werewolf 의 딜이 오르는데,
        // 늑대인간이 강해서가 아니라 요리사가 힐로 딜을 흡수해 주기 때문이다.
        PairRow foe = row(Side.FOE, +0.50, +0.02, +0.05);

        assertThat(foe.signature()).isEqualTo("딜을 받아낸다 — 내가 강한 것이 아니다");
    }

    @Test
    @DisplayName("역시너지 경고는 동료에게만 붙는다")
    void onlyAlliesRaiseWarnings() {
        // 상대가 내 딜을 줄이고 죽음을 늘리는 것은 지뢰가 아니라 그냥 카운터다.
        // 피할 수 없는 것을 "같이 뽑지 마라" 라고 경고하면 그 경고가 의미를 잃는다.
        assertThat(row(Side.ALLY, -0.20, +0.24, 0.0).isWarning()).isTrue();
        assertThat(row(Side.FOE, -0.20, +0.24, 0.0).isWarning()).isFalse();
    }

    @Test
    @DisplayName("작은 값에는 아무 말도 안 한다")
    void smallEffectsGetNoSignature() {
        // 잡음만 있어도 쌍 1,000개 중 몇은 0.4 에 닿는다(PairEffectCalculatorTest).
        // 문턱이 낮으면 화면이 잡음마다 서명을 단다.
        assertThat(row(Side.ALLY, -0.05, +0.05, +0.05).signature()).isNull();
        assertThat(row(Side.FOE, -0.05, +0.05, +0.05).signature()).isNull();
    }

    @Test
    @DisplayName("서명 · 묶음 · 경고가 같은 문턱을 본다")
    void oneThresholdGovernsEverything() {
        // 셋이 다른 문턱을 쓰면 "경고는 떴는데 듀오 칸에는 없는" 줄이 생기고,
        // 그건 읽는 사람이 화면을 못 믿게 만든다.
        double justOver = PairRow.SIGNAL + 0.01;
        PairRow ally = row(Side.ALLY, -justOver, +justOver, 0.0);

        assertThat(ally.signature()).contains("역시너지");
        assertThat(ally.bucket()).isEqualTo(PairRow.Bucket.ANTI_SYNERGY);
        assertThat(ally.isWarning()).isTrue();

        double justUnder = PairRow.SIGNAL - 0.01;
        PairRow quiet = row(Side.ALLY, -justUnder, +justUnder, 0.0);

        assertThat(quiet.signature()).isNull();
        assertThat(quiet.bucket()).isEqualTo(PairRow.Bucket.NEUTRAL);
        assertThat(quiet.isWarning()).isFalse();
    }

    @Test
    @DisplayName("지표가 하나라도 없으면 서명을 안 만든다")
    void missingMetricsMeanNoSignature() {
        Map<PerfMetric, BigDecimal> onlyDealing =
                Map.of(PerfMetric.DEALING, BigDecimal.valueOf(-0.5));
        PairRow partial = new PairRow(Side.ALLY, "X", "엑스", "MELEE", 40, onlyDealing);

        // 서명은 묶음에서 나온다. 하나만 보고 지으면 그게 정확히 D64 가 경고한 실패다.
        assertThat(partial.signature()).isNull();
    }

    @Test
    @DisplayName("정렬 크기는 딜과 죽음 중 큰 쪽이다")
    void magnitudeUsesDealingAndDeath() {
        // 힐은 t 가 다른 지표의 1/5 이라(D63) 그걸로 순위를 매기면 잡음이 위로 온다.
        assertThat(row(Side.ALLY, -0.42, +0.10, 0.0).magnitude()).isEqualTo(0.42);
        assertThat(row(Side.ALLY, -0.10, +0.37, 0.0).magnitude()).isEqualTo(0.37);
    }

    // --- 세 묶음 -------------------------------------------------------------

    @Test
    @DisplayName("묶음을 가르는 것은 데스다 — 딜이 아니다")
    void bucketsSplitOnDeathNotDealing() {
        // D64 결정 3 이 걸리는 자리다. 상대의 딜 상승은 "내가 강하다" 가 아니라
        // "저쪽이 내 딜을 받아낸다" 이므로, 딜로 가르면 흡수해 주는 상대가
        // "상대하기 쉬움" 이 아니라 엉뚱한 칸으로 간다.
        assertThat(row(Side.FOE, +0.50, +0.30, 0.0).bucket())
                .isEqualTo(PairRow.Bucket.HARD_FOE);   // 딜이 올라도 더 죽으면 어렵다
        assertThat(row(Side.FOE, -0.50, -0.30, 0.0).bucket())
                .isEqualTo(PairRow.Bucket.EASY_FOE);   // 딜이 줄어도 덜 죽으면 쉽다
    }

    @Test
    @DisplayName("역시너지는 듀오 묶음에 안 섞인다 — 경고로 따로 나간다")
    void antiSynergyIsNotADuo() {
        PairRow mine = row(Side.ALLY, -0.20, +0.24, 0.0);

        assertThat(mine.bucket()).isEqualTo(PairRow.Bucket.ANTI_SYNERGY);
        assertThat(mine.isWarning()).isTrue();
    }

    @Test
    @DisplayName("동료는 죽음이 줄거나 딜이 오르면 듀오다")
    void duosAreEitherSaferOrStronger() {
        assertThat(row(Side.ALLY, +0.02, -0.31, 0.0).bucket()).isEqualTo(PairRow.Bucket.DUO);
        assertThat(row(Side.ALLY, +0.28, +0.02, 0.0).bucket()).isEqualTo(PairRow.Bucket.DUO);
    }

    @Test
    @DisplayName("문턱을 못 넘은 줄은 어느 칸에도 안 들어간다")
    void quietPairsAreShownNowhere() {
        assertThat(row(Side.ALLY, +0.05, -0.05, 0.0).bucket()).isEqualTo(PairRow.Bucket.NEUTRAL);
        assertThat(row(Side.FOE, +0.05, -0.05, 0.0).bucket()).isEqualTo(PairRow.Bucket.NEUTRAL);
    }

    @Test
    @DisplayName("지표가 없으면 NEUTRAL 이다 — 없는 값을 0 으로 놓고 가르지 않는다")
    void missingMetricsMeanNoBucket() {
        Map<PerfMetric, BigDecimal> onlyDeath = Map.of(PerfMetric.DEATH, BigDecimal.valueOf(0.4));
        PairRow partial = new PairRow(Side.FOE, "X", "엑스", "MELEE", 40, onlyDeath);

        // "관측이 없다" 가 "효과가 없다" 로 바뀌어 화면에 나가면 안 된다.
        assertThat(partial.bucket()).isEqualTo(PairRow.Bucket.NEUTRAL);
    }

    @Test
    @DisplayName("묶음이 크게 그리는 지표는 그 칸을 가른 지표다")
    void theHeadlineMetricIsTheOneThatSplitTheBucket() {
        // 상대는 언제나 데스다.
        assertThat(row(Side.FOE, +0.50, +0.30, 0.0).leadMetric()).isEqualTo(PerfMetric.DEATH);

        // 동료는 실제로 문턱을 넘은 쪽. 죽음 −0.31 인 줄에 딜 0.02 를 그리면
        // 읽는 사람은 이 줄이 왜 듀오 칸에 있는지 알 수 없다.
        assertThat(row(Side.ALLY, +0.02, -0.31, 0.0).leadMetric()).isEqualTo(PerfMetric.DEATH);
        assertThat(row(Side.ALLY, +0.28, +0.02, 0.0).leadMetric()).isEqualTo(PerfMetric.DEALING);
    }

    @Test
    @DisplayName("막대 색은 부호가 아니라 '이 챔피언에게 좋은가' 를 따른다")
    void barColourFollowsMeaningNotSign() {
        // 둘 다 음수인데 뜻이 반대다. 부호로 색을 정하면 정확히 거꾸로 칠한다.
        assertThat(row(Side.ALLY, +0.02, -0.31, 0.0).isFavourable()).isTrue();   // 죽음 ↓ 좋다
        assertThat(row(Side.ALLY, -0.31, +0.02, 0.0).isFavourable()).isFalse();  // 딜  ↓ 나쁘다
        assertThat(row(Side.FOE, 0.0, +0.30, 0.0).isFavourable()).isFalse();
        assertThat(row(Side.FOE, 0.0, -0.30, 0.0).isFavourable()).isTrue();
    }

    @Test
    @DisplayName("칸마다 '센 것' 의 뜻이 다르다")
    void eachBucketRanksByItsOwnMeaning() {
        // 어려운 상대는 죽음이 클수록 위, 쉬운 상대는 죽음이 작을수록 위다.
        assertThat(row(Side.FOE, 0.0, +0.40, 0.0).rankValue())
                .isGreaterThan(row(Side.FOE, 0.0, +0.20, 0.0).rankValue());
        assertThat(row(Side.FOE, 0.0, -0.40, 0.0).rankValue())
                .isGreaterThan(row(Side.FOE, 0.0, -0.20, 0.0).rankValue());

        // 동료는 둘을 더한다 — 죽음만 보면 "안 죽지만 딜도 안 나오는" 조합이 1위가 된다.
        assertThat(row(Side.ALLY, +0.30, -0.30, 0.0).rankValue())
                .isGreaterThan(row(Side.ALLY, -0.10, -0.30, 0.0).rankValue());
    }

    @Test
    @DisplayName("막대는 0.8σ 에서 가득 차고 그 위는 눕는다")
    void barsSaturate() {
        // 0.8 과 2.0 을 길이로 구분해 봐야 둘 다 "아주 크다" 이고, 눈금을 넓히면
        // 정작 흔한 0.2σ 대가 전부 안 보이게 된다. 숫자는 막대 옆에 그대로 있다.
        assertThat(PairRow.barWidth(BigDecimal.valueOf(0.4))).isEqualTo(50);
        assertThat(PairRow.barWidth(BigDecimal.valueOf(-0.4))).isEqualTo(50);
        assertThat(PairRow.barWidth(BigDecimal.valueOf(2.0))).isEqualTo(100);
        assertThat(PairRow.barWidth(null)).isNull();
    }
}
