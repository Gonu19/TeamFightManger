package com.teamfighter.tfm.ingest.watcher;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SlotPathResolver} 의 슬롯 판정을 고정한다 (D28).
 *
 * <p>DB 는 필요 없다 — 파일시스템 판정일 뿐이다 (절대 규칙 4).
 *
 * <p>여기서 판정이 느슨해지면(예: "이름에 .tfm 이 들어 있으면") {@code slot_x.tfm_backup}
 * 이 별도 슬롯으로 등록되어 같은 커리어가 두 벌 적재된다. 해시 중복 검사
 * {@code UNIQUE(slot_id, file_hash)} 는 슬롯 <b>안에서만</b> 돌기 때문에 이걸 막지 못한다.
 */
class SlotPathResolverTest {

    @Test
    @DisplayName("slot_<숫자>.tfm 은 잡는다")
    void resolve_matchesSlotTfmFile(@TempDir Path dir) throws IOException {
        Path slot = Files.createFile(dir.resolve("slot_638064443900084435.tfm"));

        List<Path> result = SlotPathResolver.resolve(dir);

        // 변조: 확장자 검사를 "이름에 .tfm 포함" 으로 느슨하게 바꾸면 이 테스트 자체는 여전히
        // 통과한다(진짜 슬롯도 포함되므로) — 이 테스트 혼자로는 오탐을 못 잡는다.
        // 그래서 아래 backup 테스트가 반드시 짝을 이뤄야 한다.
        assertThat(result).containsExactly(slot);
    }

    @Test
    @DisplayName("slot_<숫자>.tfm_backup 은 잡지 않는다 — 잡으면 같은 커리어가 두 벌 적재된다 (D28)")
    void resolve_excludesTfmBackup(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("slot_638064443900084435.tfm"));
        Files.createFile(dir.resolve("slot_638064443900084435.tfm_backup"));

        List<Path> result = SlotPathResolver.resolve(dir);

        // 변조: "이름이 .tfm 로 끝나는지" 대신 "이름에 .tfm 이 들어 있는지" 로 판정을 바꾸면
        // .tfm_backup 도 걸려서 결과가 2개가 된다 — 이 단언이 그 변조를 잡는다.
        assertThat(result).hasSize(1);
        assertThat(result.get(0).getFileName().toString()).isEqualTo("slot_638064443900084435.tfm");
    }

    @Test
    @DisplayName("경계 케이스 — .tfm.bak · .tfm.old · notslot.tfm · 대문자 .TFM 은 전부 제외한다")
    void resolve_excludesBoundaryCases(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("slot_x.tfm.bak"));
        Files.createFile(dir.resolve("slot_x.tfm.old"));
        Files.createFile(dir.resolve("notslot.tfm"));
        // 대문자 케이스는 stem 을 달리한다. Windows 파일시스템은 대소문자를 구분하지 않아
        // slot_x.TFM 과 slot_x.tfm 이 같은 파일이 되고, 두 번째 createFile 이
        // FileAlreadyExistsException 으로 죽는다 — 판정이 아니라 준비 단계에서 깨진다.
        Files.createFile(dir.resolve("slot_upper.TFM"));
        Path real = Files.createFile(dir.resolve("slot_x.tfm"));

        List<Path> result = SlotPathResolver.resolve(dir);

        // 변조: 대소문자 무시 매칭(Pattern.CASE_INSENSITIVE)을 걸면 slot_upper.TFM 도 잡혀
        // 결과가 2개가 된다. 게임은 항상 소문자 .tfm 만 쓰므로 대문자를 잡는 것은 과잉이다.
        assertThat(result).containsExactly(real);
    }

    @Test
    @DisplayName("폴더가 존재하지 않으면 예외를 던진다 — 설정 오류이므로 조용히 넘어가면 안 된다")
    void resolve_missingFolder_throws() {
        Path missing = Path.of("이런_폴더는_없다_" + System.nanoTime());

        // 변조: 폴더가 없을 때 빈 목록을 조용히 돌려주면, 세이브 폴더 설정을 오타 낸 사용자가
        // "슬롯이 0개" 로만 보고 원인을 못 찾는다. 예외가 없어지면 이 단언이 실패한다.
        assertThatThrownBy(() -> SlotPathResolver.resolve(missing))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("빈 경로는 예외다 — Path.of(\"\") 는 현재 작업 디렉터리라 검사를 그냥 통과한다")
    void resolve_blankFolder_throws() {
        // TFM_SAVE_DIR 이 값 없이 정의만 돼 있으면(TFM_SAVE_DIR=) Spring 은 기본값으로
        // 폴백하지 않고 빈 문자열을 그대로 쓴다. 그게 Path.of("") 로 바인딩되면
        // Files.isDirectory 는 참을 돌려준다 — 현재 작업 디렉터리는 늘 존재하니까.
        // 변조: 이 가드를 지우면 워처가 앱 실행 디렉터리를 감시하며 "감시 시작" 을 찍고,
        // 세이브는 영영 안 잡힌다. 죽지도 동작하지도 않는 상태다.
        assertThatThrownBy(() -> SlotPathResolver.resolve(Path.of("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("설정되지 않았다");
    }

    @Test
    @DisplayName("경로가 null 이면 설정 오류로 죽는다 — NullPointerException 이 아니라")
    void resolve_nullFolder_throwsConfigError() {
        // 변조: null 가드를 지우면 Files.isDirectory(null) 이 NullPointerException 을 던진다.
        // 앱이 죽는 건 같지만, 스택트레이스만으로는 원인이 설정 오류라는 걸 알 수 없다.
        assertThatThrownBy(() -> SlotPathResolver.resolve(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("설정되지 않았다");
    }

    @Test
    @DisplayName("폴더는 있는데 슬롯 파일이 없으면 빈 목록을 돌려준다 — 이건 정상 상태다")
    void resolve_emptyFolder_returnsEmptyList(@TempDir Path dir) {
        // 변조: "폴더가 있어도 슬롯이 0개면 예외" 로 바꾸면, 게임을 아직 한 번도 실행하지 않은
        // 정상적인 새 설치 상태에서 워처가 시작부터 죽는다. 이 테스트가 그 변조를 잡는다.
        List<Path> result = SlotPathResolver.resolve(dir);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("경로에 공백이 있는 폴더에서도 동작한다 (D27: Teamfight Manager)")
    void resolve_folderWithSpaces_works(@TempDir Path dir) throws IOException {
        Path spaced = Files.createDirectories(dir.resolve("Teamfight Manager"));
        Path slot = Files.createFile(spaced.resolve("slot_638064443900084435.tfm"));

        // 변조: 공백을 구분자로 잘못 다루는 구현(예: 경로를 공백으로 split 해 첫 토큰만 쓰는
        // 실수)이 들어가면 폴더를 못 찾아 예외가 나거나 빈 목록이 나온다.
        List<Path> result = SlotPathResolver.resolve(spaced);

        assertThat(result).containsExactly(slot);
    }

    @Test
    @DisplayName("하위 폴더는 뒤지지 않는다 — 감시 대상은 한 폴더뿐이다")
    void resolve_doesNotRecurseIntoSubfolders(@TempDir Path dir) throws IOException {
        Path sub = Files.createDirectories(dir.resolve("backup"));
        Files.createFile(sub.resolve("slot_638064443900084435.tfm"));
        Path topLevel = Files.createFile(dir.resolve("slot_top.tfm"));

        // 변조: Files.walk 같은 재귀 탐색으로 바꾸면 하위 폴더의 슬롯까지 결과에 섞여
        // containsExactly(topLevel) 단언이 깨진다(원소 2개 vs 1개).
        List<Path> result = SlotPathResolver.resolve(dir);

        assertThat(result).containsExactly(topLevel);
    }
}
