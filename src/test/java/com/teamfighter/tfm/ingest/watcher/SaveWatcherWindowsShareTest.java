package com.teamfighter.tfm.ingest.watcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Windows 공유 읽기 전제를 고정한다 (D17 / D27).
 *
 * <p>D17 실측에서는 게임 실행 중에도 파일이 잠기지 않았다(파싱 에러 0건). 이 테스트는
 * <b>그 전제가 깨지는 순간 알아채기 위한 것</b>이다 — 전제가 깨지면 임시 복사 우회를
 * 넣을 근거가 된다.
 *
 * <p>{@code @EnabledOnOs(OS.WINDOWS)} 로만 돈다. Linux/CI 에서 공유 위반 동작이 달라
 * 이식성이 없는 검증이기 때문이다(D27: 이 프로젝트는 Windows 네이티브 실행이 전제).
 */
@EnabledOnOs(OS.WINDOWS)
class SaveWatcherWindowsShareTest {

    @Test
    @DisplayName("다른 핸들이 쓰기용으로 파일을 열어둔 채여도 읽기가 성공한다")
    void read_succeeds_whileAnotherHandleHoldsFileOpenForWrite(@TempDir Path dir) throws IOException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "초기 내용", StandardCharsets.UTF_8);

        try (FileChannel writer = FileChannel.open(slot, StandardOpenOption.WRITE)) {
            // 변조: 워처/리졸버가 배타적 쓰기 핸들이 열려 있는 파일을 못 읽게 되면(공유
            // 위반, Windows 의 IOException: "다른 프로세스가 파일을 사용 중이므로...")
            // 이 단언이 실패한다. 실패하면 그때 임시 복사 우회를 넣을 근거가 생긴다.
            assertThatCode(() -> Files.readAllBytes(slot)).doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("read-only 계약 — 감시 전후로 파일의 수정시각과 내용 해시가 변하지 않는다")
    void watcherAndResolver_neverWriteToSaveFile(@TempDir Path dir) throws IOException, InterruptedException {
        Path slot = dir.resolve("slot_a.tfm");
        Files.writeString(slot, "건드리면 안 되는 내용", StandardCharsets.UTF_8);

        FileTime beforeTime = Files.getLastModifiedTime(slot);
        String beforeHash = sha256(slot);

        // 리졸버로 훑고, 워처를 짧게 띄웠다 내린다 — 둘 다 "읽기만" 해야 한다.
        SlotPathResolver.resolve(dir);
        FakeIngestService ingest = new FakeIngestService();
        SaveWatcher watcher = new SaveWatcher(dir, 50L, ingest);
        watcher.start();
        Thread.sleep(200);
        watcher.stop();

        FileTime afterTime = Files.getLastModifiedTime(slot);
        String afterHash = sha256(slot);

        // 변조: 리졸버나 워처가 파일을 정규화한다며 다시 쓰거나(예: 인코딩 통일), 잠금 해제를
        // 위해 복사 후 원본에 되쓰는 우회를 넣으면 수정시각·해시가 바뀌어 이 단언이 실패한다.
        assertThat(afterTime).isEqualTo(beforeTime);
        assertThat(afterHash).isEqualTo(beforeHash);
    }

    private static String sha256(Path file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 을 쓸 수 없다", e);
        }
    }
}
