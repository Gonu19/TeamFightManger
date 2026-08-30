package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FactCheck} 의 계약을 고정한다. <b>DB 도 LLM 도 쓰지 않는다.</b>
 *
 * <p>여기서 가장 조심할 것은 <b>거짓 양성</b>이다. 산문에 나오는 숫자를 전부 오류로
 * 부르면 목록이 잡음으로 가득 차고, 그러면 아무도 보지 않게 되어 검증 장치가 죽는다.
 * 그래서 심각도를 둘로 나눈다 — brief 와 <b>어긋나는</b> 것과, brief 가 <b>모르는</b> 것.
 */
class FactCheckTest {

    private static final int HOME = 30;
    private static final int AWAY = 37;

    private static final Set<String> CHAMPIONS = Set.of(
            "Exorcist", "ShieldBearer", "Chef", "Jiangshi",
            "Fighter", "Werewolf", "DuelBlader", "Pyromancer",
            "MagicKnight", "Monk", "Ninja", "Sniper", "Demon", "Ghost", "Bard");

    private static final NameBook NAMES = new NameBook() {
        @Override
        public String teamName(Integer teamId) {
            return HOME == teamId ? "Ember scale" : (AWAY == teamId ? "Damwon Gaming" : null);
        }

        @Override
        public String competitionName(String key) {
            return "아마추어 리그";
        }
    };

    /** 2세트 매치. 매치 2-0, 킬 24-17. 세트 킬은 11-9 와 13-8. */
    private static MatchBrief brief() {
        ParsedGame one = new ParsedGame(1, 0, 2026, 7, 1, HOME, AWAY, 11, 9, 0,
                List.of("MagicKnight", "Monk", "Ninja"),
                List.of("Exorcist", "ShieldBearer", "Chef", "Jiangshi"),
                List.of("Sniper", "Demon", "Ghost"),
                List.of("Fighter", "Werewolf", "DuelBlader", "Pyromancer"),
                List.of(), false, false);
        ParsedGame two = new ParsedGame(2, 0, 2026, 7, 2, AWAY, HOME, 8, 13, 1,
                List.of("Sniper", "Demon", "Ghost"),
                List.of("Fighter", "Werewolf", "DuelBlader", "Pyromancer"),
                List.of("MagicKnight", "Monk", "Ninja"),
                List.of("Exorcist", "ShieldBearer", "Chef", "Jiangshi"),
                List.of(), false, false);
        ParsedSchedule schedule = new ParsedSchedule(0, 1, "league.amateur", 2026, 7, 3,
                HOME, AWAY, 2, 0, 24, 17, 2, 1.0, false);
        return MatchBrief.of(schedule, List.of(one, two));
    }

    private static FactCheckResult check(String article) {
        return FactCheck.run(brief(), NAMES, CHAMPIONS, article);
    }

    @Test
    @DisplayName("사실에 맞는 기사는 지적할 것이 없다")
    void accurateArticle_hasNoFindings() {
        String article = "Ember scale 이 Damwon Gaming 을 2 - 0 으로 눌렀다. "
                + "1세트는 11 - 9, 2세트는 13 - 8 이었다. Werewolf 가 인상적이었다.";

        assertThat(check(article).contradictions()).isEmpty();
    }

    @Test
    @DisplayName("스코어를 틀리게 쓰면 모순으로 잡는다")
    void wrongScore_isContradiction() {
        FactCheckResult result = check("Ember scale 이 3 - 1 로 이겼다.");

        assertThat(result.contradictions()).isNotEmpty();
        assertThat(result.contradictions().toString()).contains("3 - 1");
    }

    @Test
    @DisplayName("유니코드 대시를 쓴 스코어도 검사한다 — 모델이 실제로 U+2011 을 쓴다")
    void unicodeDashScore_isStillChecked() {
        // 실물 호출에서 gpt-oss 가 "2‑1" 로 썼다. ASCII 하이픈만 보면 이 스코어가
        // 검사 대상에서 통째로 빠져서, 검증이 있는 척만 하게 된다.
        for (String dash : new String[]{"‐", "‑", "–", "—", "−"}) {
            assertThat(check("팀이 3" + dash + "1 로 이겼다.").contradictions())
                    .as("대시 %s", dash)
                    .isNotEmpty();
            assertThat(check("팀이 2" + dash + "0 로 이겼다.").contradictions())
                    .as("대시 %s (맞는 스코어)", dash)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("진 팀을 앞에 쓴 스코어는 모순이 아니다 — 관점이 다를 뿐이다")
    void reversedScore_isNotContradiction() {
        FactCheckResult result = check("Damwon Gaming 은 0 - 2 로 무너졌다.");

        assertThat(result.contradictions()).isEmpty();
    }

    @Test
    @DisplayName("이 경기에 없던 챔피언을 쓰면 모순으로 잡는다 — 가장 흔한 환각이다")
    void championNotInMatch_isContradiction() {
        FactCheckResult result = check("Bard 의 활약이 결정적이었다.");

        assertThat(result.contradictions()).isNotEmpty();
        assertThat(result.contradictions().toString()).contains("Bard");
    }

    @Test
    @DisplayName("밴된 챔피언은 모순이 아니라 주의다 — 밴은 사실이지만 '캐리했다' 는 환각이다")
    void bannedChampion_isUnverifiedNotContradiction() {
        FactCheckResult result = check("Monk 가 밴된 것이 컸다.");

        assertThat(result.contradictions()).isEmpty();
        assertThat(result.unverified().toString()).contains("Monk");
    }

    @Test
    @DisplayName("팀 번호는 brief 의 사실이다 — 이름을 모를 때 기사가 번호를 쓴다")
    void teamIds_areKnownNumbers() {
        FactCheckResult result = check("팀 30 이 팀 37 을 눌렀다.");

        assertThat(result.contradictions()).isEmpty();
        assertThat(result.unverified()).isEmpty();
    }

    @Test
    @DisplayName("챔피언 이름이 더 긴 낱말 안에 들어 있으면 언급이 아니다 — Monk 와 Monkey")
    void championName_needsWordBoundary() {
        // 이 매치에 Bard 는 없다. 그러나 "Bardic" 은 Bard 를 말한 것이 아니다.
        // 단순 포함으로 보면 여기서 거짓 양성이 터진다.
        FactCheckResult result = check("Bardic 이라는 별명이 붙었다.");

        assertThat(result.contradictions()).isEmpty();
    }

    @Test
    @DisplayName("이름 앞에 다른 글자가 붙어도 언급이 아니다 — SuperBard 는 Bard 가 아니다")
    void championName_needsLeftBoundaryToo() {
        FactCheckResult result = check("SuperBard 라는 팀 구호가 있었다.");

        assertThat(result.contradictions()).isEmpty();
    }

    @Test
    @DisplayName("한글 조사가 바로 붙어도 언급으로 본다 — Bard가")
    void championName_allowsKoreanParticles() {
        FactCheckResult result = check("Bard가 경기를 지배했다.");

        assertThat(result.contradictions().toString()).contains("Bard");
    }

    @Test
    @DisplayName("산문 속 숫자는 모순이 아니라 미확인이다 — 여기서 거짓 양성이 터진다")
    void proseNumbers_areUnverifiedNotContradictions() {
        FactCheckResult result = check("경기는 20분 만에 끝났고, 3년 만의 우승이었다.");

        assertThat(result.contradictions()).isEmpty();
        assertThat(result.unverified()).isNotEmpty();
    }

    @Test
    @DisplayName("brief 가 아는 숫자는 미확인에도 올리지 않는다")
    void knownNumbers_areNotEvenUnverified() {
        FactCheckResult result = check("2026 시즌 7일차, 3라운드 경기였다.");

        assertThat(result.contradictions()).isEmpty();
        assertThat(result.unverified()).isEmpty();
    }

    @Test
    @DisplayName("이 경기에 없던 팀 이름을 쓰면 모순으로 잡는다")
    void wrongTeamName_isContradiction() {
        FactCheckResult result = check("T1 이 승리했다.", Set.of("T1", "Ember scale", "Damwon Gaming"));

        assertThat(result.contradictions().toString()).contains("T1");
    }

    private static FactCheckResult check(String article, Set<String> teamVocabulary) {
        return FactCheck.run(brief(), NAMES, CHAMPIONS, teamVocabulary, article);
    }

    @Test
    @DisplayName("같은 기사는 항상 같은 결과가 된다 — 화면에 그대로 붙는다")
    void run_isDeterministic() {
        String article = "Ember scale 이 2 - 0 으로 이겼다. Bard 가 활약했다. 20분 경기.";

        assertThat(check(article)).isEqualTo(check(article));
    }

    @Test
    @DisplayName("빈 기사는 지적할 것이 없다 — 없는 것을 틀렸다고 하지 않는다")
    void emptyArticle_hasNoFindings() {
        assertThat(check("").contradictions()).isEmpty();
        assertThat(check("").unverified()).isEmpty();
    }
}
