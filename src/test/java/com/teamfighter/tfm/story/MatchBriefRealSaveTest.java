package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.MatchScheduleParser;
import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.model.ParsedSave;
import com.teamfighter.tfm.parser.save.SaveParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실물 세이브 전체로 {@link MatchBrief} 를 만들어 본다.
 *
 * <p>단위 테스트는 우리가 지어낸 세트로 등식을 확인한다. 이 테스트는 <b>게임이 실제로
 * 남긴 데이터</b>로 확인한다. 조인 키가 틀렸거나 진영 처리가 틀렸다면 여기서 터진다 —
 * 한 매치라도 등식이 깨지면 {@code MatchBrief.of} 가 던지기 때문이다.
 */
class MatchBriefRealSaveTest {

    private static final Path FIXTURE = Path.of("fixtures", "slot_638683925954242004.tfm");

    static boolean fixtureExists() {
        return Files.isRegularFile(FIXTURE);
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("실물 109매치가 전부 brief 로 만들어진다 — 두 등식이 하나도 안 깨진다")
    void allRealMatches_buildWithoutViolation() throws Exception {
        List<ParsedSchedule> schedules = MatchScheduleParser.read(FIXTURE);
        Map<ParsedSchedule.MatchKey, List<ParsedGame>> setsByMatch = groupSets(FIXTURE);

        List<MatchBrief> briefs = new ArrayList<>();
        for (ParsedSchedule schedule : schedules) {
            if (!schedule.isPlayed()) {
                continue;
            }
            List<ParsedGame> sets = setsByMatch.getOrDefault(schedule.matchKey(), List.of());
            briefs.add(MatchBrief.of(schedule, sets));       // 등식이 깨지면 여기서 던진다
        }

        // 세트가 붙은 매치 109건 + 세트를 안 남기는 이벤트전 등
        assertThat(briefs).hasSizeGreaterThanOrEqualTo(109);
        assertThat(briefs).filteredOn(b -> b.setCount() > 0).hasSize(109);

        // 세트를 가진 매치는 세트 합이 스케줄과 맞아야 brief 가 만들어졌다는 뜻이다.
        // 여기서는 그 결과가 실제로 쓸 만한지만 본다.
        assertThat(briefs)
                .filteredOn(b -> b.setCount() > 0)
                .allSatisfy(b -> {
                    assertThat(b.sets()).hasSize(b.blueScore() + b.redScore());
                    assertThat(b.sets()).allSatisfy(s -> {
                        assertThat(s.bluePick()).hasSize(4);
                        assertThat(s.redPick()).hasSize(4);
                    });
                });
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("진영이 뒤바뀐 세트가 실제로 있다 — 없으면 정규화가 죽은 코드다")
    void realSave_containsSwappedSets() throws Exception {
        List<ParsedSchedule> schedules = MatchScheduleParser.read(FIXTURE);
        Map<ParsedSchedule.MatchKey, List<ParsedGame>> setsByMatch = groupSets(FIXTURE);

        long swapped = schedules.stream()
                .filter(ParsedSchedule::isPlayed)
                .map(s -> MatchBrief.of(s, setsByMatch.getOrDefault(s.matchKey(), List.of())))
                .flatMap(b -> b.sets().stream())
                .filter(MatchBrief.SetBrief::sideSwapped)
                .count();

        // 실측값이다. 크게 달라지면 세이브 구조가 바뀐 것이므로 조인을 다시 봐야 한다.
        assertThat(swapped).isEqualTo(122);
    }

    @Test
    @EnabledIf("fixtureExists")
    @DisplayName("실물에서 주목도가 퍼진다 — 한 점에 몰리면 분량을 못 가른다")
    void notability_spreadsOverRealMatches() throws Exception {
        List<ParsedSchedule> schedules = MatchScheduleParser.read(FIXTURE);
        Map<ParsedSchedule.MatchKey, List<ParsedGame>> setsByMatch = groupSets(FIXTURE);
        NotabilityContext context = NotabilityContext.unknown(0);   // 플레이어 팀만 안다

        List<Notability> scored = schedules.stream()
                .filter(ParsedSchedule::isPlayed)
                .map(s -> Notability.of(
                        MatchBrief.of(s, setsByMatch.getOrDefault(s.matchKey(), List.of())),
                        context))
                .toList();

        assertThat(scored).isNotEmpty();
        // 아는 것이 "플레이어 팀" 과 "접전" 둘뿐인데도 분량이 갈려야 한다.
        assertThat(scored.stream().map(Notability::paragraphs).distinct().count())
                .isGreaterThanOrEqualTo(3L);
        assertThat(scored.stream().mapToDouble(Notability::score).max().orElseThrow())
                .isGreaterThan(scored.stream().mapToDouble(Notability::score).min().orElseThrow() + 0.3);
    }

    private static Map<ParsedSchedule.MatchKey, List<ParsedGame>> groupSets(Path save)
            throws Exception {
        ParsedSave parsed = SaveParser.read(save);
        Map<ParsedSchedule.MatchKey, List<ParsedGame>> byMatch = new HashMap<>();
        for (ParsedGame game : parsed.gameStats()) {
            ParsedSchedule.MatchKey key = ParsedSchedule.MatchKey.of(
                    game.season(), game.day(), game.blueTeamId(), game.redTeamId());
            byMatch.computeIfAbsent(key, k -> new ArrayList<>()).add(game);
        }
        return byMatch;
    }
}
