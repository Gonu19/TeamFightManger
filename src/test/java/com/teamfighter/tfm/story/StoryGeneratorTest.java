package com.teamfighter.tfm.story;

import com.teamfighter.tfm.ingest.watcher.TfmProperties;
import com.teamfighter.tfm.parser.common.ParsedSchedule;
import com.teamfighter.tfm.parser.model.ParsedGame;
import com.teamfighter.tfm.story.dao.ArticleDao;
import com.teamfighter.tfm.story.dao.ArticleView;
import com.teamfighter.tfm.story.dao.StoryReference;
import com.teamfighter.tfm.story.dao.StoryReferenceDao;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "다음에 쓸 매치" 를 고르는 규칙을 본다.
 *
 * <p><b>세이브 파일을 읽지 않는다.</b> 파일을 읽는 입구 대신 파싱 결과를 직접 넘기는
 * 오버로드를 부른다 — 픽스처 세이브는 gitignore 라 이 PC 밖에서는 없고, 무엇보다
 * 여기서 검증하려는 것은 파싱이 아니라 <b>고르는 규칙</b>이다. 파싱은 파서 테스트가 본다.
 *
 * <p>모델은 가짜다. 진짜를 부르면 테스트가 네트워크·요금·매번 다른 답에 매인다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StoryGeneratorTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ArticleDao articles;

    @Autowired
    private StoryReferenceDao references;

    @Autowired
    private StoryProperties storyProperties;

    @Autowired
    private TfmProperties tfmProperties;

    /** 부르는 족족 같은 답을 준다. 몇 번 불렸는지 센다. */
    private static final class CountingClient implements StoryClient {
        private final List<StoryRequest> seen = new ArrayList<>();

        @Override
        public String complete(StoryRequest request) {
            seen.add(request);
            // 홀수 번째가 기사, 짝수 번째가 댓글이다 (ArticleWriter 가 그 순서로 부른다)
            return seen.size() % 2 == 1
                    ? "제목\n\n본문이다."
                    : "[{\"author\":\"ㅇㅇ(1.2)\",\"content\":\"댓글\",\"sub_comments\":[]}]";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private StoryGenerator generatorWith(CountingClient client) {
        ArticleWriter writer = new ArticleWriter(client, articles, references, storyProperties);
        return new StoryGenerator(writer, articles, references, tfmProperties);
    }

    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "gen_" + System.nanoTime());
    }

    private void team(int slotId, int gameTeamId, String name) {
        jdbc.update("""
                INSERT INTO team (slot_id, game_team_id, name) VALUES (?, ?, ?)
                """, slotId, gameTeamId, name);
    }

    /**
     * 끝난 매치 하나. {@code progress = 1.0} 이 "끝났다" 이고,
     * 스코어 2-0 · 킬 21-13 은 아래 세트 둘의 합과 맞춰 뒀다 —
     * {@code MatchBrief} 가 그 두 등식을 강제하기 때문이다.
     */
    private ParsedSchedule schedule(int season, int day, int blue, int red) {
        return new ParsedSchedule(7, 3, "competition.name.spring", season, day, 1,
                blue, red, 2, 0, 21, 13, 2, 1.0, false);
    }

    /** 아직 안 끝난 매치. 기사를 쓰면 결과를 지어내게 된다. */
    private ParsedSchedule unfinished(int season, int day, int blue, int red) {
        return new ParsedSchedule(8, 3, "competition.name.spring", season, day, 1,
                blue, red, 1, 0, 12, 8, 2, 0.5, false);
    }

    /** 그 매치의 세트 둘. 진영은 매치 기준과 같게 둔다. */
    private List<ParsedGame> sets(int season, int day, int blue, int red) {
        return List.of(
                new ParsedGame(1, 7, season, day, 1, blue, red, 12, 8, 0,
                        List.of(), List.of("Jiangshi"), List.of(), List.of("Wolfman"),
                        List.of(), false, false),
                new ParsedGame(2, 7, season, day, 2, blue, red, 9, 5, 0,
                        List.of(), List.of("Jiangshi"), List.of(), List.of("Wolfman"),
                        List.of(), false, false));
    }

    @Test
    @DisplayName("가장 최근 매치를 고른다 — 시즌이 먼저, 그 안에서 일(day)")
    void picksTheMostRecentMatch() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(
                schedule(2, 40, 33, 34),   // 같은 시즌의 나중 날
                schedule(2, 10, 33, 34),
                schedule(1, 99, 33, 34));  // 날짜는 크지만 지난 시즌

        List<ParsedGame> allSets = new ArrayList<>();
        allSets.addAll(sets(2, 40, 33, 34));
        allSets.addAll(sets(2, 10, 33, 34));
        allSets.addAll(sets(1, 99, 33, 34));

        Optional<Long> id = generatorWith(new CountingClient())
                .writeLatestUnwritten(reference, schedules, allSets);

        ArticleView written = articles.find(id.orElseThrow()).orElseThrow();
        assertThat(written.season()).isEqualTo(2);
        assertThat(written.day()).isEqualTo(40);
    }

    @Test
    @DisplayName("한 번에 한 편만 쓴다 — 모델 호출은 두 번(기사·댓글)이다")
    void writesExactlyOneArticlePerCall() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(
                schedule(2, 40, 33, 34), schedule(2, 10, 33, 34));
        List<ParsedGame> allSets = new ArrayList<>();
        allSets.addAll(sets(2, 40, 33, 34));
        allSets.addAll(sets(2, 10, 33, 34));

        CountingClient client = new CountingClient();
        generatorWith(client).writeLatestUnwritten(reference, schedules, allSets);

        assertThat(client.seen).hasSize(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*)::int FROM article WHERE slot_id = ?", Integer.class, slotId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("두 번 누르면 그 다음으로 최근인 매치를 쓴다 — 같은 것을 다시 쓰지 않는다")
    void secondCallMovesToTheNextMatch() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(
                schedule(2, 40, 33, 34), schedule(2, 10, 33, 34));
        List<ParsedGame> allSets = new ArrayList<>();
        allSets.addAll(sets(2, 40, 33, 34));
        allSets.addAll(sets(2, 10, 33, 34));

        StoryGenerator generator = generatorWith(new CountingClient());

        long first = generator.writeLatestUnwritten(reference, schedules, allSets).orElseThrow();
        long second = generator.writeLatestUnwritten(reference, schedules, allSets).orElseThrow();

        assertThat(second).isNotEqualTo(first);
        assertThat(articles.find(first).orElseThrow().day()).isEqualTo(40);
        assertThat(articles.find(second).orElseThrow().day()).isEqualTo(10);
    }

    @Test
    @DisplayName("다 쓰면 빈 값이다 — 예외가 아니다")
    void returnsEmptyWhenNothingLeft() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(schedule(2, 40, 33, 34));
        List<ParsedGame> allSets = sets(2, 40, 33, 34);

        StoryGenerator generator = generatorWith(new CountingClient());
        generator.writeLatestUnwritten(reference, schedules, allSets);

        assertThat(generator.writeLatestUnwritten(reference, schedules, allSets))
                .isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("안 끝난 매치는 고르지 않는다")
    void skipsUnfinishedMatches() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(
                unfinished(3, 50, 33, 34),   // 더 최근이지만 진행 중
                schedule(2, 40, 33, 34));

        List<ParsedGame> allSets = new ArrayList<>();
        allSets.addAll(sets(3, 50, 33, 34));
        allSets.addAll(sets(2, 40, 33, 34));

        long id = generatorWith(new CountingClient())
                .writeLatestUnwritten(reference, schedules, allSets).orElseThrow();

        assertThat(articles.find(id).orElseThrow().season()).isEqualTo(2);
    }

    @Test
    @DisplayName("세트 기록이 없는 매치는 고르지 않는다 — 게임이 지난 시즌 세트를 버린다 (D6)")
    void skipsMatchesWithoutSets() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(
                schedule(3, 50, 33, 34),   // 더 최근이지만 세트가 없다
                schedule(2, 40, 33, 34));

        long id = generatorWith(new CountingClient())
                .writeLatestUnwritten(reference, schedules, sets(2, 40, 33, 34)).orElseThrow();

        assertThat(articles.find(id).orElseThrow().season()).isEqualTo(2);
    }

    @Test
    @DisplayName("총평은 가장 최근 날을 쓴다 — 그날 경기 전부가 재료다")
    void roundSummaryCoversTheWholeDay() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        team(slotId, 35, "Anarchy");
        team(slotId, 36, "Runaway");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(
                schedule(2, 40, 33, 34),        // 같은 날 두 경기
                schedule(2, 40, 35, 36),
                schedule(2, 10, 33, 34));       // 지난 날

        long id = generatorWith(new CountingClient())
                .writeLatestRoundSummary(reference, schedules).orElseThrow();

        ArticleView view = articles.find(id).orElseThrow();
        assertThat(view.season()).isEqualTo(2);
        assertThat(view.day()).isEqualTo(40);
        // 총평에는 대전 상대가 없다
        assertThat(view.blueTeamId()).isNull();
        assertThat(view.kind()).isEqualTo(ArticleDraft.Kind.ROUND);
        // 사실 블록에 그날 두 경기가 다 있다
        assertThat(view.briefText()).contains("Seorabal Gaming").contains("Anarchy");
    }

    @Test
    @DisplayName("같은 날 총평을 두 번 쓰지 않는다 — 두 번째는 그 전날로 내려간다")
    void secondRoundSummaryMovesToTheEarlierDay() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        team(slotId, 35, "Anarchy");
        team(slotId, 36, "Runaway");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(
                schedule(2, 40, 33, 34), schedule(2, 40, 35, 36),
                schedule(2, 10, 33, 34), schedule(2, 10, 35, 36));

        StoryGenerator generator = generatorWith(new CountingClient());
        long first = generator.writeLatestRoundSummary(reference, schedules).orElseThrow();
        long second = generator.writeLatestRoundSummary(reference, schedules).orElseThrow();

        assertThat(second).isNotEqualTo(first);
        assertThat(articles.find(first).orElseThrow().day()).isEqualTo(40);
        assertThat(articles.find(second).orElseThrow().day()).isEqualTo(10);

        // 세 번째는 쓸 날이 없다
        assertThat(generator.writeLatestRoundSummary(reference, schedules))
                .isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("경기가 하나뿐인 날은 총평을 쓰지 않는다 — 매치 기사와 같은 말이 된다")
    void skipsDaysWithASingleMatch() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        StoryReference reference = references.load(slotId);

        assertThat(generatorWith(new CountingClient())
                .writeLatestRoundSummary(reference, List.of(schedule(2, 40, 33, 34))))
                .isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("총평과 매치 기사는 서로를 막지 않는다 — 같은 날이어도 둘 다 쓴다")
    void roundAndMatchArticlesCoexist() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");
        team(slotId, 35, "Anarchy");
        team(slotId, 36, "Runaway");
        StoryReference reference = references.load(slotId);

        List<ParsedSchedule> schedules = List.of(
                schedule(2, 40, 33, 34), schedule(2, 40, 35, 36));
        List<ParsedGame> allSets = new ArrayList<>();
        allSets.addAll(sets(2, 40, 33, 34));
        allSets.addAll(sets(2, 40, 35, 36));

        StoryGenerator generator = generatorWith(new CountingClient());
        generator.writeLatestUnwritten(reference, schedules, allSets);
        generator.writeLatestRoundSummary(reference, schedules);

        // 유일 키에 kind 가 들어 있어 둘이 부딪히지 않는다 (V10)
        assertThat(jdbc.queryForObject(
                "SELECT count(*)::int FROM article WHERE slot_id = ?", Integer.class, slotId))
                .isEqualTo(2);
    }
}
