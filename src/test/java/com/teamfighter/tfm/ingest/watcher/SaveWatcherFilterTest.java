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
 * {@link SaveWatcher} 의 이벤트 필터링을 고정한다 (D28).
 *
 * <p>게임은 저장할 때마다 본 파일과 {@code *.tfm_backup} 을 같이 쓴다 — 이 이벤트는
 * 실제로 매번 온다. 여기서 걸러지지 않으면 백업이 별도 슬롯으로 잘못 적재되어
 * 같은 커리어가 두 벌 잡힌다 (D28 본문).
 */
class SaveWatcherFilterTest {

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
    @DisplayName("*.tfm_backup 이 바뀌어도 ingest() 는 절대 불리지 않는다")
    void tfmBackupChanges_neverTriggerIngest(@TempDir Path dir) throws IOException {
        Path backup = dir.resolve("slot_a.tfm_backup");
        Files.writeString(backup, "b0", StandardCharsets.UTF_8);
        start(dir);

        write(backup, "b1");
        write(backup, "b2");

        // 변조: 확장자 필터를 SlotFile.isSlotFile() 이 아니라 "이름에 slot_ 로 시작"만으로
        // 완화하면 backup 도 통과해 이 단언이 실패한다.
        assertStaysFalseFor(() -> ingest.callCount() > 0, DEBOUNCE_MS * 5);
    }

    @Test
    @DisplayName("슬롯이 아닌 파일(config.json)의 이벤트도 무시한다")
    void nonSlotFileChanges_areIgnored(@TempDir Path dir) throws IOException {
        Path other = dir.resolve("config.json");
        Files.writeString(other, "{}", StandardCharsets.UTF_8);
        start(dir);

        write(other, "{\"changed\":true}");

        // 변조: "폴더 안의 아무 변경이나 감지해서 부른다" 로 필터를 아예 없애면
        // config.json 변경에도 ingest() 가 불려 이 단언이 실패한다.
        assertStaysFalseFor(() -> ingest.callCount() > 0, DEBOUNCE_MS * 5);
    }

    @Test
    @DisplayName("ENTRY_DELETE 로는 적재하지 않는다")
    void deleteEvents_doNotTriggerIngest(@TempDir Path dir) throws IOException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "v0", StandardCharsets.UTF_8);
        start(dir);

        // 베이스라인: 생성 직후 한 번은 불려야 정상(파일이 이미 있었으므로 최초 감시 시작
        // 시점 이벤트는 없을 수 있다 — 그래서 삭제 전에 한 번 수정해 기준 호출을 만든다).
        write(slot, "v1");
        awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);

        Files.delete(slot);

        // 변조: 삭제 이벤트(ENTRY_DELETE)도 디바운스에 걸어 ingest() 를 부르게 만들면
        // 존재하지 않는 파일을 파싱하려다 실패하거나, 최소한 이 카운트가 2로 늘어난다.
        assertStaysFalseFor(() -> ingest.callCount() > 1, DEBOUNCE_MS * 5);
    }

    @Test
    @DisplayName("ENTRY_CREATE 는 적재한다 — 원자적 이동으로 들어온 새 슬롯도 잡는다")
    void createEvents_triggerIngest(@TempDir Path dir, @TempDir Path elsewhere) throws IOException {
        start(dir);

        // 감시 폴더 밖에서 만들어 원자적으로 옮긴다. 이건 편의가 아니라 이 테스트가 성립하는
        // 조건이다 — 감시 폴더 안에서 Files.writeString 으로 만들면 CREATE 뒤에 MODIFY 가
        // 따라와서, ENTRY_CREATE 구독을 통째로 지워도 MODIFY 로 걸려 테스트가 통과한다.
        // 실제로 그 변조를 걸어보고 확인했다. 이동은 CREATE 하나만 낸다.
        Path staged = elsewhere.resolve("slot_new.tfm");
        write(staged, "first save");
        Path newSlot = dir.resolve("slot_new.tfm");
        Files.move(staged, newSlot);

        // 변조: 워처가 ENTRY_MODIFY 만 구독하고 ENTRY_CREATE 를 빼먹으면 이 단언이
        // 타임아웃으로 실패한다.
        awaitUntil(() -> ingest.callCount() == 1, DEBOUNCE_MS * 5);
        assertThat(ingest.calls()).containsExactly(newSlot);
    }
}
