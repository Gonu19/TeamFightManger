package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.ArticleDraft;
import com.teamfighter.tfm.story.ArticleDraft.FactStatus;
import com.teamfighter.tfm.story.ArticleDraft.Finding;
import com.teamfighter.tfm.story.ArticleDraft.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * 기사 저장이 실제 스키마와 맞는지 확인한다.
 *
 * <p>DB 가 필요하다. 각 테스트는 트랜잭션 롤백으로 격리되고 DB 에 남는 것이 없다.
 *
 * <p><b>여기가 V8 이 트리거를 두지 않기로 하며 미뤄둔 계약을 지키는 자리다.</b>
 * "모순이 하나라도 있으면 {@code fact_status = CONTRADICTED}" 는 DB 가 강제하지 않는다 —
 * 그 대신 {@link ArticleDraft#factStatus()} 가 계산하고, 그 값이 <b>정말 그대로 저장되는지</b>를
 * 여기서 본다. 둘 사이에 DAO 코드가 끼어들면 화면은 여전히 멀쩡해 보이는데 경고만 사라진다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ArticleDaoTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ArticleDao dao;

    private int newSlot() {
        return jdbc.queryForObject("""
                INSERT INTO save_slot (slot_key) VALUES (?) RETURNING slot_id
                """, Integer.class, "story_" + System.nanoTime());
    }

    private int team(int slotId, int gameTeamId, String name) {
        return jdbc.queryForObject("""
                INSERT INTO team (slot_id, game_team_id, name)
                VALUES (?, ?, ?) RETURNING team_id
                """, Integer.class, slotId, gameTeamId, name);
    }

    /** 기본 초안. 바꿔야 하는 값만 {@code with*} 없이 직접 넘긴다. */
    /** 닉네임 없는 원댓글로 바꾼다. 닉네임·대댓글은 아래 전용 테스트가 본다 */
    private static List<ArticleDraft.CommentLine> plain(List<String> bodies) {
        return bodies.stream().map(ArticleDraft.CommentLine::of).toList();
    }

    private ArticleDraft draft(int slotId, int blueTeamId, int redTeamId,
                               int season, int day, List<String> comments,
                               List<Finding> findings) {
        return new ArticleDraft(
                slotId, 7, 3, "competition.name.spring", season, day, 2,
                blueTeamId, redTeamId, 2, 1, 41, 38,
                0.62, List.of("순위 다툼", "라이벌"),
                "연장 끝에 갈린 2-1",
                "첫 세트를 내준 쪽이 남은 둘을 가져갔다.",
                "블루 2 - 1 레드 · 킬 41 - 38",
                "openai/gpt-oss-120b",
                plain(comments), findings);
    }

    @Test
    @DisplayName("저장한 기사를 그대로 돌려준다 — 댓글과 지적은 넣은 순서를 지킨다")
    void savesAndReadsBack() {
        int slotId = newSlot();
        int blue = team(slotId, 11, "Seorabal Gaming");
        int red = team(slotId, 12, "OZ Gaming");

        long articleId = dao.save(draft(slotId, blue, red, 3, 40,
                List.of("첫 댓글", "둘째 댓글", "셋째 댓글"),
                List.of(new Finding(Severity.UNVERIFIED, "brief 가 모르는 시간", "20분 만에"))));

        ArticleView view = dao.find(articleId).orElseThrow();

        assertThat(view.articleId()).isEqualTo(articleId);
        assertThat(view.slotId()).isEqualTo(slotId);
        assertThat(view.season()).isEqualTo(3);
        assertThat(view.day()).isEqualTo(40);
        assertThat(view.round()).isEqualTo(2);
        assertThat(view.blueTeamName()).isEqualTo("Seorabal Gaming");
        assertThat(view.redTeamName()).isEqualTo("OZ Gaming");
        assertThat(view.blueScore()).isEqualTo(2);
        assertThat(view.blueKill()).isEqualTo(41);
        assertThat(view.notability()).isEqualTo(0.62);
        assertThat(view.notabilityReasons()).containsExactly("순위 다툼", "라이벌");
        assertThat(view.headline()).isEqualTo("연장 끝에 갈린 2-1");
        assertThat(view.briefText()).isEqualTo("블루 2 - 1 레드 · 킬 41 - 38");
        assertThat(view.model()).isEqualTo("openai/gpt-oss-120b");
        assertThat(view.generatedAt()).isNotNull();
        assertThat(view.comments()).extracting(ArticleDraft.CommentLine::body)
                .containsExactly("첫 댓글", "둘째 댓글", "셋째 댓글");
        assertThat(view.findings()).containsExactly(
                new Finding(Severity.UNVERIFIED, "brief 가 모르는 시간", "20분 만에"));
    }

    @Test
    @DisplayName("모순이 있으면 CONTRADICTED 로 저장된다 — DB 에 든 값이 그렇다")
    void storesContradictedWhenAnyContradiction() {
        int slotId = newSlot();
        int blue = team(slotId, 21, "Anarchy");
        int red = team(slotId, 22, "Team Dynamics");

        long articleId = dao.save(draft(slotId, blue, red, 4, 12, List.of("편파적인 댓글"),
                List.of(new Finding(Severity.UNVERIFIED, "모르는 것", "20분"),
                        new Finding(Severity.CONTRADICTION, "스코어가 다르다", "3-0 으로"))));

        String stored = jdbc.queryForObject(
                "SELECT fact_status::text FROM article WHERE article_id = ?",
                String.class, articleId);

        assertThat(stored).isEqualTo("CONTRADICTED");
        assertThat(dao.find(articleId).orElseThrow().factStatus())
                .isEqualTo(FactStatus.CONTRADICTED);
    }

    @Test
    @DisplayName("지적이 없거나 미확인뿐이면 CLEAN 이다")
    void storesCleanWithoutContradictions() {
        int slotId = newSlot();
        int blue = team(slotId, 31, "Runaway");
        int red = team(slotId, 32, "ESC Ever");

        long clean = dao.save(draft(slotId, blue, red, 1, 5, List.of(), List.of()));
        assertThat(dao.find(clean).orElseThrow().factStatus()).isEqualTo(FactStatus.CLEAN);

        long unverifiedOnly = dao.save(draft(slotId, blue, red, 1, 6, List.of(),
                List.of(new Finding(Severity.UNVERIFIED, "모르는 것", "20분"))));
        assertThat(dao.find(unverifiedOnly).orElseThrow().factStatus())
                .isEqualTo(FactStatus.CLEAN);
    }

    @Test
    @DisplayName("재생성이 갱신이 된다 — 같은 매치를 다시 저장해도 기사는 한 편이다")
    void regenerationUpdatesInPlace() {
        int slotId = newSlot();
        int blue = team(slotId, 41, "Sandbox Gaming");
        int red = team(slotId, 42, "BBQ Olivers");

        long first = dao.save(draft(slotId, blue, red, 2, 20,
                List.of("댓글 1", "댓글 2", "댓글 3"),
                List.of(new Finding(Severity.CONTRADICTION, "스코어", "3-0"))));

        ArticleDraft rewritten = new ArticleDraft(
                slotId, 9, 3, "competition.name.summer", 2, 20, 3,
                blue, red, 3, 0, 55, 21,
                0.91, List.of("완봉"),
                "다시 쓴 제목", "다시 쓴 본문", "블루 3 - 0 레드 · 킬 55 - 21",
                "openai/gpt-oss-120b",
                plain(List.of("새 댓글 1", "새 댓글 2")),
                List.of());

        long second = dao.save(rewritten);

        assertThat(second).isEqualTo(first);

        Integer rows = jdbc.queryForObject(
                "SELECT count(*)::int FROM article WHERE slot_id = ?", Integer.class, slotId);
        assertThat(rows).isEqualTo(1);

        ArticleView view = dao.find(first).orElseThrow();
        assertThat(view.headline()).isEqualTo("다시 쓴 제목");
        assertThat(view.blueScore()).isEqualTo(3);
        assertThat(view.notabilityReasons()).containsExactly("완봉");
        // 댓글이 3개에서 2개로 줄었다. 옛 3번째가 남으면 화면에는 그냥 댓글로 보인다
        assertThat(view.comments()).extracting(ArticleDraft.CommentLine::body)
                .containsExactly("새 댓글 1", "새 댓글 2");
        // 모순이 사라졌으므로 지적도 상태도 함께 내려가야 한다
        assertThat(view.findings()).isEmpty();
        assertThat(view.factStatus()).isEqualTo(FactStatus.CLEAN);
        assertThat(view.factStatusMatchesFindings()).isTrue();
    }

    @Test
    @DisplayName("진영이 뒤바뀐 같은 매치는 다른 기사다 — 유일 키가 진영을 포함한다")
    void swappedSidesAreDifferentRows() {
        int slotId = newSlot();
        int blue = team(slotId, 51, "Element Mystic");
        int red = team(slotId, 52, "Spear Gaming");

        long normal = dao.save(draft(slotId, blue, red, 5, 3, List.of(), List.of()));
        long swapped = dao.save(draft(slotId, red, blue, 5, 3, List.of(), List.of()));

        assertThat(swapped).isNotEqualTo(normal);
    }

    @Test
    @DisplayName("recent 는 경기 시점 내림차순으로, 그 슬롯 것만 준다")
    void recentIsOrderedAndScopedToSlot() {
        int slotId = newSlot();
        int other = newSlot();
        int blue = team(slotId, 61, "Nongshim Redforce");
        int red = team(slotId, 62, "hyFresh Blade");
        int otherBlue = team(other, 61, "다른 슬롯 팀");
        int otherRed = team(other, 62, "다른 슬롯 상대");

        dao.save(draft(slotId, blue, red, 1, 10, List.of(), List.of()));
        dao.save(draft(slotId, blue, red, 2, 5, List.of(), List.of()));
        dao.save(draft(slotId, blue, red, 2, 40, List.of(), List.of()));
        dao.save(draft(other, otherBlue, otherRed, 9, 99, List.of(), List.of()));

        List<ArticleCard> cards = dao.recent(slotId, 10);

        assertThat(cards).extracting(ArticleCard::season, ArticleCard::day)
                .containsExactly(
                        tuple(2, 40), tuple(2, 5), tuple(1, 10));
        assertThat(cards).extracting(ArticleCard::slotId).containsOnly(slotId);
        assertThat(cards.get(0).blueTeamName()).isEqualTo("Nongshim Redforce");

        assertThat(dao.recent(slotId, 2)).hasSize(2);
    }

    @Test
    @DisplayName("쉼표와 중괄호가 든 이유도 한 원소로 남는다")
    void arrayElementsSurviveSpecialCharacters() {
        int slotId = newSlot();
        int blue = team(slotId, 71, "OZ Gaming");
        int red = team(slotId, 72, "Anarchy");

        ArticleDraft withAwkwardReasons = new ArticleDraft(
                slotId, null, null, null, 6, 1, null,
                blue, red, 2, 0, 30, 12,
                0.4, List.of("1위, 2위 맞대결", "{순위}", "따옴표 \"인용\" 포함"),
                "제목", "본문", "블루 2 - 0 레드", "openai/gpt-oss-120b",
                List.of(), List.of());

        ArticleView view = dao.find(dao.save(withAwkwardReasons)).orElseThrow();

        assertThat(view.notabilityReasons())
                .containsExactly("1위, 2위 맞대결", "{순위}", "따옴표 \"인용\" 포함");
        // 없는 값은 없는 채로 남는다 — 0 으로 바뀌면 라운드 0 과 구분되지 않는다
        assertThat(view.scheduleId()).isNull();
        assertThat(view.competitionId()).isNull();
        assertThat(view.competitionKey()).isNull();
        assertThat(view.round()).isNull();
    }

    @Test
    @DisplayName("없는 기사를 찾으면 비어 있다")
    void findMissingReturnsEmpty() {
        assertThat(dao.find(-1L)).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("limit 이 0 이하면 던진다 — 조용히 빈 목록을 주면 화면이 비어도 이유를 모른다")
    void recentRejectsNonPositiveLimit() {
        int slotId = newSlot();
        // 타입은 IllegalArgumentException 이 아니다 — @Repository 의 예외 변환이
        // InvalidDataAccessApiUsageException 으로 바꾼다. 원인은 그대로 남는다
        assertThatThrownBy(() -> dao.recent(slotId, 0))
                .hasMessageContaining("limit 은 1 이상이어야 한다")
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }
}
