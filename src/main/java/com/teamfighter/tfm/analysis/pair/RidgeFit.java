package com.teamfighter.tfm.analysis.pair;

import java.util.ArrayList;
import java.util.List;

/**
 * 이진 특성만 있는 릿지 회귀를 <b>좌표하강</b>으로 푼다.
 *
 * <h2>왜 행렬을 안 쓰는가</h2>
 *
 * 설계행렬이 <b>전부 0 아니면 1</b> 이다. 한 행(= 경기에 나온 챔피언 하나)이 켜는 특성은
 * 여덟 개 남짓인데(팀 하나 · 동료 셋 · 상대 넷 · 역할군 일곱) 특성 총수는 수천 개다.
 * 그런 행렬을 정면으로 풀면 수천 × 수천 을 만들어 뒤집어야 하고, 그건 이 문제에
 * 필요하지 않은 계산이다.
 *
 * <p>값이 전부 1 이면 좌표 하나의 최적해가 <b>나눗셈 한 번</b>으로 닫힌다:
 *
 * <pre>
 *   θ_j ← θ_j + (Σ_{i∈cells(j)} w_i·r_i − λ_j·θ_j) / (Σ_{i∈cells(j)} w_i + λ_j)
 * </pre>
 *
 * 여기서 {@code r} 은 잔차, {@code w} 는 행 가중치, {@code cells(j)} 는 그 특성이 켜진
 * 행들이다. 분모의 {@code Σw} 가 원래 자리는 {@code Σw·x²} 인데 x 가 1 뿐이라 같다.
 *
 * <h2>가중치는 나중에 붙었다 (D78)</h2>
 *
 * 처음에는 없었다 — 모든 행이 무게 1 이었고 갱신식의 분모가 <b>행의 개수</b>였다.
 * 패치 감쇠를 넣으면서(D78) 무게가 행마다 달라졌고, 그때 개수가 {@code Σw} 로 바뀌었다.
 * 무게가 전부 1 이면 두 식은 같은 값을 낸다.
 *
 * <h2>참조 구현</h2>
 *
 * {@code tools/perf_champion.py} 의 {@code fit()} 을 옮긴 것이다. 그쪽이 D63~D65 의
 * 측정을 낸 코드이고, <b>가중치 1 에서는 같은 입력에 같은 답을 내야 한다</b> — 그래야
 * 저 문서의 t 값과 상위 쌍 표가 이 앱의 화면을 설명한다. 파이썬 쪽에는 가중치가 없으므로,
 * 그 등가를 {@code RidgeFitTest} 가 고정한다. 상수(쓸기 60회 · 수렴 1e-9)도 같은 값이다.
 */
public final class RidgeFit {

    /**
     * 좌표를 훑는 횟수의 상한.
     *
     * <p>보통 그 전에 수렴해 빠져나간다. 상한이 있는 이유는 <b>안 끝나는 경우를 막기</b>
     * 위해서다 — 릿지가 0 이고 특성이 완전히 겹치면 이론상 계속 조금씩 움직인다.
     */
    private static final int MAX_SWEEPS = 60;

    /** 한 쓸기에서 가장 크게 움직인 값이 이보다 작으면 끝난 것으로 본다. */
    private static final double TOLERANCE = 1e-9;

    private RidgeFit() {
    }

    /**
     * @param rows   행마다 <b>켜진 특성 번호 목록</b>과 목표값. 값이 전부 1 이라 번호만 있으면 된다
     * @param params 특성 총수
     * @param ridge  특성마다의 λ. 팀 강도와 조합 효과에 다른 값을 주려고 배열이다
     * @return 특성마다의 계수 θ. 길이는 {@code params}
     */
    public static double[] fit(List<Row> rows, int params, double[] ridge) {
        double[] theta = new double[params];
        double[] resid = new double[rows.size()];
        double[] weight = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            resid[i] = rows.get(i).target();
            weight[i] = rows.get(i).weight();
        }

        // 특성 → 그 특성이 켜진 행 번호들. 한 번 만들어 두고 쓸기마다 재사용한다 —
        // 매번 전체 행을 훑으면 (쓸기 60) × (행 수) × (특성 수) 가 된다.
        List<int[]> cells = invert(rows, params);

        // 분모는 행의 개수가 아니라 무게의 합이다. 감쇠로 눌린 행이 많은 특성은
        // 분모가 작아지는 것이 아니라 <b>분자도 같이 작아진다</b> — 즉 그 특성의
        // 계수는 릿지 쪽으로 더 끌린다. 오래된 쌍이 조용해지는 것이 그 뜻이다.
        double[] denom = new double[params];
        for (int j = 0; j < params; j++) {
            double sum = 0.0;
            for (int i : cells.get(j)) {
                sum += weight[i];
            }
            denom[j] = sum + ridge[j];
        }

        for (int sweep = 0; sweep < MAX_SWEEPS; sweep++) {
            double moved = 0.0;
            for (int j = 0; j < params; j++) {
                int[] cell = cells.get(j);
                if (cell.length == 0) {
                    continue;                                  // 학습에 한 번도 안 나온 특성
                }
                double sum = 0.0;
                for (int i : cell) {
                    sum += weight[i] * resid[i];
                }
                double delta = (sum - ridge[j] * theta[j]) / denom[j];
                if (delta == 0.0) {
                    continue;
                }
                theta[j] += delta;
                for (int i : cell) {
                    resid[i] -= delta;                         // 잔차를 그 자리에서 갱신한다
                }
                moved = Math.max(moved, Math.abs(delta));
            }
            if (moved < TOLERANCE) {
                break;
            }
        }
        return theta;
    }

    /** 예측값과 목표값의 제곱오차. 교차검증이 이 값을 모아 모형을 고른다. */
    public static double[] squaredErrors(List<Row> rows, double[] theta) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            double predicted = 0.0;
            for (int j : row.features()) {
                predicted += theta[j];
            }
            out[i] = (row.target() - predicted) * (row.target() - predicted);
        }
        return out;
    }

    /**
     * 특성 → 켜진 행 목록. 두 번 훑는다 — 먼저 세고, 그다음 채운다.
     *
     * <p>{@code List<List<Integer>>} 로 만들면 행 하나마다 {@code Integer} 가 박싱되는데,
     * 실측 6,440행 × 특성 8개면 5만 개다. 여기서는 그냥 배열이 맞다.
     */
    private static List<int[]> invert(List<Row> rows, int params) {
        int[] counts = new int[params];
        for (Row row : rows) {
            for (int j : row.features()) {
                counts[j]++;
            }
        }

        List<int[]> cells = new ArrayList<>(params);
        for (int j = 0; j < params; j++) {
            cells.add(new int[counts[j]]);
        }

        int[] filled = new int[params];
        for (int i = 0; i < rows.size(); i++) {
            for (int j : rows.get(i).features()) {
                cells.get(j)[filled[j]++] = i;
            }
        }
        return cells;
    }

    /**
     * 적합에 넘기는 한 행.
     *
     * @param features 켜진 특성 번호. <b>값은 안 담는다</b> — 전부 1 이기 때문이고,
     *                 그 사실이 위 나눗셈 한 번짜리 갱신식을 성립시킨다
     * @param target   맞출 값. 여기서는 챔피언별로 표준화한 z 다
     * @param weight   이 행의 무게. 패치 감쇠가 여기로 들어온다 (D78).
     *                 <b>음수는 안 된다</b> — 오래된 경기가 최신보다 무거워지는 것을
     *                 넘어, 잔차를 반대로 밀어 적합이 발산한다
     */
    public record Row(int[] features, double target, double weight) {

        public Row {
            if (weight < 0.0 || Double.isNaN(weight)) {
                throw new IllegalArgumentException("행 가중치가 음수이거나 NaN 이다: " + weight);
            }
        }

        /** 무게 1 짜리 행. 감쇠를 안 거는 자리(시험·교차검증)가 쓴다. */
        public Row(int[] features, double target) {
            this(features, target, 1.0);
        }
    }
}
