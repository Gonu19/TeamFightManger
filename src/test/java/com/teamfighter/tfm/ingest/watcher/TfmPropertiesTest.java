package com.teamfighter.tfm.ingest.watcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link TfmProperties} 바인딩 계약을 고정한다.
 *
 * <p>DataSource 도, 전체 {@code @SpringBootTest} 컨텍스트도 없다 (절대 규칙 4: DB 에 붙지 마라).
 * {@link ApplicationContextRunner} 는 필요한 자동설정 조각만 골라 뜨는 경량 컨텍스트다 —
 * 검증 대상이 프로퍼티 바인딩뿐이므로 이걸로 충분하다.
 */
class TfmPropertiesTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(TestConfig.class);

    @EnableConfigurationProperties(TfmProperties.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("watch-debounce-ms 기본값은 1500 이다 (D12 근거값)")
    void watchDebounceMs_defaultsTo1500() {
        // 변조: 필드 기본값을 1500L 대신 다른 값(예: 0 또는 1000)으로 바꾸면 이 단언이 깨진다.
        // 실시간 대기 없이 설정값만 확인하므로 결정적이다.
        runner.run(ctx -> {
            TfmProperties props = ctx.getBean(TfmProperties.class);
            assertThat(props.getWatchDebounceMs()).isEqualTo(1500L);
        });
    }

    @Test
    @DisplayName("TFM_SAVE_DIR 이 없으면 ${user.home} 중첩 플레이스홀더가 실제 홈 경로로 치환된다")
    void saveDir_withoutEnvVar_resolvesNestedUserHomePlaceholder() {
        String userHome = System.getProperty("user.home");
        Path expected = Path.of(userHome, "AppData", "LocalLow", "samoyed", "Teamfight Manager");

        // 변조: Binder 가 중첩 플레이스홀더를 한 번만 풀고 멈추면(구현에 따라 가능한 실수)
        // saveDir 문자열에 "${user.home}" 이 그대로 남아 Path 변환 결과가 expected 와 달라진다.
        runner.withPropertyValues(
                        "tfm.save-dir=${TFM_SAVE_DIR:${user.home}/AppData/LocalLow/samoyed/Teamfight Manager}")
                .run(ctx -> {
                    TfmProperties props = ctx.getBean(TfmProperties.class);
                    assertThat(props.getSaveDir()).isEqualTo(expected);
                });
    }

    @Test
    @DisplayName("TFM_SAVE_DIR 이 있으면 그 값이 user.home 기본값을 이긴다")
    void saveDir_withEnvVar_envVarWins() {
        // 변조: 플레이스홀더 기본값 문법을 ":" 대신 다른 걸로 잘못 파싱하게 만들면
        // TFM_SAVE_DIR 이 있어도 무시되고 항상 user.home 쪽 기본값이 나온다.
        runner.withPropertyValues(
                        "TFM_SAVE_DIR=D:\\Custom Saves",
                        "tfm.save-dir=${TFM_SAVE_DIR:${user.home}/AppData/LocalLow/samoyed/Teamfight Manager}")
                .run(ctx -> {
                    TfmProperties props = ctx.getBean(TfmProperties.class);
                    assertThat(props.getSaveDir()).isEqualTo(Path.of("D:\\Custom Saves"));
                });
    }

    @Test
    @DisplayName("경로에 공백이 있어도 하나의 Path 로 바인딩된다 — 잘리지 않는다 (D27)")
    void saveDir_withSpaces_bindsAsSinglePath() {
        // 변조: YAML/프로퍼티 값을 공백으로 토큰화해 배열/리스트로 바인딩하면
        // 여기서 단일 Path 대신 예외가 나거나 첫 토큰만 남는다.
        runner.withPropertyValues("tfm.save-dir=D:\\Games\\Teamfight Manager\\Saves")
                .run(ctx -> {
                    TfmProperties props = ctx.getBean(TfmProperties.class);
                    assertThat(props.getSaveDir())
                            .isEqualTo(Path.of("D:\\Games\\Teamfight Manager\\Saves"));
                });
    }

    @Test
    @DisplayName("read-only 기본값은 true 다 — 세이브 파일은 절대 쓰지 않는다")
    void readOnly_defaultsToTrue() {
        // 변조: 기본값을 false 로 바꾸면 운영에서 명시적으로 false 를 넣지 않아도 쓰기 경로가
        // 열려버린다 — 이 프로젝트는 세이브 파일을 절대 쓰지 않는다는 계약이 있다.
        runner.run(ctx -> {
            TfmProperties props = ctx.getBean(TfmProperties.class);
            assertThat(props.isReadOnly()).isTrue();
        });
    }
}
