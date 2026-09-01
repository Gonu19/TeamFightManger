package com.teamfighter.tfm.story;

import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.parser.model.ParsedStat;
import com.teamfighter.tfm.story.dao.StoryReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>챔피언을 한글로 부르되, 대조가 죽지 않는가</b> (D80).
 *
 * <h2>왜 이 시험이 따로 있나</h2>
 *
 * 기사가 "Ember scale는 DuelBlader와 Demon을" 이라고 써서 몰입이 깨졌다. 고치는 것은
 * 쉽다 — 렌더러가 한글을 적으면 된다. <b>위험한 것은 그 다음이다.</b>
 *
 * <p>{@code FactCheck} 는 기사 본문에서 챔피언 이름을 찾아 "이 매치에 없는 챔피언" 을
 * 잡는다. 렌더러만 한글로 바꾸고 대조 어휘를 코드로 두면, 기사에 나온 챔피언이
 * <b>하나도 안 걸린다</b> — 지적이 0건이 되는데 그건 "깨끗하다" 와 화면에서 똑같이 보인다.
 * 검사가 꺼진 것을 아무도 모른다. D66 이 "대조는 어휘가 같을 때만 작동한다" 로 적어둔
 * 실패가 정확히 이것이다.
 *
 * <p>그래서 여기서 재는 것은 <b>두 쪽이 같은 말을 쓰는가</b>이지 번역 자체가 아니다.
 */
class ChampionNamingTest {

    private static final int HOME = 30;
    private static final int AWAY = 37;

    /** 코드 → 한글. 실제 시드에서 가져온 값이다. */
    private static final Map<String, String> KOREAN = Map.of(
            "Werewolf", "늑대인간",
            "DuelBlader", "듀얼 블레이더",
            "Demon", "악마",
            "Jiangshi", "강시",
            "Knight", "기사",
            "Chef", "요리사");

    private static StoryReference reference() {
        return new StoryReference(1, null,
                Map.of(HOME, 100, AWAY, 101),
                Map.of(HOME, "Ember scale", AWAY, "MiG"),
                KOREAN,
                Set.of("Ember scale", "MiG"),
                Map.of(7, "Clozer"));
    }

    private static MatchBrief match() {
        // 선수 줄 하나. 갤러리·댓글이 보는 표가 이 줄에서 나온다 —
        // 선수 이름을 아는 경우에만 표에 들어간다.
        ParsedStat stat = new ParsedStat("Werewolf", 5, 2, 3, 12000, 4300, 0, 0, 7);
        ParsedGame set = new ParsedGame(1, 0, 2026, 7, 1, HOME, AWAY, 11, 9, 0,
                List.of(), List.of("Werewolf", "DuelBlader"),
                List.of(), List.of("Demon", "Jiangshi"),
                List.of(stat), false, false);
        // 스코어 1-0 · 킬 11-9 는 아래 세트 하나의 값과 맞춰 뒀다 —
        // MatchBrief 가 그 두 등식을 강제한다.
        ParsedSchedule schedule = new ParsedSchedule(0, 1, "league.amateur", 2026, 7, 3,
                HOME, AWAY, 1, 0, 11, 9, 1, 1.0, false);
        return MatchBrief.of(schedule, List.of(set));
    }

    @Test
    @DisplayName("모르는 코드는 코드를 그대로 — 빈 칸을 남기지 않는다")
    void anUnknownCodeFallsBackToItself() {
        // 번호와 달리 코드는 그 자체로 읽을 수 있다. 빈 칸을 남기면 기사가 채운다 (D57).
        assertThat(reference().championName("NoSuchChampion")).isEqualTo("NoSuchChampion");
        assertThat(reference().championName("Werewolf")).isEqualTo("늑대인간");
        assertThat(reference().championName(null)).isNull();
    }

    /**
     * 선수 기록이 없는 매치. 그때만 렌더러가 <b>픽·밴 목록</b>을 그린다 —
     * 선수 줄이 있으면 그쪽이 관계를 더 잘 지키므로 목록 대신 줄을 쓴다.
     */
    private static MatchBrief picksOnly() {
        ParsedGame set = new ParsedGame(1, 0, 2026, 7, 1, HOME, AWAY, 11, 9, 0,
                List.of("Chef"), List.of("Werewolf", "DuelBlader"),
                List.of("Knight"), List.of("Demon", "Jiangshi"),
                List.of(), false, false);
        ParsedSchedule schedule = new ParsedSchedule(0, 1, "league.amateur", 2026, 7, 3,
                HOME, AWAY, 1, 0, 11, 9, 1, 1.0, false);
        return MatchBrief.of(schedule, List.of(set));
    }

    @Test
    @DisplayName("픽·밴 목록에 영어 코드가 없다")
    void picksAndBansAreWrittenInKorean() {
        String rendered = BriefRenderer.render(picksOnly(), reference());

        assertThat(rendered).contains("늑대인간", "듀얼 블레이더", "악마", "강시", "요리사", "기사");
        assertThat(rendered)
                .doesNotContain("Werewolf", "DuelBlader", "Demon", "Jiangshi", "Chef", "Knight");
    }

    @Test
    @DisplayName("선수 줄의 챔피언도 한글이다 — 환각을 가장 많이 막는 그 줄이다")
    void thePlayerLineIsWrittenInKorean() {
        String rendered = BriefRenderer.render(match(), reference());

        assertThat(rendered).contains("Clozer | 늑대인간 |");
        assertThat(rendered).doesNotContain("Werewolf");
    }

    @Test
    @DisplayName("대조 어휘도 한글이다 — 이게 갈리면 검사가 조용히 죽는다")
    void theCheckingVocabularySpeaksTheSameLanguage() {
        assertThat(reference().championNames())
                .contains("늑대인간", "악마", "기사")
                .doesNotContain("Werewolf", "Demon", "Knight");
    }

    @Test
    @DisplayName("이 매치에 나온 챔피언을 한글로 쓰면 모순이 아니다")
    void championsThatActuallyPlayedAreNotFlagged() {
        // 이 시험이 깨지는 방식이 곧 그 버그다: 어휘가 갈리면 뽑힌 챔피언이
        // "이 매치에 나오지 않은 챔피언" 으로 찍힌다.
        FactCheckResult result = FactCheck.run(match(), reference(),
                reference().championNames(),
                "늑대인간과 듀얼 블레이더가 악마와 강시를 상대로 앞섰다.");

        assertThat(result.contradictions()).isEmpty();
    }

    @Test
    @DisplayName("이 매치에 없는 챔피언을 한글로 쓰면 잡힌다 — 검사가 살아 있다")
    void aChampionThatWasNotHereIsStillCaught() {
        // 위 시험만 있으면 "아무것도 안 잡는 검사" 도 통과한다. 살아 있다는 것을
        // 따로 재야 한다.
        FactCheckResult result = FactCheck.run(match(), reference(),
                reference().championNames(),
                "요리사가 경기를 지배했다.");

        assertThat(result.contradictions())
                .extracting(FactCheckResult.Finding::evidence)
                .contains("요리사");
    }

    @Test
    @DisplayName("갤러리·댓글이 보는 선수 표에도 챔피언이 한글로 붙는다")
    void thePlayerTableCarriesTheChampionToo() {
        // 전에는 이 표에 챔피언이 <b>아예 없었다</b>. 그래서 갤 글이 "Exorcist" 같은
        // 이름을 학습 지식에서 지어냈다 — 이 매치에 없는 챔피언이 나오는 것도,
        // 영어로 나오는 것도 같은 구멍에서 왔다 (D80).
        String totals = StoryPrompts.playerTotals(match(), reference());

        assertThat(totals).contains("Clozer", "늑대인간");
        assertThat(totals).doesNotContain("Werewolf");
    }

    @Test
    @DisplayName("영어 코드로 쓰인 옛 기사는 이제 안 잡힌다 — 어휘가 바뀐 대가다")
    void oldEnglishArticlesNoLongerMatch() {
        // 숨기지 않고 시험으로 적어 둔다. 이미 저장된 기사는 코드로 쓰여 있어서
        // 다시 대조하면 챔피언 검사가 아무것도 안 잡는다. 다시 쓰면 한글이 된다.
        FactCheckResult result = FactCheck.run(match(), reference(),
                reference().championNames(),
                "Chef가 경기를 지배했다.");

        assertThat(result.contradictions()).isEmpty();
    }
}
