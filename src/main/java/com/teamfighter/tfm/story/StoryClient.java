package com.teamfighter.tfm.story;

/**
 * 모델 호출 하나. 구현이 Groq·Cerebras·로컬을 가른다.
 *
 * <p><b>인터페이스가 하나뿐인 이유.</b> 셋 다 OpenAI 호환이라 {@code base-url} 만 다르다.
 * 제공자를 바꾸는 일이 설정 한 줄이 되도록 여기서 막아둔다.
 *
 * <p><b>실패를 삼키지 않는다.</b> 키가 없거나 꺼져 있으면 빈 문자열을 돌려주는 대신
 * 던진다. 조용히 안 도는 것이 이 프로젝트에서 가장 비싼 실패였다 (D31).
 */
public interface StoryClient {

    /**
     * 요청 하나를 보내고 본문을 받는다.
     *
     * @throws StoryUnavailableException 꺼져 있거나 키가 없을 때
     * @throws StoryFailedException      호출이 실패했을 때
     */
    String complete(StoryRequest request);

    /** 지금 부를 수 있는 상태인가. 화면이 버튼을 감출 때 쓴다. */
    boolean isAvailable();

    /** 꺼져 있거나 키가 없다 — 부를 수 없는 상태. */
    class StoryUnavailableException extends RuntimeException {
        public StoryUnavailableException(String message) {
            super(message);
        }
    }

    /** 불렀는데 실패했다. */
    class StoryFailedException extends RuntimeException {
        public StoryFailedException(String message, Throwable cause) {
            super(message, cause);
        }

        public StoryFailedException(String message) {
            super(message);
        }
    }
}
