package com.teamfighter.tfm.story.gallery;

import com.teamfighter.tfm.story.gallery.GalleryIssue.GalleryIssueCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델 응답 → 이슈. 게시글 파서와 규칙이 같고, 다른 점은 <b>분류를 읽는 방식</b>이다.
 *
 * <p>분류가 왜 까다로운가: 우리는 {@code "TRANSFER"} 를 요구하지만 모델은 한글로
 * {@code "이적설"} 을 주거나 {@code "이적"} 처럼 조금 잘라 준다. 그걸 다 놓치면
 * <b>사이드바 배지가 전부 '리그'</b>가 되고, 그러면 배지 색이 있으나 마나 해진다.
 */
class GalleryIssuesTest {

    @Test
    @DisplayName("정상 응답을 그대로 읽는다")
    void parsesCleanResponse() {
        String raw = """
                [{"category":"TRANSFER","headline":"[단독] 이적설","content":"본문","date":"08.14"},
                 {"category":"SCANDAL","headline":"[루머] 불화설","content":"본문","date":"08.15"}]""";

        List<GalleryIssue> issues = GalleryIssues.parse(raw);

        assertThat(issues).hasSize(2);
        assertThat(issues.get(0).category()).isEqualTo(GalleryIssueCategory.TRANSFER);
        assertThat(issues.get(0).headline()).isEqualTo("[단독] 이적설");
        assertThat(issues.get(0).issueDate()).isEqualTo("08.14");
    }

    @Test
    @DisplayName("분류를 한글로 줘도 읽는다")
    void readsKoreanCategoryLabels() {
        String raw = """
                [{"category":"이적설","headline":"제목","content":"본문"},
                 {"category":"전력분석","headline":"제목","content":"본문"},
                 {"category":"스캔들","headline":"제목","content":"본문"}]""";

        assertThat(GalleryIssues.parse(raw))
                .extracting(GalleryIssue::category)
                .containsExactly(GalleryIssueCategory.TRANSFER,
                        GalleryIssueCategory.ANALYSIS,
                        GalleryIssueCategory.SCANDAL);
    }

    @Test
    @DisplayName("모르는 분류는 '리그' 로 채운다 — 이슈를 버리지 않는다")
    void unknownCategoryFallsBack() {
        String raw = """
                [{"category":"연예","headline":"제목","content":"본문"},
                 {"headline":"분류 없는 이슈","content":"본문"}]""";

        assertThat(GalleryIssues.parse(raw))
                .hasSize(2)
                .allSatisfy(issue ->
                        assertThat(issue.category()).isEqualTo(GalleryIssueCategory.LEAGUE));
    }

    @Test
    @DisplayName("여섯 개까지만 가져온다 — 사이드바가 스크롤되지 않게")
    void capsAtSix() {
        StringBuilder raw = new StringBuilder("[");
        for (int i = 0; i < 10; i++) {
            raw.append(i > 0 ? "," : "")
                    .append("{\"category\":\"LEAGUE\",\"headline\":\"제목")
                    .append(i).append("\",\"content\":\"본문\"}");
        }
        raw.append(']');

        assertThat(GalleryIssues.parse(raw.toString())).hasSize(GalleryIssues.MAX_ISSUES);
    }

    @Test
    @DisplayName("헤드라인이나 본문이 비면 그 이슈만 버린다")
    void dropsIncompleteIssues() {
        String raw = """
                [{"category":"LEAGUE","headline":"","content":"본문"},
                 {"category":"LEAGUE","headline":"제목","content":""},
                 {"category":"LEAGUE","headline":"멀쩡","content":"본문"}]""";

        assertThat(GalleryIssues.parse(raw))
                .extracting(GalleryIssue::headline)
                .containsExactly("멀쩡");
    }

    @Test
    @DisplayName("날짜가 없으면 null 이다 — 오늘 날짜를 붙이지 않는다")
    void missingDateStaysNull() {
        // 게임 안의 날짜라 우리 달력의 연도가 없다. 없는 값을 채우면 그게 사실이 된다.
        String raw = """
                [{"category":"LEAGUE","headline":"제목","content":"본문"}]""";

        assertThat(GalleryIssues.parse(raw).get(0).issueDate()).isNull();
    }

    @Test
    @DisplayName("JSON 이 아니면 빈 목록이다 — 게시글 생성은 계속된다")
    void nonJsonGivesEmptyList() {
        assertThat(GalleryIssues.parse("취재에 실패했습니다.")).isEmpty();
        assertThat(GalleryIssues.parse(null)).isEmpty();
    }
}
