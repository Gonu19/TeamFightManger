package com.teamfighter.tfm.parser;

import com.teamfighter.tfm.parser.common.MatchScheduleParser;
import com.teamfighter.tfm.parser.common.ParsedSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MatchScheduleParser} 의 계약을 고정한다.
 *
 * <p>스냅샷({@code fixtures/*.tfm})이 없는 환경에서는 그걸 쓰는 테스트를 건너뛴다.
 * 조인 키의 계약은 스냅샷 없이도 돈다.
 */
class MatchScheduleParserTest {

    private static final Path FIXTURE = Path.of("fixtures", "slot_638683925954242004.tfm");

    static boolean fixtureExists() {
        return Files.isRegularFile(FIXTURE);
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("스케줄 190건을 읽는다 — 이벤트전 5건 포함")
    void read_returnsAllSchedules() throws Exception {
        List<ParsedSchedule> schedules = MatchScheduleParser.read(FIXTURE);

        assertThat(schedules).hasSize(190);
        assertThat(schedules).filteredOn(ParsedSchedule::isEvent).hasSize(5);
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("ID 는 대회마다 ID 공간이 따로다 — 단독으로 쓰면 190건이 114건으로 뭉개진다")
    void scheduleId_isNotUniqueOnItsOwn() throws Exception {
        List<ParsedSchedule> schedules = MatchScheduleParser.read(FIXTURE);

        // savefile.md 의 경고를 수치로 고정한다. 이 값이 변하면 조인 전략을 다시 봐야 한다.
        assertThat(schedules.stream().map(ParsedSchedule::scheduleId).distinct().count())
                .isEqualTo(114);
        // 대회를 붙이면 유일해진다. 이벤트전은 대회가 없어(null) 목록에 null 이 들어가므로
        // List.of 가 아니라 Arrays.asList 를 쓴다 — 여기서 터지면 계약이 아니라 테스트가 깨진다.
        assertThat(schedules.stream()
                .map(s -> java.util.Arrays.asList(s.scheduleId(), s.competitionId()))
                .distinct().count())
                .isEqualTo(190);
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("조인 키는 (시즌·일·두 팀)이고 무순이다 — 190건 전부 고유하다")
    void matchKey_isUniqueAcrossAllSchedules() throws Exception {
        List<ParsedSchedule> schedules = MatchScheduleParser.read(FIXTURE);

        Set<ParsedSchedule.MatchKey> keys = schedules.stream()
                .map(ParsedSchedule::matchKey)
                .collect(Collectors.toSet());

        assertThat(keys).hasSize(schedules.size());
    }

    @Test
    @DisplayName("조인 키의 두 팀은 순서를 가리지 않는다 — 세트마다 진영이 바뀐다")
    void matchKey_ignoresSideOrder() {
        ParsedSchedule.MatchKey blueFirst = ParsedSchedule.MatchKey.of(2026, 7, 30, 37);
        ParsedSchedule.MatchKey redFirst = ParsedSchedule.MatchKey.of(2026, 7, 37, 30);

        assertThat(blueFirst).isEqualTo(redFirst);
        assertThat(blueFirst).hasSameHashCodeAs(redFirst);
    }

    @Test
    @DisplayName("다른 날·다른 팀이면 다른 키다 — 무순으로 만든다고 뭉개지면 안 된다")
    void matchKey_distinguishesDifferentMatches() {
        ParsedSchedule.MatchKey base = ParsedSchedule.MatchKey.of(2026, 7, 30, 37);

        assertThat(base).isNotEqualTo(ParsedSchedule.MatchKey.of(2026, 8, 30, 37));
        assertThat(base).isNotEqualTo(ParsedSchedule.MatchKey.of(2025, 7, 30, 37));
        assertThat(base).isNotEqualTo(ParsedSchedule.MatchKey.of(2026, 7, 30, 38));
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("치러진 매치는 스코어와 킬이 함께 있다 — 기사가 쓸 숫자의 최소 집합")
    void played_carriesScoreAndKills() throws Exception {
        List<ParsedSchedule> played = MatchScheduleParser.read(FIXTURE).stream()
                .filter(ParsedSchedule::isPlayed)
                .toList();

        assertThat(played).isNotEmpty();
        assertThat(played).allSatisfy(s -> {
            assertThat(s.blueScore()).isNotNegative();
            assertThat(s.redScore()).isNotNegative();
            assertThat(s.blueKill()).isNotNegative();
            assertThat(s.redKill()).isNotNegative();
            assertThat(s.needWin()).isPositive();
        });
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("이벤트전은 대회에 속하지 않는다 — 대회를 필수로 두면 5건이 사라진다")
    void eventMatch_hasNoCompetition() throws Exception {
        List<ParsedSchedule> events = MatchScheduleParser.read(FIXTURE).stream()
                .filter(ParsedSchedule::isEvent)
                .toList();

        assertThat(events).hasSize(5);
        assertThat(events).allSatisfy(s -> {
            assertThat(s.competitionId()).isNull();
            assertThat(s.competitionKey()).isNull();
        });
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("이벤트전에도 킬은 남는다 — 세트 기록만 없다 (D16)")
    void eventMatch_stillCarriesKills() throws Exception {
        List<ParsedSchedule> events = MatchScheduleParser.read(FIXTURE).stream()
                .filter(ParsedSchedule::isEvent)
                .filter(ParsedSchedule::isPlayed)
                .toList();

        // savefile.md 는 "스케줄에 스코어만 남는다" 고 적었는데 킬도 남는다.
        // 기사가 이벤트전을 다룰 때 쓸 수 있는 숫자가 있다는 뜻이다.
        assertThat(events).isNotEmpty();
        assertThat(events).anySatisfy(s ->
                assertThat(s.blueKill() + s.redKill()).isPositive());
    }

    @Test
    @DisplayName("세이브가 아닌 파일은 조용히 빈 목록이 아니라 예외다")
    void read_rejectsNonSaveFile(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path bogus = Files.writeString(dir.resolve("not-a-save.tfm"), "hello");

        assertThatThrownBy(() -> MatchScheduleParser.read(bogus))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
