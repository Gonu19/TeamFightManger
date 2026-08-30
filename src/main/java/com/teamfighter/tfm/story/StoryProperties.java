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
        int timeoutSeconds) {

    public StoryProperties {
        baseUrl = baseUrl == null || baseUrl.isBlank()
                ? "https://api.groq.com/openai/v1" : baseUrl.strip();
        model = model == null || model.isBlank() ? "openai/gpt-oss-120b" : model.strip();
        apiKey = apiKey == null ? "" : apiKey.strip();
        timeoutSeconds = timeoutSeconds <= 0 ? 60 : timeoutSeconds;
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
