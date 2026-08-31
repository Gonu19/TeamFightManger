package com.teamfighter.tfm.story;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ArticleDraft} 의 계약을 고정한다. <b>DB 도 LLM 도 쓰지 않는다.</b>
 *
 * <p>V8 주석이 "모순이 있으면 CONTRADICTED" 를 저장하는 쪽의 책임으로 남겼다.
 * 그 책임을 DAO 코드에 두면 DB 없이는 검증할 수 없다. 여기서 <b>계산</b>하므로
 * 그 값을 틀리게 넣을 방법 자체가 없고, 그 사실을 이 테스트가 지킨다.
 */
class ArticleDraftTest {

    private static ArticleDraft draft(List<ArticleDraft.Finding> findings) {
        return new ArticleDraft(1, 0, 1, "league.pro", 2026, 7, 3,
                10, 20, 2, 1, 40, 33, 0.75, List.of("내 팀 경기"),
                "제목", "본문이다.", "[2026 시즌 7일차] ...", "test-model",
                List.of(ArticleDraft.CommentLine.of("댓글 하나")), findings);
    }

    private static ArticleDraft.Finding contradiction() {
        return new ArticleDraft.Finding(
                ArticleDraft.Severity.CONTRADICTION, "이 매치에 없는 스코어", "3 - 0");
    }

    private static ArticleDraft.Finding unverified() {
        return new ArticleDraft.Finding(
                ArticleDraft.Severity.UNVERIFIED, "brief 에 없는 숫자", "40");
    }

    @Test
    @DisplayName("모순이 없으면 CLEAN")
    void noContradiction_isClean() {
        assertThat(draft(List.of()).factStatus()).isEqualTo(ArticleDraft.FactStatus.CLEAN);
        assertThat(draft(List.of(unverified())).isClean()).isTrue();
    }

    @Test
    @DisplayName("모순이 하나라도 있으면 CONTRADICTED — 미확인이 아무리 많아도 관계없다")
    void anyContradiction_marksContradicted() {
        assertThat(draft(List.of(unverified(), contradiction(), unverified())).factStatus())
                .isEqualTo(ArticleDraft.FactStatus.CONTRADICTED);
        assertThat(draft(List.of(contradiction())).isClean()).isFalse();
    }

    @Test
    @DisplayName("상태를 밖에서 정할 수 없다 — 생성자에 그 인자가 없다")
    void factStatus_cannotBeSuppliedFromOutside() {
        // 이 테스트는 컴파일이 곧 검증이다. ArticleDraft 에 factStatus 인자가 생기면
        // 여기가 아니라 아래 단언이 무의미해지므로, 필드 이름으로 확인한다.
        assertThat(ArticleDraft.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("factStatus");
    }

    @Test
    @DisplayName("대조 결과를 그대로 옮긴다 — 모순과 미확인이 둘 다 저장된다")
    void of_carriesBothSeverities() {
        FactCheckResult result = new FactCheckResult(
                List.of(new FactCheckResult.Finding("이 매치에 없는 스코어", "3 - 0")),
                List.of(new FactCheckResult.Finding("brief 에 없는 숫자", "40")));

        ArticleDraft made = ArticleDraft.of(1, briefFixture(), notabilityFixture(),
                10, 20, "제목", "본문", "사실 블록", "m", List.of(ArticleDraft.CommentLine.of("댓글")), result);

        assertThat(made.findings()).hasSize(2);
        assertThat(made.factStatus()).isEqualTo(ArticleDraft.FactStatus.CONTRADICTED);
    }

    @Test
    @DisplayName("빈 기사는 저장하지 않는다 — 왜 비었는지 아무도 모르게 된다")
    void blankArticle_isRejected() {
        assertThatThrownBy(() -> new ArticleDraft(1, 0, 1, "k", 2026, 7, 3,
                10, 20, 2, 1, 40, 33, 0.5, List.of(), "제목", "   ",
                "사실", "m", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("사실 블록이 비면 저장하지 않는다 — 검증할 수 없는 기사가 된다 (D61)")
    void blankBrief_isRejected() {
        assertThatThrownBy(() -> new ArticleDraft(1, 0, 1, "k", 2026, 7, 3,
                10, 20, 2, 1, 40, 33, 0.5, List.of(), "제목", "본문",
                "  ", "m", List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("사실 블록");
    }

    @Test
    @DisplayName("제목과 본문을 나눈다 — 첫 줄 뒤에 빈 줄이 규칙이다")
    void splitHeadline_takesFirstLine() {
        String[] parts = ArticleDraft.splitHeadline("팀 33, 2 - 1 승리\n\n본문 첫 문단이다.");

        assertThat(parts[0]).isEqualTo("팀 33, 2 - 1 승리");
        assertThat(parts[1]).isEqualTo("본문 첫 문단이다.");
    }

    @Test
    @DisplayName("형식을 안 지키면 제목을 비운다 — 던지지 않는다. 형식 위반은 사실 오류가 아니다")
    void splitHeadline_toleratesMissingTitle() {
        String longFirst = "이 문장은 제목이라기에는 너무 길어서 제목으로 삼으면 화면이 이상해진다. "
                + "그러므로 제목 없이 본문으로만 취급해야 맞다.\n\n두 번째 문단.";

        assertThat(ArticleDraft.splitHeadline(longFirst)[0]).isEmpty();
        assertThat(ArticleDraft.splitHeadline("한 줄뿐인 글")[0]).isEmpty();
        assertThat(ArticleDraft.splitHeadline("한 줄뿐인 글")[1]).isEqualTo("한 줄뿐인 글");
    }

    private static MatchBrief briefFixture() {
        com.teamfighter.tfm.parser.common.ParsedSchedule schedule =
                new com.teamfighter.tfm.parser.common.ParsedSchedule(
                        0, 1, "league.pro", 2026, 7, 3, 30, 37, 2, 0, 24, 17, 2, 1.0, false);
        return MatchBrief.of(schedule, List.of());
    }

    private static Notability notabilityFixture() {
        return Notability.of(briefFixture(), NotabilityContext.unknown(30));
    }
}
