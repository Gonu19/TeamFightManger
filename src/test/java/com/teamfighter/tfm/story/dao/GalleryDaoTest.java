package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.ArticleDraft;
import com.teamfighter.tfm.story.ArticleDraft.CommentLine;
import com.teamfighter.tfm.story.gallery.GalleryPost;
import com.teamfighter.tfm.story.gallery.GalleryPostKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 갤러리 저장이 실제 스키마와 맞는지 확인한다.
 *
 * <p><b>여기가 V11 이 계산으로 넘긴 계약을 지키는 자리다.</b> {@code is_concept} 는
 * 생성 컬럼이라 코드가 값을 안 넣는다 — DB 가 {@code declared_concept OR likes >= 30} 을
 * 계산한다. 그 규칙이 정말 그렇게 도는지는 여기서만 확인된다.
 *
 * <p>DB 가 필요하다. 각 테스트는 트랜잭션 롤백으로 격리된다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GalleryDaoTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private GalleryDao dao;

    @Autowired
    private ArticleDao articles;

    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "gal_" + System.nanoTime());
    }

    private int team(int slotId, int gameTeamId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO team (slot_id, game_team_id, name)
                VALUES (?, ?, ?) RETURNING team_id
                """, Integer.class, slotId, gameTeamId, name);
    }

    /** 갤러리가 매달릴 기사 하나. 본문은 짧게 둔다 — 여기서 보는 것은 갤러리다. */
    private long article(int slotId, int blueTeamId, int redTeamId, int day) {
        return articles.save(new ArticleDraft(
                slotId, 7, 3, "competition.name.spring", 2025, day, 1,
                blueTeamId, redTeamId, 2, 0, 21, 13,
                0.5, List.of("접전"), "제목", "본문", "사실 블록", "test-model",
                List.of(), List.of()));
    }

    private static GalleryPost post(GalleryPostKind kind, String title,
                                    Integer likes, List<CommentLine> comments) {
        return new GalleryPost(kind, title, "ㅇㅇ(112.47)", "본문",
                140, likes, false, null, comments);
    }

    @Test
    @DisplayName("페이지 하나를 통째로 저장하고 그대로 읽는다")
    void savesAndReadsBack() {
        int slotId = newSlot();
        long articleId = article(slotId, team(slotId, 33, "Seorabal"), team(slotId, 36, "Bahamut"), 4);

        long batchId = dao.save(articleId, "test-model", 4, List.of(
                post(GalleryPostKind.LIVE, "실황", 5, List.of(
                        new CommentLine("ㅇㅇ(220.76)", "ㄹㅇ", null),
                        new CommentLine("ㅇㅇ(112.47)", "@ㅇㅇ 그니까", 1))),
                post(GalleryPostKind.PLAYER, "저격", 2, List.of())));

        GalleryView view = dao.findLatest(articleId).orElseThrow();

        assertThat(view.batchId()).isEqualTo(batchId);
        assertThat(view.chunks()).isEqualTo(4);
        assertThat(view.posts()).extracting(GalleryView.Post::title)
                .containsExactly("실황", "저격");

        GalleryView.Post first = view.posts().get(0);
        assertThat(first.kind()).isEqualTo(GalleryPostKind.LIVE);
        assertThat(first.commentCount()).isEqualTo(2);
        assertThat(first.roots()).hasSize(1);
        assertThat(first.repliesTo(0)).hasSize(1);
    }

    @Test
    @DisplayName("추천 30 이상이면 DB 가 개념글로 올린다 — 코드가 값을 안 넣는데도")
    void databasePromotesConceptByLikes() {
        int slotId = newSlot();
        long articleId = article(slotId, team(slotId, 33, "Seorabal"), team(slotId, 36, "Bahamut"), 5);

        dao.save(articleId, "test-model", 4, List.of(
                post(GalleryPostKind.FLAME, "떡밥", 30, List.of()),
                post(GalleryPostKind.DAILY, "잡담", 29, List.of())));

        GalleryView view = dao.findLatest(articleId).orElseThrow();

        assertThat(view.posts().get(0).isConcept()).isTrue();
        assertThat(view.posts().get(1).isConcept()).isFalse();
    }

    @Test
    @DisplayName("추천수를 모르면 null 로 남는다 — 0 이 아니다")
    void unknownLikesStayNull() {
        int slotId = newSlot();
        long articleId = article(slotId, team(slotId, 33, "Seorabal"), team(slotId, 36, "Bahamut"), 6);

        dao.save(articleId, "test-model", 4, List.of(
                new GalleryPost(GalleryPostKind.DAILY, "잡담", null, "본문",
                        null, null, false, null, List.of())));

        GalleryView.Post saved = dao.findLatest(articleId).orElseThrow().posts().get(0);

        assertThat(saved.views()).isNull();
        assertThat(saved.likes()).isNull();
        assertThat(saved.author()).isNull();
        assertThat(saved.isConcept()).isFalse();
    }

    @Test
    @DisplayName("다시 만들면 덮지 않고 쌓인다 — 최신 페이지가 조회된다")
    void batchesStackInsteadOfOverwriting() {
        int slotId = newSlot();
        long articleId = article(slotId, team(slotId, 33, "Seorabal"), team(slotId, 36, "Bahamut"), 7);

        dao.save(articleId, "test-model", 4, List.of(post(GalleryPostKind.LIVE, "첫 페이지", 1, List.of())));
        dao.save(articleId, "test-model", 4, List.of(post(GalleryPostKind.LIVE, "둘째 페이지", 1, List.of())));

        // 기사는 업서트고 갤러리는 쌓인다 — 이 층에는 정답이 없기 때문이다
        assertThat(dao.countBatches(articleId)).isEqualTo(2);
        assertThat(dao.findLatest(articleId).orElseThrow().posts())
                .extracting(GalleryView.Post::title)
                .containsExactly("둘째 페이지");
    }

    @Test
    @DisplayName("개념글이 목록 위로 올라간다")
    void conceptPostsSortToTheTop() {
        int slotId = newSlot();
        long articleId = article(slotId, team(slotId, 33, "Seorabal"), team(slotId, 36, "Bahamut"), 8);

        dao.save(articleId, "test-model", 4, List.of(
                post(GalleryPostKind.DAILY, "평범한 글", 3, List.of()),
                post(GalleryPostKind.FLAME, "갤 뒤집은 글", 88, List.of()),
                post(GalleryPostKind.LIVE, "그럭저럭", 40, List.of())));

        assertThat(dao.findLatest(articleId).orElseThrow().ordered())
                .extracting(GalleryView.Post::title)
                .containsExactly("갤 뒤집은 글", "그럭저럭", "평범한 글");
    }

    @Test
    @DisplayName("갤러리 없는 최근 기사를 고른다 — 붙은 기사는 건너뛴다")
    void nextAnchorSkipsArticlesThatAlreadyHaveOne() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");
        long older = article(slotId, blue, red, 1);
        long newer = article(slotId, blue, red, 9);

        assertThat(dao.nextAnchor(slotId).orElseThrow().articleId()).isEqualTo(newer);

        dao.save(newer, "test-model", 4, List.of(post(GalleryPostKind.LIVE, "글", 1, List.of())));

        // 최신에 붙었으니 다음은 그 앞 기사다
        assertThat(dao.nextAnchor(slotId).orElseThrow().articleId()).isEqualTo(older);

        dao.save(older, "test-model", 4, List.of(post(GalleryPostKind.LIVE, "글", 1, List.of())));
        assertThat(dao.nextAnchor(slotId)).isEmpty();
    }

    @Test
    @DisplayName("글이 하나도 없는 페이지는 저장하지 않는다")
    void refusesEmptyPage() {
        int slotId = newSlot();
        long articleId = article(slotId, team(slotId, 33, "Seorabal"), team(slotId, 36, "Bahamut"), 10);

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> dao.save(articleId, "test-model", 4, List.of()))
                .hasMessageContaining("글이 하나도 없는");
    }
}
