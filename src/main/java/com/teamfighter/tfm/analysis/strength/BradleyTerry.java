package com.teamfighter.tfm.analysis.strength;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Bradley-Terry 기저 강도 — "챔피언 자체 강도만으로 예상되는 승률" (D14).
 *
 * <p>raw 승률에는 챔피언 자체 강도가 섞여 있다. Werewolf 기저 승률이 61% 라 raw 정렬로는
 * Werewolf 가 거의 모든 상대에서 상위를 차지하는데, 그건 카운터표가 아니라 센 챔피언 목록이다.
 * 여기서 구한 기대 승률을 실제에서 빼야 진짜 상성이 남는다.
 *
 * <p><b>왜 승률을 그대로 쓰지 않나.</b> 드래프트는 무작위 매칭이 아니다. 어떤 챔피언은 센
 * 상대만 만나고 어떤 챔피언은 약한 상대만 만난다. 승률은 그 편차를 강도로 착각한다.
 * MM 반복은 "누구를 이겼는가" 를 함께 본다. 강도를 승산 {@code p/(1-p)} 로 주면
 * {@link #expected}는 D14 에 적힌 공식과 정확히 같은 값을 낸다.
 *
 * <p><b>가상 관측으로 정규화한다.</b> 전승 챔피언의 강도는 원래 무한대로 발산하고,
 * 서로 붙은 적 없는 두 무리는 상대 크기가 정해지지 않는다. 강도 1 인 가상의 상대와
 * {@value #PRIOR_GAMES} 경기를 반씩 나눠 가진 것으로 쳐서 둘 다 막는다. 이 값은 화면에
 * 드러나는 임계값이 아니라 적합이 유한하게 끝나게 하는 수치 장치라서
 * {@code analysis_config} 로 빼지 않았다.
 */
public final class BradleyTerry {

    /** 가상 상대와 치른 것으로 치는 경기 수. 절반은 승, 절반은 패. */
    private static final double PRIOR_GAMES = 1.0;
    /** 가상 상대의 강도. 이 값이 고정이라 결과 강도의 절대 크기에도 의미가 생긴다(1 = 가상 평균). */
    private static final double PRIOR_STRENGTH = 1.0;

    private static final int MAX_ITERATIONS = 10_000;
    private static final double TOLERANCE = 1e-12;

    private BradleyTerry() {
    }

    /**
     * 한 관측 = "{@code winnerId} 가 {@code loserId} 를 이겼다".
     *
     * <p>4v4 라 한 경기에서 이런 관측이 16개 나온다(승리 팀 4명 × 패배 팀 4명).
     * {@code weight} 는 이중 감쇠 가중치다 (D15a).
     */
    public record Outcome(int winnerId, int loserId, double weight) {
        public Outcome {
            if (winnerId == loserId) {
                throw new IllegalArgumentException(
                        "같은 챔피언끼리의 관측이다: " + winnerId
                                + ". 한 경기에 같은 챔피언은 두 번 나올 수 없다(match_participant_unique_champ)");
            }
            if (!(weight > 0) || !Double.isFinite(weight)) {
                throw new IllegalArgumentException(
                        "가중치가 " + weight + " 다. 0 이하나 NaN 이면 그 관측이 조용히 사라진다");
            }
        }
    }

    /** 챔피언 id → 기저 강도. 관측에 등장한 챔피언만 담는다. */
    public static Map<Integer, Double> fit(Collection<Outcome> outcomes) {
        int[] ids = distinctIds(outcomes);
        int n = ids.length;
        if (n == 0) {
            return Map.of();
        }

        Map<Integer, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            index.put(ids[i], i);
        }

        double[] wins = new double[n];
        double[][] pairGames = new double[n][n];
        for (Outcome outcome : outcomes) {
            int w = index.get(outcome.winnerId());
            int l = index.get(outcome.loserId());
            wins[w] += outcome.weight();
            pairGames[w][l] += outcome.weight();
            pairGames[l][w] += outcome.weight();
        }
        for (int i = 0; i < n; i++) {
            wins[i] += PRIOR_GAMES / 2;
        }

        double[] strength = new double[n];
        java.util.Arrays.fill(strength, 1.0);

        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            double[] next = new double[n];
            double maxRelativeChange = 0;
            for (int i = 0; i < n; i++) {
                double denominator = PRIOR_GAMES / (strength[i] + PRIOR_STRENGTH);
                for (int j = 0; j < n; j++) {
                    if (pairGames[i][j] > 0) {
                        denominator += pairGames[i][j] / (strength[i] + strength[j]);
                    }
                }
                next[i] = wins[i] / denominator;
                maxRelativeChange = Math.max(maxRelativeChange,
                        Math.abs(next[i] - strength[i]) / strength[i]);
            }
            strength = next;
            if (maxRelativeChange < TOLERANCE) {
                return toMap(ids, strength);
            }
        }
        throw new IllegalStateException(
                "Bradley-Terry 적합이 " + MAX_ITERATIONS + "회 안에 수렴하지 않았다."
                        + " 수렴하지 않은 강도를 그대로 쓰면 기대 승률이 조용히 틀린다");
    }

    /**
     * 두 강도로 예측되는 승률. {@code s_A / (s_A + s_B)}.
     *
     * <p>강도를 승산으로 주면 D14 의
     * {@code p_A(1-p_B) / (p_A(1-p_B) + p_B(1-p_A))} 와 대수적으로 같은 식이다.
     */
    public static double expected(double strengthA, double strengthB) {
        requirePositive(strengthA, "A");
        requirePositive(strengthB, "B");
        return strengthA / (strengthA + strengthB);
    }

    private static int[] distinctIds(Collection<Outcome> outcomes) {
        TreeSet<Integer> ids = new TreeSet<>();
        for (Outcome outcome : outcomes) {
            ids.add(outcome.winnerId());
            ids.add(outcome.loserId());
        }
        int[] result = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) {
            result[i++] = id;
        }
        return result;
    }

    private static Map<Integer, Double> toMap(int[] ids, double[] strength) {
        Map<Integer, Double> result = new LinkedHashMap<>();
        for (int i = 0; i < ids.length; i++) {
            result.put(ids[i], strength[i]);
        }
        return result;
    }

    private static void requirePositive(double strength, String side) {
        if (!(strength > 0) || !Double.isFinite(strength)) {
            throw new IllegalArgumentException(
                    side + " 의 강도가 " + strength + " 다. 0 이하나 무한대면 기대 승률이 0·1·NaN 으로 굳는다");
        }
    }
}
