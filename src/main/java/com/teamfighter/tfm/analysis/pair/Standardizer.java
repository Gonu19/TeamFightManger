package com.teamfighter.tfm.analysis.pair;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 챔피언별로 평균 0 · 표준편차 1 로 맞춘다.
 *
 * <h2>왜 챔피언별인가</h2>
 *
 * 딜의 규모가 챔피언마다 다르다 — 법사와 탱커를 같은 자로 재면 "법사가 딜이 높다" 가
 * 모든 조합 효과를 덮어버린다. 챔피언별로 표준화하면 <b>챔피언 주효과가 0 이 되고</b>
 * 남는 것만 모형이 본다. 그 남은 것이 우리가 찾는 조합 효과다.
 *
 * <h2>학습 폴드에서만 구한다</h2>
 *
 * 검증 폴드의 값으로 평균·표준편차를 구하면 <b>정답을 훔쳐보는 것</b>이 된다.
 * 교차검증 점수가 실제보다 좋게 나오고, 그 점수를 보고 고른 λ 가 틀린 값이 된다.
 * 그래서 {@link #from} 은 학습 행만 받고, 그렇게 만든 자를 검증 행에도 그대로 쓴다.
 *
 * <p>학습에 없던 챔피언은 전체 평균·표준편차로 잰다. 버리지 않는 이유는 그 행에도
 * 동료·상대 정보가 들어 있기 때문이다 — 주인공을 몰라도 옆 사람은 안다.
 */
public final class Standardizer {

    /** 표준편차가 이보다 작으면 0 으로 본다. 나누면 값이 폭발한다. */
    private static final double MIN_SD = 1e-9;

    private final Map<Integer, double[]> byChampion;   // 챔피언 → {평균, 표준편차}
    private final double globalMean;
    private final double globalSd;

    private Standardizer(Map<Integer, double[]> byChampion, double globalMean, double globalSd) {
        this.byChampion = byChampion;
        this.globalMean = globalMean;
        this.globalSd = globalSd;
    }

    /** 학습 행에서 자를 만든다. */
    public static Standardizer from(List<PairObservation> train) {
        if (train.isEmpty()) {
            throw new IllegalArgumentException("학습 행이 없다 — 표준화할 것이 없다");
        }

        Map<Integer, List<Double>> byChamp = new HashMap<>();
        double sum = 0.0;
        for (PairObservation row : train) {
            byChamp.computeIfAbsent(row.championId(), key -> new ArrayList<>()).add(row.value());
            sum += row.value();
        }

        double globalMean = sum / train.size();
        double globalVar = 0.0;
        for (PairObservation row : train) {
            globalVar += (row.value() - globalMean) * (row.value() - globalMean);
        }
        double globalSd = Math.sqrt(globalVar / train.size());
        if (globalSd < MIN_SD) {
            // 모든 값이 같다. 어떤 자를 대도 z 는 0 이므로 1 로 두어 나눗셈만 성립시킨다.
            globalSd = 1.0;
        }

        Map<Integer, double[]> stats = new HashMap<>();
        for (Map.Entry<Integer, List<Double>> entry : byChamp.entrySet()) {
            List<Double> values = entry.getValue();
            double mean = 0.0;
            for (double v : values) {
                mean += v;
            }
            mean /= values.size();

            double var = 0.0;
            for (double v : values) {
                var += (v - mean) * (v - mean);
            }
            double sd = Math.sqrt(var / values.size());

            // 한 번만 나온 챔피언은 표준편차가 0 이다. 그 자로 나누면 무한이 되므로
            // 전체 표준편차를 빌린다 — 값은 거칠어지지만 행이 살아남는다.
            stats.put(entry.getKey(), new double[] {mean, sd < MIN_SD ? globalSd : sd});
        }
        return new Standardizer(stats, globalMean, globalSd);
    }

    /** 그 관측의 z. 모르는 챔피언은 전체 자로 잰다. */
    public double z(PairObservation row) {
        double[] stat = byChampion.get(row.championId());
        if (stat == null) {
            return (row.value() - globalMean) / globalSd;
        }
        return (row.value() - stat[0]) / stat[1];
    }
}
