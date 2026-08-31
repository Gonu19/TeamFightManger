package com.teamfighter.tfm.analysis.pair;

import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Effect;
import com.teamfighter.tfm.analysis.pair.PairEffectCalculator.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모형이 <b>심어 둔 효과를 되찾아내는가</b>.
 *
 * <h2>왜 이렇게 시험하나</h2>
 *
 * 실제 세이브로 시험하면 "맞는 답" 을 우리가 모른다 — 나온 숫자가 옳은지 그른지 판단할
 * 근거가 없다. 그래서 <b>답을 알고 있는 데이터를 만든다</b>: 특정 쌍에만 효과를 넣고,
 * 나머지는 잡음으로 채운 뒤, 모형이 그 쌍만 집어내는지 본다.
 *
 * <p>이 시험이 잡는 것은 D63~D65 의 결론이 아니다(그건 {@code tools/} 가 이미 쟀다).
 * 여기서 잡는 것은 <b>옮기는 과정에서 생긴 실수</b>다 — 부호가 뒤집혔다거나, 동료와
 * 상대가 바뀌었다거나, 방향(A←B 와 B←A)이 뭉개졌다거나.
 * 그런 실수는 실제 데이터에서는 그럴듯한 숫자로 나와 안 보인다.
 */
class PairEffectCalculatorTest {

    /** 챔피언 40종 · 역할군 5종. 실제 시드와 같은 규모다 (D05). */
    private static final int CHAMPIONS = 40;
    private static final int TEAM_SIZE = 4;

    /** 역할군은 챔피언 번호를 5로 나눈 나머지로 둔다. 시험에는 균등하기만 하면 된다. */
    private static int roleOf(int championId) {
        return championId % 5;
    }

    /**
     * 경기를 만든다. {@code plantedSubject} 가 {@code plantedOther} 와 <b>같은 팀</b>일 때만
     * 출력에 {@code effect} 를 더한다.
     *
     * <p>나머지는 전부 잡음이다. 챔피언마다 규모를 다르게 둔 이유는 표준화가 실제로
     * 일하는지 보기 위해서다 — 규모가 같으면 표준화를 빼먹어도 시험이 통과한다.
     */
    private static List<PairObservation> simulate(int matches, int plantedSubject,
                                                  int plantedOther, Side side, double effect,
                                                  long seed) {
        Random random = new Random(seed);
        List<PairObservation> rows = new ArrayList<>();

        for (int m = 0; m < matches; m++) {
            List<Integer> blue = pick(random);
            List<Integer> red = pick(random);
            if (!java.util.Collections.disjoint(blue, red)) {
                continue;                       // 같은 챔피언이 양 팀에 나오지 않는다
            }
            String team = "team-" + (m % 8);

            for (int s = 0; s < 2; s++) {
                List<Integer> own = s == 0 ? blue : red;
                List<Integer> opposing = s == 0 ? red : blue;
                for (int champ : own) {
                    List<Integer> mates = own.stream().filter(c -> c != champ).toList();

                    // 챔피언마다 규모가 다르다. 표준화가 이걸 걷어내야 한다.
                    double scale = 100.0 + champ * 40.0;
                    double value = scale + random.nextGaussian() * 20.0;

                    boolean planted = champ == plantedSubject
                            && (side == Side.ALLY ? mates.contains(plantedOther)
                                                  : opposing.contains(plantedOther));
                    if (planted) {
                        value += effect * 20.0;      // 잡음의 표준편차가 20 이므로 σ 단위다
                    }
                    rows.add(new PairObservation(champ, team, mates, opposing, value));
                }
            }
        }
        return rows;
    }

    /** 서로 다른 챔피언 넷. */
    private static List<Integer> pick(Random random) {
        List<Integer> out = new ArrayList<>();
        while (out.size() < TEAM_SIZE) {
            int c = random.nextInt(CHAMPIONS);
            if (!out.contains(c)) {
                out.add(c);
            }
        }
        return out;
    }

    private static Optional<Effect> find(List<Effect> effects, Side side, int subject, int other) {
        return effects.stream()
                .filter(e -> e.side() == side
                        && e.subjectChampionId() == subject
                        && e.otherChampionId() == other)
                .findFirst();
    }

    @Test
    @DisplayName("심어 둔 동료 효과를 찾아낸다 — 부호와 크기까지")
    void recoversAPlantedAllyEffect() {
        List<PairObservation> rows =
                simulate(4_000, 7, 13, Side.ALLY, +1.0, 42L);

        List<Effect> effects = PairEffectCalculator.effects(rows,
                PairEffectCalculatorTest::roleOf);

        Effect found = find(effects, Side.ALLY, 7, 13).orElseThrow();

        // 릿지가 값을 안쪽으로 당기므로 1.0 을 정확히 맞히지는 않는다.
        // 여기서 보는 것은 "부호가 맞고 크기가 그 언저리인가" 다.
        assertThat(found.effect()).isBetween(0.5, 1.2);
        assertThat(found.observations()).isGreaterThanOrEqualTo(
                PairEffectCalculator.MIN_OBSERVATIONS);
    }

    @Test
    @DisplayName("효과에 방향이 있다 — A←B 만 뜨고 B←A 는 안 뜬다")
    void theEffectIsDirected() {
        // 이것이 D63 결정 2 의 핵심이다. 승패로 잴 때는 A+B 가 한 덩어리라
        // 누가 누구를 살렸는지 알 수 없었다.
        List<PairObservation> rows =
                simulate(4_000, 7, 13, Side.ALLY, +1.0, 42L);

        List<Effect> effects = PairEffectCalculator.effects(rows,
                PairEffectCalculatorTest::roleOf);

        double forward = find(effects, Side.ALLY, 7, 13).orElseThrow().effect();
        double backward = find(effects, Side.ALLY, 13, 7).orElseThrow().effect();

        assertThat(forward).isGreaterThan(0.5);
        assertThat(Math.abs(backward)).isLessThan(0.25);   // 13 의 출력은 안 건드렸다
    }

    @Test
    @DisplayName("동료와 상대를 바꿔 붙이지 않는다")
    void doesNotConfuseAlliesWithFoes() {
        // 상대 쪽에 심었는데 동료 쪽에서 나오면 카운터 화면이 통째로 거짓말이 된다.
        List<PairObservation> rows =
                simulate(4_000, 7, 13, Side.FOE, -1.0, 7L);

        List<Effect> effects = PairEffectCalculator.effects(rows,
                PairEffectCalculatorTest::roleOf);

        assertThat(find(effects, Side.FOE, 7, 13).orElseThrow().effect()).isLessThan(-0.5);
        assertThat(find(effects, Side.ALLY, 7, 13).orElseThrow().effect())
                .isBetween(-0.25, 0.25);
    }

    @Test
    @DisplayName("잡음만 있으면 심은 효과 크기에 아무도 못 미친다 — 다만 0 도 아니다")
    void noiseStaysBelowTheSignal() {
        // 잡음만 있는 데이터를 돌린다. 릿지가 너무 약하면 여기서 큰 계수가 나오고,
        // 그때 상위 목록은 발견이 아니라 <b>잡음 순위표</b>가 된다.
        List<PairObservation> rows =
                simulate(4_000, 7, 13, Side.ALLY, 0.0, 99L);

        List<Effect> effects = PairEffectCalculator.effects(rows,
                PairEffectCalculatorTest::roleOf);

        double loudest = effects.stream()
                .mapToDouble(e -> Math.abs(e.effect())).max().orElse(0.0);

        // 심은 효과(위 시험에서 0.5 이상)에는 아무도 못 미친다 — 신호와 잡음이 갈린다.
        assertThat(loudest).isLessThan(0.45);

        // <b>그렇다고 0 도 아니다.</b> 쌍 1,000개를 늘어놓으면 그중 몇은 우연히 0.4 에
        // 닿는다. 이것이 화면이 효과만 보여주면 안 되는 이유다 — 관측 수를 나란히 놓고
        // "판정 불가" 를 말할 수 있어야 한다 (D13·D60). 계수 하나는 발견이 아니다.
        assertThat(loudest).isGreaterThan(0.2);
        assertThat(effects.stream().filter(e -> Math.abs(e.effect()) > 0.30).count())
                .isLessThan(effects.size() / 20L);          // 그런 쌍은 5% 미만이다
    }

    @Test
    @DisplayName("관측이 얇은 쌍은 아예 안 내보낸다")
    void thinPairsAreDropped() {
        List<PairObservation> rows = simulate(60, 7, 13, Side.ALLY, 1.0, 5L);

        List<Effect> effects = PairEffectCalculator.effects(rows,
                PairEffectCalculatorTest::roleOf);

        // 표본이 얇으면 계수가 흔들린다. 화면에 내보내면 그 흔들림이 "발견" 으로 읽힌다.
        assertThat(effects).allSatisfy(e -> assertThat(e.observations())
                .isGreaterThanOrEqualTo(PairEffectCalculator.MIN_OBSERVATIONS));
    }

    @Test
    @DisplayName("관측이 없으면 빈 목록이다 — 예외가 아니다")
    void emptyInputGivesEmptyOutput() {
        assertThat(PairEffectCalculator.effects(List.of(),
                PairEffectCalculatorTest::roleOf)).isEmpty();
    }
}
