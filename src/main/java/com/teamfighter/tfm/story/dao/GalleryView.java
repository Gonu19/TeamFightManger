package com.teamfighter.tfm.story.dao;

import com.teamfighter.tfm.story.gallery.GalleryComment;
import com.teamfighter.tfm.story.gallery.GalleryPostKind;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 화면이 그리는 갤러리 한 페이지. 저장된 그대로다.
 *
 * <p>{@code GalleryPost} 를 재사용하지 않는 이유는 {@link ArticleView} 와 같다 —
 * 그쪽은 저장 <i>전</i>의 것이고 {@code postId} 도 {@code isConcept} 의 <b>저장된 값</b>도
 * 없다. 개념글 여부는 DB 가 계산해 굳힌 값을 그대로 들고 온다. 화면이 다시 계산하면
 * 저장 규칙이 바뀌었을 때 목록과 DB 가 조용히 갈린다.
 *
 * <p><b>정렬과 페이지 나누기를 여기서 하지 않는다.</b> 그 둘은 브라우저가 한다 —
 * 이 페이지의 데이터를 통째로 내려보내고, 최신순·조회순·추천순 전환은 화면 안에서
 * 끝난다(모드가 그렇다). 서버가 정렬해 주면 정렬 버튼마다 왕복이 한 번씩 생기는데,
 * 글 스무 개짜리 목록에서 그건 순전히 손해다.
 *
 * @param articleId 이 매치의 기사. 없으면 {@code null} — 갤러리는 기사 없이도 선다 (D73)
 */
public record GalleryView(
        long batchId,
        int slotId,
        Long articleId,
        int season,
        int day,
        Integer blueTeamId,
        Integer redTeamId,
        String blueTeamName,
        String redTeamName,
        Integer blueScore,
        Integer redScore,
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
            String postedAt,
            List<GalleryComment> comments) {

        public Post {
            comments = List.copyOf(comments);
        }

        /** 원댓글만. 화면이 바깥 목록으로 그린다. */
        public List<GalleryComment> roots() {
            return comments.stream().filter(c -> !c.isReply()).toList();
        }

        /**
         * 그 원댓글에 달린 대댓글.
         *
         * <p>순번은 <b>원댓글만 세어</b> 매긴 것이다 — 파서가 그렇게 붙였고
         * ({@code GalleryPosts.readComments}) 저장도 그 순번을 그대로 넣는다.
         */
        public List<GalleryComment> repliesTo(int rootIndex) {
            int parentOrdinal = rootIndex + 1;
            return comments.stream()
                    .filter(c -> c.isReply() && c.parentOrdinal() == parentOrdinal)
                    .toList();
        }

        /** 댓글 총수(대댓글 포함). 목록의 {@code [12]} 가 이 값이다. */
        public int commentCount() {
            return comments.size();
        }

        /** 짤방이 있는가. 파일명이 있으면 있는 것이다 — 별도 플래그를 두지 않았다. */
        public boolean hasImage() {
            return imageDesc != null && !imageDesc.isBlank();
        }
    }
}
