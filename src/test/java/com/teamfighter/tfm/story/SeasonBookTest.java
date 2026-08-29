package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link SeasonBook} 의 계약을 고정한다. <b>DB 도 LLM 도 쓰지 않는다.</b>
 *
 * <p>가장 중요한 성질은 <b>미래를 보지 않는 것</b>이다. 순위도 승률도 그 매치
 * <i>이전</i> 경기만으로 세야 한다. 시즌이 끝난 뒤의 순위로 기사를 쓰면
 * "3위 팀이 2위 팀을 잡았다" 가 실제로는 경기 후에야 정해진 순위가 된다.
 */
class SeasonBookTest {

    private static final int LEAGUE = 23;
    private static final int A = 1;
    private static final int B = 2;
    private static final int C = 3;

    private static ParsedSchedule match(int season, int day, Integer competition,
                                        int blue, int red, int blueScore, int redScore) {
        return new ParsedSchedule(day, competition, competition == null ? null : "league.pro",
                season, day, 1, blue, red, blueScore, redScore, 10, 8, 2, 1.0, false);
    }

    private static ParsedSchedule unplayed(int season, int day, int blue, int red) {
        return new ParsedSchedule(day, LEAGUE, "league.pro", season, day, 1,
                blue, red, 0, 0, 0, 0, 2, 0.0, false);
    }

    @Test
    @DisplayName("순위는 그 매치 이전 경기만으로 센다 — 미래를 보면 안 된다")
    void standings_useOnlyEarlierMatches() {
        ParsedSchedule target = match(2025, 5, LEAGUE, A, B, 2, 0);
        List<ParsedSchedule> season = List.of(
                match(2025, 1, LEAGUE, A, C, 2, 0),      // A 1승
                match(2025, 2, LEAGUE, B, C, 2, 0),      // B 1승
                match(2025, 3, LEAGUE, B, C, 2, 0),      // B 2승 → 이 시점 B가 1위
                target,
                match(2025, 9, LEAGUE, A, C, 2, 0),      // 나중 경기. 세면 안 된다
                match(2025, 10, LEAGUE, A, C, 2, 0));

        NotabilityContext ctx = new SeasonBook(season).contextFor(target, null);

        assertThat(ctx.blueRank()).isEqualTo(2);        // A: 1승
        assertThat(ctx.redRank()).isEqualTo(1);         // B: 2승
        assertThat(ctx.leagueSize()).isEqualTo(3);
    }

    @Test
    @DisplayName("치르지 않은 매치는 순위에 넣지 않는다")
    void standings_ignoreUnplayedMatches() {
        ParsedSchedule target = match(2025, 5, LEAGUE, A, B, 2, 0);
        List<ParsedSchedule> season = List.of(
                match(2025, 1, LEAGUE, A, C, 2, 0),
                unplayed(2025, 2, B, C),
                target);

        NotabilityContext ctx = new SeasonBook(season).contextFor(target, null);

        assertThat(ctx.blueRank()).isEqualTo(1);
        assertThat(ctx.redRank()).isEqualTo(2);         // B 는 아직 0승
    }

    @Test
    @DisplayName("첫 경기에는 순위가 없다 — 0승끼리의 순위는 근거가 아니다")
    void standings_absentBeforeAnyResult() {
        ParsedSchedule opener = match(2025, 1, LEAGUE, A, B, 2, 0);

        NotabilityContext ctx = new SeasonBook(List.of(opener)).contextFor(opener, null);

        assertThat(ctx.hasStandings()).isFalse();
    }

    @Test
    @DisplayName("대회가 없는 매치는 순위가 없다 — 이벤트전이 그렇다 (D16)")
    void standings_absentForEventMatches() {
        ParsedSchedule event = match(2025, 5, null, A, B, 1, 0);
        List<ParsedSchedule> season = List.of(match(2025, 1, LEAGUE, A, C, 2, 0), event);

        NotabilityContext ctx = new SeasonBook(season).contextFor(event, null);

        assertThat(ctx.hasStandings()).isFalse();
    }

    @Test
    @DisplayName("표본이 모자란 팀은 승률을 내지 않는다 — 2승 0패로 100% 라고 하지 않는다")
    void winProbability_absentWithoutEnoughMatches() {
        // 두 팀 다 경기를 했지만 하한에 못 미친다. 한쪽이 0경기면 다른 조건에 걸려서
        // 하한 자체를 검사하지 못한다 — 그러면 하한을 없애도 이 테스트가 통과한다.
        ParsedSchedule target = match(2025, 9, LEAGUE, A, B, 2, 0);
        List<ParsedSchedule> season = List.of(
                match(2025, 1, LEAGUE, A, C, 2, 0),
                match(2025, 2, LEAGUE, A, C, 2, 0),
                match(2025, 3, LEAGUE, B, C, 0, 2),
                match(2025, 4, LEAGUE, B, C, 0, 2),
                target);

        NotabilityContext ctx = new SeasonBook(season).contextFor(target, null);

        assertThat(ctx.blueWinProbability()).isNull();
    }

    @Test
    @DisplayName("표본이 차면 강한 팀의 승률이 더 높게 나온다")
    void winProbability_favoursTheStrongerTeam() {
        List<ParsedSchedule> season = new ArrayList<>();
        for (int day = 1; day <= 6; day++) {
            season.add(match(2025, day, LEAGUE, A, C, 2, 0));        // A 전승
        }
        for (int day = 7; day <= 12; day++) {
            season.add(match(2025, day, LEAGUE, B, C, 0, 2));        // B 전패
        }
        ParsedSchedule target = match(2025, 20, LEAGUE, A, B, 2, 0);
        season.add(target);

        NotabilityContext ctx = new SeasonBook(season).contextFor(target, null);

        assertThat(ctx.blueWinProbability()).isNotNull();
        assertThat(ctx.blueWinProbability()).isGreaterThan(0.8);
    }

    @Test
    @DisplayName("지난 시즌 브래킷에서 만났으면 라이벌이다 — 세이브가 그 대진만 남긴다")
    void rivalry_comesFromPastBrackets() {
        List<ParsedSchedule> history = List.of(
                match(2024, 30, 19, A, B, 3, 2),          // 지난 시즌 월즈에서 만났다
                match(2025, 5, LEAGUE, A, B, 2, 0));

        ParsedSchedule target = history.get(1);
        NotabilityContext ctx = new SeasonBook(history).contextFor(target, null);

        assertThat(ctx.rivalry()).isTrue();
    }

    @Test
    @DisplayName("같은 시즌에 여러 번 만난 것은 라이벌이 아니다 — 리그 일정이면 당연한 일이다")
    void rivalry_notFromSameSeasonRepeats() {
        List<ParsedSchedule> season = List.of(
                match(2025, 1, LEAGUE, A, B, 2, 0),
                match(2025, 9, LEAGUE, B, A, 2, 1));

        NotabilityContext ctx = new SeasonBook(season).contextFor(season.get(1), null);

        assertThat(ctx.rivalry()).isFalse();
    }

    @Test
    @DisplayName("브래킷 대회에는 순위가 없다 — 준결승을 '1위 대 1위' 라고 하면 안 된다")
    void standings_absentInBrackets() {
        // 4팀 3매치 = 단판 토너먼트. 매치 수가 팀 수보다 정확히 하나 적다.
        int D = 4;
        List<ParsedSchedule> bracket = List.of(
                match(2024, 30, 19, A, B, 2, 0),
                match(2024, 30, 19, C, D, 2, 0),
                match(2024, 31, 19, A, C, 3, 1));

        NotabilityContext ctx = new SeasonBook(bracket).contextFor(bracket.get(2), null);

        assertThat(ctx.hasStandings()).isFalse();
    }

    @Test
    @DisplayName("리그에는 순위가 있다 — 브래킷 판정이 리그까지 삼키면 안 된다")
    void standings_presentInLeagues() {
        // 3팀인데 매치가 4건이라 브래킷이 아니다
        List<ParsedSchedule> league = List.of(
                match(2025, 1, LEAGUE, A, B, 2, 0),
                match(2025, 2, LEAGUE, B, C, 2, 0),
                match(2025, 3, LEAGUE, A, C, 2, 0),
                match(2025, 4, LEAGUE, A, B, 2, 0));

        NotabilityContext ctx = new SeasonBook(league).contextFor(league.get(3), null);

        assertThat(ctx.hasStandings()).isTrue();
    }

    @Test
    @DisplayName("플레이어 팀 번호를 그대로 넘긴다 — 책이 그것까지 추측하지 않는다")
    void contextFor_passesPlayerTeamThrough() {
        ParsedSchedule target = match(2025, 1, LEAGUE, A, B, 2, 0);

        assertThat(new SeasonBook(List.of(target)).contextFor(target, 0).playerTeamId())
                .isEqualTo(0);
        assertThat(new SeasonBook(List.of(target)).contextFor(target, null).playerTeamId())
                .isNull();
    }
}
