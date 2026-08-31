package com.teamfighter.tfm.story.gallery;

import java.util.Objects;

/**
 * 게시글에 달린 댓글 하나.
 *
 * <h2>왜 {@code ArticleDraft.CommentLine} 을 안 쓰는가</h2>
 *
 * 값이 하나 더 있다 — <b>작성 시각</b>. 모드의 게시판은 댓글마다 {@code "16:41"} 을
 * 오른쪽에 그리고, 그 시각이 있어야 댓글이 시간 순으로 쌓인 것처럼 읽힌다.
 *
 * <p>기사 댓글에는 그 칸이 없다. 공유 타입에 컴포넌트를 하나 더 붙이면 기사 쪽 생성자와
 * 저장 경로가 전부 바뀌는데, 그쪽에서는 <b>영원히 {@code null} 일 값</b>이다.
 * 두 표가 다르므로(D69 의 {@code article_comment} · 이쪽의 {@code gallery_comment})
 * 타입도 나눠 둔다.
 *
 * <p>나머지 규칙은 D69 그대로다: <b>평평하다.</b> 대댓글은 자기 안에 자식을 담는 대신
 * 부모의 순번을 들고 있다 — DB 가 행 하나에 {@code parent_ordinal} 하나를 갖는 꼴과 같다.
 *
 * @param author        유동닉. 모델이 안 주면 {@code null} 이고 화면이 익명으로 그린다
 * @param parentOrdinal 받아친 원댓글의 순번(1부터). {@code null} 이면 원댓글이다
 * @param postedAt      {@code "16:41"} 꼴의 문자열. 게임 안의 시각이라 우리 시계가 아니다
 */
public record GalleryComment(String author, String body, Integer parentOrdinal, String postedAt) {

    public GalleryComment {
        Objects.requireNonNull(body, "body");
        author = blankToNull(author);
        postedAt = blankToNull(postedAt);
    }

    public boolean isReply() {
        return parentOrdinal != null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
