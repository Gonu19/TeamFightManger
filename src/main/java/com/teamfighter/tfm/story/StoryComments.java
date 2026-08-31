package com.teamfighter.tfm.story;

import com.teamfighter.tfm.story.ArticleDraft.CommentLine;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 모델이 준 댓글 덩어리를 {@link CommentLine} 목록으로 만든다.
 *
 * <h2>왜 JSON 인가</h2>
 *
 * 닉네임과 대댓글이 생기면서 한 댓글이 값 세 개(누가·무엇을·누구에게)를 갖게 됐다.
 * 줄 단위 텍스트로는 그 셋을 안전하게 못 나눈다 — 닉네임에 콜론이 들어가거나 본문에
 * 줄바꿈이 들어가는 순간 구분이 무너진다. 레퍼런스 모드도 같은 이유로 JSON 을 쓴다.
 *
 * <h2>깨진 JSON 을 버리지 않는다</h2>
 *
 * 모델은 출력 상한에 걸려 배열을 <b>중간에서 끊어먹는다</b>. 그때 통째로 버리면 호출
 * 한 번(요금과 몇 초)이 사라진다. 배열을 건져내는 일은 {@link JsonSalvage} 가 한다 —
 * 게시글({@code gallery/})도 똑같이 깨지므로 규칙이 한 곳에 있어야 한다.
 *
 * <p>여기 남은 것은 <b>그마저 실패했을 때의 폴백</b>이다: 줄 단위로 자르고 닉네임을
 * 비운다. 닉네임 없는 댓글이라도 있는 편이 낫다 — 이 폴백이 D69 가 "닉네임을
 * NOT NULL 로 두지 않는다" 고 정한 이유이기도 하다.
 */
public final class StoryComments {

    /** 한 원댓글에 달릴 수 있는 대댓글 수. 넘으면 화면이 대댓글로만 찬다. */
    private static final int MAX_REPLIES = 3;

    private StoryComments() {
    }

    /**
     * 모델 응답 → 댓글 목록. <b>평평한 목록</b>이고, 대댓글은 부모의 순번을 들고 있다.
     *
     * <p>중첩 구조 대신 평평하게 두는 이유는 저장이 그렇기 때문이다 — DB 의
     * {@code article_comment} 는 행 하나에 {@code parent_ordinal} 하나를 갖는다.
     * 여기서 중첩을 만들면 저장 직전에 다시 펴야 하고, 그 변환이 한 겹 더 늘어난다.
     */
    public static List<CommentLine> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }

        JsonNode array = JsonSalvage.readArray(raw);                                        // 1. JSON 배열로 읽어 본다
        if (array == null) {
            return fallbackToLines(raw);                                        // 2. 못 읽으면 줄 단위로
        }

        List<CommentLine> out = new ArrayList<>();
        for (JsonNode node : array) {
            String body = JsonSalvage.text(node, "content");
            if (body.isBlank()) {                                               // 3. 본문 없는 항목은 버린다
                continue;
            }
            int parentOrdinal = out.size() + 1;                                 // 4. 이 원댓글의 순번 (1부터)
            out.add(new CommentLine(JsonSalvage.text(node, "author"), body, null));

            JsonNode replies = node.path("sub_comments");                       // 5. 대댓글은 부모 순번을 들고 붙는다
            if (!replies.isArray()) {
                continue;
            }
            int added = 0;
            for (JsonNode reply : replies) {
                if (added >= MAX_REPLIES) {
                    break;
                }
                String replyBody = JsonSalvage.text(reply, "content");
                if (replyBody.isBlank()) {
                    continue;
                }
                out.add(new CommentLine(JsonSalvage.text(reply, "author"), replyBody, parentOrdinal));
                added++;
            }
        }
        return out.isEmpty() ? fallbackToLines(raw) : List.copyOf(out);
    }

    /**
     * JSON 이 아니었을 때. 줄 단위로 자르고 닉네임은 비운다.
     *
     * <p>버리지 않는 이유는 호출 한 번이 요금이고 몇 초이기 때문이다. 닉네임 없는
     * 댓글은 화면이 익명으로 그린다 — 없는 것보다 낫다.
     */
    private static List<CommentLine> fallbackToLines(String raw) {
        return StoryPrompts.splitComments(raw).stream()
                .map(body -> new CommentLine(null, body, null))
                .toList();
    }
}
