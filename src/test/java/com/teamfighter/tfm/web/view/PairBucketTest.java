package com.teamfighter.tfm.web.view;

import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Side;
import com.teamfighter.tfm.analysis.pair.PerfMetric;
import com.teamfighter.tfm.web.view.PairRow.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>칸을 채우면서도 근거를 안 흐리는가.</b>
 *
 * <p>세 칸으로 나눈 순간 자주 비는 칸이 생겼다. 문턱을 0.15σ 에서 0.10σ 로 내려
 * 많이 나아졌지만 그것으로 "칸마다 최소 다섯 줄" 이 되지는 않는다 — 챔피언마다
 * 잰 쌍의 수가 다르기 때문이다.
 *
 * <p>그래서 모자란 만큼 문턱 아래에서 채우되 <b>따로 담는다</b>. 여기서 지키는 것은
 * 그 분리다: 합쳐 버리면 "확실한 것" 과 "그럴지도 모르는 것" 이 같은 무게로 읽히고,
 * 그건 문턱을 아예 없앤 것과 같다.
 */
class PairBucketTest {

    private static PairRow row(Side side, String name, double dealing, double death) {
        Map<PerfMetric, BigDecimal> effects = new LinkedHashMap<>();
        effects.put(PerfMetric.DEALING, BigDecimal.valueOf(dealing));
        effects.put(PerfMetric.DEATH, BigDecimal.valueOf(death));
        effects.put(PerfMetric.TANKING, BigDecimal.ZERO);
        return new PairRow(side, name, name, "MELEE", 30, effects);
    }

    @Test
    @DisplayName("모자란 만큼만 채운다 — 문턱을 넘은 것이 다섯이면 안 채운다")
    void fillingOnlyKicksInWhenThereAreTooFewConfidentRows() {
        List<PairRow> foes = List.of(
                row(Side.FOE, "a", 0.0, +0.42), row(Side.FOE, "b", 0.0, +0.35),
                row(Side.FOE, "c", 0.0, +0.30), row(Side.FOE, "d", 0.0, +0.25),
                row(Side.FOE, "e", 0.0, +0.20), row(Side.FOE, "약함", 0.0, +0.04));

        PairBucket bucket = PairBucket.of("", "", "", Bucket.HARD_FOE, foes, 10);

        assertThat(bucket.rows()).hasSize(5);
        assertThat(bucket.faint()).isEmpty();
    }

    @Test
    @DisplayName("확실한 줄이 모자라면 문턱 아래에서 다섯까지 채운다")
    void weakRowsFillTheGapUpToFive() {
        List<PairRow> foes = List.of(
                row(Side.FOE, "확실", 0.0, +0.42),
                row(Side.FOE, "약1", 0.0, +0.08), row(Side.FOE, "약2", 0.0, +0.06),
                row(Side.FOE, "약3", 0.0, +0.04), row(Side.FOE, "약4", 0.0, +0.02),
                row(Side.FOE, "약5", 0.0, +0.01));

        PairBucket bucket = PairBucket.of("", "", "", Bucket.HARD_FOE, foes, 10);

        assertThat(bucket.rows()).extracting(PairRow::nameKo).containsExactly("확실");
        // 다섯을 채우려면 넷이 더 필요하다. 센 것부터.
        assertThat(bucket.faint()).extracting(PairRow::nameKo)
                .containsExactly("약1", "약2", "약3", "약4");
    }

    @Test
    @DisplayName("채우는 줄도 방향이 맞아야 한다")
    void fillersMustLeanTheRightWay() {
        // "상대하기 어려움" 칸에 죽음이 줄어드는 챔피언을 채워 넣으면
        // 칸의 제목이 거짓말이 된다.
        List<PairRow> foes = List.of(
                row(Side.FOE, "조금더죽는다", 0.0, +0.06),
                row(Side.FOE, "덜죽는다", 0.0, -0.08),
                row(Side.FOE, "훨씬덜죽는다", 0.0, -0.09));

        PairBucket hard = PairBucket.of("", "", "", Bucket.HARD_FOE, foes, 10);

        assertThat(hard.rows()).isEmpty();
        assertThat(hard.faint()).extracting(PairRow::nameKo).containsExactly("조금더죽는다");
    }

    @Test
    @DisplayName("같은 챔피언이 두 칸에 나오지 않는다")
    void aChampionNeverAppearsInTwoBuckets() {
        // 문턱을 넘어 어느 칸에 든 줄은 채우기로 끌어오지 않는다.
        List<PairRow> foes = List.of(
                row(Side.FOE, "쉬운쪽", 0.0, -0.30),
                row(Side.FOE, "약한어려움", 0.0, +0.05));

        PairBucket hard = PairBucket.of("", "", "", Bucket.HARD_FOE, foes, 10);

        assertThat(hard.rows()).isEmpty();
        assertThat(hard.faint()).extracting(PairRow::nameKo).containsExactly("약한어려움");
    }

    @Test
    @DisplayName("잰 쌍이 다섯보다 적으면 있는 만큼만 나온다 — 지어내지 않는다")
    void nothingIsInventedToReachFive() {
        List<PairRow> foes = List.of(
                row(Side.FOE, "a", 0.0, +0.42), row(Side.FOE, "b", 0.0, +0.05));

        PairBucket bucket = PairBucket.of("", "", "", Bucket.HARD_FOE, foes, 10);

        assertThat(bucket.rows()).hasSize(1);
        assertThat(bucket.faint()).hasSize(1);
    }

    @Test
    @DisplayName("문턱을 넘은 것이 없는 칸은 화면이 그 사실을 말한다")
    void aBucketWithOnlyWeakRowsSaysSo() {
        // 흐린 줄만 있는 칸을 말없이 그리면 읽는 사람은 확실한 목록인 줄 안다.
        PairBucket bucket = PairBucket.of("", "", "", Bucket.HARD_FOE,
                List.of(row(Side.FOE, "약", 0.0, +0.05)), 10);

        assertThat(bucket.hasNoConfidentRows()).isTrue();
        assertThat(bucket.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("아무것도 못 잰 칸과 방향이 안 맞는 칸은 다른 문구다")
    void unmeasuredIsNotTheSameAsNothingLeaningThisWay() {
        PairBucket nothing = PairBucket.of("", "", "", Bucket.HARD_FOE, List.of(), 10);
        PairBucket wrongWay = PairBucket.of("", "", "", Bucket.HARD_FOE,
                List.of(row(Side.FOE, "덜죽는다", 0.0, -0.05)), 10);

        assertThat(nothing.isUnmeasured()).isTrue();
        assertThat(wrongWay.isUnmeasured()).isFalse();
        assertThat(wrongWay.isEmpty()).isTrue();
        assertThat(wrongWay.measured()).isEqualTo(1);
    }

    @Test
    @DisplayName("센 것부터, 정해진 수만큼만")
    void theStrongestComeFirstAndTheListIsCapped() {
        List<PairRow> foes = List.of(
                row(Side.FOE, "약함", 0.0, +0.18),
                row(Side.FOE, "셈", 0.0, +0.42),
                row(Side.FOE, "중간", 0.0, +0.30));

        PairBucket bucket = PairBucket.of("", "", "", Bucket.HARD_FOE, foes, 2);

        assertThat(bucket.rows()).extracting(PairRow::nameKo).containsExactly("셈", "중간");
    }

    @Test
    @DisplayName("잰 수는 문턱과 무관하다")
    void theMeasuredCountIgnoresTheThreshold() {
        PairBucket bucket = PairBucket.of("", "", "", Bucket.HARD_FOE, List.of(
                row(Side.FOE, "a", 0.0, +0.42),
                row(Side.FOE, "b", 0.0, +0.01),
                row(Side.FOE, "c", 0.0, -0.03)), 10);

        assertThat(bucket.measured()).isEqualTo(3);
    }

    @Test
    @DisplayName("듀오 칸에 채운 줄은 그 칸이 무엇으로 끌어왔는지를 그린다")
    void aFillerShowsTheMetricThatBroughtItIn() {
        // 채운 줄의 bucket() 은 NEUTRAL 이라 자기에게 물으면 언제나 데스가 나온다.
        // 딜로 기운 줄에 데스를 그리면 왜 거기 있는지 모를 숫자가 뜬다.
        PairRow leansOnDamage = row(Side.ALLY, "딜동료", +0.08, +0.01);

        assertThat(leansOnDamage.bucket()).isEqualTo(Bucket.NEUTRAL);
        assertThat(leansOnDamage.leadMetric()).isEqualTo(PerfMetric.DEATH);
        assertThat(leansOnDamage.leadMetric(Bucket.DUO)).isEqualTo(PerfMetric.DEALING);
        assertThat(leansOnDamage.leadEffect(Bucket.DUO)).isEqualByComparingTo("0.08");
    }
}
