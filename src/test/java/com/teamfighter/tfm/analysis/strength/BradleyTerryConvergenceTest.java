package com.teamfighter.tfm.analysis.strength;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 데이터 규모에서 적합이 끝나는지 본다.
 *
 * <p>DB 는 필요 없다. 대신 <b>운영 DB 와 같은 모양</b>의 입력을 만든다 — 챔피언 40종,
 * 4v4 경기 1,200건, 경기당 관측 16개니까 약 19,000개.
 *
 * <p><b>이 테스트가 왜 필요한가.</b> 기존 Bradley-Terry 테스트는 전부 챔피언 2~5종에
 * 관측 수십 개짜리였다. 그 크기에서는 반복이 몇 번 만에 끝나서 수렴 문제가 드러나지
 * 않는다. 실제 데이터로 처음 돌렸을 때 10,000회 안에 수렴하지 않아 집계가 통째로 죽었고,
 * <b>작은 표본으로만 검증한 것이 원인이었다.</b> D34 가 골든 파일에 대해 짚은 것과 같은
 * 종류다 — 표본이 담고 있는 것만 지킨다.
 */
class BradleyTerryConvergenceTest {

    private static final int CHAMPIONS = 40;
    private static final int MATCHES = 1_200;
    private static final int TEAM_SIZE = 4;

    /**
     * 잠재 강도를 정해두고 그것으로 승패를 뽑는다. 적합이 그 순서를 되찾아야 한다.
     *
     * <p>강도 분포는 실측을 따랐다 — 챔피언 기저 승률의 표준편차가 3.6%p 로 작다(D14 주변의
     * 측정). 로그정규 σ=0.35 면 승률로 환산해 대략 그 폭이 나온다.
     */
    private record Season(List<BradleyTerry.Outcome> outcomes, double[] latentStrength) {
    }

    private static Season generate(long seed) {
        Random random = new Random(seed);
        double[] latent = new double[CHAMPIONS + 1];
        for (int id = 1; id <= CHAMPIONS; id++) {
            latent[id] = Math.exp(random.nextGaussian() * 0.35);
        }

        List<Integer> pool = new ArrayList<>();
        for (int id = 1; id <= CHAMPIONS; id++) {
            pool.add(id);
        }

        List<BradleyTerry.Outcome> outcomes = new ArrayList<>(MATCHES * TEAM_SIZE * TEAM_SIZE);
        for (int match = 0; match < MATCHES; match++) {
            Collections.shuffle(pool, random);
            List<Integer> blue = pool.subList(0, TEAM_SIZE);
            List<Integer> red = pool.subList(TEAM_SIZE, TEAM_SIZE * 2);

            double blueStrength = blue.stream().mapToDouble(id -> latent[id]).sum();
            double redStrength = red.stream().mapToDouble(id -> latent[id]).sum();
            boolean blueWins = random.nextDouble() < blueStrength / (blueStrength + redStrength);

            List<Integer> winners = blueWins ? blue : red;
            List<Integer> losers = blueWins ? red : blue;
            for (int winner : winners) {
                for (int loser : losers) {
                    outcomes.add(new BradleyTerry.Outcome(winner, loser, 1.0));
                }
            }
        }
        return new Season(outcomes, latent);
    }

    @Test
    @DisplayName("챔피언 40종 · 경기 1,200건 규모에서 적합이 끝난다 — 실제 데이터에서 죽었던 자리다")
    void fit_convergesAtProductionScale() {
        Season season = generate(20260827L);

        assertThat(season.outcomes()).hasSize(MATCHES * TEAM_SIZE * TEAM_SIZE);

        // 고치기 전에는 여기서 IllegalStateException 이 난다.
        Map<Integer, Double> strength = BradleyTerry.fit(season.outcomes());

        assertThat(strength).hasSize(CHAMPIONS);
        assertThat(strength.values()).allMatch(v -> Double.isFinite(v) && v > 0);
    }

    @Test
    @DisplayName("적합된 강도가 잠재 강도의 순서를 되찾는다 — 끝나기만 하는 게 아니라 맞아야 한다")
    void fit_recoversLatentOrdering() {
        Season season = generate(20260827L);
        Map<Integer, Double> fitted = BradleyTerry.fit(season.outcomes());

        int strongest = 1;
        int weakest = 1;
        for (int id = 2; id <= CHAMPIONS; id++) {
            if (season.latentStrength()[id] > season.latentStrength()[strongest]) {
                strongest = id;
            }
            if (season.latentStrength()[id] < season.latentStrength()[weakest]) {
                weakest = id;
            }
        }

        // 변조: 반복을 1회로 줄이면 강도가 전부 비슷해져 이 부등호가 흔들린다.
        assertThat(fitted.get(strongest)).isGreaterThan(fitted.get(weakest));
        assertThat(spearmanish(season, fitted)).isGreaterThan(0.7);
    }

    @Test
    @DisplayName("적합이 우도방정식을 만족한다 — 챔피언마다 기대 승수가 실제 승수와 같다")
    void fit_satisfiesLikelihoodEquations() {
        Season season = generate(20260827L);
        Map<Integer, Double> strength = BradleyTerry.fit(season.outcomes());

        Map<Integer, Double> actualWins = new java.util.HashMap<>();
        Map<Integer, Double> expectedWins = new java.util.HashMap<>();
        for (BradleyTerry.Outcome outcome : season.outcomes()) {
            double sw = strength.get(outcome.winnerId());
            double sl = strength.get(outcome.loserId());
            actualWins.merge(outcome.winnerId(), outcome.weight(), Double::sum);
            actualWins.merge(outcome.loserId(), 0.0, Double::sum);
            expectedWins.merge(outcome.winnerId(),
                    outcome.weight() * BradleyTerry.expected(sw, sl), Double::sum);
            expectedWins.merge(outcome.loserId(),
                    outcome.weight() * BradleyTerry.expected(sl, sw), Double::sum);
        }

        // 이것이 Bradley-Terry 적합의 정의다: 강도가 맞으면 그 강도로 예측한 승수가
        // 실제 승수와 같아진다. 순위만 보는 단언은 반복을 한 번만 돌려도 통과하지만
        // 이 등식은 실제로 수렴해야만 성립한다.
        //
        // 정확히 0 이 아닌 이유는 가상 관측(정규화) 때문이다 — 챔피언마다 1경기어치라
        // 실제 승수 200건 규모에서는 1 미만의 차이로 남는다.
        for (int id = 1; id <= CHAMPIONS; id++) {
            assertThat(expectedWins.get(id))
                    .as("챔피언 " + id + " 의 기대 승수")
                    .isCloseTo(actualWins.get(id), org.assertj.core.api.Assertions.within(1.5));
        }
    }

    /** 잠재 순위와 적합 순위의 일치 비율. 무작위면 0.5 근처다. */
    private static double spearmanish(Season season, Map<Integer, Double> fitted) {
        int agree = 0;
        int total = 0;
        for (int a = 1; a <= CHAMPIONS; a++) {
            for (int b = a + 1; b <= CHAMPIONS; b++) {
                boolean latentOrder = season.latentStrength()[a] > season.latentStrength()[b];
                boolean fittedOrder = fitted.get(a) > fitted.get(b);
                if (latentOrder == fittedOrder) {
                    agree++;
                }
                total++;
            }
        }
        return (double) agree / total;
    }

    @Test
    @DisplayName("가중치가 실려도 끝난다 — 감쇠를 먹인 관측이 실제 입력이다")
    void fit_convergesWithDecayedWeights() {
        Season season = generate(4242L);
        List<BradleyTerry.Outcome> decayed = new ArrayList<>(season.outcomes().size());
        double weight = 1.0;
        for (BradleyTerry.Outcome outcome : season.outcomes()) {
            // 오래된 경기일수록 가볍게. 실제 감쇠와 같은 모양의 흩어짐을 만든다.
            weight = Math.max(0.02, weight * 0.9995);
            decayed.add(new BradleyTerry.Outcome(
                    outcome.winnerId(), outcome.loserId(), weight));
        }

        Map<Integer, Double> strength = BradleyTerry.fit(decayed);

        assertThat(strength).hasSize(CHAMPIONS);
        assertThat(strength.values()).allMatch(v -> Double.isFinite(v) && v > 0);
    }
}
