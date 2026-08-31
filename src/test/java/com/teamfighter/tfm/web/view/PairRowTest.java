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
        assertThat(row(Side.ALLY, -0.10, +0.10, +0.10).signature()).isNull();
        assertThat(row(Side.FOE, -0.10, +0.10, +0.10).signature()).isNull();
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
}
