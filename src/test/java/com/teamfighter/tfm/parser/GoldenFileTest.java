package com.teamfighter.tfm.parser;

import com.teamfighter.tfm.parser.model.ParsedSave;
import com.teamfighter.tfm.parser.save.SaveJson;
import com.teamfighter.tfm.parser.save.SaveParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Java 파서가 Python 레퍼런스와 같은 결과를 내는지 확인한다.
 *
 * <p>골든 파일은 <b>언어 무관 계약</b>이다. 비교는 바이트 단위다 —
 * 필드 하나만 어긋나도 해시가 달라진다. NRBF 는 틀려도 예외를 던지지 않고
 * 조용히 잘못된 값을 내놓기 때문에(Java 의 {@code byte} 는 부호가 있다)
 * 이 테스트가 유일한 방어선이다.
 *
 * <p>골든 파일은 커밋되지만 세이브 스냅샷({@code fixtures/*.tfm})은 커밋되지 않는다.
 * 스냅샷이 없는 환경에서는 이 테스트를 건너뛴다 — 없는 것을 통과로 위장하지 않도록
 * {@link #fixturesExist()} 로 명시한다.
 */
class GoldenFileTest {

    private static final Path FIXTURES = Path.of("fixtures");
    private static final Path BASELINE = Path.of("tests", "baseline");

    static boolean fixturesExist() {
        return !fixtures().isEmpty();
    }

    private static List<Path> fixtures() {
        if (!Files.isDirectory(FIXTURES)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(FIXTURES)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".tfm")).sorted().toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("Java 파서 출력이 골든 파일과 바이트 단위로 일치한다")
    void parserOutput_matchesGoldenFiles_byteForByte() throws Exception {
        List<String> mismatches = new ArrayList<>();

        for (Path fixture : fixtures()) {
            String slot = fixture.getFileName().toString().replace(".tfm", "");
            Path golden = BASELINE.resolve(slot + ".json");
            assertThat(golden)
                    .as("골든 파일이 있어야 한다. 없으면 tools/make_baseline.py 로 만든다")
                    .exists();

            ParsedSave parsed = SaveParser.read(fixture);
            String actual = SaveJson.write(parsed);
            String expected = Files.readString(golden, StandardCharsets.UTF_8);

            if (!actual.equals(expected)) {
                mismatches.add(slot + ": " + describe(expected, actual));
            }
        }

        assertThat(mismatches)
                .as("Python 레퍼런스와 어긋난 슬롯")
                .isEmpty();
    }

    @Test
    @EnabledIf("fixturesExist")
    @DisplayName("골든 파일의 sha256 이 manifest 와 일치한다")
    void goldenFiles_matchManifestDigest() throws Exception {
        for (Path fixture : fixtures()) {
            String slot = fixture.getFileName().toString().replace(".tfm", "");
            String json = Files.readString(BASELINE.resolve(slot + ".json"), StandardCharsets.UTF_8);
            String manifest = Files.readString(BASELINE.resolve("manifest.json"), StandardCharsets.UTF_8);

            assertThat(manifest)
                    .as("manifest 에 %s 의 해시가 있어야 한다", slot)
                    .contains(sha256(json));
        }
    }

    private static String sha256(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
    }

    /** 어긋난 지점을 바로 짚어준다. 길이만 알려주면 원인을 찾는 데 시간이 걸린다. */
    private static String describe(String expected, String actual) {
        int n = Math.min(expected.length(), actual.length());
        int i = 0;
        while (i < n && expected.charAt(i) == actual.charAt(i)) {
            i++;
        }
        int from = Math.max(0, i - 60);
        int to = Math.min(n, i + 60);
        return "위치 " + i + " 에서 갈림 (기대 " + expected.length() + "자 / 실제 " + actual.length() + "자)"
                + System.lineSeparator() + "  기대: ..." + expected.substring(from, Math.min(expected.length(), to)) + "..."
                + System.lineSeparator() + "  실제: ..." + actual.substring(from, Math.min(actual.length(), to)) + "...";
    }
}
