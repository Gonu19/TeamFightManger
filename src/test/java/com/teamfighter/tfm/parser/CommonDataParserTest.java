package com.teamfighter.tfm.parser;

import com.teamfighter.tfm.parser.common.CommonDataParser;
import com.teamfighter.tfm.parser.common.ParsedRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link CommonDataParser} 의 계약을 고정한다.
 *
 * <p>스냅샷({@code fixtures/common.data})이 없는 환경에서는 그걸 쓰는 테스트를 건너뛴다 —
 * {@code GoldenFileTest} 와 같은 방식이다. 형식이 깨졌을 때의 계약은 스냅샷 없이도 돈다.
 */
class CommonDataParserTest {

    private static final Path FIXTURE = Path.of("fixtures/common.data");

    static boolean fixtureExists() {
        return Files.isRegularFile(FIXTURE);
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("번호는 딕셔너리 키에서 온다 — 배열 순서를 번호로 쓰면 전부 어긋난다")
    void read_takesIdFromDictionaryKeyNotArrayIndex() throws Exception {
        ParsedRoster roster = CommonDataParser.read(FIXTURE);

        assertThat(roster.aiTeamNames()).isNotEmpty();
        assertThat(roster.aiTeamNames().keySet()).allMatch(id -> id > 0);
        assertThat(roster.aiTeamNames().values()).allSatisfy(n -> assertThat(n).isNotBlank());

        // 핵심 단언. 번호에는 구멍이 있다(실측: 1~56 에 팀 52개 — 8·19·30·41 이 비었다).
        // 배열 인덱스를 번호로 쓰면 0..n-1 로 촘촘해져 여기서 깨진다.
        int maxId = roster.aiTeamNames().keySet().stream().mapToInt(Integer::intValue).max().orElseThrow();
        assertThat(maxId)
                .as("번호에 구멍이 있어야 이 단언이 인덱스 매핑을 잡는다")
                .isGreaterThan(roster.aiTeamNames().size());

        // 이름이 겹쳐 덮이지 않았는지. 키를 잘못 읽으면 항목이 서로를 덮어쓴다.
        assertThat(roster.size()).isEqualTo(roster.aiTeamNames().size() + 1);
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("플레이어 팀은 딕셔너리가 아니라 CommonStore 에서 온다 — 번호 0")
    void read_playerTeamComesFromStoreRoot() throws Exception {
        ParsedRoster roster = CommonDataParser.read(FIXTURE);

        assertThat(roster.playerTeamName()).isNotBlank();
        assertThat(roster.nameOf(0)).contains(roster.playerTeamName());
        // 변조: 0 을 딕셔너리에서 찾게 바꾸면 플레이어 팀 이름이 영영 안 나온다.
        assertThat(roster.aiTeamNames()).doesNotContainKey(0);
        assertThat(roster.profileName()).isNotBlank();
    }

    @Test
    @DisplayName("번호가 없거나 이름이 비면 이름도 없다 — 빈 문자열을 이름으로 두지 않는다")
    void nameOf_isEmpty_whenUnknownOrBlank() {
        ParsedRoster roster = new ParsedRoster("p", "  ", "c", Map.of(3, "Gen.G"));

        assertThat(roster.nameOf(null)).isEmpty();
        assertThat(roster.nameOf(99)).isEmpty();
        assertThat(roster.nameOf(0)).isEmpty();          // 플레이어 팀 이름이 공백이다
        assertThat(roster.nameOf(3)).contains("Gen.G");
        assertThat(roster.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("NRBF 가 아니면 던진다 — 조용히 빈 이름표를 내지 않는다")
    void read_throws_whenFileIsNotNrbf(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("common.data");
        Files.write(junk, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});

        // 빈 이름표를 돌려주면 "게임이 형식을 바꿨다" 와 "팀 이름이 원래 없다" 가
        // 화면에서 구별되지 않는다.
        assertThatThrownBy(() -> CommonDataParser.read(junk))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("경로는 세이브 폴더 안에서 찾는다 — 슬롯 파일과 같은 폴더다")
    void pathIn_resolvesInsideSaveDir() {
        assertThat(CommonDataParser.pathIn(Path.of("some", "dir")).toString())
                .endsWith("common.data");
    }
}
