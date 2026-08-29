package com.teamfighter.tfm.parser;

import com.teamfighter.tfm.parser.common.AthleteParser;
import com.teamfighter.tfm.parser.common.ParsedAthlete;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AthleteParser} 의 계약을 고정한다.
 *
 * <p>스냅샷({@code fixtures/*.tfm})이 없는 환경에서는 그걸 쓰는 테스트를 건너뛴다.
 * 이름 참조 형식의 계약은 스냅샷 없이도 돈다.
 */
class AthleteParserTest {

    private static final Path FIXTURE = Path.of("fixtures", "slot_638683925954242004.tfm");

    static boolean fixtureExists() {
        return Files.isRegularFile(FIXTURE);
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("선수를 ID 순으로 읽고, 이름은 인덱스로만 넘긴다 (실측 443명 · 인덱스 0~550)")
    void read_returnsAthletesWithNameIndex() throws Exception {
        List<ParsedAthlete> athletes = AthleteParser.read(FIXTURE);

        assertThat(athletes).isNotEmpty();
        assertThat(athletes).isSortedAccordingTo((a, b) -> Integer.compare(a.id(), b.id()));
        // 같은 선수가 팀·경기에서 여러 번 참조된다. 접지 않으면 443명이 수천 행이 된다.
        assertThat(athletes.stream().map(ParsedAthlete::id).distinct().count())
                .isEqualTo(athletes.size());

        // 이름은 참조 형식이다 — 파서가 이름을 지어내지 않는지 확인한다.
        // (사용자가 직접 지은 이름은 참조가 아니므로 "전부" 로 단언하지 않는다.)
        assertThat(athletes).anySatisfy(a -> assertThat(a.nameIndex()).isNotNull());
        assertThat(athletes)
                .filteredOn(a -> a.nameIndex() != null)
                .allSatisfy(a -> assertThat(a.nameIndex()).isNotNegative());
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("소속이 없는 선수가 있다 — 팀을 필수로 두면 절반이 사라진다")
    void read_allowsAthletesWithoutTeam() throws Exception {
        List<ParsedAthlete> athletes = AthleteParser.read(FIXTURE);

        // 실측: 443명 중 215명만 소속이 있다. FK 를 NOT NULL 로 두면 228명이 버려진다.
        assertThat(athletes).anySatisfy(a -> assertThat(a.gameTeamId()).isNull());
        assertThat(athletes).anySatisfy(a -> assertThat(a.gameTeamId()).isNotNull());
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("커리어 누적이 실려 온다 — 기사에 쓸 재료다")
    void read_carriesCareerTotals() throws Exception {
        List<ParsedAthlete> athletes = AthleteParser.read(FIXTURE);

        assertThat(athletes).anySatisfy(a -> {
            assertThat(a.career()).isNotNull();
            assertThat(a.career().sets()).isNotNull();
            assertThat(a.career().kill()).isNotNull();
            assertThat(a.career().deal()).isNotNull();
        });
    }

    @Test
    @DisplayName("세이브가 아니면 던진다 — 조용히 빈 목록을 내지 않는다")
    void read_throws_whenNotASaveFile(@TempDir Path dir) throws Exception {
        Path junk = dir.resolve("slot_x.tfm");
        Files.write(junk, new byte[] {0, 1, 2, 3, 4, 5, 6, 7});

        assertThatThrownBy(() -> AthleteParser.read(junk))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("`N 만 인덱스다 — 숫자로 지은 이름을 인덱스로 오해하면 남의 이름이 붙는다")
    void nameIndexOf_requiresTheReferencePrefix() {
        assertThat(AthleteParser.nameIndexOf("`33")).isEqualTo(33);
        assertThat(AthleteParser.nameIndexOf("`0")).isZero();
        assertThat(AthleteParser.nameIndexOf("`550")).isEqualTo(550);

        // 변조: 접두사 검사를 빼면 사용자가 '33' 으로 지은 이름이 SnowFlower 가 된다.
        assertThat(AthleteParser.nameIndexOf("33")).isNull();
        assertThat(AthleteParser.nameIndexOf("`")).isNull();
        assertThat(AthleteParser.nameIndexOf("`3a")).isNull();
        assertThat(AthleteParser.nameIndexOf("`-1")).isNull();
        assertThat(AthleteParser.nameIndexOf("`99999999999999")).isNull();
        assertThat(AthleteParser.nameIndexOf(null)).isNull();
        assertThat(AthleteParser.nameIndexOf(33)).isNull();
    }

    @Test
    @DisplayName("참조가 아닌 이름은 인덱스로 오해하지 않는다 — 직접 지은 이름이 숫자여도")
    void literalName_isNotMistakenForAnIndex() {
        // 사용자가 선수 이름을 '33' 으로 바꿔도 SnowFlower 가 되면 안 된다.
        assertThat(AthleteParser.literalName("33")).isEqualTo("33");
        assertThat(AthleteParser.literalName("`33")).isNull();
        assertThat(AthleteParser.literalName("")).isNull();
        assertThat(AthleteParser.literalName(null)).isNull();
    }
}
