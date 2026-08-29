package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MatchBrief} 의 계약을 고정한다. <b>DB 도 LLM 도 쓰지 않는다.</b>
 *
 * <p>여기서 지키는 두 등식은 실측으로 확인된 것이다 — 109/109 매치에서
 * 세트 승수의 합이 스케줄 스코어와, 세트 킬의 합이 스케줄 킬과 정확히 일치했다.
 * 그 등식이 깨지면 조인이 틀렸거나 진영 처리가 틀린 것이다.
 */
class MatchBriefTest {

    private static final int BLUE = 30;
    private static final int RED = 37;

    /** 2세트짜리 매치. 2세트는 진영이 뒤바뀌어 있다 — 실측 294세트 중 122세트가 그렇다. */
    private static ParsedSchedule schedule(int blueScore, int redScore,
                                           int blueKill, int redKill) {
        return new ParsedSchedule(0, 1, "league.amateur", 2026, 7, 1,
                BLUE, RED, blueScore, redScore, blueKill, redKill, 2, 1.0, false);
    }

    private static ParsedGame set(int setNo, int gameBlue, int gameRed,
                                  int winTeam, int gameBlueKill, int gameRedKill) {
        return new ParsedGame(setNo, 0, 2026, 7, setNo, gameBlue, gameRed,
                gameBlueKill, gameRedKill, winTeam,
                List.of("A", "B", "C"), List.of("P1", "P2", "P3", "P4"),
                List.of("D", "E", "F"), List.of("Q1", "Q2", "Q3", "Q4"),
                List.of(), false, false);
    }

    @Test
    @DisplayName("세트를 매치 기준 진영으로 돌려세운다 — 뒤바뀐 세트도 같은 팀 편에 선다")
    void of_normalizesSidesToMatchOrientation() {
        // 1세트는 스케줄과 같은 진영, 2세트는 반대. 둘 다 BLUE(30) 가 이겼다.
        ParsedGame normal = set(1, BLUE, RED, 0, 11, 9);
        ParsedGame swapped = set(2, RED, BLUE, 1, 8, 13);

        MatchBrief brief = MatchBrief.of(schedule(2, 0, 24, 17), List.of(normal, swapped));

        assertThat(brief.sets()).hasSize(2);
        assertThat(brief.sets()).allSatisfy(s -> assertThat(s.blueWon()).isTrue());
        assertThat(brief.sets().get(1).sideSwapped()).isTrue();
        assertThat(brief.sets().get(0).sideSwapped()).isFalse();
        // 2세트는 게임 기준 8:13 이지만 매치 기준으로는 13:8 이다
        assertThat(brief.sets().get(1).blueKill()).isEqualTo(13);
        assertThat(brief.sets().get(1).redKill()).isEqualTo(8);
    }

    @Test
    @DisplayName("세트 승수의 합이 스케줄 스코어와 다르면 던진다 — 실측 109/109 로 성립하는 등식")
    void of_rejectsScoreMismatch() {
        List<ParsedGame> sets = List.of(set(1, BLUE, RED, 0, 11, 9),
                                        set(2, RED, BLUE, 1, 8, 13));

        assertThatThrownBy(() -> MatchBrief.of(schedule(1, 1, 24, 17), sets))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("스코어");
    }

    @Test
    @DisplayName("세트 킬의 합이 스케줄 킬과 다르면 던진다")
    void of_rejectsKillMismatch() {
        List<ParsedGame> sets = List.of(set(1, BLUE, RED, 0, 11, 9),
                                        set(2, RED, BLUE, 1, 8, 13));

        assertThatThrownBy(() -> MatchBrief.of(schedule(2, 0, 24, 99), sets))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("킬");
    }

    @Test
    @DisplayName("매치에 속하지 않는 세트가 섞이면 던진다 — 조용히 버리지 않는다")
    void of_rejectsForeignSet() {
        ParsedGame foreign = set(1, 99, 98, 0, 11, 9);

        assertThatThrownBy(() -> MatchBrief.of(schedule(1, 0, 11, 9), List.of(foreign)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("팀");
    }

    @Test
    @DisplayName("이벤트전은 세트가 없다 — 빈 목록이 정상이고 등식은 킬만 본다 (D16)")
    void of_allowsEventMatchWithoutSets() {
        ParsedSchedule event = new ParsedSchedule(109, null, null, 2025, 43, 1,
                27, 0, 0, 1, 15, 29, 1, 1.0, true);

        MatchBrief brief = MatchBrief.of(event, List.of());

        assertThat(brief.sets()).isEmpty();
        assertThat(brief.isEvent()).isTrue();
        assertThat(brief.winnerTeamId()).isEqualTo(0);
    }

    @Test
    @DisplayName("끝나지 않은 매치로는 brief 를 만들지 않는다 — 기사는 결과가 있어야 쓴다")
    void of_rejectsUnfinishedMatch() {
        ParsedSchedule playing = new ParsedSchedule(0, 1, "league.amateur", 2026, 7, 1,
                BLUE, RED, 1, 0, 11, 9, 2, 0.5, false);

        assertThatThrownBy(() -> MatchBrief.of(playing, List.of(set(1, BLUE, RED, 0, 11, 9))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("끝나지");
    }

    @Test
    @DisplayName("승자와 스윕 여부를 계산한다 — 기사 첫 문장이 쓰는 값")
    void of_derivesHeadlineFacts() {
        MatchBrief sweep = MatchBrief.of(schedule(2, 0, 24, 17),
                List.of(set(1, BLUE, RED, 0, 11, 9), set(2, RED, BLUE, 1, 8, 13)));

        assertThat(sweep.winnerTeamId()).isEqualTo(BLUE);
        assertThat(sweep.loserTeamId()).isEqualTo(RED);
        assertThat(sweep.isSweep()).isTrue();
        assertThat(sweep.setCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("무승부는 승자가 없다 — 이벤트전에서 나올 수 있다")
    void of_allowsNoWinner() {
        ParsedSchedule drawn = new ParsedSchedule(109, null, null, 2025, 43, 1,
                27, 0, 0, 0, 10, 10, 1, 1.0, true);

        MatchBrief brief = MatchBrief.of(drawn, List.of());

        assertThat(brief.winnerTeamId()).isNull();
        assertThat(brief.loserTeamId()).isNull();
        assertThat(brief.isSweep()).isFalse();
    }
}
