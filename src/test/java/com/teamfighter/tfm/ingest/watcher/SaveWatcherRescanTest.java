package com.teamfighter.tfm.ingest.watcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static com.teamfighter.tfm.ingest.watcher.WatcherTestSupport.assertStaysFalseFor;
import static com.teamfighter.tfm.ingest.watcher.WatcherTestSupport.awaitUntil;

/**
 * {@link SaveWatcher#rescan()} 의 계약을 고정한다 — 기동 시 따라잡기(D12/D17/D28)의 핵심.
 *
 * <p>{@code rescan()} 은 {@code OVERFLOW} 처리와 {@link StartupCatchUp} 이 공유하는
 * 하나의 메서드다. 여기서 검증하는 것은 그 메서드 자체의 계약이고, {@code OVERFLOW} 경로
 * 배선은 {@code SaveWatcherRobustnessTest#overflow_triggersRescanOfFolder} 가,
 * {@link StartupCatchUp} 배선은 {@code StartupCatchUpTest} 가 각각 따로 못 박는다.
 *
 * <p>DB 는 안 쓴다 — {@link FakeIngestService} 로 호출만 기록한다 (절대 규칙 4).
 */
class SaveWatcherRescanTest {

    private static final long DEBOUNCE_MS = 120L;

    private SaveWatcher watcher;
    private FakeIngestService ingest;

    @AfterEach
    void tearDown() {
        if (watcher != null) {
            watcher.stop();
        }
    }

    private void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------
    // A. rescan()
    // ---------------------------------------------------------------

    @Test
    @DisplayName("슬롯이 3개면 rescan() 은 3개 전부를 각자의 경로로 ingest() 한다")
    void rescan_ingestsEveryExistingSlot_viaItsOwnPath(@TempDir Path dir) throws IOException {
        Path slotA = dir.resolve("slot_a.tfm");
        Path slotB = dir.resolve("slot_b.tfm");
        Path slotC = dir.resolve("slot_c.tfm");
        write(slotA, "a");
        write(slotB, "b");
        write(slotC, "c");

        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);

        watcher.rescan();

        // 변조: rescan() 이 SlotPathResolver.resolve() 결과의 일부만(예: 첫 번째 파일만)
        // 돌면 이 단언이 실패한다. 또는 세 파일을 전부 같은 경로 하나로 뭉뚱그려 부르면
        // containsExactlyInAnyOrder 가 실패한다.
        awaitUntil(() -> ingest.callCount() == 3, DEBOUNCE_MS * 5);
        assertThat(ingest.calls()).containsExactlyInAnyOrder(slotA, slotB, slotC);
    }

    @Test
    @DisplayName("rescan() 은 *.tfm_backup 과 슬롯이 아닌 파일을 부르지 않는다 (D28)")
    void rescan_ignoresBackupAndNonSlotFiles(@TempDir Path dir) throws IOException {
        Path slot = dir.resolve("slot_a.tfm");
        Path backup = dir.resolve("slot_a.tfm_backup");
        Path other = dir.resolve("config.json");
        write(slot, "본 파일");
        write(backup, "백업 — 크기·수정시각이 본 파일과 같을 수 있다 (D28)");
        write(other, "{}");

        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);

        watcher.rescan();

        // 변조: rescan() 이 SlotPathResolver 대신 "폴더의 모든 파일" 이나 "slot_ 로 시작하는
        // 모든 파일" 을 돌면 backup 이나 config.json 도 ingest() 로 넘어가 이 단언이 실패한다.
        awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);
        assertThat(ingest.calls()).containsExactly(slot);
    }

    @Test
    @DisplayName("rescan() 직후에는 안 불리고, 디바운스 시간이 지난 뒤에 불린다 (D12)")
    void rescan_isDebounced_notImmediate(@TempDir Path dir) throws IOException {
        Path slot = dir.resolve("slot_a.tfm");
        write(slot, "기동 순간 게임이 쓰는 중일 수 있다");

        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);

        watcher.rescan();

        // 변조: rescan() 이 scheduleIngest() 대신 ingest() 를 즉시 부르면(디바운스를 건너뛰면)
        // 이 구간에서 이미 호출이 발생해 assertStaysFalseFor 가 실패한다.
        assertStaysFalseFor(() -> ingest.callCount() > 0, DEBOUNCE_MS / 2);

        awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);
    }

    @Test
    @DisplayName("rescan() 으로 건 슬롯 하나가 ingest() 에서 던져도 나머지 슬롯은 그대로 적재된다")
    void rescan_onePathThrows_othersStillIngested(@TempDir Path dir) throws IOException {
        Path slotA = dir.resolve("slot_a.tfm");
        Path slotB = dir.resolve("slot_b.tfm");
        Path slotC = dir.resolve("slot_c.tfm");
        write(slotA, "a");
        write(slotB, "b");
        write(slotC, "c");

        ingest = new FakeIngestService();
        // SlotPathResolver 는 파일명 순으로 정렬해 돌려주고, rescan() 은 그 순서대로
        // scheduleIngest() 를 부른다. 같은 디바운스 시간이면 스케줄러는 제출 순서(FIFO)를
        // 지키므로 slot_a 가 첫 호출이 된다 — throwOnNextCall 이 노리는 대상이다.
        ingest.throwOnNextCall(new RuntimeException("커리어 하나가 깨졌다(테스트로 주입)"));
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);

        watcher.rescan();

        // 변조: 슬롯 하나의 예외를 처리하다가 rescan() 의 루프 자체를 멈추면(예: for 문 밖으로
        // 예외가 새 나가게 만들면) slotB·slotC 는 스케줄조차 안 돼 이 단언이 실패한다.
        awaitUntil(() -> ingest.callCount() == 3, DEBOUNCE_MS * 5);
        assertThat(ingest.calls()).containsExactlyInAnyOrder(slotA, slotB, slotC);
    }

    @Test
    @DisplayName("rescan() 은 워처가 start() 되지 않은 상태에서도 부를 수 있다")
    void rescan_worksWithoutStart(@TempDir Path dir) throws IOException {
        Path slot = dir.resolve("slot_a.tfm");
        write(slot, "감시 스레드 없이도 재훑기는 동작해야 한다");

        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);
        // start() 를 부르지 않는다 — 이 테스트가 성립하는 조건이다.

        watcher.rescan();

        // 변조: rescan() 내부에서 watchService 나 감시 스레드에 접근하는 코드를 넣으면
        // start() 없이 부를 때 NPE 가 나 이 단언 이전에 예외로 죽는다.
        awaitUntil(() -> ingest.countCallsFor(slot) == 1, DEBOUNCE_MS * 5);
        assertThat(watcher.isRunning()).isFalse();
    }

    @Test
    @DisplayName("stop() 된 워처에서 rescan() 을 부르면 조용히 넘어가지 않고 시끄럽게 던진다")
    void rescan_afterStop_failsLoudly(@TempDir Path dir) throws IOException {
        Path slot = dir.resolve("slot_a.tfm");
        write(slot, "v0");

        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);
        watcher.start();
        watcher.stop();

        // 결정: stop() 된 워처는 디바운스 스케줄러가 shutdown 되어 되살릴 수 없다(start() 도
        // 이미 같은 이유로 던진다). rescan() 을 조용히 반환하게 두면, scheduleIngest() 내부의
        // "정지 중이라 적재를 걸지 않는다" 경고 로그 한 줄만 남기고 호출부는 "성공"으로 보게
        // 된다 — 이 프로젝트가 절대 규칙으로 금지하는 조용한 무동작이다. 그래서 start() 와
        // 대칭으로 IllegalStateException 을 던지게 정했다.
        //
        // 변조: 이 가드를 지우면 rescan() 이 예외 없이 반환하고, 파일별 스케줄만 조용히
        // 무시된다 — 호출자는 재훑기가 됐다고 믿는다.
        assertThatThrownBy(() -> watcher.rescan())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("정지된 워처");

        assertThat(ingest.callCount()).isZero();
    }

    // ---------------------------------------------------------------
    // B. 따라잡기가 워처 스레드와 겹치지 않는다
    // ---------------------------------------------------------------

    @Test
    @DisplayName("워처 이벤트가 건 적재와 rescan() 이 건 적재는 동시에 겹쳐 실행되지 않는다")
    void rescanAndWatchEventIngests_neverOverlap_singleThreadScheduler(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path slotA = dir.resolve("slot_a.tfm");
        Path slotB = dir.resolve("slot_b.tfm");
        write(slotA, "a0");
        write(slotB, "b0");

        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);
        watcher.start();

        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ingest.blockOnNextCall(entered, release);

        // 워처 이벤트 경로로 slotA 의 적재를 건다 — 이게 블록을 잡는 첫 호출이 된다.
        write(slotA, "a1");
        assertThat(entered.await(5, TimeUnit.SECONDS))
                .as("slotA 의 적재가 실제로 시작돼(블록에 들어가) 있어야 이 테스트가 의미가 있다")
                .isTrue();

        // slotA 가 ingest() 안에서 블록된 채로, 재훑기 경로를 겹쳐 건다. rescan() 은 폴더의
        // 슬롯 전부(slotA, slotB) 를 다시 스케줄하므로 slotA 는 한 번 더, slotB 는 처음 걸린다.
        watcher.rescan();

        // slotB(그리고 slotA 의 재스케줄분)의 디바운스가 충분히 지날 시간을 준다. 별도
        // 스레드로 돌면 이 구간에 slotB 가 slotA 와 동시에 ingest() 안에 들어가
        // maxConcurrentCalls() 가 2 이상이 된다.
        Thread.sleep(DEBOUNCE_MS * 3);

        // 변조: rescan() 을 별도 스레드(예: 새 ExecutorService)에서 돌리면, 워처 스레드가
        // slotA 를 붙잡고 있는 동안 rescan 스레드가 slotB 를 동시에 ingest() 해 이 단언이
        // 실패한다. 지금 구현이 같은 단일 디바운스 스레드(scheduler)를 재사용하는 한 성립한다.
        assertThat(ingest.maxConcurrentCalls())
                .as("워처 이벤트와 재훑기가 같은 단일 스레드 스케줄러를 타야 동시 진입이 없다")
                .isEqualTo(1);

        release.countDown();

        // slotA 는 워처 이벤트로 1번 + rescan() 재스케줄로 1번, slotB 는 rescan() 으로 1번,
        // 도합 3번. (같은 파일 재적재를 막는 중복 판정은 워처의 일이 아니다 — 기존 계약.)
        awaitUntil(() -> ingest.callCount() == 3, DEBOUNCE_MS * 5);
        assertThat(ingest.countCallsFor(slotA)).isEqualTo(2);
        assertThat(ingest.countCallsFor(slotB)).isEqualTo(1);
        assertThat(ingest.maxConcurrentCalls())
                .as("블록을 푼 뒤 나머지 호출들도 결국 동시에 겹치면 안 된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 파일에 대해 rescan() 과 워처 이벤트가 겹치면 디바운스가 합쳐 한 번만 부른다")
    void rescanAndWatchEvent_onSameFile_coalesceIntoSingleIngestCall(@TempDir Path dir)
            throws IOException, InterruptedException {
        Path slot = dir.resolve("slot_a.tfm");
        write(slot, "v0");

        ingest = new FakeIngestService();
        watcher = new SaveWatcher(dir, DEBOUNCE_MS, ingest);
        watcher.start();

        write(slot, "v1");                 // 워처 이벤트 경로로 디바운스를 건다
        watcher.rescan();                  // 디바운스 창 안에서 같은 파일을 다시 건다 — 리셋

        // 변조: rescan() 이 워처 이벤트와 다른 pending 맵/스케줄 경로를 쓰면(별도 캐시 등),
        // 같은 파일인데도 합쳐지지 않고 callCount() 가 2가 된다.
        awaitUntil(() -> ingest.callCount() >= 1, DEBOUNCE_MS * 5);
        Thread.sleep(DEBOUNCE_MS); // 안정화 — 더 안 늘어나는지 재확인
        assertThat(ingest.callCount()).isEqualTo(1);
        assertThat(ingest.calls()).containsExactly(slot);
    }
}
