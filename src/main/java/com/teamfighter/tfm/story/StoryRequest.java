package com.teamfighter.tfm.story;

import java.util.Objects;

/**
 * 모델에 보낼 한 번의 요청. <b>제공자를 모른다</b> — Groq·Cerebras·로컬이 같은 것을 받는다.
 *
 * @param system      역할과 제약. 두 목소리를 가르는 것이 여기다 (D61)
 * @param user        이번 요청의 내용. 사실 블록이 여기 들어간다
 * @param maxTokens   출력 상한. 분량(Notability)에서 나온다
 * @param temperature 기사는 낮게(사실에 묶는다), 댓글은 높게(날조를 허용한다)
 */
public record StoryRequest(String system, String user, int maxTokens, double temperature) {

    public StoryRequest {
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(user, "user");
        if (maxTokens <= 0) {
            throw new IllegalArgumentException("maxTokens 는 양수여야 한다: " + maxTokens);
        }
        if (temperature < 0.0 || temperature > 2.0) {
            throw new IllegalArgumentException("temperature 범위를 벗어났다: " + temperature);
        }
    }
}
