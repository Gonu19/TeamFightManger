package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.ArticleDraft.FactStatus;

import java.time.OffsetDateTime;

/**
 * 목록 화면의 한 줄. <b>본문도 사실 블록도 담지 않는다.</b>
 *
 * <p>목록은 기사 수십 편을 한 번에 그리는데, 그때마다 {@code body} 와 {@code brief_text} 를
 * 통째로 끌어오면 화면 하나가 수백 KB 를 읽는다. 목록에서 쓰지 않는 값이다.
 *
 * <p>{@code factStatus} 는 <b>목록에도 있다.</b> 상세에서만 경고를 띄우면 모순이 있는 기사를
 * 열어보기 전까지 알 수 없고, 그러면 검증 장치가 사실상 꺼진 것과 같다 (D61).
 *
 * @param blueTeamName {@code team.name} 이 비어 있을 수 있어 {@code null} 이 온다.
 *                     이름을 붙이는 것은 적재의 몫이고 여기서 지어내지 않는다
 */
public record ArticleCard(
        long articleId,
        int slotId,
        int season,
        int day,
        Integer blueTeamId,
        Integer redTeamId,
        String blueTeamName,
        String redTeamName,
        Integer blueScore,
        Integer redScore,
        double notability,
        String headline,
        OffsetDateTime generatedAt,
        FactStatus factStatus) {

    /** 총평이면 대전 상대가 없다. 목록이 스코어 대신 날짜만 그린다. */
    public boolean isRoundSummary() {
        return blueTeamId == null;
    }

    /** 화면이 경고를 띄워야 하는가. */
    public boolean isContradicted() {
        return factStatus == FactStatus.CONTRADICTED;
    }
}
