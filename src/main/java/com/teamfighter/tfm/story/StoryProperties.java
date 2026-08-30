package com.teamfighter.tfm.story;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code tfm.story.*} 설정.
 *
 * <p><b>기본은 꺼짐이다 (D61 결정 4).</b> 켜지 않으면 요청이 하나도 나가지 않고
 * 적재·집계·통계 화면은 그대로 돈다. {@code story/} 없이도 앱이 성립해야 한다.
 *
 * @param apiKey 환경변수 또는 {@code .env} 로만 받는다. 코드·설정 파일·커밋에 넣지 않는다
 */
@ConfigurationProperties(prefix = "tfm.story")
public record StoryProperties(
        boolean enabled,
        String baseUrl,
        String model,
        String apiKey,
        int timeoutSeconds,
        String reasoningEffort) {

    // 생성자를 하나만 둔다. @ConfigurationProperties 는 record 의 <b>정규 생성자</b>로
    // 값을 바인딩하는데, 편의 생성자를 하나 더 두면 어느 것으로 바인딩할지 정하지 못해
    // "No default constructor found" 로 컨텍스트가 통째로 안 뜬다. 실제로 그렇게 깼다.

    public StoryProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.groq.com/openai/v1" : baseUrl.strip();
        model = model == null || model.isBlank() ? "openai/gpt-oss-120b" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
        // gpt-oss 같은 추론 모델은 답하기 전에 생각을 먼저 쓴다. 그 생각도 출력 토큰을
        // 쓰기 때문에, 상한이 빠듯하면 생각만 하다 끝나고 content 가 빈 채로 돌아온다 —
        // 실물에서 실제로 그랬다. low 로 두면 생각을 짧게 하고 답에 토큰을 남긴다.
        // 이 값을 모르는 서버를 만나면 빈 문자열로 두어 아예 안 보낼 수 있다.
        reasoningEffort = reasoningEffort == null ? "low" : reasoningEffort.strip();
    }

    /** 켜져 있고 키가 있는가. */
    public boolean isUsable() {
        return enabled && !apiKey.isEmpty();
    }

    /** 로그에 찍어도 되는 요약. <b>키를 절대 넣지 않는다.</b> */
    public String describe() {
        return "story{enabled=" + enabled + ", baseUrl=" + baseUrl + ", model=" + model
                + ", apiKey=" + (apiKey.isEmpty() ? "없음" : "설정됨(" + apiKey.length() + "자)") + "}";
    }
}
