package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.ArticleDraft;
import com.teamfighter.tfm.story.gallery.GalleryComment;
import com.teamfighter.tfm.story.gallery.GalleryIssue;
import com.teamfighter.tfm.story.gallery.GalleryIssue.GalleryIssueCategory;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 갤러리 저장이 실제 스키마와 맞는지 확인한다.
 *
 * <p><b>여기가 V11 이 계산으로 넘긴 계약을 지키는 자리다.</b> {@code is_concept} 는
 * 생성 컬럼이라 코드가 값을 안 넣는다 — DB 가 {@code declared_concept OR likes >= 30} 을
 * 계산한다. 그 규칙이 정말 그렇게 도는지는 여기서만 확인된다.
 *
 * <p>V12 가 갤러리를 기사에서 뗀 것도 여기서 본다 — <b>기사 없이 저장되는가</b>.
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

    /** 기사 없는 갤러리 머리말. D73 이후로 이것이 기본 모양이다. */
    private static GalleryBatch batch(int slotId, int blue, int red, int day) {
        return new GalleryBatch(slotId, null, 2025, day, blue, red, 2, 0, "test-model", 4);
    }

    private static GalleryPost post(GalleryPostKind kind, String title,
                                    Integer likes, List<GalleryComment> comments) {
        return new GalleryPost(kind, title, "ㅇㅇ(112.47)", "본문",
                140, likes, false, null, "16:40", comments);
    }

    @Test
    @DisplayName("기사 없이 저장된다 — 갤러리는 매치에 직접 붙는다")
    void savesWithoutAnArticle() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        long batchId = dao.save(batch(slotId, blue, red, 4), List.of(), List.of(
                post(GalleryPostKind.LIVE, "실황", 5, List.of(
                        new GalleryComment("ㅇㅇ(220.76)", "ㄹㅇ", null, "16:41"),
                        new GalleryComment("ㅇㅇ(112.47)", "@ㅇㅇ 그니까", 1, "16:42"))),
                post(GalleryPostKind.PLAYER, "저격", 2, List.of())));

        GalleryView view = dao.find(batchId).orElseThrow();

        assertThat(view.articleId()).isNull();
        assertThat(view.season()).isEqualTo(2025);
        assertThat(view.blueTeamName()).isEqualTo("Seorabal");
        assertThat(view.chunks()).isEqualTo(4);
        assertThat(view.posts()).extracting(GalleryView.Post::title)
                .containsExactly("실황", "저격");

        GalleryView.Post first = view.posts().get(0);
        assertThat(first.kind()).isEqualTo(GalleryPostKind.LIVE);
        assertThat(first.postedAt()).isEqualTo("16:40");
        assertThat(first.commentCount()).isEqualTo(2);
        assertThat(first.roots()).hasSize(1);
        assertThat(first.repliesTo(0)).hasSize(1);
        assertThat(first.repliesTo(0).get(0).postedAt()).isEqualTo("16:42");
    }

    @Test
    @DisplayName("기사가 있으면 링크로 잇는다")
    void linksToTheArticleWhenThereIsOne() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        long articleId = articles.save(new ArticleDraft(
                slotId, 7, 3, "competition.name.spring", 2025, 4, 1,
                blue, red, 2, 0, 21, 13,
                0.5, List.of("접전"), "제목", "본문", "사실 블록", "test-model",
                List.of(), List.of()));

        // 갤러리 생성기가 하는 일과 같다 — 매치 신원으로 기사를 찾아 번호만 채운다
        assertThat(articles.findIdByKey(slotId, new ArticleKey(2025, 4, blue, red)))
                .contains(articleId);

        long batchId = dao.save(
                new GalleryBatch(slotId, articleId, 2025, 4, blue, red, 2, 0, "test-model", 4),
                List.of(), List.of(post(GalleryPostKind.LIVE, "글", 1, List.of())));

        assertThat(dao.find(batchId).orElseThrow().articleId()).isEqualTo(articleId);
    }

    @Test
    @DisplayName("이슈를 같이 저장하고 순서대로 읽는다")
    void savesIssues() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        long batchId = dao.save(batch(slotId, blue, red, 5), List.of(
                new GalleryIssue(GalleryIssueCategory.TRANSFER, "[단독] 이적설", "본문", "08.14"),
                new GalleryIssue(GalleryIssueCategory.SCANDAL, "[루머] 불화설", "본문", null)),
                List.of(post(GalleryPostKind.SCRAP, "스크랩", 1, List.of())));

        assertThat(dao.find(batchId).orElseThrow().issues())
                .extracting(GalleryIssue::headline)
                .containsExactly("[단독] 이적설", "[루머] 불화설");

        // 다음 이슈가 겹치지 않게 프롬프트로 되돌려 줄 목록이다
        assertThat(dao.recentHeadlines(slotId, 10))
                .containsExactly("[단독] 이적설", "[루머] 불화설");
    }

    @Test
    @DisplayName("추천 30 이상이면 DB 가 개념글로 올린다 — 코드가 값을 안 넣는데도")
    void databasePromotesConceptByLikes() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        long batchId = dao.save(batch(slotId, blue, red, 6), List.of(), List.of(
                post(GalleryPostKind.FLAME, "떡밥", 30, List.of()),
                post(GalleryPostKind.DAILY, "잡담", 29, List.of())));

        GalleryView view = dao.find(batchId).orElseThrow();

        assertThat(view.posts().get(0).isConcept()).isTrue();
        assertThat(view.posts().get(1).isConcept()).isFalse();
    }

    @Test
    @DisplayName("추천수를 모르면 null 로 남는다 — 0 이 아니다")
    void unknownLikesStayNull() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        long batchId = dao.save(batch(slotId, blue, red, 7), List.of(), List.of(
                new GalleryPost(GalleryPostKind.DAILY, "잡담", null, "본문",
                        null, null, false, null, null, List.of())));

        GalleryView.Post saved = dao.find(batchId).orElseThrow().posts().get(0);

        assertThat(saved.views()).isNull();
        assertThat(saved.likes()).isNull();
        assertThat(saved.author()).isNull();
        assertThat(saved.postedAt()).isNull();
        assertThat(saved.isConcept()).isFalse();
    }

    @Test
    @DisplayName("다시 만들면 덮지 않고 쌓인다 — 페이지가 는다")
    void batchesStackInsteadOfOverwriting() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        dao.save(batch(slotId, blue, red, 8), List.of(),
                List.of(post(GalleryPostKind.LIVE, "첫 페이지", 1, List.of())));
        dao.save(batch(slotId, blue, red, 8), List.of(),
                List.of(post(GalleryPostKind.LIVE, "둘째 페이지", 1, List.of())));

        // 기사는 업서트고 갤러리는 쌓인다 — 이 층에는 정답이 없기 때문이다
        assertThat(dao.pages(slotId)).hasSize(2);
    }

    @Test
    @DisplayName("갤러리를 뽑은 매치의 신원을 돌려준다 — 생성기가 그걸 건너뛴다")
    void writtenKeysTellWhichMatchesAreDone() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        assertThat(dao.writtenKeys(slotId)).isEmpty();

        dao.save(batch(slotId, blue, red, 9), List.of(),
                List.of(post(GalleryPostKind.LIVE, "글", 1, List.of())));

        assertThat(dao.writtenKeys(slotId))
                .containsExactly(new ArticleKey(2025, 9, blue, red));
        assertThat(dao.slotsWithGalleries()).contains(slotId);
    }

    @Test
    @DisplayName("페이지 목록은 경기 시점 순이다 — 최근 경기가 첫 페이지")
    void pagesAreOrderedByMatchTime() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        // 옛 경기를 나중에 뽑아도 최근 경기가 위여야 한다. generated_at 으로 정렬하면
        // 이 순서가 뒤집힌다 — 그것을 막는 것이 이 테스트의 전부다.
        dao.save(batch(slotId, blue, red, 12), List.of(),
                List.of(post(GalleryPostKind.LIVE, "최근 경기", 1, List.of())));
        dao.save(batch(slotId, blue, red, 3), List.of(),
                List.of(post(GalleryPostKind.LIVE, "옛 경기", 1, List.of())));

        assertThat(dao.pages(slotId)).extracting(GalleryView::day).containsExactly(12, 3);
    }

    @Test
    @DisplayName("글이 하나도 없는 페이지는 저장하지 않는다")
    void refusesEmptyPage() {
        int slotId = newSlot();
        int blue = team(slotId, 33, "Seorabal");
        int red = team(slotId, 36, "Bahamut");

        assertThatThrownBy(() -> dao.save(batch(slotId, blue, red, 10), List.of(), List.of()))
                .hasMessageContaining("글이 하나도 없는");
    }
}
