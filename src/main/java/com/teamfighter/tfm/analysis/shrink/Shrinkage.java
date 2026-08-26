package com.teamfighter.tfm.analysis.shrink;

/**
 * 2단 축소 — 패치 반영과 표본 부족을 동시에 푸는 구조 (D15b).
 *
 * <pre>
 * 1단  θ_all   = (Σw·win + k₀·expected) / (Σw + k₀)
 * 2단  θ_patch = (Σw_p·win + k₁·θ_all)  / (Σw_p + k₁)
 * </pre>
 *
 * <p>패치별로 완전히 분리하지도, 완전히 합치지도 않는다. 데이터가 많은 패치는 자기 데이터가
 * 지배하고, 적은 패치는 전체 누적값으로 수렴한다. 축소 대상이 3단 계단을 이룬다 —
 * <b>패치 추정 → 전체 누적 추정 → 챔피언 강도 기대값.</b> 데이터가 없을수록 위로 올라가며
 * "모르면 아는 것에 가깝게" 답한다.
 *
 * <p>1단의 목표값이 전체 평균 50% 가 아니라 <b>챔피언 강도 기대값</b>이라는 점이 핵심이다.
 * 50% 로 당기면 약한 챔피언의 정당한 열세까지 중앙으로 밀려, 걷어내려던 챔피언 강도가
 * 오히려 상성 값에 다시 섞인다 (D14).
 *
 * <p>2단에서 원시 경기 수 대신 가중 합계를 쓰는 것은 D15b 의 식과 어긋나지 않는다 —
 * 기준 시점이 그 패치 자신이면 그 패치의 경기는 자기 변경도 경과 패치도 0 이라 가중치가
 * 모두 1 이고, 두 값이 같아진다.
 */
public final class Shrinkage {

    private Shrinkage() {
    }

    /** 1단 — 전체 누적 추정. 목표값은 Bradley-Terry 기대 승률이다. */
    public static double overall(double weightedWins, double weightedGames, double expected, double k0) {
        return shrink(weightedWins, weightedGames, expected, k0, "expected");
    }

    /** 2단 — 특정 패치 추정. 목표값은 1단이 낸 전체 누적 추정이다. */
    public static double byPatch(double weightedWins, double weightedGames, double overall, double k1) {
        return shrink(weightedWins, weightedGames, overall, k1, "overall");
    }

    /**
     * 유효표본수 {@code (Σw)² / Σw²}. 신뢰구간은 원시 경기 수가 아니라 이 값으로 잡는다 (D15).
     *
     * <p>감쇠를 먹인 뒤에는 30경기가 30경기어치 정보가 아니다. 가중치가 한쪽에 몰릴수록
     * 실제 정보량은 경기 수보다 작아진다.
     */
    public static double ess(double sumWeights, double sumSquaredWeights) {
        requireNonNegative(sumWeights, "Σw");
        requireNonNegative(sumSquaredWeights, "Σw²");
        if (sumSquaredWeights == 0) {
            return 0.0;
        }
        return sumWeights * sumWeights / sumSquaredWeights;
    }

    private static double shrink(double wins, double games, double target, double k, String targetName) {
        requireNonNegative(wins, "가중 승수");
        requireNonNegative(games, "가중 경기 수");
        requireNonNegative(k, "축소 강도");
        if (wins > games) {
            throw new IllegalArgumentException(
                    "가중 승수(" + wins + ")가 가중 경기 수(" + games + ")보다 크다."
                            + " 조인이 어긋나 승패가 중복 집계된 것이다 — 그대로 두면 승률이 100% 를 넘는다");
        }
        if (target < 0 || target > 1 || !Double.isFinite(target)) {
            throw new IllegalArgumentException(
                    targetName + " 가 승률 범위 밖이다: " + target);
        }
        double denominator = games + k;
        if (denominator == 0) {
            throw new IllegalArgumentException(
                    "표본도 0 이고 축소 강도도 0 이라 분모가 0 이다."
                            + " 그대로 나누면 NaN 이 되는데, 정렬에서 NaN 은 어떤 비교에도 false 라"
                            + " 순위표가 예외 없이 뒤죽박죽이 된다");
        }
        return (wins + k * target) / denominator;
    }

    private static void requireNonNegative(double value, String field) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " 가 음수이거나 유한하지 않다: " + value);
        }
    }
}
