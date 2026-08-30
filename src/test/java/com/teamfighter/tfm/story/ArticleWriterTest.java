package com.teamfighter.tfm.story;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 네 계층을 이어 붙인 결과가 DB 에 제대로 앉는지 본다.
 *
 * <p><b>모델만 가짜다.</b> DAO 도 스키마도 진짜를 쓴다 — 이 클래스가 하는 일이 "이어 붙이기"
 * 뿐이라서, 진짜를 하나라도 빼면 검증할 것이 남지 않는다. 반대로 모델은 가짜여야 한다.
 * 네트워크를 타면 테스트가 키와 요금과 남의 서버 사정에 매이고, 무엇보다 <b>답이 매번 달라져서</b>
 * 제목 분리나 대조 결과를 고정할 수 없다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ArticleWriterTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ArticleDao articles;

    @Autowired
    private StoryReferenceDao references;

    @Autowired
    private StoryProperties properties;

    /** 정해진 답을 순서대로 돌려준다. 기사 한 번, 댓글 한 번 — 두 번 불린다. */
    private static final class ScriptedClient implements StoryClient {
        private final List<String> answers;
        private final List<StoryRequest> seen = new ArrayList<>();
        private int next;

        ScriptedClient(String... answers) {
            this.answers = List.of(answers);
        }

        @Override
        public String complete(StoryRequest request) {
            seen.add(request);
            if (next >= answers.size()) {
                throw new IllegalStateException("예상보다 많이 불렸다: " + (next + 1) + "번째");
            }
            return answers.get(next++);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    /** 부르면 무조건 실패한다. */
    private static final class FailingClient implements StoryClient {
        private final int failAt;
        private int calls;

        FailingClient(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public String complete(StoryRequest request) {
            if (++calls >= failAt) {
                throw new StoryFailedException("연결이 끊겼다");
            }
            return "제목\n\n본문이다.";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }

    private ArticleWriter writerWith(StoryClient client) {
        return new ArticleWriter(client, articles, references, properties);
    }

    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "writer_" + System.nanoTime());
    }

    private void team(int slotId, int gameTeamId, String name) {
        jdbc.update("""
                INSERT INTO team (slot_id, game_team_id, name) VALUES (?, ?, ?)
                """, slotId, gameTeamId, name);
    }

    /** 세트 둘로 끝난 2-0 매치. 두 등식(승수 합·킬 합)을 맞춰 둔다 */
    private MatchBrief brief(int blueGameTeamId, int redGameTeamId) {
        List<MatchBrief.SetBrief> sets = List.of(
                new MatchBrief.SetBrief(1, false, true, 12, 8,
                        List.of("Jiangshi"), List.of("Wolfman"), List.of(), List.of(), false, false),
                new MatchBrief.SetBrief(2, true, true, 9, 5,
                        List.of("Jiangshi"), List.of("Wolfman"), List.of(), List.of(), false, false));
        return new MatchBrief(7, 3, "competition.name.spring", 2, 30, 1,
                blueGameTeamId, redGameTeamId, 2, 0, 21, 13, 2, false, sets);
    }

    @Test
    @DisplayName("기사와 댓글을 만들어 저장한다 — 팀 실명이 붙는다")
    void writesAndStores() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");

        ScriptedClient client = new ScriptedClient(
                "완봉으로 끝난 승부\n\n첫 세트부터 흐름을 놓지 않았다.",
                "1. 이게 실화냐\n2. 다음 경기도 보자");

        long articleId = writerWith(client).write(slotId, brief(33, 34), NotabilityContext.unknown(null));

        ArticleView view = articles.find(articleId).orElseThrow();
        assertThat(view.headline()).isEqualTo("완봉으로 끝난 승부");
        assertThat(view.body()).isEqualTo("첫 세트부터 흐름을 놓지 않았다.");
        assertThat(view.comments()).hasSize(2);
        assertThat(view.model()).isEqualTo(properties.model());
        // 프롬프트와 화면이 같은 문자열이다 — 사실 블록에 번호가 아니라 이름이 있어야 한다
        assertThat(view.briefText()).contains("Seorabal Gaming", "OZ Gaming");
        assertThat(view.blueTeamName()).isEqualTo("Seorabal Gaming");
        assertThat(client.seen).hasSize(2);
    }

    @Test
    @DisplayName("모델이 형식을 어기면 스코어라인으로 제목을 짓는다 — 지어내지 않는다")
    void fallsBackToScorelineHeadline() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");

        // 줄바꿈이 없다 — 제목을 뗄 수 없는 답
        ScriptedClient client = new ScriptedClient(
                "첫 세트부터 흐름을 놓지 않은 경기였고 두 세트 모두 큰 차이로 끝났다.",
                "1. 댓글");

        long articleId = writerWith(client).write(slotId, brief(33, 34), NotabilityContext.unknown(null));

        ArticleView view = articles.find(articleId).orElseThrow();
        assertThat(view.headline()).isEqualTo("Seorabal Gaming 2 - 0 OZ Gaming");
        assertThat(view.body()).startsWith("첫 세트부터");
    }

    @Test
    @DisplayName("이 매치에 없는 챔피언을 쓰면 CONTRADICTED 로 저장된다")
    void factCheckReachesTheStoredStatus() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");

        StoryReference reference = references.load(slotId);
        String absent = reference.championCodes().stream()
                .filter(code -> !code.equals("Jiangshi") && !code.equals("Wolfman"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("챔피언 시드가 비어 있다"));

        ScriptedClient client = new ScriptedClient(
                "제목\n\n" + absent + " 가 경기를 지배했다.",
                "1. 댓글");

        long articleId = writerWith(client).write(slotId, brief(33, 34), NotabilityContext.unknown(null));

        ArticleView view = articles.find(articleId).orElseThrow();
        assertThat(view.factStatus()).isEqualTo(ArticleDraft.FactStatus.CONTRADICTED);
        assertThat(view.findings()).anyMatch(f -> f.evidence().equals(absent));
        assertThat(view.factStatusMatchesFindings()).isTrue();
    }

    @Test
    @DisplayName("같은 매치를 다시 쓰면 갱신이다")
    void rewritingUpdates() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");

        long first = writerWith(new ScriptedClient("제목 하나\n\n본문 하나.", "1. 댓글"))
                .write(slotId, brief(33, 34), NotabilityContext.unknown(null));
        long second = writerWith(new ScriptedClient("제목 둘\n\n본문 둘.", "1. 새 댓글"))
                .write(slotId, brief(33, 34), NotabilityContext.unknown(null));

        assertThat(second).isEqualTo(first);
        assertThat(articles.find(first).orElseThrow().headline()).isEqualTo("제목 둘");
        assertThat(jdbc.queryForObject(
                "SELECT count(*)::int FROM article WHERE slot_id = ?", Integer.class, slotId))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("댓글 호출이 실패하면 기사도 저장되지 않는다 — 반쪽 기사를 남기지 않는다")
    void failedCommentCallLeavesNothing() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        team(slotId, 34, "OZ Gaming");

        assertThatThrownBy(() -> writerWith(new FailingClient(2))
                .write(slotId, brief(33, 34), NotabilityContext.unknown(null)))
                .isInstanceOf(StoryClient.StoryFailedException.class);

        assertThat(jdbc.queryForObject(
                "SELECT count(*)::int FROM article WHERE slot_id = ?", Integer.class, slotId))
                .isEqualTo(0);
    }

    @Test
    @DisplayName("적재에 없는 팀이면 저장하지 않고 던진다")
    void unknownTeamThrows() {
        int slotId = newSlot();
        team(slotId, 33, "Seorabal Gaming");
        // 34번 팀을 넣지 않았다

        assertThatThrownBy(() -> writerWith(new ScriptedClient("제목\n\n본문.", "1. 댓글"))
                .write(slotId, brief(33, 34), NotabilityContext.unknown(null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("34");
    }
}
