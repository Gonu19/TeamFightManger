package com.teamfighter.tfm.ingest.watcher;

import com.teamfighter.tfm.ingest.IngestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.teamfighter.tfm.ingest.watcher.WatcherTestSupport.awaitUntil;

/**
 * {@link StartupCatchUp} 의 계약을 고정한다 — 기동 시 따라잡기가 실제로 배선되는지.
 *
 * <p>DB 는 안 쓴다 — {@link FakeIngestService} (절대 규칙 4). {@link SaveWatcher} 는
 * {@code final} 클래스라 Mockito 로 목킹할 수 없으므로, 실제 인스턴스 +
 * {@link FakeIngestService} 조합으로 관측 가능한 결과(ingest 호출)를 통해 검증한다.
 *
 * <p><b>주의.</b> {@link ApplicationContextRunner} 는 일반 {@code ApplicationContext} 만
 * 띄운다 — {@code SpringApplication.run()} 이 하는 "등록된 {@link ApplicationRunner} 빈을
 * 기동 직후 한 번씩 부른다" 는 동작은 하지 않는다. 그래서 "빈이 올바른 조건으로 등록되는가"
 * 는 {@link ApplicationContextRunner} 로, "등록되면 실제로 무슨 일을 하는가" 는
 * {@link StartupCatchUp} 을 직접 만들어 {@code run()} 을 호출하는 방식으로 나눠 검증한다.
 * 실제 앱 기동에서 "정확히 한 번" 불리는 것은 Spring Boot 의 {@code ApplicationRunner}
 * 계약 자체가 보장한다 — 이 테스트가 새로 보장하는 것은 아니다.
 */
class StartupCatchUpTest {

    private static final long DEBOUNCE_MS = 120L;

    private void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("run() 은 rescan() 을 위임한다 — 기동 시 있던 슬롯이 전부 따라잡힌다")
    void run_delegatesToRescan_catchesUpAllExistingSlots(@TempDir Path dir) throws IOException {
        Path slotA = dir.resolve("slot_a.tfm");
        Path slotB = dir.resolve("slot_b.tfm");
        write(slotA, "a0");
        write(slotB, "b0");

        FakeIngestService ingest = new FakeIngestService();
        SaveWatcher watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);
        StartupCatchUp catchUp = new StartupCatchUp(watcher);

        try {
            catchUp.run(new DefaultApplicationArguments());

            // 변조: run() 을 빈 메서드로 두거나 rescan() 대신 아무 일도 안 하는 코드를 넣으면
            // 기동 시 있던 슬롯이 영원히 안 잡힌다 — 이 단언이 그 회귀를 잡는다.
            awaitUntil(() -> ingest.callCount() == 2, DEBOUNCE_MS * 5);
            assertThat(ingest.calls()).containsExactlyInAnyOrder(slotA, slotB);
        } finally {
            watcher.stop();
        }
    }

    @Test
    @DisplayName("tfm.watch-enabled=true 면 StartupCatchUp 이 ApplicationRunner 빈으로 등록된다")
    void startupCatchUp_isRegisteredAsApplicationRunnerBean_whenWatchEnabled(@TempDir Path dir) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(WatcherConfig.class, TfmPropertiesConfig.class)
                .withBean(IngestService.class, FakeIngestService::new)
                .withPropertyValues(
                        "tfm.save-dir=" + dir,
                        "tfm.watch-debounce-ms=" + DEBOUNCE_MS,
                        "tfm.watch-enabled=true");

        // 변조: WatcherConfig 에서 startupCatchUp 빈 등록 메서드를 빼먹으면 이 단언이 실패한다.
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(StartupCatchUp.class);
            assertThat(ctx.getBean(StartupCatchUp.class)).isInstanceOf(ApplicationRunner.class);
        });
    }

    @Test
    @DisplayName("tfm.watch-enabled=false 면 StartupCatchUp 빈이 없다 — 워처 없이 따라잡기만 도는 상태 금지")
    void startupCatchUp_isAbsent_whenWatchDisabled(@TempDir Path dir) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withUserConfiguration(WatcherConfig.class, TfmPropertiesConfig.class)
                .withBean(IngestService.class, FakeIngestService::new)
                .withPropertyValues(
                        "tfm.save-dir=" + dir,
                        "tfm.watch-enabled=false");

        // 변조: SaveWatcher 빈에만 @ConditionalOnProperty 를 걸고 startupCatchUp 빈에는
        // 안 걸면(혹은 다른 조건을 걸면), 워처는 없는데 따라잡기 러너만 등록되는 상태가
        // 생긴다 — 그 상태를 이 단언이 잡는다.
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(StartupCatchUp.class);
            assertThat(ctx).doesNotHaveBean(SaveWatcher.class);
        });
    }

    @Test
    @DisplayName("rescan() 이 던진 예외를 run() 은 삼키지 않는다 — 기동을 막는다")
    void run_propagatesRescanException_ratherThanSwallowingIt(@TempDir Path dir) {
        // 결정: 구조적으로 잘못된 경로(세이브 폴더 자체가 없음)면 따라잡기가 아무것도
        // 할 수 없다 — 조용히 넘어가면 "정상 기동" 으로 보이지만 실은 아무 것도 안 적재된다.
        // 슬롯 하나의 ingest() 실패는 SaveWatcher.runIngest() 가 이미 슬롯 단위로 삼켜(로그만
        // 남기고) 여기까지 올라오지 않으므로, 나머지 슬롯 때문에 앱이 못 뜨는 일은 없다.
        Path missingDir = dir.resolve("does-not-exist");
        FakeIngestService ingest = new FakeIngestService();
        SaveWatcher watcher = new SaveWatcher(missingDir, DEBOUNCE_MS, ingest);
        StartupCatchUp catchUp = new StartupCatchUp(watcher);

        // 변조: run() 이 rescan() 호출을 try/catch 로 감싸 로그만 남기고 삼키면, 세이브 폴더
        // 경로가 통째로 잘못됐는데도 앱이 정상 기동한 것처럼 보인다 — 절대 규칙이 금지하는
        // 조용한 무동작이다.
        assertThatThrownBy(() -> catchUp.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class);
    }

    @EnableConfigurationProperties(TfmProperties.class)
    static class TfmPropertiesConfig {
    }
}
