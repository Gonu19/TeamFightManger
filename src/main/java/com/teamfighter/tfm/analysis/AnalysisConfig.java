package com.teamfighter.tfm.analysis;

import java.math.BigDecimal;
import java.util.Map;

/**
 * {@code analysis_config} 테이블의 임계값. 집계가 쓰는 모든 상수가 여기로 들어온다.
 *
 * <p><b>기본값을 코드에 두지 않는다.</b> 두는 순간 임계값이 시드(V1)와 코드에 두 벌이 되어
 * 반드시 어긋난다 — {@code schema.sql} 을 없앤 것과 같은 이유다. 키가 없으면 던진다.
 * 조용히 기본값으로 넘어가면 "설정을 바꿨는데 결과가 안 바뀐다" 가 되는데, 그건 죽지도
 * 동작하지도 않는 상태라 로그만 보면 성공으로 보인다.
 *
 * <p>모르는 키는 무시한다. 설정 테이블에 항목을 추가하는 마이그레이션이 구버전 앱의 기동을
 * 막을 이유가 없다 — 없는 것은 위험하지만 남는 것은 위험하지 않다.
 */
public record AnalysisConfig(
        /** 이 경기 수 미만인 조합은 화면에 노출하지 않는다 (D9). */
        int minSample,
        /** 1단 축소 강도. 전체 누적 추정을 챔피언 강도 기대값 쪽으로 당긴다 (D15b). */
        double priorK0,
        /** 2단 축소 강도. 패치별 추정을 전체 누적 추정 쪽으로 당긴다 (D15b). */
        double priorK1,
        /** 시너지 조합 최대 크기 (D11). */
        int synergyMaxSize,
        /** 자기 변경 감쇠 반감기(변경 횟수). 1차 효과라 짧다 (D15a). */
        double selfDecayHalfLife,
        /** 메타 변화 감쇠 반감기(경과 패치 수). 2차 효과라 길다 (D15a). */
        double metaDecayHalfLife) {

    public static final String MIN_SAMPLE = "min_sample";
    public static final String PRIOR_K0 = "prior_strength_k0";
    public static final String PRIOR_K1 = "prior_strength_k1";
    public static final String SYNERGY_MAX_SIZE = "synergy_max_size";
    public static final String SELF_DECAY_HALF_LIFE = "self_decay_half_life";
    public static final String META_DECAY_HALF_LIFE = "meta_decay_half_life";

    public AnalysisConfig {
        requirePositive(minSample, 1, MIN_SAMPLE, "1경기 100% 승률이 상위로 올라온다 (D9)");
        requireNonNegative(priorK0, PRIOR_K0);
        requireNonNegative(priorK1, PRIOR_K1);
        if (synergyMaxSize < 2 || synergyMaxSize > 3) {
            throw new IllegalArgumentException(
                    SYNERGY_MAX_SIZE + " 는 2~3 이어야 한다: " + synergyMaxSize
                            + ". 4인 챔피언 조합은 최다 9경기라 통계가 아니다 (D11)");
        }
        requireHalfLife(selfDecayHalfLife, SELF_DECAY_HALF_LIFE);
        requireHalfLife(metaDecayHalfLife, META_DECAY_HALF_LIFE);
    }

    /** {@code SELECT key, value FROM analysis_config} 결과를 그대로 받는다. */
    public static AnalysisConfig from(Map<String, BigDecimal> values) {
        return new AnalysisConfig(
                require(values, MIN_SAMPLE).intValueExact(),
                require(values, PRIOR_K0).doubleValue(),
                require(values, PRIOR_K1).doubleValue(),
                require(values, SYNERGY_MAX_SIZE).intValueExact(),
                require(values, SELF_DECAY_HALF_LIFE).doubleValue(),
                require(values, META_DECAY_HALF_LIFE).doubleValue());
    }

    private static BigDecimal require(Map<String, BigDecimal> values, String key) {
        BigDecimal value = values.get(key);
        if (value == null) {
            throw new IllegalStateException(
                    "analysis_config 에 " + key + " 가 없다. 마이그레이션이 덜 적용됐거나 행이 지워졌다");
        }
        return value;
    }

    private static void requirePositive(int value, int min, String key, String consequence) {
        if (value < min) {
            throw new IllegalArgumentException(key + " 는 " + min + " 이상이어야 한다: " + value + ". " + consequence);
        }
    }

    private static void requireNonNegative(double value, String key) {
        if (!(value >= 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    key + " 는 0 이상이어야 한다: " + value + ". 음수면 추정이 관측과 목표값 밖으로 튄다");
        }
    }

    private static void requireHalfLife(double value, String key) {
        if (!(value > 0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException(
                    key + " 는 0 보다 커야 한다: " + value
                            + ". 0 이면 가중치가 전부 0 이 되어 표본이 통째로 사라지고,"
                            + " 음수면 오래된 데이터가 오히려 증폭된다");
        }
    }
}
