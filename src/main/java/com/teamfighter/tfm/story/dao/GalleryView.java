package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.ArticleDraft.CommentLine;
import com.teamfighter.tfm.story.gallery.GalleryPostKind;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 화면이 그리는 갤러리 한 페이지. 저장된 그대로다.
 *
 * <p>{@code GalleryPost} 를 재사용하지 않는 이유는 {@link ArticleView} 와 같다 —
 * 그쪽은 저장 <i>전</i>의 것이고 {@code postId} 도 {@code isConcept} 의 <b>저장된 값</b>도
 * 없다. 개념글 여부는 DB 가 계산해 굳힌 값을 그대로 들고 온다. 화면이 다시 계산하면
 * 저장 규칙이 바뀌었을 때 목록과 DB 가 조용히 갈린다.
 */
public record GalleryView(
        long batchId,
        long articleId,
        OffsetDateTime generatedAt,
        String model,
        int chunks,
        List<Post> posts) {

    public GalleryView {
        posts = List.copyOf(posts);
    }

    /**
     * 게시글 한 편.
     *
     * @param isConcept DB 가 계산해 굳힌 값 ({@code declared_concept OR likes >= 30}).
     *                  화면은 이 값만 본다
     */
    public record Post(
            long postId,
            int ordinal,
            GalleryPostKind kind,
            String title,
            String author,
            String body,
            Integer views,
            Integer likes,
            boolean isConcept,
            String imageDesc,
            List<CommentLine> comments) {

        public Post {
            comments = List.copyOf(comments);
        }

        /** 원댓글만. 화면이 바깥 목록으로 그린다. */
        public List<CommentLine> roots() {
            return comments.stream().filter(c -> !c.isReply()).toList();
        }

        /**
         * 그 원댓글에 달린 대댓글.
         *
         * <p>순번은 <b>원댓글만 세어</b> 매긴 것이다 — 파서가 그렇게 붙였고
         * ({@code GalleryPosts.readComments}) 저장도 그 순번을 그대로 넣는다.
         * 그래서 여기서 세는 방식도 같아야 한다.
         */
        public List<CommentLine> repliesTo(int rootIndex) {
            int parentOrdinal = rootIndex + 1;
            return comments.stream()
                    .filter(c -> c.isReply() && c.parentOrdinal() == parentOrdinal)
                    .toList();
        }

        /** 댓글 총수(대댓글 포함). 목록의 `[12]` 가 이 값이다. */
        public int commentCount() {
            return comments.size();
        }

        /** 짤방이 있는가. 파일명이 있으면 있는 것이다 — 별도 플래그를 두지 않았다. */
        public boolean hasImage() {
            return imageDesc != null && !imageDesc.isBlank();
        }
    }

    /**
     * 목록에 그릴 순서.
     *
     * <p><b>개념글이 위로 올라간다.</b> 모드의 게시판이 그렇고, 그게 없으면 스무 개가
     * 평평하게 늘어서서 어느 글이 갤을 뒤집었는지 안 보인다. 개념글끼리는 추천수 순,
     * 나머지는 올라온 순({@code ordinal})이다.
     */
    public List<Post> ordered() {
        List<Post> out = new ArrayList<>(posts);
        out.sort((a, b) -> {
            if (a.isConcept() != b.isConcept()) {
                return a.isConcept() ? -1 : 1;
            }
            if (a.isConcept()) {
                return Integer.compare(orZero(b.likes()), orZero(a.likes()));
            }
            return Integer.compare(a.ordinal(), b.ordinal());
        });
        return List.copyOf(out);
    }

    /** 추천수를 모르는 글은 맨 뒤로 간다. 0 으로 읽는 것이 그 뜻이다. */
    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}
