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
import java.util.regex.Pattern;
import java.util.regex.Matcher;

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

    /** 요청 한도 초과. 이것만 다시 시도한다 — 나머지 4xx 는 다시 보내도 같은 답이다. */
    private static final int TOO_MANY_REQUESTS = 429;

    /**
     * 429 재시도 횟수.
     *
     * <p>다섯이다. 원래 둘이었는데 <b>갤러리가 그 값을 뒤집었다</b>(D73) — 페이지 하나가
     * 호출 다섯이고 합이 분당 한도(8,000)의 두 배를 넘는다. 뒤 조각은 걸리는 것이 예외가
     * 아니라 <b>정상</b>이고, 둘 만에 포기하면 그 조각이 통째로 빈다.
     *
     * <p>그래도 무한은 아니다. 다섯 번을 다 쓰고도 429 면 요청 자체가 한도보다 큰 것이라
     * 더 보내도 같다 — 그때는 조각 하나를 포기하고 나머지로 페이지를 만든다.
     */
    private static final int RETRIES = 5;

    /** 서버가 아무 말도 안 했을 때 기다릴 시간. */
    private static final Duration DEFAULT_RETRY_WAIT = Duration.ofSeconds(5);

    /**
     * 아무리 길어도 이만큼만 기다린다.
     *
     * <p>원래 20초였다. "더 길면 멈춘 것처럼 보인다" 가 이유였는데, 이제 화면이 단계를
     * 그리므로 멈춘 것처럼 보이지 않는다({@code StoryJobs}). 분당 한도가 리셋되는 데
     * 실제로 그만큼 걸리는 일이 있어 40초로 올린다.
     */
    private static final Duration MAX_RETRY_WAIT = Duration.ofSeconds(40);

    /** {@code "Please try again in 2.79s"} — Groq 이 본문에 넣어주는 남은 시간. */
    private static final Pattern RETRY_HINT =
            Pattern.compile("try again in ([0-9]+(?:\\.[0-9]+)?)s");

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

        for (int attempt = 1; ; attempt++) {                                    // 1. 429 면 기다렸다 다시 — 그 밖의 실패는 곧바로 던진다
            HttpResponse<String> response = transport.apply(http);              // 2. 여기를 지나야 실제로 나간 것이다

            if (response.statusCode() / 100 == 2) {                             // 3. 성공
                return extractContent(response.body());
            }

            if (response.statusCode() != TOO_MANY_REQUESTS || attempt > RETRIES) {
                throw new StoryFailedException(                                 // 4. 못 고칠 실패이거나 재시도를 다 썼다
                        "모델이 " + response.statusCode() + " 로 응답했다: " + snippet(response.body()));
            }

            Duration wait = retryAfter(response);                               // 5. 얼마나 기다릴지는 서버가 안다
            log.warn("모델이 429(요청 한도)로 응답했다. {}초 뒤 다시 시도한다 ({}/{})",
                    wait.toMillis() / 1000.0, attempt, RETRIES);
            sleep(wait);
        }
    }

    /**
     * 429 를 만났을 때 얼마나 기다릴까.
     *
     * <p>순서대로 본다.
     * <ol>
     *   <li>{@code Retry-After} 헤더 — HTTP 표준이고 초 단위 정수다</li>
     *   <li>본문의 {@code "Please try again in 2.79s"} — Groq 이 여기에 넣어준다.
     *       한도가 <b>분당 토큰</b>이라 남은 시간이 초 단위로 정확히 계산되는데,
     *       그 값을 무시하고 고정 대기를 쓰면 너무 일찍(또 429) 또는 너무 늦게 간다</li>
     *   <li>둘 다 없으면 {@link #DEFAULT_RETRY_WAIT}</li>
     * </ol>
     *
     * <p>{@link #MAX_RETRY_WAIT} 로 자른다. 서버가 "60초 뒤" 라고 해도 화면 뒤에서
     * 그만큼 붙잡고 있으면 사용자는 앱이 멈춘 줄 안다 — 그때는 차라리 실패로 알리는 편이 낫다.
     */
    private static Duration retryAfter(HttpResponse<String> response) {
        Duration wait = response.headers().firstValue("retry-after")            // 1. 표준 헤더가 있으면 그것
                .map(HttpStoryClient::parseSeconds)
                .orElse(null);

        if (wait == null) {                                                     // 2. 없으면 본문에서 찾는다
            Matcher matcher = RETRY_HINT.matcher(response.body() == null ? "" : response.body());
            if (matcher.find()) {
                wait = parseSeconds(matcher.group(1));
            }
        }

        if (wait == null || wait.isNegative() || wait.isZero()) {               // 3. 그래도 모르면 기본값
            wait = DEFAULT_RETRY_WAIT;
        }
        return wait.compareTo(MAX_RETRY_WAIT) > 0 ? MAX_RETRY_WAIT : wait;      // 4. 너무 길면 자른다
    }

    /** {@code "2.79"} · {@code "3"} 을 Duration 으로. 못 읽으면 {@code null}. */
    private static Duration parseSeconds(String raw) {
        try {
            double seconds = Double.parseDouble(raw.trim());
            return Duration.ofMillis(Math.round(seconds * 1000));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 기다린다. 기다리는 동안 인터럽트가 오면 <b>플래그를 되살리고</b> 던진다.
     *
     * <p>{@code InterruptedException} 을 잡고 아무것도 안 하면 "누가 이 스레드를 멈추려 했다"
     * 는 사실이 사라진다. 그러면 앱을 종료할 때 이 요청이 끝까지 버틴다.
     */
    private static void sleep(Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoryFailedException("모델 호출을 기다리다 중단됐다", e);
        }
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
        if (!properties.reasoningEffort().isEmpty()) {                          // 추론 모델의 생각 길이. 빈 값이면 안 보낸다
            root.put("reasoning_effort", properties.reasoningEffort());
        }
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
        JsonNode message = root.path("choices").path(0).path("message");
        JsonNode content = message.path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            // 추론 모델은 답하기 전에 생각을 먼저 쓴다(reasoning 필드). 그 생각도 출력
            // 토큰을 쓰기 때문에 상한이 빠듯하면 생각만 하다 끝나고 content 가 빈다 —
            // 실물에서 댓글 호출이 그렇게 죽었다. 원인을 메시지에 적어야 사람이 고칠 수 있다.
            boolean thoughtButDidNotAnswer = message.path("reasoning").isTextual()
                    && !message.path("reasoning").asText().isBlank();
            throw new StoryFailedException(
                    thoughtButDidNotAnswer
                            ? "모델이 생각만 하고 답을 안 썼다 — 출력 상한이 모자라거나"
                                    + " reasoning_effort 가 높다 (지금 "
                                    + properties.reasoningEffort() + "). 상한을 올린다: "
                                    + snippet(responseBody)
                            : "응답에 본문이 없다: " + snippet(responseBody));
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
