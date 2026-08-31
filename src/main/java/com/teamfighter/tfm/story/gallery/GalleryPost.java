package com.teamfighter.tfm.story.gallery;

import java.util.List;
import java.util.Objects;

/**
 * 게시글 한 편과 그 댓글. <b>저장 직전의 꼴</b>이다.
 *
 * <p>댓글은 {@link GalleryComment} 다. 기사 댓글과 값이 하나 다르다 — 작성 시각이 있다.
 *
 * @param kind       유형. 모델이 안 주면 부르는 쪽이 조각의 기본값으로 채운다
 * @param author     유동닉. 모델이 안 주면 {@code null} 이고 화면이 익명으로 그린다
 * @param views      조회수. <b>모델이 지어낸 값이다</b> (D71). 안 주면 {@code null} —
 *                   0 으로 채우면 "안 준 것" 과 "0 이라고 한 것" 이 구분되지 않는다
 * @param likes      추천수. 같은 규칙. 30 이상이면 DB 가 개념글로 승격시킨다
 * @param declaredConcept 모델이 스스로 개념글이라고 표시했는가
 * @param imageDesc  짤방 파일명. 실제 이미지는 없고 파일명만 노출된다. 없으면 {@code null}
 * @param postedAt   {@code "2025. 08. 31. 16:40"} 꼴의 문자열. <b>경기 날짜에서 나오지
 *                   우리 시계에서 나오지 않는다</b> — 시즌 3 경기를 오늘 뽑았다고 오늘
 *                   날짜가 붙으면 게시판이 게임 세계 밖으로 나간다
 */
public record GalleryPost(
        GalleryPostKind kind,
        String title,
        String author,
        String body,
        Integer views,
        Integer likes,
        boolean declaredConcept,
        String imageDesc,
        String postedAt,
        List<GalleryComment> comments) {

    public GalleryPost {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        author = blankToNull(author);
        imageDesc = blankToNull(imageDesc);
        postedAt = blankToNull(postedAt);
        comments = List.copyOf(comments);

        if (title.isBlank()) {
            throw new IllegalArgumentException("제목 없는 글은 목록에 그릴 수 없다");
        }
        if (views != null && views < 0) {
            throw new IllegalArgumentException("조회수가 음수다: " + views);
        }
        if (likes != null && likes < 0) {
            throw new IllegalArgumentException("추천수가 음수다: " + likes);
        }
    }

    /**
     * 화면에 개념글로 그릴 것인가.
     *
     * <p><b>DB 의 {@code gallery_post.is_concept} 와 같은 식이어야 한다.</b> 저장 전
     * 미리보기와 저장 후 목록이 다른 답을 하면 그건 화면 버그로 보이지 논리 버그로
     * 안 보인다 — 여기와 V11 이 짝이라는 사실을 두 곳 모두에 적어 둔다.
     */
    public boolean isConcept() {
        return declaredConcept || (likes != null && likes >= 30);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
