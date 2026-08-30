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

    /** 선수 이름을 아는 이름표. 101=Faker, 201=Chovy 로 둔다. */
    private static final NameBook NAMES_WITH_ATHLETES = new NameBook() {
        @Override
        public String teamName(Integer teamId) {
            return NAMES.teamName(teamId);
        }

        @Override
        public String competitionName(String key) {
            return NAMES.competitionName(key);
        }

        @Override
        public String athleteName(Integer athleteId) {
            if (athleteId == null) {
                return null;
            }
            return switch (athleteId) {
                case 101 -> "Faker";
                case 201 -> "Chovy";
                default -> null;
            };
        }
    };

    private static final Set<String> ATHLETES = Set.of("Faker", "Chovy", "Deft");

    /**
     * 선수 기록이 붙은 매치.
     *
     * <p>Faker 는 홈팀에서 {@code MagicKnight}, Chovy 는 원정팀에서 {@code Exorcist} 를 했다.
     * 관계 검사는 이 두 쌍을 기준으로 돈다.
     */
    private static MatchBrief briefWithPlayers() {
        List<MatchBrief.PlayerLine> players = List.of(
                new MatchBrief.PlayerLine(true, 101, "MagicKnight", 7, 2, 3, 12000, 4300, 0),
                new MatchBrief.PlayerLine(false, 201, "Exorcist", 5, 4, 1, 9000, 2100, 0));
        MatchBrief.SetBrief set = new MatchBrief.SetBrief(
                1, false, true, 11, 9,
                List.of("MagicKnight"), List.of("Exorcist"),
                List.of("Sniper"), List.of("Fighter"),
                false, false, players);
        return new MatchBrief(0, 1, "league.amateur", 2026, 7, 3,
                HOME, AWAY, 2, 0, 24, 17, 2, false, List.of(set));
    }

    @Test
    @DisplayName("선수와 챔피언을 잘못 묶으면 모순이다 — 낱말은 다 사실인데 연결이 틀렸다")
    void wrongPlayerChampionPairIsContradiction() {
        // Faker 는 MagicKnight 를 했고 Exorcist 는 Chovy 의 것이다.
        // 숫자도 이름도 전부 이 매치의 것이라 기존 검사로는 하나도 안 걸린다.
        String article = "Faker 가 Exorcist 로 경기를 지배했다.";

        FactCheckResult result = FactCheck.run(
                briefWithPlayers(), NAMES_WITH_ATHLETES, CHAMPIONS, Set.of(), ATHLETES, article);

        assertThat(result.contradictions())
                .anyMatch(f -> f.what().contains("선수와 챔피언을 잘못 묶었다"));
        assertThat(result.isClean()).isFalse();
    }

    @Test
    @DisplayName("맞게 묶으면 모순이 아니다")
    void correctPairIsClean() {
        String article = "Faker 가 MagicKnight 로 7킬을 올렸다. Chovy 는 Exorcist 로 맞섰다.";

        FactCheckResult result = FactCheck.run(
                briefWithPlayers(), NAMES_WITH_ATHLETES, CHAMPIONS, Set.of(), ATHLETES, article);

        assertThat(result.contradictions()).isEmpty();
    }

    @Test
    @DisplayName("한 문장에 선수 둘이 섞이면 모순이 아니라 미확인이다 — 확신할 때만 모순으로 올린다")
    void ambiguousSentenceIsUnverifiedNotContradiction() {
        // "상대로" 구조라 누가 무엇을 했는지 코드가 단정할 수 없다.
        String article = "Faker 는 Chovy 의 Exorcist 를 상대로 버텼다. 그리고 Sniper 는 밴이었다.";

        FactCheckResult result = FactCheck.run(
                briefWithPlayers(), NAMES_WITH_ATHLETES, CHAMPIONS, Set.of(), ATHLETES, article);

        assertThat(result.contradictions())
                .noneMatch(f -> f.what().contains("잘못 묶었다"));
    }

    @Test
    @DisplayName("이 매치에 없는 선수를 부르면 모순이다")
    void unknownAthleteIsContradiction() {
        String article = "Deft 가 결정적인 역할을 했다.";

        FactCheckResult result = FactCheck.run(
                briefWithPlayers(), NAMES_WITH_ATHLETES, CHAMPIONS, Set.of(), ATHLETES, article);

        assertThat(result.contradictions())
                .anyMatch(f -> f.what().equals("이 매치에 없는 선수") && f.evidence().equals("Deft"));
    }

    @Test
    @DisplayName("선수 이름을 안 넘기면 관계 검사를 하지 않는다 — 기존 호출은 그대로 돈다")
    void withoutAthleteVocabularyNothingChanges() {
        String article = "Faker 가 Exorcist 로 경기를 지배했다.";

        FactCheckResult result = FactCheck.run(
                briefWithPlayers(), NAMES, CHAMPIONS, Set.of(), article);

        assertThat(result.contradictions())
                .noneMatch(f -> f.what().contains("잘못 묶었다"));
    }

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

    @Test
    @DisplayName("선수 기록 숫자는 미확인이 아니다 — 우리가 준 사실이다")
    void playerStatsAreKnownNumbers() {
        // 실물에서 지적 23건이 거의 전부 이것이었다. 기사가 "6킬 3데스" 를 인용할 때마다
        // brief 에 없는 숫자로 잡혔다 — 선수 줄을 넣고 knownNumbers 를 안 고쳤기 때문이다.
        String article = "Faker 가 MagicKnight 로 7킬 2데스 3어시, 딜 12000 을 기록했다.";

        FactCheckResult result = FactCheck.run(
                briefWithPlayers(), NAMES_WITH_ATHLETES, CHAMPIONS, Set.of(), ATHLETES, article);

        assertThat(result.unverified())
                .as("선수 기록 숫자가 미확인으로 올라왔다: %s", result.unverified())
                .isEmpty();
    }

    @Test
    @DisplayName("천 단위를 띄어 쓴 숫자도 하나로 읽는다 — 모델이 19 461 로 쓴다")
    void thousandsSeparatorIsOneNumber() {
        String article = "Faker 의 딜은 12 000 이었다.";

        FactCheckResult result = FactCheck.run(
                briefWithPlayers(), NAMES_WITH_ATHLETES, CHAMPIONS, Set.of(), ATHLETES, article);

        // 12 와 000 두 개로 쪼개졌다면 미확인이 쌓인다
        assertThat(result.unverified()).isEmpty();
    }

    @Test
    @DisplayName("쉼표를 쓴 천 단위도 같다")
    void commaSeparatorIsOneNumber() {
        String article = "Faker 의 딜은 12,000 이었다.";

        assertThat(FactCheck.run(briefWithPlayers(), NAMES_WITH_ATHLETES,
                CHAMPIONS, Set.of(), ATHLETES, article).unverified()).isEmpty();
    }
}
