package com.teamfighter.tfm.story;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link HttpStoryClient} 의 계약을 고정한다. <b>네트워크를 쓰지 않는다.</b>
 *
 * <p>전송을 갈아끼워 요청이 어떻게 만들어지는지와 실패를 어떻게 드러내는지를 본다.
 * 여기서 가장 중요한 것은 <b>실패를 삼키지 않는 것</b>이다 — 빈 문자열을 돌려주면
 * 호출한 쪽이 "모델이 할 말이 없었나 보다" 로 넘어간다 (D31).
 */
class HttpStoryClientTest {

    private static final ObjectMapper MAPPER = tools.jackson.databind.json.JsonMapper.builder().build();
    private static final StoryRequest REQUEST =
            new StoryRequest("너는 기자다", "이 경기를 써라", 500, 0.4);

    private static StoryProperties props(boolean enabled, String apiKey) {
        return new StoryProperties(enabled, "https://example.test/v1", "test-model", apiKey, 5);
    }

    /** 보낸 요청을 붙잡아 두는 가짜 전송. */
    private static final class Capturing implements Function<HttpRequest, HttpResponse<String>> {
        private final List<HttpRequest> sent = new ArrayList<>();
        private final int status;
        private final String body;

        Capturing(int status, String body) {
            this.status = status;
            this.body = body;
        }

        @Override
        public HttpResponse<String> apply(HttpRequest request) {
            sent.add(request);
            return new FakeResponse(status, body);
        }
    }

    private record FakeResponse(int code, String payload) implements HttpResponse<String> {
        @Override public int statusCode() { return code; }
        @Override public HttpRequest request() { return null; }
        @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
        @Override public java.net.http.HttpHeaders headers() {
            return java.net.http.HttpHeaders.of(java.util.Map.of(), (a, b) -> true);
        }
        @Override public String body() { return payload; }
        @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
        @Override public java.net.URI uri() { return java.net.URI.create("https://example.test"); }
        @Override public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }
    }

    private static String okBody(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}]}";
    }

    @Test
    @DisplayName("꺼져 있으면 부르지 않고 던진다 — 빈 문자열을 돌려주지 않는다")
    void disabled_throwsInsteadOfReturningEmpty() {
        Capturing transport = new Capturing(200, okBody("기사"));
        StoryClient client = new HttpStoryClient(props(false, "key"), MAPPER, transport);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(StoryClient.StoryUnavailableException.class)
                .hasMessageContaining("꺼져 있다");
        assertThat(transport.sent).isEmpty();
    }

    @Test
    @DisplayName("키가 없으면 부르지 않고 던진다 — 어디에 넣는지도 알려준다")
    void missingKey_throwsWithGuidance() {
        Capturing transport = new Capturing(200, okBody("기사"));
        StoryClient client = new HttpStoryClient(props(true, ""), MAPPER, transport);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(StoryClient.StoryUnavailableException.class)
                .hasMessageContaining("TFM_GROQ_API_KEY");
        assertThat(transport.sent).isEmpty();
    }

    @Test
    @DisplayName("오류 메시지에 키가 새지 않는다")
    void errors_neverLeakTheKey() {
        String secret = "gsk_verysecretvalue123";
        Capturing transport = new Capturing(401, "{\"error\":\"invalid api key\"}");
        StoryClient client = new HttpStoryClient(props(true, secret), MAPPER, transport);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(StoryClient.StoryFailedException.class)
                .hasMessageNotContaining(secret);
    }

    @Test
    @DisplayName("요청에 모델·제약·본문이 실린다")
    void request_carriesModelAndMessages() throws Exception {
        Capturing transport = new Capturing(200, okBody("기사"));
        new HttpStoryClient(props(true, "key"), MAPPER, transport).complete(REQUEST);

        assertThat(transport.sent).hasSize(1);
        HttpRequest sent = transport.sent.get(0);
        assertThat(sent.uri().toString()).endsWith("/chat/completions");
        assertThat(sent.headers().firstValue("Authorization")).contains("Bearer key");
    }

    @Test
    @DisplayName("2xx 가 아니면 던진다 — 상태 코드를 메시지에 남긴다")
    void nonSuccessStatus_throws() {
        StoryClient client = new HttpStoryClient(props(true, "key"), MAPPER,
                new Capturing(429, "{\"error\":\"rate limit\"}"));

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(StoryClient.StoryFailedException.class)
                .hasMessageContaining("429");
    }

    @Test
    @DisplayName("응답에 본문이 없으면 던진다 — 빈 기사를 저장하지 않는다")
    void emptyContent_throws() {
        StoryClient client = new HttpStoryClient(props(true, "key"), MAPPER,
                new Capturing(200, "{\"choices\":[{\"message\":{\"content\":\"  \"}}]}"));

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(StoryClient.StoryFailedException.class)
                .hasMessageContaining("본문이 없다");
    }

    @Test
    @DisplayName("JSON 이 아니면 던진다 — 프록시가 HTML 을 돌려줄 수 있다")
    void nonJsonResponse_throws() {
        StoryClient client = new HttpStoryClient(props(true, "key"), MAPPER,
                new Capturing(200, "<html>gateway timeout</html>"));

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(StoryClient.StoryFailedException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    @DisplayName("정상 응답은 본문만 꺼내 다듬어 준다")
    void success_returnsTrimmedContent() {
        StoryClient client = new HttpStoryClient(props(true, "key"), MAPPER,
                new Capturing(200, okBody("  제목\\n\\n본문입니다.  ")));

        assertThat(client.complete(REQUEST)).isEqualTo("제목\n\n본문입니다.");
    }

    @Test
    @DisplayName("설명 문자열에 키가 들어가지 않는다 — 로그로 새는 가장 흔한 경로다")
    void describe_hidesTheKey() {
        String secret = "gsk_anothersecret";

        assertThat(props(true, secret).describe())
                .doesNotContain(secret)
                .contains("설정됨");
    }

    @Test
    @DisplayName("키에 헤더로 못 쓰는 문자가 있으면 부르기 전에 막는다 — 값은 메시지에 안 넣는다")
    void rejectsKeyThatCannotGoIntoAHeader() {
        // 실제로 겪은 사고: 예시 명령의 자리표시자 <키> 를 그대로 환경변수에 넣었다.
        // 한글이라 헤더 값이 될 수 없고, JDK 가 던지는 예외 메시지에는 그 값이 통째로 들어간다.
        Capturing transport = new Capturing(200, "{}");
        HttpStoryClient client = new HttpStoryClient(props(true, "<키>"), MAPPER, transport);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(StoryClient.StoryUnavailableException.class)
                .hasMessageContaining("HTTP 헤더로 쓸 수 없는 문자")
                .hasMessageNotContaining("<키>");                 // 우리 메시지에 값이 새면 안 된다

        assertThat(transport.sent).isEmpty();                     // 요청은 나가지 않았다
    }

    @Test
    @DisplayName("줄바꿈이 섞인 키도 막는다 — 헤더 분리 공격의 통로다")
    void rejectsKeyWithNewline() {
        Capturing transport = new Capturing(200, "{}");
        // 줄바꿈을 문자로 붙인다 — 이스케이프로 적으면 소스에서 눈에 안 띈다
        String keyWithNewline = "gsk_abc" + (char) 10 + "def";
        HttpStoryClient client = new HttpStoryClient(
                props(true, keyWithNewline), MAPPER, transport);

        assertThatThrownBy(() -> client.complete(REQUEST))
                .isInstanceOf(StoryClient.StoryUnavailableException.class);
        assertThat(transport.sent).isEmpty();
    }
}
