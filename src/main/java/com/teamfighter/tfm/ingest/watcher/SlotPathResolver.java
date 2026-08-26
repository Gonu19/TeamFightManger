package com.teamfighter.tfm.ingest.watcher;

import com.teamfighter.tfm.ingest.SlotFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 세이브 폴더에서 현재 슬롯 파일 목록을 뽑는다 (D28).
 *
 * <p>판정은 {@link SlotFile#isSlotFile(Path)} 하나에만 맡긴다. 여기에 정규식을 한 벌 더 두면
 * 두 곳이 따로 늙는다 — {@code *.tfm_backup} 을 거르는 규칙은 프로젝트에 딱 하나만 있어야 한다.
 *
 * <p><b>하위 폴더는 뒤지지 않는다.</b> 감시 대상은 한 폴더이고, 사용자가 세이브를 복사해 두는
 * 하위 폴더까지 훑으면 같은 커리어가 두 벌 적재된다 — 백업 파일과 정확히 같은 사고다.
 */
public final class SlotPathResolver {

    private SlotPathResolver() {
    }

    /**
     * {@code folder} 바로 아래(하위 폴더 제외)의 {@code slot_*.tfm} 파일만 돌려준다.
     *
     * <p>폴더가 없는 것과 슬롯이 없는 것을 구분한다. 앞은 설정 오류라 시끄러워야 하고,
     * 뒤는 게임을 아직 한 번도 저장하지 않은 정상 상태라 조용해야 한다.
     * 둘을 뭉뚱그려 빈 목록으로 돌려주면 경로 오타가 "슬롯 0개" 로만 보인다.
     *
     * @param folder 감시할 세이브 폴더
     * @return 파일명 순으로 정렬된 슬롯 경로 목록. 슬롯이 없으면 빈 목록
     * @throws IllegalStateException {@code folder} 가 비어 있거나, 없거나, 폴더가 아닐 때 (설정 오류)
     */
    public static List<Path> resolve(Path folder) {
        // 빈 경로를 먼저 막는다. Path.of("") 는 현재 작업 디렉터리를 가리키고, 그 폴더는 늘
        // 존재하므로 아래 검사를 그냥 통과한다 — 워처가 앱 실행 디렉터리를 감시하며
        // "정상 기동" 을 찍고 세이브는 영영 안 잡히는 상태가 된다.
        // TFM_SAVE_DIR 이 값 없이 정의만 돼 있으면(TFM_SAVE_DIR=) 실제로 이 경로로 들어온다.
        if (folder == null || folder.toString().isBlank()) {
            throw new IllegalStateException(
                    "세이브 폴더가 설정되지 않았다. tfm.save-dir(또는 TFM_SAVE_DIR) 을 확인해라"
                            + " — 값이 비어 있으면 현재 작업 디렉터리를 감시하게 된다");
        }
        if (!Files.isDirectory(folder)) {
            throw new IllegalStateException(
                    "세이브 폴더가 없다: " + folder.toAbsolutePath()
                            + ". tfm.save-dir(또는 TFM_SAVE_DIR) 을 확인해라");
        }

        List<Path> slots = new ArrayList<>();
        // newDirectoryStream 은 한 단계만 훑는다. Files.walk 로 바꾸면 하위 폴더의 사본까지 잡힌다.
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            for (Path entry : entries) {
                if (Files.isRegularFile(entry) && SlotFile.isSlotFile(entry)) {
                    slots.add(entry);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("세이브 폴더를 읽지 못했다: " + folder, e);
        }

        // 순서를 파일시스템 열거 순서에 맡기지 않는다 — 적재 순서가 실행마다 달라지면
        // 로그를 나란히 놓고 비교할 수 없다.
        slots.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return List.copyOf(slots);
    }
}
