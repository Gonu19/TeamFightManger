package com.teamfighter.tfm.story;

import com.teamfighter.tfm.story.ArticleDraft.CommentLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StoryComments} 의 계약을 고정한다. <b>DB 도 LLM 도 쓰지 않는다.</b>
 *
 * <p>여기서 지켜야 할 것은 <b>버리지 않는 것</b>이다. 모델 호출 한 번은 요금이고 몇 초다.
 * JSON 이 조금 깨졌다고 통째로 버리면 그 비용이 그대로 사라진다.
 */
class StoryCommentsTest {

    @Test
    @DisplayName("닉네임과 대댓글을 평평한 목록으로 편다 — 대댓글은 부모 순번을 든다")
    void parsesAuthorsAndReplies() {
        String raw = """
                [
                  {"author":"ㅇㅇ(118.35)","content":"밴픽이 왜 저럼","sub_comments":[
                     {"author":"ㅇㅇ(211.36)","content":"@ㅇㅇ 니가 해보든가"}
                  ]},
                  {"author":"분석노트","content":"5세트가 전부였다","sub_comments":[]}
                ]
                """;

        List<CommentLine> lines = StoryComments.parse(raw);

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).author()).isEqualTo("ㅇㅇ(118.35)");
        assertThat(lines.get(0).isReply()).isFalse();
        // 대댓글은 바로 앞 원댓글(1번)을 가리킨다
        assertThat(lines.get(1).parentOrdinal()).isEqualTo(1);
        assertThat(lines.get(2).isReply()).isFalse();
    }

    @Test
    @DisplayName("배열 앞뒤에 말을 붙여도 읽는다 — 모델이 코드펜스를 두른다")
    void parsesWhenWrappedInProse() {
        String raw = """
                여기 있습니다:
                ```json
                [{"author":"ㅇㅇ(1.2)","content":"ㅋㅋㅋ","sub_comments":[]}]
                ```
                """;

        assertThat(StoryComments.parse(raw)).hasSize(1);
    }

    @Test
    @DisplayName("출력 상한에 걸려 잘린 배열도 살린다 — 온전히 닫힌 객체까지만 쓴다")
    void recoversTruncatedArray() {
        // 모델이 두 번째 객체를 쓰다 토큰이 끊겼다
        String raw = """
                [{"author":"ㅇㅇ(1.2)","content":"첫 댓글","sub_comments":[]},
                 {"author":"ㅇㅇ(3.4)","content":"두 번째인데 여기서 끊
                """;

        List<CommentLine> lines = StoryComments.parse(raw);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).body()).isEqualTo("첫 댓글");
    }

    @Test
    @DisplayName("본문에 중괄호가 들어 있어도 경계를 잘못 잡지 않는다")
    void bracesInsideStringsDoNotConfuseTheRecovery() {
        String raw = """
                [{"author":"ㅇㅇ(1.2)","content":"이걸 {이렇게} 쓰네","sub_comments":[]},
                 {"author":"ㅇㅇ(3.4)","content":"끊
                """;

        List<CommentLine> lines = StoryComments.parse(raw);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).body()).isEqualTo("이걸 {이렇게} 쓰네");
    }

    @Test
    @DisplayName("JSON 이 아니면 줄 단위로 자른다 — 닉네임 없는 댓글이라도 남긴다")
    void fallsBackToLines() {
        String raw = """
                1. 이게 실화냐
                2. 다음 경기도 보자
                """;

        List<CommentLine> lines = StoryComments.parse(raw);

        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).author()).isNull();
        assertThat(lines.get(0).body()).isEqualTo("이게 실화냐");
    }

    @Test
    @DisplayName("대댓글은 세 개까지만 — 넘으면 화면이 대댓글로만 찬다")
    void capsReplies() {
        String raw = """
                [{"author":"ㅇㅇ(1.2)","content":"원댓","sub_comments":[
                   {"author":"a","content":"1"},{"author":"b","content":"2"},
                   {"author":"c","content":"3"},{"author":"d","content":"4"},
                   {"author":"e","content":"5"}
                ]}]
                """;

        assertThat(StoryComments.parse(raw)).hasSize(4);   // 원댓 1 + 대댓글 3
    }

    @Test
    @DisplayName("빈 응답은 빈 목록이다 — 예외가 아니다")
    void emptyInputIsEmptyList() {
        assertThat(StoryComments.parse("")).isEmpty();
        assertThat(StoryComments.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("본문이 없는 항목은 버린다 — 빈 댓글이 화면에 뜨면 고장으로 보인다")
    void dropsEmptyBodies() {
        String raw = """
                [{"author":"ㅇㅇ(1.2)","content":"","sub_comments":[]},
                 {"author":"ㅇㅇ(3.4)","content":"살아남는 댓글","sub_comments":[]}]
                """;

        List<CommentLine> lines = StoryComments.parse(raw);

        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).body()).isEqualTo("살아남는 댓글");
    }
}
