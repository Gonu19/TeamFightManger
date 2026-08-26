package com.teamfighter.tfm.analysis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AnalysisConfig} 가 {@code analysis_config} 테이블을 읽는 계약을 못 박는다.
 *
 * <p>DB 는 필요 없다 — 맵에서 값을 꺼내 검증하는 일뿐이다.
 *
 * <p><b>기본값을 코드에 두지 않는다.</b> 두면 시드(V1)와 코드에 임계값이 두 벌이 되어
 * 반드시 어긋난다. 그리고 키가 빠졌을 때 조용히 기본값으로 넘어가면 "죽지도 동작하지도 않는"
 * 상태가 된다 — 빈 {@code TFM_SAVE_DIR} 가 현재 디렉터리를 감시하게 만든 것과 같은 모양이다.
 */
class AnalysisConfigTest {

    private static Map<String, BigDecimal> seeded() {
        Map<String, BigDecimal> m = new HashMap<>();
        m.put("min_sample", new BigDecimal("10"));
        m.put("prior_strength_k0", new BigDecimal("24"));
        m.put("prior_strength_k1", new BigDecimal("15"));
        m.put("synergy_max_size", new BigDecimal("3"));
        m.put("self_decay_half_life", new BigDecimal("2"));
        m.put("meta_decay_half_life", new BigDecimal("12"));
        return m;
    }

    @Test
    @DisplayName("V1 시드값 여섯 개를 그대로 읽는다")
    void from_readsSeededValues() {
        AnalysisConfig config = AnalysisConfig.from(seeded());

        assertThat(config.minSample()).isEqualTo(10);
        assertThat(config.priorK0()).isEqualTo(24.0);
        assertThat(config.priorK1()).isEqualTo(15.0);
        assertThat(config.synergyMaxSize()).isEqualTo(3);
        assertThat(config.selfDecayHalfLife()).isEqualTo(2.0);
        assertThat(config.metaDecayHalfLife()).isEqualTo(12.0);
    }

    @Test
    @DisplayName("키가 하나라도 빠지면 던진다 — 기본값으로 조용히 넘어가지 않는다")
    void from_missingKeyThrows() {
        for (String key : seeded().keySet()) {
            Map<String, BigDecimal> m = seeded();
            m.remove(key);

            // 변조: 빠진 키에 기본값을 채워 넣으면 이 단언이 깨진다. 그게 이 테스트가 막는 것이다.
            assertThatThrownBy(() -> AnalysisConfig.from(m))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(key);
        }
    }

    @Test
    @DisplayName("알 수 없는 키는 무시한다 — 설정 테이블이 나중에 늘어도 기동이 막히면 안 된다")
    void from_ignoresUnknownKeys() {
        Map<String, BigDecimal> m = seeded();
        m.put("some_future_knob", new BigDecimal("7"));

        assertThat(AnalysisConfig.from(m).minSample()).isEqualTo(10);
    }

    @Test
    @DisplayName("반감기 0 은 던진다 — 0 으로 나누면 가중치가 전부 0 이 되어 표본이 통째로 사라진다")
    void constructor_zeroHalfLifeThrows() {
        assertThatThrownBy(() -> new AnalysisConfig(10, 24, 15, 3, 0, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("self_decay_half_life");
        assertThatThrownBy(() -> new AnalysisConfig(10, 24, 15, 3, 2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("meta_decay_half_life");
    }

    @Test
    @DisplayName("반감기가 음수면 던진다 — 오래된 데이터가 오히려 증폭된다")
    void constructor_negativeHalfLifeThrows() {
        assertThatThrownBy(() -> new AnalysisConfig(10, 24, 15, 3, -2, 12))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisConfig(10, 24, 15, 3, 2, -12))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("표본 기준선이 1 미만이면 던진다 — 0 이면 1경기 100% 가 상위로 올라온다 (D9)")
    void constructor_minSampleBelowOneThrows() {
        assertThatThrownBy(() -> new AnalysisConfig(0, 24, 15, 3, 2, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("min_sample");
    }

    @Test
    @DisplayName("축소 강도가 음수면 던진다 — 분모가 0 이 되거나 추정이 관측 밖으로 튄다")
    void constructor_negativePriorThrows() {
        assertThatThrownBy(() -> new AnalysisConfig(10, -1, 15, 3, 2, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prior_strength_k0");
        assertThatThrownBy(() -> new AnalysisConfig(10, 24, -1, 3, 2, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("prior_strength_k1");
    }

    @Test
    @DisplayName("시너지 조합 크기는 2~3 만 허용한다 — 4인은 최다 9경기라 통계가 아니다 (D11)")
    void constructor_synergySizeOutOfRangeThrows() {
        assertThatThrownBy(() -> new AnalysisConfig(10, 24, 15, 1, 2, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("synergy_max_size");
        assertThatThrownBy(() -> new AnalysisConfig(10, 24, 15, 4, 2, 12))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("synergy_max_size");
    }

    @Test
    @DisplayName("null 값은 던진다 — numeric 컬럼이 NULL 이면 계산이 조용히 NaN 이 된다")
    void from_nullValueThrows() {
        Map<String, BigDecimal> m = seeded();
        m.put("prior_strength_k0", null);

        assertThatThrownBy(() -> AnalysisConfig.from(m))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prior_strength_k0");
    }
}
