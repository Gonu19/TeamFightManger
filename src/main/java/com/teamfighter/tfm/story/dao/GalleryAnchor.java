package com.teamfighter.tfm.story.dao;

/**
 * 갤러리가 매달릴 기사. <b>갤러가 읽은 것</b>이 이 기사다.
 *
 * <p>{@link ArticleView} 를 안 쓰는 이유는 필요한 값이 넷뿐이기 때문이다 —
 * 갤러리 생성에는 사실 블록도 지적 목록도 안 들어간다. 그것들은 기사가 <i>검증</i>될 때
 * 쓰는 값이고, 갤러리는 이미 검증이 끝난 기사 <b>위에</b> 얹힌다.
 *
 * @param key 그 기사가 어느 매치인가. 세이브를 다시 읽어 {@code MatchBrief} 를 만들 때
 *            이 신원으로 매치를 찾는다 — DB 에는 매치 일정이 없기 때문이다
 */
public record GalleryAnchor(long articleId, ArticleKey key, String headline, String body) {
}
