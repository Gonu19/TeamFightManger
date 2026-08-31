package com.teamfighter.tfm.story.dao;

import java.util.Objects;

/**
 * 저장 직전의 갤러리 페이지 머리말. <b>무슨 경기의 갤인가</b>가 전부다.
 *
 * <p>V11 에서는 이 자리에 {@code article_id} 하나뿐이었다 — 갤러리가 기사에 매달렸기
 * 때문이다. D73 이 그것을 뗐다: 갤러리는 매치에 직접 붙고, 기사는 있으면 링크로만 잇는다.
 *
 * @param articleId 이 매치의 기사. 없으면 {@code null}. <b>있어도 갤러리 생성에 안 쓴다</b> —
 *                  화면이 "기사 보기" 를 그릴지 정하는 데만 쓴다
 * @param blueTeamId {@code team.team_id} 다. 세이브의 {@code game_team_id} 가 아니다 —
 *                   두 번호 공간이 섞이면 "이 매치 갤을 이미 뽑았나" 판정이 조용히 어긋난다
 * @param chunks     이 페이지를 만드는 데 <b>시도한</b> 호출 수. 성공한 수가 아니다 (D72 결정 4)
 */
public record GalleryBatch(
        int slotId,
        Long articleId,
        int season,
        int day,
        int blueTeamId,
        int redTeamId,
        Integer blueScore,
        Integer redScore,
        String model,
        int chunks) {

    public GalleryBatch {
        Objects.requireNonNull(model, "model");
        if (chunks <= 0) {
            throw new IllegalArgumentException("조각을 하나도 안 부르고 만든 페이지는 없다: " + chunks);
        }
    }

    /** 이 페이지가 어느 매치인가. 같은 매치를 또 뽑았는지 판정하는 열쇠다. */
    public ArticleKey key() {
        return new ArticleKey(season, day, blueTeamId, redTeamId);
    }
}
