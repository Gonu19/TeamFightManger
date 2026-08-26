package com.teamfighter.tfm.ingest.watcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static com.teamfighter.tfm.ingest.watcher.WatcherTestSupport.assertStaysFalseFor;
import static com.teamfighter.tfm.ingest.watcher.WatcherTestSupport.awaitUntil;

/**
 * {@link SaveWatcher} 의 디바운스 계약을 고정한다 (D12).
 *
 * <p>DB 는 안 쓴다 — {@link FakeIngestService} 로 호출만 기록한다 (절대 규칙 4).
 * 실시간 1.5초를 기다리는 대신 디바운스를 120ms 로 주입해 결정적으로 짧게 만든다
 * (절대 규칙 5). 폴링은 {@link WatcherTestSupport} 를 쓴다 — Awaitility 는 새로 추가하지 않는다.
 */
class SaveWatcherDebounceTest {

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
    @DisplayName("조용한 구간이 디바운스 시간만큼 지나야 ingest() 가 불린다 — 그 전에는 불리지 않는다")
    void debounce_waitsForQuietPeriodBeforeIngesting(@TempDir Path dir) throws IOException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "v1", StandardCharsets.UTF_8);
        start(dir);

        write(slot, "v2");

        // 변조: 디바운스를 없애고 이벤트마다 즉시 ingest() 를 부르게 바꾸면, 이 구간에서
        // 이미 호출이 발생해 assertStaysFalseFor 가 실패한다.
        assertStaysFalseFor(() -> ingest.callCount() > 0, DEBOUNCE_MS / 2);

        // 변조: 반대로 디바운스가 걸린 채로 영영 안 불리게(타이머가 취소만 되고 재등록이
        // 안 되는 버그) 만들면 이 awaitUntil 이 타임아웃으로 실패한다.
        awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);
        assertThat(ingest.calls()).containsExactly(slot);
    }

    @Test
    @DisplayName("디바운스 구간 안에서 쓰기가 연속 5번 오면 ingest() 는 정확히 1번 불린다 — 합쳐진다")
    void debounce_coalescesBurstOfWrites(@TempDir Path dir) throws IOException, InterruptedException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "v0", StandardCharsets.UTF_8);
        start(dir);

        for (int i = 1; i <= 5; i++) {
            write(slot, "v" + i);
            Thread.sleep(DEBOUNCE_MS / 4); // 디바운스보다 짧은 간격 — 매번 타이머를 리셋시킨다
        }

        awaitUntil(() -> ingest.callCount() >= 1, DEBOUNCE_MS * 5);
        // 조용해진 뒤에도 카운트가 더 늘지 않는지 짧게 한 번 더 재확인한다(안정화 확인).
        Thread.sleep(DEBOUNCE_MS);

        // 변조: 디바운스 타이머를 파일별이 아니라 "이벤트 하나당 새 타이머" 로 잘못 구현하면
        // 연속 쓰기 5번이 각각 독립 타이머를 만들어 결국 5번 호출된다. 이 단언이 그걸 잡는다.
        assertThat(ingest.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("디바운스는 마지막 변경 기준으로 리셋된다 — 계속 쓰는 동안은 안 불리고, 멈춘 뒤에 불린다")
    void debounce_resetsOnEachChange_firesOnlyAfterWritesStop(@TempDir Path dir) throws IOException, InterruptedException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "v0", StandardCharsets.UTF_8);
        start(dir);

        long writeIntervalMs = DEBOUNCE_MS / 2; // 디바운스보다 짧은 간격으로 계속 갱신 → 매번 리셋
        long keepWritingForMs = DEBOUNCE_MS * 4;
        long stopAt = System.currentTimeMillis() + keepWritingForMs;
        int i = 1;
        while (System.currentTimeMillis() < stopAt) {
            write(slot, "v" + i++);
            Thread.sleep(writeIntervalMs);
        }

        // 변조: 디바운스를 "첫 변경 후 고정 지연"으로 바꾸면(리셋하지 않으면) 이 구간(총 쓰기
        // 시간 > 디바운스 시간) 동안 이미 최소 한 번 호출됐을 것이다 — 이 단언이 실패한다.
        assertThat(ingest.callCount())
                .as("계속 쓰는 동안에는 조용한 구간이 한 번도 없었으므로 아직 불리면 안 된다")
                .isZero();

        awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);
    }

    @Test
    @DisplayName("슬롯이 둘이면 디바운스는 파일별로 돈다 — 한 슬롯의 쓰기가 다른 슬롯의 타이머를 밀지 않는다")
    void debounce_isPerFile_independentTimers(@TempDir Path dir) throws IOException, InterruptedException {
        Path slotA = dir.resolve("slot_a.tfm");
        Path slotB = dir.resolve("slot_b.tfm");
        Files.writeString(slotA, "a0", StandardCharsets.UTF_8);
        Files.writeString(slotB, "b0", StandardCharsets.UTF_8);
        start(dir);

        // A 는 디바운스보다 짧은 간격으로 계속 갱신(타이머가 계속 리셋됨).
        // B 는 한 번만 건드리고 내버려 둔다 — 디바운스 시간 뒤 B 만 먼저 불려야 한다.
        write(slotB, "b1");
        long writeIntervalMs = DEBOUNCE_MS / 3;
        long keepWritingForMs = DEBOUNCE_MS * 3;
        long stopAt = System.currentTimeMillis() + keepWritingForMs;
        int i = 1;
        while (System.currentTimeMillis() < stopAt) {
            write(slotA, "a" + i++);
            Thread.sleep(writeIntervalMs);
        }

        // 변조: 디바운스 타이머를 폴더 전체에 하나만 두면(파일별이 아니라 전역), A 가 계속
        // 리셋시키는 동안 B 도 영원히 안 불린다 — 이 awaitUntil 이 타임아웃으로 실패한다.
        awaitUntil(() -> ingest.countCallsFor(slotB) == 1, DEBOUNCE_MS * 5);
        assertThat(ingest.countCallsFor(slotA))
                .as("A 는 계속 쓰이는 중이라 아직 불리면 안 된다")
                .isZero();

        // A 를 멈추면 곧 A 도 불린다 — 각자 자기 경로로.
        awaitUntil(() -> ingest.countCallsFor(slotA) == 1, DEBOUNCE_MS * 5);
    }
}
