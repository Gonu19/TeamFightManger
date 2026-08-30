package com.teamfighter.tfm.story;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Function;

/**
 * OpenAI 호환 {@code /chat/completions} 를 부른다 — Groq · Cerebras · 로컬이 전부 이 꼴이다.
 *
 * <p><b>새 의존을 들이지 않았다.</b> JDK 의 {@link HttpClient} 와 이미 쓰는 Jackson 이면
 * 된다. Spring AI 는 Boot 4.1 에서 실제로 도는지 확인되지 않았고(D31 이 준 교훈),
 * 이 호출은 요청 하나·응답 하나라 추상화가 벌어줄 것이 없다.
 *
 * <p><b>실패를 삼키지 않는다.</b> 꺼져 있으면 {@link StoryUnavailableException},
 * 부르다 실패하면 {@link StoryFailedException} 이다. 빈 문자열을 돌려주면
 * 호출한 쪽이 "모델이 할 말이 없었나 보다" 로 넘어가고, 그게 D31 이 겪은 실패다.
 *
 * <p><b>키는 로그에 찍히지 않는다.</b> 헤더에만 들어가고 어떤 예외 메시지에도 안 나온다.
 */
public class HttpStoryClient implements StoryClient {

    private static final Logger log = LoggerFactory.getLogger(HttpStoryClient.class);

    private final StoryProperties properties;
    private final ObjectMapper mapper;
    private final Function<HttpRequest, HttpResponse<String>> transport;

    /** 운영용. 진짜 HTTP 를 쓴다. */
    public HttpStoryClient(StoryProperties properties, ObjectMapper mapper) {
        this(properties, mapper, defaultTransport(properties));
    }

    /** 테스트용. 전송을 갈아끼워 네트워크 없이 계약을 검증한다. */
    public HttpStoryClient(StoryProperties properties, ObjectMapper mapper,
                           Function<HttpRequest, HttpResponse<String>> transport) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    private static Function<HttpRequest, HttpResponse<String>> defaultTransport(
            StoryProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(10, properties.timeoutSeconds())))
                .build();
        return request -> {
            try {
                return http.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                throw new StoryFailedException("모델 호출이 실패했다: " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoryFailedException("모델 호출이 중단됐다", e);
            }
        };
    }

    @Override
    public boolean isAvailable() {
        return properties.isUsable();
    }

    @Override
    public String complete(StoryRequest request) {
        Objects.requireNonNull(request, "request");
        if (!properties.enabled()) {
            throw new StoryUnavailableException(
                    "기사 생성이 꺼져 있다. tfm.story.enabled=true 로 켠다. " + properties.describe());
        }
        if (properties.apiKey().isEmpty()) {
            throw new StoryUnavailableException(
                    "API 키가 없다. 환경변수 TFM_GROQ_API_KEY 또는 .env 에 넣는다. "
                            + properties.describe());
        }
        if (!isHeaderSafe(properties.apiKey())) {                                // 키가 헤더로 나갈 수 있는 값인지 먼저 본다
            throw new StoryUnavailableException(
                    "API 키에 HTTP 헤더로 쓸 수 없는 문자가 있다 (한글·공백·줄바꿈 등). "
                            + "자리표시자를 그대로 넣지 않았는지 확인한다. "
                            + "환경변수가 .env 보다 우선하므로, 셸에 남은 값이 파일을 가릴 수 있다 — "
                            + "PowerShell 이면 Remove-Item Env:TFM_GROQ_API_KEY. "
                            + properties.describe());
        }

        String body = toRequestBody(request);
        // 무엇이 나가는지 남긴다 (D61 결정 4). 키도 본문도 찍지 않는다 — 크기만 남긴다.
        //
        // "호출" 이 아니라 "준비" 라고 적는다. 이 줄은 요청을 만들기 전에 찍히므로
        // 여기까지 왔다고 요청이 나간 것이 아니다 — 실제로 헤더를 만들다 죽은 적이 있고,
        // 그때 이 로그가 "모델 호출" 이라 적혀 있어서 모델까지 갔다고 읽혔다.
        log.info("모델 호출 준비 — {} · 요청 {}자 · 출력 상한 {}토큰",
                properties.model(), body.length(), request.maxTokens());

        HttpRequest http = HttpRequest.newBuilder()
                .uri(URI.create(properties.baseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = transport.apply(http);                  // 여기를 지나야 실제로 나간 것이다
        if (response.statusCode() / 100 != 2) {
            throw new StoryFailedException(
                    "모델이 " + response.statusCode() + " 로 응답했다: " + snippet(response.body()));
        }
        return extractContent(response.body());
    }

    /**
     * 값이 HTTP 헤더로 나갈 수 있는가.
     *
     * <p>헤더 값에는 <b>보이는 ASCII</b>(0x21~0x7E)와 공백만 허용된다. 한글·제어문자·줄바꿈이
     * 들어가면 JDK 의 {@code HttpRequest.Builder.header} 가
     * {@code IllegalArgumentException: invalid header value} 를 던지는데, 그 예외 메시지에는
     * <b>값이 그대로 들어간다</b> — 진짜 키였다면 로그와 오류 화면에 통째로 찍힌다.
     * 그래서 그 앞에서 우리가 먼저 막고, 우리 메시지에는 값을 절대 넣지 않는다.
     *
     * <p>공백도 거른다. 문법상 헤더 값 안의 공백은 허용되지만 API 키에는 들어갈 일이 없고,
     * 들어갔다면 붙여넣기 사고일 가능성이 높다.
     */
    private static boolean isHeaderSafe(String value) {
        for (int i = 0; i < value.length(); i++) {                              // 1. 한 글자씩 코드포인트를 본다
            char ch = value.charAt(i);
            if (ch < 0x21 || ch > 0x7E) {                                       // 2. 보이는 ASCII 밖이면 (한글·공백·제어문자·탭·줄바꿈)
                return false;                                                   // 3. 헤더로 못 쓴다
            }
        }
        return true;
    }

    private String toRequestBody(StoryRequest request) {
        ObjectNode root = mapper.createObjectNode();
        root.put("model", properties.model());
        root.put("max_tokens", request.maxTokens());
        root.put("temperature", request.temperature());
        ArrayNode messages = root.putArray("messages");
        messages.addObject().put("role", "system").put("content", request.system());
        messages.addObject().put("role", "user").put("content", request.user());
        try {
            return mapper.writeValueAsString(root);
        } catch (tools.jackson.core.JacksonException e) {
            throw new StoryFailedException("요청을 만들 수 없다", e);
        }
    }

    /**
     * 응답에서 본문을 꺼낸다.
     *
     * <p>모양이 다르면 던진다. {@code null} 이나 빈 문자열로 돌려주면 호출한 쪽이
     * 빈 기사를 저장하고, 그 기사가 왜 비었는지는 아무도 모르게 된다.
     */
    private String extractContent(String responseBody) {
        JsonNode root;
        try {
            root = mapper.readTree(responseBody);
        } catch (tools.jackson.core.JacksonException e) {
            throw new StoryFailedException("응답이 JSON 이 아니다: " + snippet(responseBody), e);
        }
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new StoryFailedException(
                    "응답에 본문이 없다: " + snippet(responseBody));
        }
        return content.asText().strip();
    }

    /** 오류 메시지에 응답 전체를 붙이지 않는다. 로그가 부풀고 읽히지 않는다. */
    private static String snippet(String body) {
        if (body == null) {
            return "(빈 응답)";
        }
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 300 ? flat : flat.substring(0, 300) + "…";
    }
}
