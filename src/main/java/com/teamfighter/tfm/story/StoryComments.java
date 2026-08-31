package com.teamfighter.tfm.story;

import com.teamfighter.tfm.story.ArticleDraft.CommentLine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

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
 * 한 번(요금과 몇 초)이 사라진다. 그래서 두 단계로 복구한다.
 *
 * <ol>
 *   <li>그대로 파싱해 본다</li>
 *   <li>실패하면 <b>마지막으로 온전히 닫힌 객체까지</b> 잘라 다시 파싱한다</li>
 *   <li>그래도 안 되면 줄 단위로 자른다 — 닉네임 없는 댓글이라도 있는 편이 낫다</li>
 * </ol>
 *
 * <p>이 마지막 폴백이 D69 가 "닉네임을 NOT NULL 로 두지 않는다" 고 정한 이유이기도 하다.
 */
public final class StoryComments {

    /**
     * 파서 하나를 공유한다. {@code JsonMapper} 는 <b>스레드 안전</b>하고 만드는 비용이
     * 싸지 않다 — 호출마다 만들면 그 비용이 그대로 붙는다.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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

        JsonNode array = readArray(raw);                                        // 1. JSON 배열로 읽어 본다
        if (array == null) {
            return fallbackToLines(raw);                                        // 2. 못 읽으면 줄 단위로
        }

        List<CommentLine> out = new ArrayList<>();
        for (JsonNode node : array) {
            String body = text(node, "content");
            if (body.isBlank()) {                                               // 3. 본문 없는 항목은 버린다
                continue;
            }
            int parentOrdinal = out.size() + 1;                                 // 4. 이 원댓글의 순번 (1부터)
            out.add(new CommentLine(text(node, "author"), body, null));

            JsonNode replies = node.path("sub_comments");                       // 5. 대댓글은 부모 순번을 들고 붙는다
            if (!replies.isArray()) {
                continue;
            }
            int added = 0;
            for (JsonNode reply : replies) {
                if (added >= MAX_REPLIES) {
                    break;
                }
                String replyBody = text(reply, "content");
                if (replyBody.isBlank()) {
                    continue;
                }
                out.add(new CommentLine(text(reply, "author"), replyBody, parentOrdinal));
                added++;
            }
        }
        return out.isEmpty() ? fallbackToLines(raw) : List.copyOf(out);
    }

    /**
     * JSON 배열을 꺼낸다. 못 꺼내면 {@code null}.
     *
     * <p>모델이 배열 앞뒤에 말을 붙이는 일이 잦아서({@code 여기 있습니다:} · 코드펜스)
     * <b>첫 {@code [} 부터 마지막 {@code ]} 까지</b>만 떼어 본다.
     */
    private static JsonNode readArray(String raw) {
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0) {
            return null;
        }

        // 후보를 순서대로 시도한다. 순서가 중요하다 —
        //   1) 마지막 ] 까지: 정상 응답이면 이게 바로 배열이다
        //   2) 끝까지: 잘린 응답에도 ] 는 남아 있다("sub_comments":[] 의 것). 1번만 쓰면
        //      그 ] 에서 잘려 <b>온전한 객체까지 함께 날아간다</b> — 실제로 그렇게 실패했다
        //   3) 마지막으로 닫힌 객체까지 자르고 ] 를 붙이기
        String toEnd = raw.substring(start);
        String toLastBracket = end > start ? raw.substring(start, end + 1) : null;

        for (String candidate : new String[] {
                toLastBracket, toEnd, truncateToLastCompleteObject(toEnd)}) {
            JsonNode parsed = tryRead(candidate);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static JsonNode tryRead(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(json);
            return node.isArray() ? node : null;
        } catch (RuntimeException e) {                                          // Jackson 3 의 파싱 실패는 비검사 예외다
            return null;
        }
    }

    /**
     * 잘린 배열을 마지막으로 <b>온전히 닫힌 객체</b>까지 줄이고 {@code ]} 를 붙인다.
     *
     * <p>중괄호 깊이를 세다가 0 으로 떨어지는 지점이 객체 하나가 끝난 자리다.
     * 문자열 안의 중괄호는 세면 안 되므로 따옴표 상태를 함께 본다 — 그걸 빼먹으면
     * 본문에 {@code {} } 가 든 댓글에서 경계를 잘못 잡는다.
     */
    private static String truncateToLastCompleteObject(String json) {
        int depth = 0;
        int lastComplete = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char ch = json.charAt(i);
            if (escaped) {                                                      // 1. 직전이 역슬래시면 이 글자는 값이다
                escaped = false;
                continue;
            }
            if (ch == '\\') {
                escaped = true;
                continue;
            }
            if (ch == '"') {                                                    // 2. 따옴표가 문자열을 열고 닫는다
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) {
                    lastComplete = i;                                           // 3. 객체 하나가 온전히 끝난 자리
                }
            }
        }
        return lastComplete < 0 ? null : json.substring(0, lastComplete + 1) + "]";
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().strip() : "";
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
