package com.teamfighter.tfm.story.gallery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 모델 응답 → 게시글. <b>실물이 어떻게 깨지는지</b>를 고정한다.
 *
 * <p>DB 도 네트워크도 없다. 여기서 보는 것은 파싱 규칙 하나뿐이고, 그 규칙이
 * 호출 한 번(요금과 몇 초)을 살리느냐 버리느냐를 정한다.
 */
class GalleryPostsTest {

    private static final GalleryChunk CHUNK = GalleryChunk.page().get(0);

    @Test
    @DisplayName("정상 응답을 그대로 읽는다 — 유형·조회수·추천수·댓글까지")
    void parsesCleanResponse() {
        String raw = """
                [
                  {"kind": "LIVE", "title": "3세트 그 한타 뭐냐", "author": "ㅇㅇ(112.47)",
                   "content": "실시간으로 봤는데 손이 떨림", "views": 1420, "likes": 51,
                   "is_concept": false, "image_desc": "멍때리는 감독.jpg",
                   "comments": [
                     {"author": "ㅇㅇ(220.76)", "content": "ㄹㅇ 미쳤더라",
                      "sub_comments": [{"author": "ㅇㅇ(112.47)", "content": "@ㅇㅇ 그니까"}]}
                   ]}
                ]""";

        List<GalleryPost> posts = GalleryPosts.parse(raw, CHUNK);

        assertThat(posts).hasSize(1);
        GalleryPost post = posts.get(0);
        assertThat(post.kind()).isEqualTo(GalleryPostKind.LIVE);
        assertThat(post.title()).isEqualTo("3세트 그 한타 뭐냐");
        assertThat(post.author()).isEqualTo("ㅇㅇ(112.47)");
        assertThat(post.views()).isEqualTo(1420);
        assertThat(post.likes()).isEqualTo(51);
        assertThat(post.imageDesc()).isEqualTo("멍때리는 감독.jpg");
        assertThat(post.comments()).hasSize(2);
        assertThat(post.comments().get(1).parentOrdinal()).isEqualTo(1);
    }

    @Test
    @DisplayName("추천 30 이상이면 개념글이다 — 모델이 표시 안 해도")
    void promotesToConceptByLikes() {
        String raw = """
                [{"kind":"FLAME","title":"POG 이거 맞냐","content":"본문",
                  "likes": 30, "is_concept": false, "comments": []}]""";

        assertThat(GalleryPosts.parse(raw, CHUNK).get(0).isConcept()).isTrue();
    }

    @Test
    @DisplayName("조회수를 안 주면 null 이다 — 0 으로 채우지 않는다")
    void missingViewsStayNull() {
        String raw = """
                [{"kind":"DAILY","title":"저녁 뭐 먹지","content":"본문","comments":[]}]""";

        GalleryPost post = GalleryPosts.parse(raw, CHUNK).get(0);

        // 0 으로 채우면 "안 줬다" 와 "아무도 안 봤다" 가 같아진다 (D71)
        assertThat(post.views()).isNull();
        assertThat(post.likes()).isNull();
        assertThat(post.isConcept()).isFalse();
    }

    @Test
    @DisplayName("유형을 모르면 그 조각의 기본값으로 채운다 — 글을 버리지 않는다")
    void unknownKindFallsBack() {
        String raw = """
                [{"kind":"밈글","title":"제목","content":"본문","comments":[]},
                 {"title":"유형 없는 글","content":"본문","comments":[]}]""";

        List<GalleryPost> posts = GalleryPosts.parse(raw, CHUNK);

        assertThat(posts).hasSize(2);
        assertThat(posts).allSatisfy(p -> assertThat(p.kind()).isEqualTo(CHUNK.fallbackKind()));
    }

    @Test
    @DisplayName("잘린 응답에서 온전한 글만 건진다 — 통째로 버리지 않는다")
    void salvagesTruncatedArray() {
        // 두 번째 글이 중간에서 끊겼다. 출력 상한에 걸리면 실물이 이렇게 온다.
        String raw = """
                [{"kind":"LIVE","title":"온전한 글","content":"본문","comments":[]},
                 {"kind":"PLAYER","title":"끊긴 글","content":"본""";

        List<GalleryPost> posts = GalleryPosts.parse(raw, CHUNK);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).title()).isEqualTo("온전한 글");
    }

    @Test
    @DisplayName("빈 sub_comments 가 남은 채 잘려도 앞 글이 살아남는다")
    void trailingBracketDoesNotEatCompleteObjects() {
        // 잘린 응답에도 ] 가 남아 있다("sub_comments":[] 의 것). 마지막 ] 까지만 보면
        // 거기서 잘려 온전한 객체까지 함께 날아간다 — 실물에서 실제로 그렇게 실패했다.
        String raw = """
                [{"kind":"LIVE","title":"첫 글","content":"본문",
                  "comments":[{"author":"ㅇㅇ","content":"댓","sub_comments":[]}]},
                 {"kind":"PLAYER","title":"끊긴""";

        List<GalleryPost> posts = GalleryPosts.parse(raw, CHUNK);

        assertThat(posts).hasSize(1);
        assertThat(posts.get(0).comments()).hasSize(1);
    }

    @Test
    @DisplayName("앞뒤에 말을 붙여도 배열만 떼어 읽는다")
    void ignoresChatterAroundArray() {
        String raw = """
                여기 있습니다:
                ```json
                [{"kind":"BAIT","title":"제목","content":"본문","comments":[]}]
                ```
                더 필요하시면 말씀해 주세요.""";

        assertThat(GalleryPosts.parse(raw, CHUNK)).hasSize(1);
    }

    @Test
    @DisplayName("제목이나 본문이 비면 그 글만 버린다")
    void dropsPostsWithoutTitleOrBody() {
        String raw = """
                [{"kind":"LIVE","title":"","content":"본문","comments":[]},
                 {"kind":"LIVE","title":"제목","content":"","comments":[]},
                 {"kind":"LIVE","title":"멀쩡","content":"본문","comments":[]}]""";

        assertThat(GalleryPosts.parse(raw, CHUNK))
                .extracting(GalleryPost::title)
                .containsExactly("멀쩡");
    }

    @Test
    @DisplayName("JSON 이 아니면 빈 목록이다 — 예외가 아니다")
    void nonJsonGivesEmptyList() {
        // 조각 하나가 실패해도 나머지 조각은 계속 돌아야 한다 (D72)
        assertThat(GalleryPosts.parse("죄송합니다. 요청을 이해하지 못했습니다.", CHUNK)).isEmpty();
        assertThat(GalleryPosts.parse("", CHUNK)).isEmpty();
        assertThat(GalleryPosts.parse(null, CHUNK)).isEmpty();
    }
}
