package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.ArticleDraft;
import com.teamfighter.tfm.story.ArticleDraft.FactStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 상세 화면이 그리는 기사 한 편. 저장된 그대로다.
 *
 * <p><b>{@link ArticleDraft} 를 재사용하지 않는다.</b> 초안은 저장 <i>전</i>의 것이라
 * {@code articleId} 도 {@code generatedAt} 도 없고, 무엇보다 {@code factStatus} 를
 * 지적 목록에서 <b>계산</b>한다. 읽을 때 다시 계산하면 저장된 값과 갈릴 수 있고 —
 * 저장 로직이 틀렸을 때 화면이 그것을 덮어 가려버린다. 여기서는 <b>DB 가 준 값</b>을 그대로 들고
 * 다니고, 둘이 어긋나는지는 {@link #factStatusMatchesFindings()} 로 물어볼 수 있게 남긴다.
 *
 * <p>{@code briefText} 는 생성 시점의 문자열 그대로다 (D61 결정 2). 집계가 나중에 갱신돼도
 * 이 값은 안 바뀐다 — 기사가 <i>그때</i> 무엇을 보고 썼는지가 남아야 검증이 되기 때문이다.
 */
public record ArticleView(
        long articleId,
        int slotId,
        Integer scheduleId,
        Integer competitionId,
        String competitionKey,
        int season,
        int day,
        Integer round,
        int blueTeamId,
        int redTeamId,
        String blueTeamName,
        String redTeamName,
        int blueScore,
        int redScore,
        int blueKill,
        int redKill,
        double notability,
        List<String> notabilityReasons,
        String headline,
        String body,
        String briefText,
        String model,
        OffsetDateTime generatedAt,
        FactStatus factStatus,
        List<ArticleDraft.CommentLine> comments,
        List<ArticleDraft.Finding> findings) {

    public ArticleView {
        notabilityReasons = List.copyOf(notabilityReasons);
        comments = List.copyOf(comments);
        findings = List.copyOf(findings);
    }

    /** 원댓글만. 화면이 바깥 목록으로 그린다. */
    public List<ArticleDraft.CommentLine> topLevelComments() {
        return comments.stream().filter(c -> !c.isReply()).toList();
    }

    /**
     * 그 원댓글에 달린 대댓글.
     *
     * <p>순번은 <b>저장 순서</b>다({@code ordinal}). 목록에서 몇 번째인지가 곧 순번이므로
     * 화면은 인덱스를 따로 세지 않아도 된다 — 다만 원댓글만 걸러 그리므로 그 인덱스가
     * 아니라 <b>전체 목록에서의 위치</b>를 써야 한다. 그래서 이 메서드가 필요하다.
     */
    public List<ArticleDraft.CommentLine> repliesTo(ArticleDraft.CommentLine parent) {
        int ordinal = comments.indexOf(parent) + 1;
        return comments.stream()
                .filter(c -> c.parentOrdinal() != null && c.parentOrdinal() == ordinal)
                .toList();
    }

    /** 화면이 경고를 띄워야 하는가. */
    public boolean isContradicted() {
        return factStatus == FactStatus.CONTRADICTED;
    }

    /**
     * 저장된 {@code fact_status} 가 지적 목록과 맞는가.
     *
     * <p>맞지 않으면 <b>저장 경로에 구멍이 있다는 뜻</b>이다. 화면이 이걸 조용히 고쳐 쓰면
     * 구멍은 영영 안 보인다 — 그래서 고치지 않고 물어볼 수만 있게 뒀다.
     */
    public boolean factStatusMatchesFindings() {
        boolean contradicted = findings.stream()
                .anyMatch(f -> f.severity() == ArticleDraft.Severity.CONTRADICTION);
        return factStatus == (contradicted ? FactStatus.CONTRADICTED : FactStatus.CLEAN);
    }
}
