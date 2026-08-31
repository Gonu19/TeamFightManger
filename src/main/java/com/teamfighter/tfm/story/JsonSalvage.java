package com.teamfighter.tfm.story;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모델이 뱉은 덩어리에서 <b>JSON 배열을 건져낸다.</b> 못 건지면 {@code null}.
 *
 * <h2>왜 버리지 않고 건지는가</h2>
 *
 * 호출 한 번이 요금이고 몇 초다. 모델은 두 가지로 이걸 망친다 —
 * 배열 앞뒤에 말을 붙이거나({@code 여기 있습니다:} · 코드펜스), 출력 상한에 걸려
 * <b>배열을 중간에서 끊어먹는다.</b> 통째로 버리면 그 호출이 사라진다.
 *
 * <h2>세 후보를 순서대로 시도한다</h2>
 *
 * <ol>
 *   <li><b>첫 {@code [} 부터 마지막 {@code ]} 까지</b> — 정상 응답이면 이게 배열이다</li>
 *   <li><b>첫 {@code [} 부터 끝까지</b> — 잘린 응답에도 {@code ]} 는 남아 있다
 *       ({@code "sub_comments":[]} 의 것). 1번만 쓰면 그 {@code ]} 에서 잘려
 *       <b>온전한 객체까지 함께 날아간다</b> — 실물에서 실제로 그렇게 실패했다</li>
 *   <li><b>마지막으로 닫힌 객체까지 자르고 {@code ]} 를 붙이기</b></li>
 * </ol>
 *
 * <p>댓글({@link StoryComments})과 게시글({@code gallery/GalleryPosts})이 같은 방식으로
 * 깨진다. 그래서 규칙이 한 곳에 있다 — 두 벌이면 한쪽만 고쳐지고, 고쳐지지 않은 쪽은
 * 조용히 글을 잃는다.
 */
public final class JsonSalvage {

    /**
     * 파서 하나를 공유한다. {@code JsonMapper} 는 <b>스레드 안전</b>하고 만드는 비용이
     * 싸지 않다 — 호출마다 만들면 그 비용이 그대로 붙는다.
     */
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private JsonSalvage() {
    }

    /** JSON 배열을 꺼낸다. 못 꺼내면 {@code null}. */
    public static JsonNode readArray(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('[');
        int end = raw.lastIndexOf(']');
        if (start < 0) {
            return null;
        }

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

    /** 문자열 필드. 없거나 문자열이 아니면 빈 문자열이다 — 부르는 쪽이 빈 값을 판단한다. */
    public static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText().strip() : "";
    }

    /**
     * 정수 필드. 없거나 수가 아니면 {@code null}.
     *
     * <p><b>0 으로 채우지 않는다.</b> 조회수·추천수가 이 메서드를 쓰는데(D71),
     * 거기서 "안 준 것" 과 "0 이라고 한 것" 이 같아지면 개념글 판정이 조용히 달라진다.
     */
    public static Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {                                                // 모델이 "144" 처럼 문자열로 주는 일이 잦다
            try {
                return Integer.valueOf(value.asText().strip());
            } catch (NumberFormatException e) {
                return null;
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
     * 본문에 중괄호가 든 글에서 경계를 잘못 잡는다.
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
}
