package com.teamfighter.tfm.ingest.watcher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.teamfighter.tfm.ingest.watcher.WatcherTestSupport.assertStaysFalseFor;
import static com.teamfighter.tfm.ingest.watcher.WatcherTestSupport.awaitUntil;

/**
 * {@link SaveWatcher} 의 견고성을 고정한다 — "실패를 조용히 삼키지 않는다" 규칙.
 *
 * <p>여기서 검증해야 하는 실패 모드가 둘이다: (1) 예외를 삼켜서 아무 흔적도 안 남기는 것,
 * (2) 예외 때문에 루프 자체가 죽는 것. 둘 다 실패고, 정답은 "로그로 남기되 루프는 계속"이다.
 * 그래서 아래 테스트는 <b>로그 캡처</b>와 <b>루프 생존 확인</b>을 둘 다 따로 단언한다 —
 * 하나만 확인하면 나머지 실패 모드를 놓친다.
 */
class SaveWatcherRobustnessTest {

    private static final long DEBOUNCE_MS = 120L;

    private SaveWatcher watcher;
    private FakeIngestService ingest;

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.stop();
        }
    }

    private void start(Path dir) {
        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);
        watcher.start();
    }

    private void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("ingest() 가 예외를 던져도 루프는 죽지 않는다 — 다음 변경에 다시 시도한다")
    void loopSurvivesIngestException_andRetriesOnNextChange(@TempDir Path dir) throws IOException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "v0", StandardCharsets.UTF_8);
        start(dir);
        ingest.throwOnNextCall(new RuntimeException("파싱 실패(테스트로 주입한 예외)"));

        write(slot, "v1");
        awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);

        write(slot, "v2");

        // 변조: 예외를 잡은 뒤 루프 스레드를 그대로 종료시키면(catch 후 return), 두 번째
        // 변경에 대한 재시도가 영영 안 일어나 이 단언이 타임아웃으로 실패한다.
        awaitUntil(() -> ingest.callCount() == 2, DEBOUNCE_MS * 5);
        assertThat(watcher.isRunning()).isTrue();
    }

    @Test
    @DisplayName("ingest() 가 던진 예외는 로그로 남는다 — 조용히 삼키면 안 된다")
    void ingestException_isLogged_notSwallowedSilently(@TempDir Path dir) throws IOException {
        Logger watcherLogger = (Logger) LoggerFactory.getLogger(SaveWatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        watcherLogger.addAppender(appender);

        try {
            Path slot = dir.resolve("slot_a.tfm");
            Files.writeString(slot, "v0", StandardCharsets.UTF_8);
            start(dir);
            ingest.throwOnNextCall(new RuntimeException("파싱 실패(테스트로 주입한 예외)"));

            write(slot, "v1");
            awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);

            // 변조: catch (Exception e) { } 처럼 아무 것도 안 하고 삼키면 로그 이벤트가
            // 하나도 안 남아 이 단언이 실패한다. 예외를 콘솔 stderr 로만 찍고 SLF4J 로거를
            // 안 쓰는 구현도 이 테스트는 실패로 잡는다(ListAppender 는 로거에만 붙는다).
            List<ILoggingEvent> errorLogs = appender.list.stream()
                    .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                    .toList();
            assertThat(errorLogs).isNotEmpty();
        } finally {
            watcherLogger.detachAppender(appender);
        }
    }

    @Test
    @DisplayName("OVERFLOW 이벤트를 받으면 폴더를 다시 훑어 놓친 변경을 따라잡는다")
    void overflow_triggersRescanOfFolder(@TempDir Path dir) throws IOException {
        // 워처를 start() 하지 않는다. 이건 편의가 아니라 이 테스트가 성립하는 조건이다.
        // start() 한 채로 파일을 만들면 정상 CREATE 이벤트만으로도 ingest() 가 불려서,
        // handleOverflow() 를 빈 메서드로 바꿔도 테스트가 통과한다 — 즉 잡겠다는 변조를
        // 못 잡는 테스트가 된다. WatchService 이벤트가 아예 없는 상태로 만들어야
        // "재훑기가 실제로 일을 했는가" 만 남는다.
        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);

        Path missedSlot = dir.resolve("slot_missed.tfm");
        write(missedSlot, "이벤트가 유실된 채로 생긴 슬롯");

        // 기준선 — 감시 스레드가 없으니 이 경로로는 아무 일도 일어나지 않아야 한다.
        assertStaysFalseFor(() -> ingest.callCount() > 0, DEBOUNCE_MS * 2);

        watcher.handleOverflow();

        // 변조: OVERFLOW 처리를 빈 메서드(no-op)로 두면 유실된 변경이 영원히 적재되지 않는다.
        // 이제 ingest() 로 가는 길이 이 경로뿐이라 그 무동작이 여기서 드러난다.
        awaitUntil(() -> ingest.countCallsFor(missedSlot) >= 1, DEBOUNCE_MS * 5);
    }

    @Test
    @DisplayName("워처를 stop() 하면 스레드가 실제로 끝나고, 그 뒤 변경으로는 ingest() 가 안 불린다")
    void stop_actuallyEndsThread_andIgnoresLaterChanges(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "v0", StandardCharsets.UTF_8);
        start(dir);

        // 정지 시점에 "아직 만료되지 않은 디바운스 타이머" 가 실제로 걸려 있어야 한다.
        // 그게 없으면 타이머 취소 로직을 통째로 지워도 아래 단언이 통과해버린다.
        write(slot, "v1");
        Thread.sleep(DEBOUNCE_MS / 2);   // 디바운스 만료 전 — 타이머가 대기 중인 상태

        watcher.stop();

        // 변조: stop() 이 "정지 신호만 세팅하고 스레드 종료를 기다리지 않으면"(join 없음),
        // 이 시점에 스레드가 아직 살아있을 수 있어 isRunning() 이 true 로 남는다.
        assertThat(watcher.isRunning()).isFalse();

        write(slot, "after-stop");

        // 변조: stop() 이 스레드만 죽이고 아직 큐에 남아있던 디바운스 타이머를 취소하지
        // 않으면, 정지 후에도 지연된 ingest() 호출이 한 번 더 발생할 수 있다.
        assertStaysFalseFor(() -> ingest.callCount() > 0, DEBOUNCE_MS * 5);
    }

    @Test
    @DisplayName("디바운스가 0 이하면 생성 시점에 죽는다 — 0 이면 쓰는 도중에 읽는다 (D12)")
    void nonPositiveDebounce_failsAtConstruction(@TempDir Path dir) {
        // 변조: 이 검사를 지우면 스케줄러가 이벤트를 받자마자 실행한다. 게임이 쓰는 도중에
        // 읽어 NRBF 스트림이 잘리는데, 증상은 워처가 아니라 파서에서 "적재 실패" 로만 나온다 —
        // 설정 실수가 다른 계층의 잡음으로 둔갑해 원인 추적이 어려워진다.
        assertThatThrownBy(() -> new SaveWatcher(dir, 0L, new FakeIngestService()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("양수");
        assertThatThrownBy(() -> new SaveWatcher(dir, -1L, new FakeIngestService()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("stop() 은 이미 시작된 적재가 끝날 때까지 기다린다 — 먼저 반환하면 컨테이너가 DB 를 먼저 닫는다")
    void stop_waitsForInFlightIngest(@TempDir Path dir) throws IOException, InterruptedException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "v0", StandardCharsets.UTF_8);
        start(dir);

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ingest.blockOnNextCall(entered, release);

        write(slot, "v1");
        assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("적재가 실제로 시작돼야 이 테스트가 의미가 있다")
                .isTrue();

        AtomicBoolean stopReturned = new AtomicBoolean(false);
        Thread stopper = new Thread(() -> {
            watcher.stop();
            stopReturned.set(true);
        }, "stop-caller");
        stopper.start();

        // 변조: stop() 에서 scheduler.awaitTermination(...) 을 빼면, 적재가 아직 도는 중인데도
        // stop() 이 즉시 반환해 이 단언이 실패한다. 운영에서는 그 뒤에 Spring 이 DataSource 를
        // 닫아, 멀쩡히 돌던 적재가 종료 순서 때문에 실패한다.
        assertStaysFalseFor(stopReturned::get, DEBOUNCE_MS * 4);

        release.countDown();
        awaitUntil(stopReturned::get, 5_000);
        stopper.join(TimeUnit.SECONDS.toMillis(5));
    }

    @Test
    @DisplayName("stop() 뒤에 start() 를 다시 부르면 시끄럽게 죽는다 — 조용히 아무 일도 안 하면 안 된다")
    void restartAfterStop_failsLoudly_ratherThanSilentlyDoingNothing(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("slot_a.tfm"), "v0", StandardCharsets.UTF_8);
        start(dir);
        watcher.stop();

        // 디바운스 스케줄러는 한 번 shutdown 되면 되살릴 수 없다. 그런데 감시 스레드만 다시
        // 뜨면 isRunning() 은 true 가 되고, 이벤트는 계속 들어오는데 적재는 한 건도 안 된다 —
        // 로그 한 줄 없이. 이 프로젝트가 Flyway 로 이미 한 번 당한 실패 방식이 정확히 이거다.
        // 되살릴 수 없다면 되살릴 수 있는 척도 하면 안 된다.
        assertThatThrownBy(() -> watcher.start())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("다시 시작할 수 없다");

        assertThat(watcher.isRunning()).isFalse();
    }

    @Test
    @DisplayName("같은 내용이 다시 저장돼도 워처는 그냥 ingest() 를 부른다 — 중복 판정은 워처의 일이 아니다")
    void sameContentSavedAgain_stillCallsIngest_watcherDoesNotDeduplicate(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "동일한 내용", StandardCharsets.UTF_8);
        start(dir);

        write(slot, "재저장1");
        awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);

        Thread.sleep(DEBOUNCE_MS); // 안정화 — 다음 변경과 섞이지 않게

        // 내용은 바뀌지 않았지만(같은 텍스트를 다시 씀) 워처 입장에서는 그냥 "쓰기 이벤트"다.
        write(slot, "재저장1");

        // 변조: 워처가 내용 해시를 캐시해두고 "직전과 같으면 건너뛴다" 를 자체 구현하면
        // 이 두 번째 호출이 안 일어난다. 그러면 D17 의 "쓰기 3~4건 중 1건은 경기 증가 없음"
        // 판단을 IngestService 대신 워처가 잘못 가로채게 된다 — 이 단언이 그 회귀를 잡는다.
        awaitUntil(() -> ingest.callCount() == 2, DEBOUNCE_MS * 5);
    }
}
