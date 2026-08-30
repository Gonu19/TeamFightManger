package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BriefRenderer} 의 계약을 고정한다. <b>DB 도 LLM 도 쓰지 않는다.</b>
 *
 * <p>이 텍스트는 두 곳에 쓰인다 — LLM 프롬프트의 사실 블록, 그리고 화면의
 * 「이 기사가 쓴 숫자」(D61). <b>같은 문자열이어야 한다.</b> 독자가 보는 숫자가
 * 모델이 본 숫자와 다르면 그 블록은 검증 장치가 아니라 장식이 된다.
 */
class BriefRendererTest {

    private static final int HOME = 30;
    private static final int AWAY = 37;

    private static final NameBook NAMES = new NameBook() {
        private final Map<Integer, String> teams = Map.of(HOME, "Ember scale", AWAY, "Damwon Gaming");

        @Override
        public String teamName(Integer teamId) {
            return teams.get(teamId);
        }

        @Override
        public String competitionName(String key) {
            return "league.amateur".equals(key) ? "아마추어 리그" : null;
        }
    };

    private static ParsedGame set(int setNo, int blue, int red, int winTeam, int bk, int rk) {
        return new ParsedGame(setNo, 0, 2026, 7, setNo, blue, red, bk, rk, winTeam,
                List.of("MagicKnight", "Monk", "Ninja"),
                List.of("Exorcist", "ShieldBearer", "Chef", "Jiangshi"),
                List.of("Sniper", "Demon", "Ghost"),
                List.of("Fighter", "Werewolf", "DuelBlader", "Pyromancer"),
                List.of(), false, false);
    }

    private static MatchBrief twoSetMatch() {
        // 2세트는 진영이 뒤바뀐다 — 실측 294세트 중 122세트가 그렇다
        List<ParsedGame> sets = List.of(set(1, HOME, AWAY, 0, 11, 9),
                                        set(2, AWAY, HOME, 1, 8, 13));
        ParsedSchedule schedule = new ParsedSchedule(0, 1, "league.amateur", 2026, 7, 3,
                HOME, AWAY, 2, 0, 24, 17, 2, 1.0, false);
        return MatchBrief.of(schedule, sets);
    }

    @Test
    @DisplayName("머리글에 대회·라운드·날짜·스코어·킬이 전부 들어간다")
    void render_headlineCarriesAllMatchNumbers() {
        String text = BriefRenderer.render(twoSetMatch(), NAMES);

        assertThat(text).contains("아마추어 리그").contains("3라운드");
        assertThat(text).contains("2026").contains("7");
        assertThat(text).contains("Ember scale").contains("Damwon Gaming");
        assertThat(text).contains("2 - 0");
        assertThat(text).contains("24").contains("17");
    }

    @Test
    @DisplayName("세트는 매치 기준 진영으로 적는다 — 뒤바뀐 세트도 같은 팀 편에 선다")
    void render_usesMatchOrientation() {
        String text = BriefRenderer.render(twoSetMatch(), NAMES);

        // 두 세트 다 Ember scale 이 이겼다. 2세트는 게임 기준 8:13 이지만 13:8 로 적혀야 한다
        assertThat(text).contains("13 - 8");
        assertThat(text).doesNotContain("8 - 13");
    }

    @Test
    @DisplayName("진영이 바뀐 세트는 그 사실을 적는다 — 기사가 '진영을 바꿔'를 말할 수 있어야 한다")
    void render_marksSwappedSides() {
        String text = BriefRenderer.render(twoSetMatch(), NAMES);

        assertThat(text).contains("진영 교체");
    }

    @Test
    @DisplayName("세트마다 픽과 밴이 양쪽 다 들어간다")
    void render_includesPicksAndBans() {
        String text = BriefRenderer.render(twoSetMatch(), NAMES);

        assertThat(text).contains("Exorcist").contains("Jiangshi");
        assertThat(text).contains("Fighter").contains("Pyromancer");
        assertThat(text).contains("MagicKnight").contains("Sniper");
    }

    @Test
    @DisplayName("픽·밴 줄에 어느 팀 것인지 적는다 — 슬래시 좌우로는 알 수 없다")
    void render_labelsEachSideOfPicks() {
        String text = BriefRenderer.render(twoSetMatch(), NAMES);

        // 실물 호출에서 모델이 2세트 진영을 반대로 썼다. 챔피언은 전부 이 매치의
        // 것이라 FactCheck 도 잡지 못하는 종류의 오류다.
        assertThat(text).contains("Ember scale: Exorcist");
        assertThat(text).contains("Damwon Gaming: Fighter");
    }

    @Test
    @DisplayName("이름을 모르면 번호로 적는다 — 지어내지 않는다")
    void render_fallsBackToIdsWithoutInventingNames() {
        String text = BriefRenderer.render(twoSetMatch(), NameBook.ids());

        assertThat(text).contains("30").contains("37");
        assertThat(text).doesNotContain("Ember scale");
        // 대회 이름을 모르면 키를 그대로 둔다. 빈 칸으로 두면 기사가 지어낸다
        assertThat(text).contains("league.amateur");
    }

    @Test
    @DisplayName("이벤트전은 세트 절이 없다 — 세트가 없다는 사실이 드러나야 한다 (D16)")
    void render_eventMatchHasNoSetSection() {
        ParsedSchedule event = new ParsedSchedule(109, null, null, 2025, 43, 1,
                27, 0, 0, 1, 15, 29, 1, 1.0, true);

        String text = BriefRenderer.render(MatchBrief.of(event, List.of()), NameBook.ids());

        assertThat(text).contains("이벤트전");
        assertThat(text).doesNotContain("1세트");
    }

    @Test
    @DisplayName("같은 brief 는 항상 같은 문자열이 된다 — 프롬프트와 화면이 갈리면 안 된다")
    void render_isDeterministic() {
        MatchBrief brief = twoSetMatch();

        assertThat(BriefRenderer.render(brief, NAMES))
                .isEqualTo(BriefRenderer.render(brief, NAMES));
    }

    @Test
    @DisplayName("brief 에 없는 숫자는 텍스트에도 없다 — 나중에 FactCheck 의 기준이 된다")
    void render_containsOnlyNumbersFromBrief() {
        MatchBrief brief = twoSetMatch();
        String text = BriefRenderer.render(brief, NAMES);

        // 텍스트에 나오는 정수를 전부 모아 brief 가 아는 값인지 확인한다.
        java.util.Set<Integer> allowed = new java.util.HashSet<>(List.of(
                brief.season(), brief.day(), brief.round(),
                brief.blueScore(), brief.redScore(), brief.blueKill(), brief.redKill(),
                brief.blueTeamId(), brief.redTeamId()));
        brief.sets().forEach(s -> {
            allowed.add(s.setNo());
            allowed.add(s.blueKill());
            allowed.add(s.redKill());
        });

        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d+").matcher(text);
        while (m.find()) {
            assertThat(allowed).contains(Integer.parseInt(m.group()));
        }
    }
}
