package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Notability} 의 계약을 고정한다. <b>DB 도 LLM 도 쓰지 않는다.</b>
 *
 * <p>가중치는 측정된 값이 아니다. 그래서 테스트는 <b>절대값이 아니라 순서</b>를 고정한다 —
 * "플레이어 경기가 남의 경기보다 주목도가 높다" 는 가중치를 바꿔도 지켜져야 하지만,
 * "플레이어 경기의 점수가 0.7 이다" 는 그렇지 않다.
 */
class NotabilityTest {

    private static final int PLAYER = 0;
    private static final int RIVAL = 37;
    private static final int OTHER_A = 30;
    private static final int OTHER_B = 31;

    private static MatchBrief match(int blue, int red, int blueScore, int redScore) {
        int sets = blueScore + redScore;
        ParsedSchedule schedule = new ParsedSchedule(0, 1, "league.amateur", 2026, 7, 1,
                blue, red, blueScore, redScore, 10 * sets, 8 * sets, 2, 1.0, false);
        List<ParsedGame> games = new java.util.ArrayList<>();
        for (int i = 1; i <= sets; i++) {
            boolean blueWins = i <= blueScore;
            games.add(new ParsedGame(i, 0, 2026, 7, i, blue, red,
                    blueWins ? 10 : 8, blueWins ? 8 : 10, blueWins ? 0 : 1,
                    List.of(), List.of("P1", "P2", "P3", "P4"),
                    List.of(), List.of("Q1", "Q2", "Q3", "Q4"),
                    List.of(), false, false));
        }
        // 킬 합을 스케줄과 맞춘다 — MatchBrief 가 등식을 강제한다
        int bk = games.stream().mapToInt(g -> g.blueScore()).sum();
        int rk = games.stream().mapToInt(g -> g.redScore()).sum();
        ParsedSchedule fixed = new ParsedSchedule(0, 1, "league.amateur", 2026, 7, 1,
                blue, red, blueScore, redScore, bk, rk, 2, 1.0, false);
        return MatchBrief.of(fixed, games);
    }

    private static NotabilityContext ctx() {
        return NotabilityContext.unknown(PLAYER);
    }

    @Test
    @DisplayName("점수는 0~1 을 벗어나지 않는다 — 분량 계산이 이 범위를 전제한다")
    void score_staysInRange() {
        NotabilityContext rich = new NotabilityContext(PLAYER, 1, 2, 12, 0.05, true);

        assertThat(Notability.of(match(PLAYER, RIVAL, 2, 1), rich).score())
                .isBetween(0.0, 1.0);
        assertThat(Notability.of(match(OTHER_A, OTHER_B, 2, 0), ctx()).score())
                .isBetween(0.0, 1.0);
    }

    @Test
    @DisplayName("플레이어 경기가 남의 경기보다 주목도가 높다")
    void playerMatch_outranksOthers() {
        double mine = Notability.of(match(PLAYER, OTHER_A, 2, 0), ctx()).score();
        double theirs = Notability.of(match(OTHER_A, OTHER_B, 2, 0), ctx()).score();

        assertThat(mine).isGreaterThan(theirs);
    }

    @Test
    @DisplayName("풀세트 접전이 스윕보다 주목도가 높다 — brief 만으로 알 수 있는 값")
    void closeSeries_outranksSweep() {
        double close = Notability.of(match(OTHER_A, OTHER_B, 2, 1), ctx()).score();
        double sweep = Notability.of(match(OTHER_A, OTHER_B, 2, 0), ctx()).score();

        assertThat(close).isGreaterThan(sweep);
    }

    @Test
    @DisplayName("업셋이 예상대로의 결과보다 주목도가 높다")
    void upset_outranksExpectedResult() {
        // 이길 확률 5% 인 팀이 이겼다 vs 95% 인 팀이 이겼다
        NotabilityContext upset = new NotabilityContext(null, null, null, null, 0.05, false);
        NotabilityContext expected = new NotabilityContext(null, null, null, null, 0.95, false);

        assertThat(Notability.of(match(OTHER_A, OTHER_B, 2, 0), upset).score())
                .isGreaterThan(Notability.of(match(OTHER_A, OTHER_B, 2, 0), expected).score());
    }

    @Test
    @DisplayName("라이벌전이 그렇지 않은 경기보다 주목도가 높다")
    void rivalry_outranksPlainMatch() {
        NotabilityContext rival = new NotabilityContext(null, null, null, null, null, true);

        assertThat(Notability.of(match(OTHER_A, OTHER_B, 2, 0), rival).score())
                .isGreaterThan(Notability.of(match(OTHER_A, OTHER_B, 2, 0), ctx()).score());
    }

    @Test
    @DisplayName("순위가 가까운 팀끼리의 경기가 멀리 떨어진 경기보다 주목도가 높다")
    void closeStandings_outrankDistantOnes() {
        NotabilityContext near = new NotabilityContext(null, 1, 2, 12, null, false);
        NotabilityContext far = new NotabilityContext(null, 1, 10, 12, null, false);

        assertThat(Notability.of(match(OTHER_A, OTHER_B, 2, 0), near).score())
                .isGreaterThan(Notability.of(match(OTHER_A, OTHER_B, 2, 0), far).score());
    }

    @Test
    @DisplayName("모르는 값으로는 이유를 지어내지 않는다")
    void unknownContext_inventsNoReasons() {
        Notability blank = Notability.of(match(OTHER_A, OTHER_B, 2, 0),
                NotabilityContext.unknown(null));

        assertThat(blank.score()).isBetween(0.0, 1.0);
        assertThat(blank.reasons()).doesNotContain("업셋", "라이벌", "순위 싸움");
    }

    @Test
    @DisplayName("아는 항에서 만점이면 모르는 항이 있어도 만점이다 — 몰라서 깎이면 안 된다")
    void unknownAxes_doNotDilute() {
        // 아는 것은 둘뿐이다: 내 팀 경기이고, 마지막 세트까지 갔다. 둘 다 만점이다.
        // 순위·업셋·라이벌을 모른다고 점수가 깎이면 새 커리어의 모든 기사가 짧아진다.
        Notability n = Notability.of(match(PLAYER, OTHER_A, 2, 1),
                NotabilityContext.unknown(PLAYER));

        assertThat(n.score()).isEqualTo(1.0);
        assertThat(n.paragraphs()).isEqualTo(6);
    }

    @Test
    @DisplayName("맥락을 더 안다고 같은 매치의 점수가 흔들리지 않는다 — 중립 맥락 기준")
    void neutralContext_matchesUnknownContext() {
        MatchBrief brief = match(PLAYER, OTHER_A, 2, 1);

        // 순위가 한가운데로 벌어져 있고 승률이 반반이면 그 항들은 중립이다.
        double known = Notability.of(brief,
                new NotabilityContext(PLAYER, 1, 7, 13, 0.5, false)).score();
        double unknown = Notability.of(brief, NotabilityContext.unknown(PLAYER)).score();

        assertThat(known).isLessThan(unknown);   // 중립 항이 들어오면 만점에서는 내려간다
        assertThat(known).isGreaterThan(0.5);    // 그래도 절반 아래로 떨어지지는 않는다
    }

    @Test
    @DisplayName("분량은 점수에서 나오고 단조롭다 — 점수가 높은데 더 짧아지지 않는다")
    void length_isMonotonicInScore() {
        Notability low = Notability.of(match(OTHER_A, OTHER_B, 2, 0), ctx());
        Notability high = Notability.of(match(PLAYER, RIVAL, 2, 1),
                new NotabilityContext(PLAYER, 1, 2, 12, 0.05, true));

        assertThat(high.score()).isGreaterThan(low.score());
        assertThat(high.paragraphs()).isGreaterThanOrEqualTo(low.paragraphs());
        assertThat(high.commentCount()).isGreaterThanOrEqualTo(low.commentCount());
    }

    @Test
    @DisplayName("이유를 사람이 읽을 수 있게 남긴다 — 왜 이 기사가 긴지 설명되어야 한다")
    void reasons_explainTheScore() {
        Notability n = Notability.of(match(PLAYER, RIVAL, 2, 1),
                new NotabilityContext(PLAYER, 1, 2, 12, 0.05, true));

        assertThat(n.reasons()).isNotEmpty();
        assertThat(String.join(" ", n.reasons())).contains("내 팀");
    }
}
